package eu.kanade.tachiyomi.extension.en.novelhi

import dokusho.annotation.Source
import dokusho.network.get
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.asJsoup
import dokusho.utils.parseAs
import dokusho.utils.parseChapterDate
import dokusho.utils.sanitizedChapterHtml
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

/**
 * NovelHi serves listings and the chapter index as JSON. Chapter text comes from a per-chapter
 * token endpoint with letters ROT13-shifted. Guests get a daily reading allowance; when the site
 * refuses the text the chapter shows as locked, and logging in through WebView lifts the limit.
 */
@Source
abstract class NovelHi : DokushoSource() {

    // The search endpoint answers "your search is too fast" above roughly one call per two seconds.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 1, period = 2.seconds)

    override val supportsLatest get() = false

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = shelf(page, keyword = null)

    override suspend fun getLatestUpdates(page: Int): MangasPage = shelf(page, keyword = null)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = shelf(page, query.takeIf { it.isNotBlank() })

    private suspend fun shelf(page: Int, keyword: String?): MangasPage {
        val requestUrl = "$baseUrl/book/searchByPageInShelf".toHttpUrl().newBuilder()
            .addQueryParameter("curr", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .apply { if (keyword != null) addQueryParameter("keyword", keyword) }
            .build()
        val response = client.get(requestUrl, headers).parseAs<ApiResponse<PageDto<BookDto>>>()
        val data = response.data ?: error(response.msg ?: "NovelHi returned no data")
        val novels = data.list.map { book ->
            SManga.create().apply {
                url = "/s/${book.simpleName}"
                title = book.bookName
                author = book.authorName
                thumbnail_url = book.picUrl
                genre = book.genres.joinToString { it.genreName }
                description = Jsoup.parse(book.bookDesc.replace(BR, "\n")).wholeText().trim()
                status = if (book.bookStatus == "1") SManga.COMPLETED else SManga.ONGOING
            }
        }
        return MangasPage(novels, hasNextPage = page * PAGE_SIZE < (data.total.toIntOrNull() ?: 0))
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // /s/<name> redirects to the canonical /novel/<genre>/<slug>, where chapters live.
        val document = client.get(getMangaUrl(manga), headers).asJsoup()
        val details = if (fetchDetails) parseDetails(document, manga) else manga
        val chapterList = if (fetchChapters) fetchChapterList(document) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        val info = document.selectFirst("div.book_info")
        title = info?.selectFirst("h1")?.text() ?: manga.title
        author = info?.infoItem("Author") ?: manga.author
        description = document.selectFirst("p.detail-desc")?.wholeText()?.trim() ?: manga.description
        thumbnail_url = document.selectFirst("img.cover")?.absUrl("src")?.ifBlank { null } ?: manga.thumbnail_url
        genre = manga.genre
        status = when (info?.infoItem("Status")?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> manga.status
        }
        initialized = true
    }

    private fun Element.infoItem(label: String): String? = select("span.item")
        .firstOrNull { it.ownText().startsWith("$label:") }
        ?.selectFirst("em")?.text()

    private suspend fun fetchChapterList(document: Document): List<SChapter> {
        val bookId = document.hidden("bookId") ?: return emptyList()
        val novelPath = document.hidden("canonicalNovelPath") ?: return emptyList()
        val indexUrl = "$baseUrl/book/queryIndexList".toHttpUrl().newBuilder()
            .addQueryParameter("bookId", bookId)
            .addQueryParameter("curr", "1")
            .addQueryParameter("limit", "100000")
            .build()
        val index = client.get(indexUrl, headers).parseAs<ApiResponse<PageDto<IndexDto>>>().data?.list.orEmpty()
        return index.map { item ->
            SChapter.create().apply {
                this.url = "$novelPath/${item.indexNum}"
                name = (if (item.isVip == "1") "$LOCK " else "") + item.indexName
                chapter_number = item.indexNum.toFloatOrNull() ?: -1f
                date_upload = parseChapterDate(item.createTime)
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != "novelhi.com") return null
        val document = client.get(url, headers).asJsoup()
        val simpleName = document.hidden("bookSimpleName") ?: return null
        return parseDetails(document, SManga.create().apply { this.url = "/s/$simpleName" })
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val chapterUrl = getChapterUrl(chapter)
        val page = client.get(chapterUrl, headers).asJsoup()
        val title = page.hidden("currentIndexName") ?: chapter.name
        val path = page.hidden("chapterContentPath")
        val token = page.hidden("chapterContentToken")
        if (path == null || token == null) return ChapterContent(html = "", title = title, locked = true)

        val contentHeaders = headers.newBuilder()
            .set("Referer", chapterUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        // The path comes from the page; keep it on this host.
        val contentUrl = "$baseUrl/${path.trimStart('/')}".toHttpUrl()
            .newBuilder().addQueryParameter("token", token).build()
        val response = client.get(contentUrl, contentHeaders, ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            return ChapterContent(html = "", title = title, locked = true)
        }
        // Daily guest limit and VIP chapters come back without text.
        val raw = response.parseAs<ApiResponse<ContentDto>>().data?.content?.takeIf { it.isNotBlank() }
            ?: return ChapterContent(html = "", title = title, locked = true)

        val body = Jsoup.parseBodyFragment(rot13OutsideTags(raw), baseUrl).body()
        body.select("sent").tagName("p")
        body.select("br").remove()
        return ChapterContent(html = body.sanitizedChapterHtml(), title = title)
    }

    // Helpers

    private fun Document.hidden(id: String): String? = selectFirst("input#$id")?.attr("value")?.takeIf { it.isNotBlank() }

    private fun rot13OutsideTags(text: String): String = TAG_OR_LETTER.replace(text) { match ->
        match.groupValues[1].ifEmpty {
            val c = match.value[0]
            val base = if (c <= 'Z') 'A' else 'a'
            Char((c - base + 13) % 26 + base.code).toString()
        }
    }

    @Serializable
    private class ApiResponse<T>(val code: String? = null, val msg: String? = null, val data: T? = null)

    @Serializable
    private class PageDto<T>(val total: String = "0", val list: List<T> = emptyList())

    @Serializable
    private class BookDto(
        val bookName: String,
        val simpleName: String,
        val authorName: String? = null,
        val picUrl: String? = null,
        val bookDesc: String = "",
        val bookStatus: String? = null,
        val genres: List<GenreDto> = emptyList(),
    )

    @Serializable
    private class GenreDto(val genreName: String = "")

    @Serializable
    private class IndexDto(
        val indexNum: String,
        val indexName: String = "",
        val isVip: String? = null,
        val createTime: String? = null,
    )

    @Serializable
    private class ContentDto(val content: String? = null)

    companion object {
        private const val PAGE_SIZE = 20
        private const val LOCK = "🔒"
        private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val TAG_OR_LETTER = Regex("(<[^>]+>)|[a-zA-Z]")
    }
}
