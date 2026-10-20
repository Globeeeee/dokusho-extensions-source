package eu.kanade.tachiyomi.extension.en.wuxiaworldeu

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.time.OffsetDateTime

/**
 * wuxiaworld.eu (not the official wuxiaworld.com). A Next.js site: every page carries its data in
 * the `__NEXT_DATA__` script, so listings, the chapter index and chapter text are read from there.
 */
@Source
abstract class WuxiaWorldEu : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = browse("$baseUrl/browse?order_by=views&page=$page")

    override suspend fun getLatestUpdates(page: Int): MangasPage = browse("$baseUrl/browse?order_by=latest&page=$page")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        val listing = pageProps(url.toString())["result"]?.parseAs<ListingDto>() ?: return MangasPage(emptyList(), false)
        return listing.toPage()
    }

    private suspend fun browse(url: String): MangasPage = pageProps(url)["listing"]?.parseAs<ListingDto>()?.toPage()
        ?: MangasPage(emptyList(), false)

    private fun ListingDto.toPage(): MangasPage {
        val novels = items.map { item ->
            SManga.create().apply {
                url = "/novel/${item.slug}"
                title = item.title
                author = item.authorName
                thumbnail_url = coverUrl(item.coverKey)
            }
        }
        val hasNext = (page ?: 1) < (totalPages ?: 1)
        return MangasPage(novels, hasNext)
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val props = pageProps(getMangaUrl(manga))
        val novel = props["novel"]?.parseAs<NovelDto>() ?: return SMangaUpdate(manga, chapters)
        val details = if (fetchDetails) novel.toManga(manga) else manga
        val chapterList = if (fetchChapters) fetchChapterList(novel.slug, props) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun NovelDto.toManga(manga: SManga) = SManga.create().apply {
        url = manga.url
        title = this@toManga.title
        author = this@toManga.author?.name
        description = this@toManga.description
        genre = (genres + tags).map { it.name }.distinct().joinToString()
        thumbnail_url = coverUrl(coverKey) ?: manga.thumbnail_url
        status = when (this@toManga.status) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "DROPPED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    // The index comes 100 chapters per page (`?page=N`); page 1 is already in [firstPage].
    private suspend fun fetchChapterList(slug: String, firstPage: JsonObject): List<SChapter> {
        val firstToc = firstPage["toc"]?.parseAs<TocDto>() ?: return emptyList()
        val items = firstToc.items.toMutableList()
        for (range in firstToc.ranges.drop(1)) {
            items += pageProps("$baseUrl/novel/$slug?page=${range.page}")["toc"]?.parseAs<TocDto>()?.items.orEmpty()
        }
        return items.distinctBy { it.number }.map { item ->
            SChapter.create().apply {
                url = "/chapter/$slug-${item.number}"
                name = item.title.ifBlank { "Chapter ${item.number}" }
                chapter_number = item.number
                date_upload = item.createdAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.getOrNull(1)?.takeIf { url.pathSegments.firstOrNull() == "novel" } ?: return null
        val manga = SManga.create().apply { this.url = "/novel/$slug" }
        return pageProps(getMangaUrl(manga))["novel"]?.parseAs<NovelDto>()?.toManga(manga)
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val data = pageProps(getChapterUrl(chapter))["data"]?.jsonObject
        val content = data?.get("chapter")?.parseAs<ChapterDto>()
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)
        val paragraphs = content.paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return ChapterContent(html = "", title = content.title, locked = true)
        val html = paragraphs.joinToString("") { "<p>${Entities.escape(it)}</p>" }
        return ChapterContent(html = html, title = content.title)
    }

    // Helpers

    private suspend fun pageProps(url: String): JsonObject {
        val document: Document = client.get(url, headers).asJsoup()
        val json = document.selectFirst("script#__NEXT_DATA__")?.data() ?: error("No page data at $url")
        return json.parseAs<JsonObject>()["props"]!!.jsonObject["pageProps"]!!.jsonObject
    }

    private fun coverUrl(coverKey: String?): String? = coverKey?.let { "$CDN/$it-480.webp" }

    @Serializable
    private class ListingDto(val items: List<ListItemDto> = emptyList(), val page: Int? = null, val totalPages: Int? = null)

    @Serializable
    private class ListItemDto(val slug: String, val title: String, val coverKey: String? = null, val authorName: String? = null)

    @Serializable
    private class NovelDto(
        val slug: String,
        val title: String,
        val description: String? = null,
        val status: String? = null,
        val coverKey: String? = null,
        val author: AuthorDto? = null,
        val genres: List<NameDto> = emptyList(),
        val tags: List<NameDto> = emptyList(),
    )

    @Serializable
    private class AuthorDto(val name: String)

    @Serializable
    private class NameDto(val name: String)

    @Serializable
    private class TocDto(val items: List<TocItemDto> = emptyList(), val ranges: List<RangeDto> = emptyList())

    @Serializable
    private class TocItemDto(val number: Float, val title: String = "", val createdAt: String? = null)

    @Serializable
    private class RangeDto(val page: Int)

    @Serializable
    private class ChapterDto(val title: String? = null, val paragraphs: List<String> = emptyList())

    companion object {
        private const val CDN = "https://cdn.wuxiaworld.eu"
    }
}
