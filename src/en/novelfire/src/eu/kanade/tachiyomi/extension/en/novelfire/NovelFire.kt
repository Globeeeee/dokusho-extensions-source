package eu.kanade.tachiyomi.extension.en.novelfire

import dokusho.annotation.Source
import dokusho.network.get
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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

/**
 * Novel Fire (the old LightNovelPub/LightNovelWorld catalogue), now served from novelphoenix.com with novels
 * under `/novel/` instead of `/book/`. The site throttles bursts, so requests are kept slow; the chapter
 * list is the `/chapters` TOC at 100 a page.
 */
@Source
abstract class NovelFire : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 2, period = 2.seconds)

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
            val link = item.selectFirst("a[href*=/novel/]") ?: return@mapNotNull null
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
        val seen = HashSet<String>()
        var page = 1
        while (page <= MAX_TOC_PAGES) {
            val document = client.get("$baseUrl${novelPath.toSitePath().trimEnd('/')}/chapters?page=$page", headers).asJsoup()
            val fresh = document.select("ul.chapter-list li a[href]").map { link ->
                SChapter.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    // Library entries added on novelfire.net keep their /book/ chapter urls so read progress matches.
                    if (novelPath.startsWith(LEGACY_PREFIX)) url = url.replaceFirst(SITE_PREFIX, LEGACY_PREFIX)
                    name = link.selectFirst(".chapter-title")?.text() ?: link.attr("title")
                    link.selectFirst(".chapter-no")?.text()?.toFloatOrNull()?.let { chapter_number = it }
                    date_upload = parseChapterDate(
                        link.selectFirst("time[datetime]")?.attr("datetime")?.ifBlank { null }
                            ?: link.selectFirst("time")?.text(),
                    )
                }
            }.filter { seen.add(it.url) }
            chapters += fresh
            if (fresh.isEmpty() || document.selectFirst(TOC_NEXT_SELECTOR) == null) break
            page++
        }
        return chapters.reversed()
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val document = client.get(getChapterUrl(chapter), headers).asJsoup()
        val title = document.selectFirst("span.chapter-title")?.text()
        val body = document.selectFirst("div#content")
            ?: return ChapterContent(html = "", title = title ?: chapter.name, locked = true)
        body.select(".adsbox, .nf-ads").remove()
        // Watermark lines are wrapped in random <nf…> tags.
        body.allElements.filter { it.tagName().startsWith("nf", ignoreCase = true) }.forEach { it.remove() }
        val html = body.sanitizedChapterHtml().ifBlank { return ChapterContent(html = "", title = title ?: chapter.name, locked = true) }
        return ChapterContent(html = html, title = title ?: chapter.name)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") !in setOf(baseUrl.toHttpUrl().host, LEGACY_HOST)) return null
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] !in setOf("novel", "book")) return null
        val manga = SManga.create().apply { this.url = "$SITE_PREFIX${segments[1]}" }
        return parseDetails(client.get(getMangaUrl(manga), headers).asJsoup(), manga)
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url.toSitePath()

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.toSitePath()

    /** Maps a stored novelfire.net `/book/<slug>` url to its novelphoenix.com `/novel/<slug>` path. */
    private fun String.toSitePath(): String = if (startsWith(LEGACY_PREFIX)) SITE_PREFIX + removePrefix(LEGACY_PREFIX) else this

    private fun Element.imageUrl(): String? = absUrl("data-src").ifBlank { absUrl("src") }
        .takeIf { it.isNotBlank() && !it.startsWith("data:") }

    companion object {
        private const val MAX_TOC_PAGES = 200
        private const val LEGACY_HOST = "novelfire.net"
        private const val LEGACY_PREFIX = "/book/"
        private const val SITE_PREFIX = "/novel/"

        // The TOC pages with a chapter range pager; listings still use rel=next.
        private const val TOC_NEXT_SELECTOR =
            "a[rel=next], .chapter-range-pager a[aria-label^=Next]:not([aria-disabled=true])"
    }
}
