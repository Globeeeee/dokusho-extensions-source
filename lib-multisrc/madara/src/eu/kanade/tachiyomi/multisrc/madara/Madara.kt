package eu.kanade.tachiyomi.multisrc.madara

import dokusho.network.get
import dokusho.network.post
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.asJsoup
import dokusho.utils.parseChapterDate
import dokusho.utils.sanitizedChapterHtml
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

/**
 * WordPress sites on the Madara theme with text chapters (wuxiaworld.site, lightnovelheaven.com).
 * Children set [novelPath], the URL segment novels live under.
 */
abstract class Madara : DokushoSource() {

    protected open val novelPath = "novel"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 3, period = 2.seconds)

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = listing(archiveUrl(page, "views"), "div.page-item-detail")

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing(archiveUrl(page, "latest"), "div.page-item-detail")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = listing(
        baseUrl.toHttpUrl().newBuilder()
            .apply { if (page > 1) addPathSegments("page/$page/") }
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")
            .build()
            .toString(),
        "div.c-tabs-item__content",
    )

    private fun archiveUrl(page: Int, order: String): String {
        val pagePath = if (page > 1) "page/$page/" else ""
        return "$baseUrl/$novelPath/$pagePath?m_orderby=$order"
    }

    // The "load more" button is on every page, including the last, so the page after it is a 404.
    private suspend fun listing(url: String, itemSelector: String): MangasPage {
        val response = client.get(url, headers, ensureSuccess = false)
        if (response.code == 404) {
            response.close()
            return MangasPage(emptyList(), false)
        }
        val document = response.asJsoup()
        val novels = document.select(itemSelector).mapNotNull { item ->
            val link = item.selectFirst("div.post-title a[href]") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = item.selectFirst("img")?.imageUrl()
            }
        }
        val hasNext = novels.isNotEmpty() && document.selectFirst("nav.navigation-ajax, div.nav-previous a") != null
        return MangasPage(novels, hasNext)
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = if (fetchDetails) parseDetails(client.get(getMangaUrl(manga), headers).asJsoup(), manga) else manga
        val chapterList = if (fetchChapters) fetchChapterList(manga) else chapters
        return SMangaUpdate(details, chapterList)
    }

    /** A site promo paragraph to drop from the description, such as "You're Reading X at <site>". */
    protected open val promoLine: Regex? = null

    private fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("div.post-title h1")?.apply { select("span").remove() }?.text() ?: manga.title
        thumbnail_url = document.selectFirst("div.summary_image img")?.imageUrl() ?: manga.thumbnail_url
        author = document.select("div.author-content a").eachText().joinToString()
        genre = document.select("div.genres-content a").eachText().joinToString()
        description = document.selectFirst("div.summary__content, div.description-summary")?.let { summary ->
            summary.select("p").eachText().filterNot { promoLine?.containsMatchIn(it) == true }
                .joinToString("\n\n").ifBlank { summary.text() }
        }
        val statusText = document.selectFirst("div.post-content_item:has(h5:contains(Status)) div.summary-content")?.text().orEmpty()
        status = when {
            statusText.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("completed", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("hiatus", ignoreCase = true) || statusText.contains("on hold", ignoreCase = true) -> SManga.ON_HIATUS
            statusText.contains("cancel", ignoreCase = true) || statusText.contains("dropped", ignoreCase = true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    // The novel page has no chapters; this endpoint returns all of them, newest first.
    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val ajaxHeaders = headers.newBuilder().add("X-Requested-With", "XMLHttpRequest").build()
        val novelUrl = getMangaUrl(manga).trimEnd('/')
        val items = client.post("$novelUrl/ajax/chapters/", ajaxHeaders, FormBody.Builder().build())
            .asJsoup().select("li.wp-manga-chapter")
        return items.mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.text().replace(INVISIBLE, "").replace(SPACES, " ").trim()
                CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.toFloatOrNull()?.let { chapter_number = it }
                val release = item.selectFirst("span.chapter-release-date")
                date_upload = parseChapterDate(
                    release?.selectFirst("i")?.text()?.takeIf { it.isNotBlank() }
                        // Recent chapters carry a "2 hours ago" title on a c-new-tag link instead of an <i> date.
                        ?: release?.selectFirst("[title]")?.attr("title")?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val document = client.get(getChapterUrl(chapter), headers).asJsoup()
        val title = document.selectFirst("#chapter-heading")?.text()
        val body = document.selectFirst("div.reading-content")
            ?: return ChapterContent(html = "", title = title ?: chapter.name, locked = true)
        body.select("div.code-block").remove()
        val html = body.sanitizedChapterHtml(imageUrl = { it.imageUrl() }).ifBlank { return ChapterContent(html = "", title = title ?: chapter.name, locked = true) }
        return ChapterContent(html = html, title = title ?: chapter.name)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.")) return null
        val segments = url.pathSegments.filter { it.isNotBlank() }
        if (segments.size < 2 || segments[0] != novelPath) return null
        val manga = SManga.create().apply { this.url = "/$novelPath/${segments[1]}/" }
        return parseDetails(client.get(getMangaUrl(manga), headers).asJsoup(), manga)
    }

    private fun Element.imageUrl(): String? = listOf("data-src", "data-lazy-src", "src").map(::absUrl)
        .firstOrNull { it.startsWith("http") && "dflazy" !in it }

    companion object {
        private val INVISIBLE = Regex("[\u200b\u200c\u200d\ufeff]")
        private val SPACES = Regex("""\s+""")
        private val CHAPTER_NUMBER = Regex("""Chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    }
}
