package eu.kanade.tachiyomi.multisrc.fanfiction

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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import kotlin.time.Duration.Companion.seconds

/**
 * FanFiction.Net and FictionPress run the same engine: `div.z-list` listings, a `#profile_top`
 * header, the chapter list in `select#chap_select` and the text in `#storytext`.
 */
abstract class FanFiction : DokushoSource() {

    /** Category archive the popular listing sorts by favourites, e.g. `/book/Harry-Potter/`. */
    protected abstract val popularPath: String

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 1, period = 2.seconds)

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = listing("$baseUrl$popularPath?&srt=4&r=10&p=$page")

    // "Just In: Stories Updated" is one page with no pager.
    override suspend fun getLatestUpdates(page: Int): MangasPage = if (page > 1) {
        MangasPage(emptyList(), false)
    } else {
        MangasPage(listing("$baseUrl/j/0/2/0/").mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchUrl = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", query)
            .addQueryParameter("ready", "1")
            .addQueryParameter("type", "story")
            .addQueryParameter("ppage", page.toString())
            .build()
        return listing(searchUrl.toString())
    }

    private suspend fun listing(url: String): MangasPage {
        val document = client.get(url, headers).asJsoup()
        val stories = document.select("div.z-list").mapNotNull { row ->
            val link = row.selectFirst("a.stitle[href^=/s/]") ?: return@mapNotNull null
            SManga.create().apply {
                this.url = storyPath(link.attr("href")) ?: return@mapNotNull null
                title = link.text()
                author = row.selectFirst("a[href^=/u/]")?.text()
                thumbnail_url = row.selectFirst("img.cimage[data-original]")?.let { coverUrl(it.absUrl("data-original")) }
            }
        }
        return MangasPage(stories, document.selectFirst("a:matchesOwn(^Next)") != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val path = storyPath(url.encodedPath) ?: return null
        val manga = SManga.create().apply { this.url = path }
        return parseDetails(fetchFirstChapter(manga), manga)
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = fetchFirstChapter(manga)
        val details = if (fetchDetails) parseDetails(document, manga) else manga
        val chapterList = if (fetchChapters) parseChapters(document, manga) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private suspend fun fetchFirstChapter(manga: SManga): Document = client.get("$baseUrl${manga.url}1/", headers).asJsoup()

    private fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        val profile = document.selectFirst("#profile_top") ?: error("Story not found")
        url = manga.url
        title = profile.selectFirst("> b.xcontrast_txt")?.text() ?: manga.title
        author = profile.selectFirst("a[href^=/u/]")?.text()
        description = profile.selectFirst("> div.xcontrast_txt")?.text()
        thumbnail_url = document.selectFirst("#img_large img[data-original]")?.absUrl("data-original")
            ?: profile.selectFirst("img.cimage")?.let { coverUrl(it.absUrl("src")) }
            ?: manga.thumbnail_url
        val meta = profile.selectFirst("span.xgray")?.text().orEmpty()
        val parts = meta.split(" - ").map { it.trim() }
        val rating = parts.firstOrNull { it.startsWith("Rated:") }?.substringAfterLast(' ')?.let { "Rated $it" }
        val genres = parts.drop(1).take(2).firstOrNull(::isGenreList)
            ?.replace(HURT_COMFORT, "Hurt|Comfort")
            ?.split('/')
            ?.map { it.replace("Hurt|Comfort", HURT_COMFORT) }
            .orEmpty()
        val fandom = document.select("#pre_story_links a").lastOrNull()?.text()
        genre = (listOfNotNull(fandom, rating) + genres).distinct().joinToString()
        status = if (parts.any { it == "Status: Complete" }) SManga.COMPLETED else SManga.ONGOING
        initialized = true
    }

    private fun isGenreList(part: String): Boolean = ':' !in part &&
        part.replace(HURT_COMFORT, "General").split('/').all { it in GENRES }

    /** Chapter rows carry no date; the first gets the publish date and the newest the update date. */
    private fun parseChapters(document: Document, manga: SManga): List<SChapter> {
        val id = STORY_ID.find(manga.url)?.groupValues?.get(1) ?: error("Not a story: ${manga.url}")
        val options = document.selectFirst("select#chap_select")?.select("option").orEmpty()
        val chapters = if (options.isEmpty()) {
            listOf(chapter(id, 1, document.selectFirst("#profile_top > b.xcontrast_txt")?.text() ?: manga.title))
        } else {
            options.mapNotNull { option ->
                val number = option.attr("value").toIntOrNull() ?: return@mapNotNull null
                chapter(id, number, option.text())
            }
        }
        val profile = document.selectFirst("#profile_top")
        val published = profile?.let { xutime(it, "Published") }
        val updated = profile?.let { xutime(it, "Updated") } ?: published
        chapters.first().date_upload = published ?: 0L
        if (chapters.size > 1) chapters.last().date_upload = updated ?: 0L
        return chapters.reversed()
    }

    private fun chapter(storyId: String, number: Int, title: String): SChapter = SChapter.create().apply {
        url = "/s/$storyId/$number/"
        name = title
        chapter_number = number.toFloat()
    }

    /** Epoch millis of the `span[data-xutime]` that follows "[label]:" in the metadata line. */
    private fun xutime(profile: Element, label: String): Long? = profile.select("span[data-xutime]").firstOrNull { span ->
        (span.previousSibling() as? TextNode)?.text()?.trimEnd()?.endsWith("$label:") == true
    }?.attr("data-xutime")?.toLongOrNull()?.times(1000)

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val document = client.get(getChapterUrl(chapter), headers).asJsoup()
        val body = document.selectFirst("#storytext") ?: error("Chapter text not found")
        val title = document.selectFirst("select#chap_select option[selected]")?.text()
        return ChapterContent(html = body.sanitizedChapterHtml(), title = title ?: chapter.name)
    }

    /** `/s/<id>/` from any story or chapter link. */
    private fun storyPath(href: String): String? = STORY_ID.find(href)?.let { "/s/${it.groupValues[1]}/" }

    /** Listing covers are 75 px wide; the same image at 180 is the one the story page enlarges. */
    private fun coverUrl(url: String): String = url.replace(COVER_SIZE, "/180/")

    companion object {
        private val STORY_ID = Regex("""/s/(\d+)""")
        private val COVER_SIZE = Regex("""/\d+/$""")
        private const val HURT_COMFORT = "Hurt/Comfort"
        private val GENRES = setOf(
            "Adventure", "Angst", "Crime", "Drama", "Family", "Fantasy", "Friendship", "General", "Horror",
            "Humor", "Mystery", "Parody", "Poetry", "Romance", "Sci-Fi", "Spiritual", "Supernatural",
            "Suspense", "Tragedy", "Western",
        )
    }
}
