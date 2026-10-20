package eu.kanade.tachiyomi.multisrc.readnovelfull

import dokusho.network.get
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.asJsoup
import dokusho.utils.sanitizeChapter
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
import java.text.Normalizer
import kotlin.time.Duration.Companion.seconds

/**
 * Sites on the ReadNovelFull engine (readnovelfull.com, freewebnovel.com, novellive.com, novgo.net, novelphoenix.net). Listing rows,
 * the info block and the chapter body share markup; children set the listing paths and how the
 * chapter list is paged.
 */
abstract class ReadNovelFull : DokushoSource() {

    protected abstract val popularPath: String
    protected abstract val latestPath: String

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 3, period = 2.seconds)

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

    // ReadNovelFull rows, then the FreeWebNovel layout every other child uses.
    private val listingRowSelector = "div.list-novel div.row, div.ul-list1 div.li-row"

    // ReadNovelFull and NovelLive use a Bootstrap pager; FreeWebNovel renders a missing page as a javascript: link.
    protected open val nextPageSelector = "ul.pagination li.next:not(.disabled) a, div.pages a[href^=/]:contains(>>)"

    protected fun parseListing(document: Document): MangasPage {
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

    private fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("div.books h3.title, div.m-desc h1.tit")?.text() ?: manga.title
        thumbnail_url = document.selectFirst("div.books img, div.m-imgtxt .pic img")?.imageUrl() ?: manga.thumbnail_url
        author = document.select("ul.info-meta li:has(h3:contains(Author)) a, div.m-imgtxt a[href*=/author]").eachText().joinToString()
        genre = document.select("ul.info-meta li:has(h3:contains(Genre)) a, div.m-imgtxt a[href*=/genre]")
            .eachText().distinct().joinToString()
        description = document.selectFirst("div.desc-text, div.m-desc div.inner")?.let { desc ->
            desc.select("p").eachText().joinToString("\n\n").ifBlank { desc.wholeText().trim() }
        }
        val statusText = document
            .selectFirst("ul.info-meta li:has(h3:contains(Status)), div.m-imgtxt div.item:has([title=Status])")?.text().orEmpty()
        status = when {
            statusText.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("completed", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    /** Every chapter of the novel, newest first. */
    protected abstract suspend fun fetchChapterList(document: Document, manga: SManga): List<SChapter>

    // Chapter text

    protected open val contentSelector = "div#chr-content, div#article"

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val document = client.get(getChapterUrl(chapter), headers).asJsoup()
        val body = document.selectFirst(contentSelector)
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)
        // ReadNovelFull and FreeWebNovel open the body with the chapter title as a heading.
        val heading = body.children().firstOrNull()?.takeIf { it.tagName() == "h3" || it.tagName() == "h4" }
        val title = heading?.text()
        heading?.remove()
        // Unlock buttons mean the rest of the chapter is behind a coin or login gate.
        if (body.selectFirst("div.unlock-buttons") != null) return ChapterContent(html = "", title = title ?: chapter.name, locked = true)
        body.select("sub, div.ads, .adsbygoogle, div[id^=pf-]").remove()
        // ReadNovelFull puts a <br> between every paragraph.
        body.select("> br").remove()
        body.sanitizeChapter(imageUrl = { it.imageUrl() })
        body.select("p").filter { it.text().length < 160 && WATERMARK.containsMatchIn(it.text().unmasked()) }.forEach { it.remove() }
        val html = body.html().ifBlank { return ChapterContent(html = "", title = title ?: chapter.name, locked = true) }
        return ChapterContent(html = html, title = title ?: chapter.name)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.")) return null
        val manga = SManga.create().apply { this.url = url.encodedPath }
        return parseDetails(client.get(getMangaUrl(manga), headers).asJsoup(), manga)
    }

    // Helpers

    /** Undoes watermark disguises: NFKC folds styled letters such as 𝚎, then zero-width marks go and look-alikes map to ASCII. */
    private fun String.unmasked(): String {
        val folded = Normalizer.normalize(this, Normalizer.Form.NFKC)
        return buildString(folded.length) {
            for (char in folded) {
                if (char in ZERO_WIDTH) continue
                val index = HOMOGLYPHS.indexOf(char)
                append(if (index >= 0) HOMOGLYPH_ASCII[index] else char)
            }
        }
    }

    private fun Element.imageUrl(): String? = absUrl("data-src").ifBlank { absUrl("src") }.takeIf { it.startsWith("http") }

    companion object {
        /** Aggregator watermark lines such as "This chapter is updated by freewebnovel.com". */
        private const val ZERO_WIDTH = "\u00ad\u034f\u180e\u200b\u200c\u200d\u200e\u200f\u2060\u2061\u2062\u2063\u2064\ufeff"

        // Cyrillic and Greek letters that render like Latin ones, paired by position with HOMOGLYPH_ASCII.
        private const val HOMOGLYPHS = "аеіјорсхуѕԁӏԝһкмтвнАВЕКМНОРСТХІЅЈαβεικνορτυχωΑΒΕΖΗΙΚΜΝΟΡΤΥΧı"
        private const val HOMOGLYPH_ASCII = "aeijopcxysdlwhkmtbhABEKMHOPCTXISJabeikvoptuxwABEZHIKMNOPTYXi"

        private val WATERMARK = Regex("""(free\s*web\s*novel|readnovelfull|novellive|novel\s*bin|novgo|all\s*novel\s*full|novel\s*phoenix)\s*\.\s*(com|app|net)""", RegexOption.IGNORE_CASE)
    }
}
