package eu.kanade.tachiyomi.extension.en.wuxiaworldeu

import dokusho.annotation.Source
import dokusho.network.get
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.asJsoup
import dokusho.utils.parseAs
import dokusho.utils.parseChapterDate
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Entities

/**
 * wuxiaworld.eu (not the official wuxiaworld.com). A Next.js site: every page carries its data in
 * the `__NEXT_DATA__` script, so listings, the chapter index and chapter text are read from there.
 */
@Source
abstract class WuxiaWorldEu : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = browse("$baseUrl/browse?order_by=views&page=$page")

    override suspend fun getLatestUpdates(page: Int): MangasPage = browse("$baseUrl/browse?order_by=latest&page=$page")

    // `/search?q=` redirects to `/search/<q>` and drops `page`, so the query goes in the path.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addPathSegment(query.trim())
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
        val page = nextData(getMangaUrl(manga))
        val novel = page.props.pageProps["novel"]?.parseAs<NovelDto>() ?: return SMangaUpdate(manga, chapters)
        val details = if (fetchDetails) novel.toManga(manga) else manga
        val chapterList = if (fetchChapters) fetchChapterList(novel.slug, page) else chapters
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

    /**
     * The index comes 100 chapters per page. Page 1 is in [firstPage]; the rest come from the Next.js
     * data route (about 26 KB a page instead of 250 KB of HTML).
     */
    private suspend fun fetchChapterList(slug: String, firstPage: NextDataDto): List<SChapter> {
        val firstToc = firstPage.props.pageProps["toc"]?.parseAs<TocDto>() ?: return emptyList()
        val items = firstToc.items.toMutableList()
        val seen = items.mapTo(HashSet()) { it.number }
        val firstPageNumber = firstToc.range?.page ?: 1
        for (range in firstToc.ranges.filter { it.page != firstPageNumber }.take(MAX_TOC_PAGES)) {
            val dataUrl = "$baseUrl/_next/data".toHttpUrl().newBuilder()
                .addPathSegment(firstPage.buildId)
                .addPathSegment("novel")
                .addPathSegment("$slug.json")
                .addQueryParameter("page", range.page.toString())
                .build()
            val fresh = client.get(dataUrl, headers).parseAs<PropsDto>().pageProps["toc"]
                ?.parseAs<TocDto>()?.items.orEmpty()
                .filter { seen.add(it.number) }
            if (fresh.isEmpty()) break
            items += fresh
        }
        return items.map { item ->
            val number = item.number.toUrlNumber()
            SChapter.create().apply {
                url = "/chapter/$slug-$number"
                name = item.title.ifBlank { "Chapter $number" }
                chapter_number = item.number
                date_upload = parseChapterDate(item.createdAt)
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { url.pathSegments.firstOrNull() == "novel" } ?: return null
        val manga = SManga.create().apply { this.url = "/novel/$slug" }
        return pageProps(getMangaUrl(manga))["novel"]?.parseAs<NovelDto>()?.toManga(manga)
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val data = pageProps(getChapterUrl(chapter))["data"] as? JsonObject
        val content = (data?.get("chapter") as? JsonObject)?.parseAs<ChapterDto>()
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)
        val paragraphs = content.paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return ChapterContent(html = "", title = content.title, locked = true)
        val html = paragraphs.joinToString("") { "<p>${Entities.escape(it)}</p>" }
        return ChapterContent(html = html, title = content.title)
    }

    // Helpers

    private suspend fun nextData(url: String): NextDataDto {
        val json = client.get(url, headers).asJsoup().selectFirst("script#__NEXT_DATA__")?.data()
            ?: error("No page data at $url")
        return json.parseAs<NextDataDto>()
    }

    private suspend fun pageProps(url: String): JsonObject = nextData(url).props.pageProps

    // The site's numbers are whole except for side chapters; its URLs use `12`, not `12.0`.
    private fun Float.toUrlNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()

    private fun coverUrl(coverKey: String?): String? = coverKey?.let { "$CDN/$it-480.webp" }

    @Serializable
    private class NextDataDto(val buildId: String = "", val props: PropsDto = PropsDto())

    @Serializable
    private class PropsDto(val pageProps: JsonObject = JsonObject(emptyMap()))

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
    private class TocDto(
        val items: List<TocItemDto> = emptyList(),
        val range: RangeDto? = null,
        val ranges: List<RangeDto> = emptyList(),
    )

    @Serializable
    private class TocItemDto(val number: Float, val title: String = "", val createdAt: String? = null)

    @Serializable
    private class RangeDto(val page: Int)

    @Serializable
    private class ChapterDto(val title: String? = null, val paragraphs: List<String> = emptyList())

    companion object {
        private const val CDN = "https://cdn.wuxiaworld.eu"
        private const val MAX_TOC_PAGES = 300
    }
}
