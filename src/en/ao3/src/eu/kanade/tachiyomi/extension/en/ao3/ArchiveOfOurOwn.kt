package eu.kanade.tachiyomi.extension.en.ao3

import dokusho.annotation.Source
import dokusho.network.get
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.asJsoup
import dokusho.utils.parseChapterDate
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
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ArchiveOfOurOwn : DokushoSource() {

    // AO3 asks crawlers to stay off search and view_adult; a reader making single requests stays slow.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 1, period = 2.seconds)

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = search(page, "", "kudos_count")

    override suspend fun getLatestUpdates(page: Int): MangasPage = search(page, "", "revised_at")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = search(page, query, "_score")

    private suspend fun search(page: Int, query: String, sort: String): MangasPage {
        val searchUrl = "$baseUrl/works/search".toHttpUrl().newBuilder()
            .addQueryParameter("work_search[query]", query)
            .addQueryParameter("work_search[sort_column]", sort)
            .addQueryParameter("page", page.toString())
            .build()
        val document = client.get(searchUrl, headers).asJsoup()
        val works = document.select("li.work.blurb").mapNotNull { blurb ->
            val link = blurb.selectFirst("h4.heading a[href^=/works/]") ?: return@mapNotNull null
            SManga.create().apply {
                url = WORK_ID.find(link.attr("href"))?.let { "/works/${it.groupValues[1]}" } ?: return@mapNotNull null
                title = link.text()
                author = blurb.select("h4.heading a[rel=author]").eachText().joinToString().ifBlank { null }
            }
        }
        return MangasPage(works, document.selectFirst("ol.pagination li.next a[rel=next]") != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != "archiveofourown.org") return null
        val id = WORK_ID.find(url.encodedPath)?.groupValues?.get(1) ?: return null
        val manga = SManga.create().apply { this.url = "/works/$id" }
        return parseDetails(fetchWork(manga), manga)
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = if (fetchDetails) parseDetails(fetchWork(manga), manga) else manga
        val chapterList = if (fetchChapters) fetchChapters(manga) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private suspend fun fetchWork(manga: SManga): Document {
        val document = client.get("${getMangaUrl(manga)}?view_adult=true", headers).asJsoup()
        if (document.isLoginPage()) error(LOGIN_MESSAGE)
        return document
    }

    private fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h2.title.heading")?.text() ?: manga.title
        author = document.select("h3.byline a[rel=author]").eachText().joinToString().ifBlank { null }
        description = document.select("div.preface div.summary blockquote.userstuff p").eachText().joinToString("\n\n")
            .ifBlank { document.selectFirst("div.preface div.summary blockquote.userstuff")?.text() }
        genre = TAG_GROUPS.flatMap { document.select("dd.$it.tags a.tag").eachText() }.distinct().joinToString()
        // "5/5" is complete, "3/?" or "3/10" is still being written.
        val parts = document.selectFirst("dl.stats dd.chapters")?.text()?.split('/')
        status = when {
            parts == null || parts.size != 2 -> SManga.UNKNOWN
            parts[0].trim() == parts[1].trim() -> SManga.COMPLETED
            else -> SManga.ONGOING
        }
        initialized = true
    }

    private suspend fun fetchChapters(manga: SManga): List<SChapter> {
        val document = client.get("${getMangaUrl(manga)}/navigate?view_adult=true", headers).asJsoup()
        if (document.isLoginPage()) error(LOGIN_MESSAGE)
        val rows = document.select("ol.chapter.index li").mapIndexedNotNull { index, row ->
            val link = row.selectFirst("a[href*=/chapters/]") ?: return@mapIndexedNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.text()
                chapter_number = (index + 1).toFloat()
                date_upload = parseChapterDate(row.selectFirst("span.datetime")?.text()?.trim('(', ')', ' '))
            }
        }
        if (rows.isNotEmpty()) return rows.reversed()
        // A work without a chapter index reads as one page.
        return listOf(
            SChapter.create().apply {
                url = manga.url
                name = manga.title
                chapter_number = 1f
            },
        )
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val document = client.get("${getChapterUrl(chapter)}?view_adult=true", headers).asJsoup()
        // Works for registered users only send logged-out readers to the login page.
        if (document.isLoginPage()) return ChapterContent(html = "", title = chapter.name, locked = true)
        val body = document.selectFirst("#chapters div.userstuff[role=article], #chapters > div.userstuff")
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)
        body.select("h3.landmark").remove()
        val title = document.selectFirst("#chapters div.chapter.preface h3.title")?.text()
        return ChapterContent(html = body.sanitizedChapterHtml(), title = title ?: chapter.name)
    }

    private fun Document.isLoginPage(): Boolean = location().toHttpUrl().encodedPath.startsWith("/users/login") ||
        (selectFirst("#chapters, ol.chapter.index, dl.work.meta") == null && selectFirst("form#new_user_session_small, form#new_user") != null && text().contains("registered users"))

    companion object {
        private val WORK_ID = Regex("""/works/(\d+)""")
        private val TAG_GROUPS = listOf("rating", "fandom", "relationship", "character", "freeform")
        private const val LOGIN_MESSAGE = "This work is only available to registered users. Log in to AO3 in WebView."
    }
}
