package eu.kanade.tachiyomi.extension.en.gutenberg

import dokusho.annotation.Source
import dokusho.network.get
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.parseAs
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Public-domain books from Project Gutenberg. The catalogue comes from Gutendex; each book's HTML
 * edition is split into chapters at its h2 headings.
 */
@Source
abstract class ProjectGutenberg : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(1)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = books(page) {}

    override suspend fun getLatestUpdates(page: Int): MangasPage = books(page) { addQueryParameter("sort", "descending") }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = books(page) { addQueryParameter("search", query) }

    private suspend fun books(page: Int, params: okhttp3.HttpUrl.Builder.() -> Unit): MangasPage {
        val requestUrl = "$API/books/".toHttpUrl().newBuilder()
            .apply(params)
            .addQueryParameter("page", page.toString())
            .build()
        val result = client.get(requestUrl, headers).parseAs<BookListDto>()
        return MangasPage(result.results.map { it.toManga() }, hasNextPage = result.next != null)
    }

    private fun BookDto.toManga() = SManga.create().apply {
        url = "/ebooks/$id"
        title = this@toManga.title
        author = authors.joinToString { it.name }
        thumbnail_url = formats["image/jpeg"]
        description = summaries.firstOrNull()
        genre = (bookshelves.map { it.removePrefix("Category: ") } + subjects).distinct().take(12).joinToString()
        status = SManga.COMPLETED
    }

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
            client.get("$API/books/$id", headers).parseAs<BookDto>().toManga().apply {
                url = manga.url
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
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)
        val index = chapter.url.substringAfter('#', "0").toIntOrNull() ?: 0
        val document = bookHtml(id)
        val headings = document.select("h2")
        if (headings.isEmpty()) return ChapterContent(html = document.body().html(), title = chapter.name)
        // Mark every heading, then cut the body between this chapter's mark and the next.
        headings.forEachIndexed { i, heading -> heading.before("<hr data-chapter=\"$i\">") }
        val html = document.body().html()
        val start = html.indexOf("data-chapter=\"$index\"").takeIf { it >= 0 }
            ?: return ChapterContent(html = "", title = chapter.name)
        val end = html.indexOf("data-chapter=\"${index + 1}\"").takeIf { it >= 0 } ?: html.length
        val piece = html.substring(html.indexOf('>', start) + 1, html.lastIndexOf('<', end).coerceAtLeast(start))
        val body = Jsoup.parseBodyFragment(piece).body()
        body.select("h2").first()?.remove()
        body.select("hr[data-chapter], span.pagenum, a.pginternal").remove()
        return ChapterContent(html = body.html(), title = chapter.name)
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.substringBefore('#')

    private suspend fun bookHtml(id: String): Document {
        val document = client.get("$baseUrl/ebooks/$id.html.images", headers).use { Jsoup.parse(it.body.string(), baseUrl) }
        document.select("#pg-header, #pg-footer, section.pg-boilerplate, div.pg-boilerplate, script, style").remove()
        return document
    }

    // DTOs

    @Serializable
    private class BookListDto(val results: List<BookDto> = emptyList(), val next: String? = null)

    @Serializable
    private class BookDto(
        val id: Int,
        val title: String = "",
        val authors: List<PersonDto> = emptyList(),
        val subjects: List<String> = emptyList(),
        val bookshelves: List<String> = emptyList(),
        val summaries: List<String> = emptyList(),
        val formats: Map<String, String> = emptyMap(),
    )

    @Serializable
    private class PersonDto(@SerialName("name") val name: String = "")

    companion object {
        private const val API = "https://gutendex.com"
        private val BOOK_ID = Regex("""/ebooks/(\d+)""")
    }
}
