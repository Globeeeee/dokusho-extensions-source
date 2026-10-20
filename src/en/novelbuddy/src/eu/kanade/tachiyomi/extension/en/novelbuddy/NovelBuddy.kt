package eu.kanade.tachiyomi.extension.en.novelbuddy

import dokusho.annotation.Source
import dokusho.network.get
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.asJsoup
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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * NovelBuddy. Listings, the chapter list and chapter text come from api.novelbuddy.me; the novel's
 * API id is only on its page (`__NEXT_DATA__`). Chapter URLs keep the ids in the query.
 */
@Source
abstract class NovelBuddy : DokushoSource() {

    private val apiUrl = "https://api.novelbuddy.me"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 3, period = 1.seconds)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = search(page, sort = "views")

    override suspend fun getLatestUpdates(page: Int): MangasPage = search(page, sort = "latest")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = search(page, query = query)

    private suspend fun search(page: Int, query: String? = null, sort: String? = null): MangasPage {
        val searchUrl = "$apiUrl/titles/search".toHttpUrl().newBuilder()
            .apply { if (!query.isNullOrBlank()) addQueryParameter("q", query) }
            .apply { if (sort != null) addQueryParameter("sort", sort) }
            .addQueryParameter("limit", "24")
            .addQueryParameter("page", page.toString())
            .build()
        val data = client.get(searchUrl, headers).parseAs<ApiResponse<SearchData>>().data ?: return MangasPage(emptyList(), false)
        val novels = data.items.map { item ->
            SManga.create().apply {
                url = item.url
                title = item.name
                thumbnail_url = item.cover?.let { if (it.startsWith("//")) "https:$it" else it }
            }
        }
        return MangasPage(novels, data.pagination?.hasNext ?: (data.items.size >= 24))
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val novel = fetchNovel(manga.url)
        val details = if (fetchDetails) novel.toSManga(manga.url) else manga
        val chapterList = if (fetchChapters) fetchChapterList(novel) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private suspend fun fetchNovel(path: String): NovelDto {
        val script = client.get(baseUrl + path, headers).asJsoup().selectFirst("script#__NEXT_DATA__")?.data()
            ?: error("Novel page has no data; open it in WebView")
        return script.parseAs<NextData>().props.pageProps.initialManga ?: error("Novel not found")
    }

    private fun NovelDto.toSManga(path: String) = SManga.create().apply {
        url = path
        title = name
        thumbnail_url = cover
        author = authors.map { it.name }.distinct().joinToString()
        genre = genres.map { it.name }.joinToString()
        description = summary?.let { Jsoup.parseBodyFragment(it).text() }
        status = when (this@toSManga.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private suspend fun fetchChapterList(novel: NovelDto): List<SChapter> {
        val chaptersUrl = "$apiUrl/titles/${novel.id}/chapters".toHttpUrl().newBuilder()
            .apply { novel.cv?.let { addQueryParameter("cv", it.toString()) } }
            .build()
        val list = client.get(chaptersUrl, headers).parseAs<ApiResponse<ChaptersData>>().data?.chapters.orEmpty()
        return list.map { chapter ->
            SChapter.create().apply {
                this.url = "${chapter.url}?tid=${novel.id}&cid=${chapter.id}"
                name = chapter.name.replace(INVISIBLE, "").replace(SPACES, " ").trim()
                chapter.number?.let { chapter_number = it }
                date_upload = chapter.updatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.substringBefore('?')

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val query = "https://x${chapter.url}".toHttpUrl()
        val titleId = query.queryParameter("tid")
        val chapterId = query.queryParameter("cid")
        if (titleId == null || chapterId == null) return ChapterContent(html = "", title = chapter.name, locked = true)
        val content = client.get("$apiUrl/titles/$titleId/chapters/$chapterId", headers)
            .parseAs<ApiResponse<ChapterData>>().data?.chapter
        val body = Jsoup.parseBodyFragment(content?.content.orEmpty()).body()
        body.select("script, style, iframe, br").remove()
        body.select("a, span").unwrap()
        body.allElements.forEach { element ->
            val src = if (element.tagName() == "img") element.absUrl("src") else null
            element.clearAttributes()
            if (!src.isNullOrBlank()) element.attr("src", src)
        }
        body.select("p").filter { it.text().isBlank() && it.select("img").isEmpty() }.forEach { it.remove() }
        val title = content?.name?.replace(INVISIBLE, "")?.replace(SPACES, " ")?.trim()
        val html = body.html().ifBlank { return ChapterContent(html = "", title = title ?: chapter.name, locked = true) }
        return ChapterContent(html = html, title = title ?: chapter.name)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val path = "/$slug"
        return fetchNovel(path).toSManga(path)
    }

    // DTOs

    @Serializable
    class ApiResponse<T>(val data: T? = null)

    @Serializable
    class SearchData(val items: List<SearchItem> = emptyList(), val pagination: Pagination? = null)

    @Serializable
    class SearchItem(val url: String, val name: String, val cover: String? = null)

    @Serializable
    class Pagination(@SerialName("has_next") val hasNext: Boolean? = null)

    @Serializable
    class NextData(val props: Props)

    @Serializable
    class Props(val pageProps: PageProps)

    @Serializable
    class PageProps(val initialManga: NovelDto? = null)

    @Serializable
    class NovelDto(
        val id: String,
        val name: String,
        val cover: String? = null,
        val summary: String? = null,
        val status: String? = null,
        val cv: Long? = null,
        val authors: List<NamedDto> = emptyList(),
        val genres: List<NamedDto> = emptyList(),
    )

    @Serializable
    class NamedDto(val name: String)

    @Serializable
    class ChaptersData(val chapters: List<ChapterDto> = emptyList())

    @Serializable
    class ChapterDto(
        val id: String,
        val url: String,
        val name: String,
        val number: Float? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
    )

    @Serializable
    class ChapterData(val chapter: ChapterBody? = null)

    @Serializable
    class ChapterBody(val name: String? = null, val content: String? = null)

    companion object {
        private val INVISIBLE = Regex("[\u200b\u200c\u200d\ufeff]")
        private val SPACES = Regex("""\s+""")
    }
}
