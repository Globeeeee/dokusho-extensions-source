package eu.kanade.tachiyomi.multisrc.readnovelfull

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
import kotlin.time.Duration.Companion.seconds

/**
 * Sites on the ReadNovelFull engine (readnovelfull.com, freewebnovel.com, novellive.app, novgo.net). Listing rows,
 * the info block and the chapter body share markup; children set the listing paths and how the
 * chapter list is paged.
 */
abstract class ReadNovelFull : DokushoSource() {

    protected abstract val popularPath: String
    protected abstract val latestPath: String

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 3, period = 2.seconds)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = listing(listingUrl(popularPath, page))

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing(listingUrl(latestPath, page))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = listing(
        "$baseUrl/novel-list/search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .build()
            .toString(),
    )

    /** Page 1 of [path] and later pages; query style (`?page=2`) by default. */
    protected open fun listingUrl(path: String, page: Int): String = "$baseUrl/$path?page=$page"

    protected suspend fun listing(url: String): MangasPage = parseListing(client.get(url, headers).asJsoup())

    protected open val listingRowSelector = "div.list-novel div.row, div.ul-list1 div.li-row"

    protected open val nextPageSelector = "ul.pagination li.next:not(.disabled) a, div.pages a:contains(>>)"

    protected open fun parseListing(document: Document): MangasPage {
        val novels = document.select(listingRowSelector).mapNotNull(::listingRow)
        return MangasPage(novels, document.selectFirst(nextPageSelector) != null)
    }

    protected open fun listingRow(row: Element): SManga? {
        val link = row.selectFirst("h3.novel-title a, h3.tit a") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = link.attr("title").ifBlank { link.text() }
            thumbnail_url = row.selectFirst("img")?.imageUrl()
        }
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
        val chapterList = if (fetchChapters) fetchChapterList(document, manga) else chapters
        return SMangaUpdate(details, chapterList)
    }

    protected open fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("div.books h3.title, div.m-desc h1.tit")?.text() ?: manga.title
        thumbnail_url = document.selectFirst("div.books img, div.m-imgtxt .pic img")?.imageUrl() ?: manga.thumbnail_url
        author = document.select("ul.info-meta li:has(h3:contains(Author)) a, div.m-imgtxt a[href*=/author]")
            .eachText().joinToString().ifBlank { document.metaContent("og:novel:author") }
        genre = document.select("ul.info-meta li:has(h3:contains(Genre)) a, div.m-imgtxt a[href*=/genre]")
            .eachText().distinct().joinToString().ifBlank { document.metaContent("og:novel:genre") }
        description = document.selectFirst("div.desc-text, div.m-desc div.inner")?.let { desc ->
            desc.select("p").eachText().joinToString("\n\n").ifBlank { desc.wholeText().trim() }
        }
        val statusText = document.selectFirst("ul.info-meta li:has(h3:contains(Status)), div.m-imgtxt div.item:has([title=Status])")?.text()
            ?: document.metaContent("og:novel:status").orEmpty()
        status = when {
            statusText.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("completed", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    protected abstract suspend fun fetchChapterList(document: Document, manga: SManga): List<SChapter>

    /** Latest chapter number from the `#indexselect` page picker ("C.3201 - C.3220" gives 3220). */
    protected fun latestFromIndexSelect(document: Document): Int = document.select("#indexselect option").lastOrNull()?.text()
        ?.let { TRAILING_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: 0

    /** Chapters 1..[latest] at `<novel path>/chapter-N`, newest first, for sites with stable chapter URLs. */
    protected fun numberedChapters(novelPath: String, latest: Int): List<SChapter> = (latest downTo 1).map { number ->
        SChapter.create().apply {
            url = "${novelPath.trimEnd('/')}/chapter-$number"
            name = "Chapter $number"
            chapter_number = number.toFloat()
        }
    }

    // Chapter text

    protected open val contentSelector = "div#chr-content, div#article"

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val document = client.get(getChapterUrl(chapter), headers).asJsoup()
        val body = document.selectFirst(contentSelector)
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)
        val title = body.selectFirst("h3, h4")?.text()
        body.select("h3, h4").first()?.remove()
        body.select("script, style, iframe, ins, noscript, sub, div.ads, .adsbygoogle, div[id^=pf-], div.unlock-buttons").remove()
        body.select("a, span").unwrap()
        body.allElements.forEach { element ->
            val src = if (element.tagName() == "img") element.absUrl("src") else null
            element.clearAttributes()
            if (!src.isNullOrBlank()) element.attr("src", src)
        }
        body.select("p").filter { it.text().isBlank() && it.select("img").isEmpty() }.forEach { it.remove() }
        body.select("p").filter { WATERMARK.containsMatchIn(it.text()) && it.text().length < 160 }.forEach { it.remove() }
        val html = body.html().ifBlank { return ChapterContent(html = "", title = title ?: chapter.name, locked = true) }
        return ChapterContent(html = html, title = title ?: chapter.name)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.")) return null
        val manga = SManga.create().apply { this.url = url.encodedPath }
        return parseDetails(client.get(getMangaUrl(manga), headers).asJsoup(), manga)
    }

    // Helpers

    protected fun Element.imageUrl(): String? = absUrl("data-src").ifBlank { absUrl("src") }.takeIf { it.isNotBlank() }

    protected fun Document.metaContent(property: String): String? = selectFirst("meta[property=$property]")?.attr("content")?.takeIf { it.isNotBlank() }

    companion object {
        private val TRAILING_NUMBER = Regex("""(\d+)\D*$""")

        /** Aggregator watermark lines such as "This chapter is updated by freewebnovel.com". */
        private val WATERMARK = Regex("""(free\s*web\s*novel|readnovelfull|novellive|novel\s*bin|novgo|all\s*novel\s*full)\s*\.\s*(com|app|net)""", RegexOption.IGNORE_CASE)
    }
}
