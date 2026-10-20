package eu.kanade.tachiyomi.multisrc.readwn

import dokusho.network.get
import dokusho.network.post
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.asJsoup
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

/**
 * Sites on the ReadWN engine (wuxiabox.com, wuxiaspot.com, fanmtl.com). Novels live at
 * `/novel/<slug>.html` and chapter N at `/novel/<slug>_N.html`; the novel page only lists a few
 * chapters, so the rest are numbered from the header's chapter count.
 */
abstract class ReadWn : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 3, period = 2.seconds)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = listing("$baseUrl/list/all/all-onclick-${page - 1}.html")

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing("$baseUrl/list/all/all-lastdotime-${page - 1}.html")

    // Search is a single POST form that redirects to a result page.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val body = FormBody.Builder()
            .add("show", "title")
            .add("tempid", "1")
            .add("tbname", "news")
            .add("keyboard", query)
            .build()
        val searchHeaders = headers.newBuilder().set("Referer", "$baseUrl/search.html").add("Origin", baseUrl).build()
        val result = parseListing(client.post("$baseUrl/e/search/index.php", searchHeaders, body).asJsoup())
        return MangasPage(result.mangas, false)
    }

    private suspend fun listing(url: String): MangasPage = parseListing(client.get(url, headers).asJsoup())

    private fun parseListing(document: Document): MangasPage {
        val novels = document.select("li.novel-item").mapNotNull { item ->
            val link = item.selectFirst("a[href*=/novel/]") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = item.selectFirst("h4.novel-title")?.text() ?: link.attr("title")
                thumbnail_url = item.selectFirst(".novel-cover img")?.imageUrl()
            }
        }
        return MangasPage(novels, document.selectFirst("ul.pagination li.active + li a[href]") != null)
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), headers).asJsoup()
        val details = if (fetchDetails) parseDetails(document, manga) else manga
        val chapterList = if (fetchChapters) parseChapterList(document, manga.url) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h1.novel-title")?.text() ?: manga.title
        thumbnail_url = document.selectFirst("figure.cover img")?.imageUrl() ?: manga.thumbnail_url
        author = document.selectFirst("div.author span[itemprop=author]")?.text()
        genre = document.select("div.categories li a").eachText().joinToString()
        description = document.selectFirst("div.summary div.content")?.select("p")?.eachText()?.joinToString("\n\n")
        val statusText = document.select("div.header-stats span:has(small:contains(Status)) strong").text()
        status = when {
            statusText.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("completed", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private fun parseChapterList(document: Document, novelPath: String): List<SChapter> {
        val titles = document.select("ul.chapter-list li a[href]").mapNotNull { link ->
            val number = CHAPTER_NUMBER.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            number to link.selectFirst(".chapter-title")?.text()?.trimStart(':', ' ').orEmpty()
        }.toMap()
        val count = document.selectFirst("div.header-stats span:has(small:contains(Chapters)) strong")?.text()
            ?.filter(Char::isDigit)?.toIntOrNull()
            ?: titles.keys.maxOrNull()
            ?: 0
        val base = novelPath.removeSuffix(".html")
        return (count downTo 1).map { number ->
            SChapter.create().apply {
                url = "${base}_$number.html"
                name = titles[number]?.takeIf { it.isNotBlank() }?.let { "Chapter $number: $it" } ?: "Chapter $number"
                chapter_number = number.toFloat()
            }
        }
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val document = client.get(getChapterUrl(chapter), headers).asJsoup()
        val title = document.selectFirst("div.titles h2, span.chapter-title")?.text()?.trimStart(':', ' ')
        val body = document.selectFirst("div.chapter-content")
            ?: return ChapterContent(html = "", title = title ?: chapter.name, locked = true)
        body.select("script, style, iframe, ins, noscript, div[align=center]").remove()
        body.select("a, span").unwrap()
        body.allElements.forEach { element ->
            val src = if (element.tagName() == "img") element.absUrl("src") else null
            element.clearAttributes()
            if (!src.isNullOrBlank()) element.attr("src", src)
        }
        body.select("p").filter { it.text().isBlank() && it.select("img").isEmpty() }.forEach { it.remove() }
        val html = body.html().ifBlank { return ChapterContent(html = "", title = title ?: chapter.name, locked = true) }
        return ChapterContent(html = html, title = title?.takeIf { it.isNotBlank() } ?: chapter.name)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.")) return null
        val slug = url.pathSegments.getOrNull(1)?.substringBefore(".html")?.substringBefore('_') ?: return null
        if (url.pathSegments.firstOrNull() != "novel") return null
        val manga = SManga.create().apply { this.url = "/novel/$slug.html" }
        return parseDetails(client.get(getMangaUrl(manga), headers).asJsoup(), manga)
    }

    private fun Element.imageUrl(): String? = absUrl("data-src").ifBlank { absUrl("src") }
        .takeIf { it.isNotBlank() && "placeholder" !in it }

    companion object {
        private val CHAPTER_NUMBER = Regex("""_(\d+)\.html""")
    }
}
