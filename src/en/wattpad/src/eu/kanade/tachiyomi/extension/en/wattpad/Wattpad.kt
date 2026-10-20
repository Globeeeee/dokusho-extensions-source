package eu.kanade.tachiyomi.extension.en.wattpad

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
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.time.OffsetDateTime

/**
 * Wattpad's public JSON API. It is served from api.wattpad.com, which also works on networks where
 * www.wattpad.com is blocked; links shown to the user still point at www.
 */
@Source
abstract class Wattpad : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = stories(page) { addQueryParameter("filter", "hot") }

    override suspend fun getLatestUpdates(page: Int): MangasPage = stories(page) { addQueryParameter("filter", "new") }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = stories(page) { addQueryParameter("query", query) }

    private suspend fun stories(page: Int, params: okhttp3.HttpUrl.Builder.() -> Unit): MangasPage {
        val requestUrl = "$API/api/v3/stories".toHttpUrl().newBuilder()
            .apply(params)
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("fields", "stories(id,title,cover,user(name),completed),nextUrl")
            .build()
        val result = client.get(requestUrl, headers).parseAs<StoryListDto>()
        val novels = result.stories.map { story ->
            SManga.create().apply {
                url = "/story/${story.id}"
                title = story.title
                thumbnail_url = story.cover
                author = story.user?.name
            }
        }
        return MangasPage(novels, hasNextPage = result.nextUrl != null)
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = STORY_ID.find(manga.url)?.groupValues?.get(1) ?: return SMangaUpdate(manga, chapters)
        val storyUrl = "$API/api/v3/stories/$id".toHttpUrl().newBuilder()
            .addQueryParameter(
                "fields",
                "id,title,description,cover,user(name),completed,tags,isPaywalled,parts(id,title,url,createDate)",
            )
            .build()
        val story = client.get(storyUrl, headers).parseAs<StoryDto>()
        val details = if (!fetchDetails) {
            manga
        } else {
            SManga.create().apply {
                url = manga.url
                title = story.title
                author = story.user?.name
                description = story.description
                genre = story.tags.joinToString()
                thumbnail_url = story.cover
                status = if (story.completed) SManga.COMPLETED else SManga.ONGOING
                initialized = true
            }
        }
        val chapterList = if (!fetchChapters) {
            chapters
        } else {
            story.parts.mapIndexed { index, part ->
                SChapter.create().apply {
                    url = "/${part.id}"
                    name = part.title
                    chapter_number = (index + 1).toFloat()
                    date_upload = runCatching { OffsetDateTime.parse(part.createDate).toInstant().toEpochMilli() }
                        .getOrDefault(0L)
                }
            }.reversed()
        }
        return SMangaUpdate(details, chapterList)
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val partId = PART_ID.find(chapter.url)?.groupValues?.get(1)
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)
        // Long parts are split into pages; an empty page marks the end.
        val html = buildString {
            for (page in 1..MAX_TEXT_PAGES) {
                val text = client.get("$API/apiv2/?m=storytext&id=$partId&page=$page", headers)
                    .use { it.body.string() }
                if (text.isBlank()) break
                append(text)
            }
        }
        // Paid (Wattpad Originals) parts come back as a short purchase stub instead of the text.
        if (html.isBlank() || PAYWALL_MARKER in html) {
            return ChapterContent(html = "", title = chapter.name, locked = true)
        }
        return ChapterContent(html = html, title = chapter.name)
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    // DTOs

    @Serializable
    private class StoryListDto(val stories: List<StoryDto> = emptyList(), val nextUrl: String? = null)

    @Serializable
    private class StoryDto(
        val id: String = "",
        val title: String = "",
        val description: String? = null,
        val cover: String? = null,
        val user: UserDto? = null,
        val completed: Boolean = false,
        val tags: List<String> = emptyList(),
        val isPaywalled: Boolean = false,
        val parts: List<PartDto> = emptyList(),
    )

    @Serializable
    private class UserDto(val name: String? = null)

    @Serializable
    private class PartDto(val id: Long, val title: String = "", val url: String = "", val createDate: String = "")

    companion object {
        private const val API = "https://api.wattpad.com"
        private const val PAGE_SIZE = 20
        private const val MAX_TEXT_PAGES = 50
        private const val PAYWALL_MARKER = "in-app-purchase-content-public"
        private val STORY_ID = Regex("""/story/(\d+)""")
        private val PART_ID = Regex("""^/?(\d+)""")
    }
}
