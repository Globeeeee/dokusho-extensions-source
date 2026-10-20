package eu.kanade.tachiyomi.extension.en.novelfire

import dokusho.annotation.Source
import dokusho.network.get
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.asJsoup
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Novel Fire (the old LightNovelPub/LightNovelWorld catalogue). The site throttles bursts, so requests
 * are kept slow; the chapter list is the `/chapters` TOC at 100 a page.
 */
@Source
abstract class NovelFire : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 2, period = 2.seconds)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = listing("$baseUrl/genre-all/sort-popular/status-all/all-novel?page=$page")

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing(
        "$baseUrl/search-adv?ctgcon=and&totalchapter=0&ratcon=min&rating=0&status=-1&sort=date&tagcon=and&page=$page",
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = listing(
        "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .build()
            .toString(),
    )

    private suspend fun listing(url: String): MangasPage {
        val document = client.get(url, headers).asJsoup()
        val novels = document.select("li.novel-item").mapNotNull { item ->
            val link = item.selectFirst("a[href*=/book/]") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.attr("title").ifBlank { item.selectFirst(".novel-title")?.text().orEmpty() }
                thumbnail_url = item.selectFirst(".novel-cover img")?.imageUrl()
            }
        }
        return MangasPage(novels, document.selectFirst("a[rel=next]") != null)
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = if (fetchDetails) parseDetails(client.get(getMangaUrl(manga), headers).asJsoup(), manga) else manga
        val chapterList = if (fetchChapters) fetchChapterList(manga.url) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h1.novel-title")?.text() ?: manga.title
        thumbnail_url = document.selectFirst("figure.cover img")?.imageUrl() ?: manga.thumbnail_url
        author = document.select("div.author .property-item span[itemprop=author]").eachText().joinToString()
        genre = document.select("div.categories .property-item").eachText().joinToString()
        description = document.selectFirst("div.summary div.content")?.let { content ->
            content.select(".expand").remove()
            content.select("p").eachText().joinToString("\n\n").ifBlank { content.wholeText().trim() }
        }
        val statusText = document.selectFirst("div.header-stats .ongoing, div.header-stats .completed")?.text().orEmpty()
        status = when {
            statusText.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("completed", ignoreCase = true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private suspend fun fetchChapterList(novelPath: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (page <= MAX_TOC_PAGES) {
            val document = client.get("$baseUrl${novelPath.trimEnd('/')}/chapters?page=$page", headers).asJsoup()
            document.select("ul.chapter-list li a[href]").mapTo(chapters) { link ->
                SChapter.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    name = link.selectFirst(".chapter-title")?.text() ?: link.attr("title")
                    link.selectFirst(".chapter-no")?.text()?.toFloatOrNull()?.let { chapter_number = it }
                    date_upload = link.selectFirst("time[datetime]")?.attr("datetime")
                        ?.let { runCatching { DATE_FORMAT.parse(it)?.time }.getOrNull() } ?: 0L
                }
            }
            if (document.selectFirst("a[rel=next]") == null) break
            page++
        }
        return chapters.distinctBy { it.url }.reversed()
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val document = client.get(getChapterUrl(chapter), headers).asJsoup()
        val title = document.selectFirst("span.chapter-title")?.text()
        val body = document.selectFirst("div#content")
            ?: return ChapterContent(html = "", title = title ?: chapter.name, locked = true)
        body.select("script, style, iframe, ins, noscript, .adsbox, .nf-ads").remove()
        // Watermark lines are wrapped in random <nf…> tags.
        body.allElements.filter { it.tagName().startsWith("nf", ignoreCase = true) }.forEach { it.remove() }
        body.select("a, span").unwrap()
        body.allElements.forEach { element ->
            val src = if (element.tagName() == "img") element.absUrl("src") else null
            element.clearAttributes()
            if (!src.isNullOrBlank()) element.attr("src", src)
        }
        body.select("p").filter { it.text().isBlank() && it.select("img").isEmpty() }.forEach { it.remove() }
        val html = body.html().ifBlank { return ChapterContent(html = "", title = title ?: chapter.name, locked = true) }
        return ChapterContent(html = html, title = title ?: chapter.name)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "book") return null
        val manga = SManga.create().apply { this.url = "/book/${segments[1]}" }
        return parseDetails(client.get(getMangaUrl(manga), headers).asJsoup(), manga)
    }

    private fun Element.imageUrl(): String? = absUrl("data-src").ifBlank { absUrl("src") }
        .takeIf { it.isNotBlank() && !it.startsWith("data:") }

    companion object {
        private const val MAX_TOC_PAGES = 200
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
