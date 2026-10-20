package eu.kanade.tachiyomi.extension.en.gutenberg

import dokusho.annotation.Source
import dokusho.network.get
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.asJsoup
import dokusho.utils.sanitizedChapterHtml
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Public-domain books from Project Gutenberg. The catalogue comes from Gutenberg's own OPDS feed
 * (fast and official); each book's HTML edition is split into chapters at its h2 headings.
 */
@Source
abstract class ProjectGutenberg : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(1)

    // The last book's cleaned HTML, so consecutive chapters of one book are cut from a single download.
    @Volatile
    private var lastBook: Pair<String, Document>? = null

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = catalogue(page) { addQueryParameter("sort_order", "downloads") }

    override suspend fun getLatestUpdates(page: Int): MangasPage = catalogue(page) { addQueryParameter("sort_order", "release_date") }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = catalogue(page) { addQueryParameter("query", query) }

    private suspend fun catalogue(page: Int, params: okhttp3.HttpUrl.Builder.() -> Unit): MangasPage {
        val requestUrl = "$baseUrl/ebooks/search.opds/".toHttpUrl().newBuilder()
            .apply(params)
            .addQueryParameter("start_index", ((page - 1) * PAGE_SIZE + 1).toString())
            .build()
        val feed = client.get(requestUrl, headers).asJsoup(Parser.xmlParser())
        // Navigation entries (sort links) have no numeric book id and are skipped.
        val books = feed.select("entry").mapNotNull { entry ->
            val id = entry.bookId() ?: return@mapNotNull null
            SManga.create().apply {
                url = "/ebooks/$id"
                title = entry.selectFirst("title")?.text().orEmpty()
                author = entry.selectFirst("content")?.text()?.takeIf { it.isNotBlank() }
                thumbnail_url = coverUrl(id)
            }
        }
        return MangasPage(books, hasNextPage = feed.selectFirst("link[rel=next]") != null)
    }

    private fun Element.bookId(): String? = BOOK_FEED_ID.find(selectFirst("id")?.text().orEmpty())?.groupValues?.get(1)

    private fun coverUrl(id: String) = "$baseUrl/cache/epub/$id/pg$id.cover.medium.jpg"

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = BOOK_ID.find(manga.url)?.groupValues?.get(1) ?: return SMangaUpdate(manga, chapters)
        val details = if (!fetchDetails) {
            manga
        } else {
            val entry = client.get("$baseUrl/ebooks/$id.opds", headers).asJsoup(Parser.xmlParser())
                .select("entry").firstOrNull { it.bookId() == id }
            SManga.create().apply {
                url = manga.url
                title = entry?.selectFirst("title")?.text() ?: manga.title
                author = entry?.select("author > name")?.eachText()?.joinToString()?.ifBlank { null } ?: manga.author
                description = entry?.selectFirst("content")?.text()
                genre = entry?.select("category")?.map { it.attr("term") }?.filter { it.isNotBlank() }?.take(12)?.joinToString()
                thumbnail_url = coverUrl(id)
                status = SManga.COMPLETED
                initialized = true
            }
        }
        val chapterList = if (!fetchChapters) {
            chapters
        } else {
            val headings = bookHtml(id).select("h2").map { it.text().ifBlank { null } }
            if (headings.isEmpty()) {
                listOf(chapter(id, 0, "Full text"))
            } else {
                headings.mapIndexed { index, name -> chapter(id, index, name ?: "Part ${index + 1}") }.reversed()
            }
        }
        return SMangaUpdate(details, chapterList)
    }

    private fun chapter(id: String, index: Int, name: String) = SChapter.create().apply {
        url = "/ebooks/$id#$index"
        this.name = name
        chapter_number = (index + 1).toFloat()
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val id = BOOK_ID.find(chapter.url)?.groupValues?.get(1)
            ?: throw Exception("No Project Gutenberg book id in chapter URL ${chapter.url}")
        val index = chapter.url.substringAfter('#', "0").toIntOrNull() ?: 0
        val document = bookHtml(id)
        val headings = document.select("h2")
        if (headings.isEmpty()) return ChapterContent(html = document.body().sanitized(), title = chapter.name)
        // Mark every heading, then cut the body between this chapter's mark and the next.
        headings.forEachIndexed { i, heading -> heading.before("<hr data-chapter=\"$i\">") }
        val html = document.body().html()
        val start = html.indexOf("data-chapter=\"$index\"").takeIf { it >= 0 }
            ?: return ChapterContent(html = "", title = chapter.name)
        val end = html.indexOf("data-chapter=\"${index + 1}\"").takeIf { it >= 0 } ?: html.length
        val piece = html.substring(html.indexOf('>', start) + 1, html.lastIndexOf('<', end).coerceAtLeast(start))
        val body = Jsoup.parseBodyFragment(piece).body()
        body.select("h2").first()?.remove()
        body.select("hr[data-chapter], a.pginternal").remove()
        return ChapterContent(html = body.sanitized(), title = chapter.name)
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.substringBefore('#')

    /**
     * The book's HTML edition. Images are made absolute here, against the edition's own URL, before the text is cut up.
     * Returns a copy of the cached document, since callers modify it.
     */
    private suspend fun bookHtml(id: String): Document {
        lastBook?.let { (cachedId, cached) -> if (cachedId == id) return cached.clone() }
        val document = client.get("$baseUrl/ebooks/$id.html.images", headers).asJsoup()
        document.select("#pg-header, #pg-footer, section.pg-boilerplate, div.pg-boilerplate, span.pagenum").remove()
        document.select("img").forEach { it.attr("src", it.absUrl("src")) }
        lastBook = id to document
        return document.clone()
    }

    /** Image sources were made absolute in [bookHtml]; the cut-out fragment has no base URL. */
    private fun Element.sanitized(): String = sanitizedChapterHtml(imageUrl = { it.attr("src") })

    companion object {
        private const val PAGE_SIZE = 25
        private val BOOK_FEED_ID = Regex("""/ebooks/(\d+)\.opds""")
        private val BOOK_ID = Regex("""/ebooks/(\d+)""")
    }
}
