package eu.kanade.tachiyomi.extension.en.shadowslave

import dokusho.annotation.Source
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
import okhttp3.OkHttpClient

/**
 * A single-novel site for Shadow Slave. The catalogue is that one book; chapters live at
 * `/chapters/N/` and the home page links the latest one.
 */
@Source
abstract class ShadowSlave : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    override val supportsLatest get() = false

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(listOf(novel()), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = if ("shadow slave".contains(query.trim(), ignoreCase = true) || query.isBlank()) getPopularManga(page) else MangasPage(emptyList(), false)

    private fun novel() = SManga.create().apply {
        url = "/"
        title = "Shadow Slave"
        author = "Guiltythree"
        thumbnail_url = COVER
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val home = client.get(baseUrl, headers).asJsoup()
        val details = if (!fetchDetails) {
            manga
        } else {
            novel().apply {
                description = home.selectFirst("meta[name=description]")?.attr("content")
                genre = "Action, Adventure, Fantasy, Mystery"
                status = SManga.ONGOING
                initialized = true
            }
        }
        val chapterList = if (!fetchChapters) {
            chapters
        } else {
            val latest = home.select("a[href^=/chapters/]").mapNotNull { CHAPTER_NUMBER.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
                .maxOrNull() ?: 0
            (latest downTo 1).map { number ->
                SChapter.create().apply {
                    url = "/chapters/$number/"
                    name = "Chapter $number"
                    chapter_number = number.toFloat()
                }
            }
        }
        return SMangaUpdate(details, chapterList)
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val document = client.get(getChapterUrl(chapter), headers).asJsoup()
        val body = document.selectFirst("article div.prose")
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)
        val title = document.selectFirst("article h2")?.text()
            ?.removePrefix("Shadow Slave Chapter ")?.substringAfter(" - ")
        body.select("script, style, iframe, ins, noscript, .adsbygoogle").remove()
        body.select("a, span").unwrap()
        body.allElements.forEach { element ->
            val src = if (element.tagName() == "img") element.absUrl("src") else null
            element.clearAttributes()
            if (!src.isNullOrBlank()) element.attr("src", src)
        }
        body.select("p").filter { it.text().isBlank() && it.select("img").isEmpty() }.forEach { it.remove() }
        return ChapterContent(html = body.html(), title = title ?: chapter.name)
    }

    companion object {
        private const val COVER = "https://manga.xtdz.top/shadow-slave/cover.webp"
        private val CHAPTER_NUMBER = Regex("""/chapters/(\d+)""")
    }
}
