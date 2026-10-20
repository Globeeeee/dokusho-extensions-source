package eu.kanade.tachiyomi.extension.en.scribblehub

import dokusho.annotation.Source
import dokusho.network.get
import dokusho.network.post
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.asJsoup
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Source
abstract class ScribbleHub : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = listing("$baseUrl/series-ranking/?sort=1&order=1&pg=$page", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing("$baseUrl/latest-series/?pg=$page", page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/series-finder/".toHttpUrl().newBuilder()
            .addQueryParameter("sf", "1")
            .addQueryParameter("sh", query)
            .addQueryParameter("order", "desc")
            .addQueryParameter("sort", "ratings")
            .addQueryParameter("pg", page.toString())
            .build()
        return parseListing(client.get(url, headers).asJsoup(), page)
    }

    private suspend fun listing(url: String, page: Int): MangasPage = parseListing(client.get(url, headers).asJsoup(), page)

    private fun parseListing(document: Document, page: Int): MangasPage {
        val novels = document.select(".search_main_box").mapNotNull { card ->
            val link = card.selectFirst(".search_title a") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = card.selectFirst(".search_img img")?.absUrl("src")
            }
        }
        val hasNext = document.select("a.page-link[href*=pg=${page + 1}]").isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), headers).asJsoup()
        val details = if (fetchDetails) parseDetails(document, manga) else manga
        val chapterList = if (fetchChapters) fetchChapters(manga, document) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst(".fic_title")?.text() ?: manga.title
        author = document.selectFirst(".auth_name_fic")?.text()
        description = document.select(".wi_fic_desc p").eachText().joinToString("\n\n")
            .ifBlank { document.selectFirst(".wi_fic_desc")?.text() }
        genre = (document.select(".wi_fic_genre a").eachText() + document.select(".wi_fic_showtags a").eachText())
            .distinct()
            .joinToString()
        thumbnail_url = document.selectFirst(".fic_image img")?.absUrl("src") ?: manga.thumbnail_url
        val statusText = document.select("span").eachText().firstOrNull { STATUS.containsMatchIn(it) }.orEmpty()
        status = when {
            statusText.startsWith("Completed") -> SManga.COMPLETED
            statusText.startsWith("Ongoing") -> SManga.ONGOING
            statusText.startsWith("Hiatus") -> SManga.ON_HIATUS
            statusText.startsWith("Dropped") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    /**
     * The series page shows 15 chapters. The site's own "show all" call returns the whole list;
     * if it ever fails, walk the `?toc=` pages instead.
     */
    private suspend fun fetchChapters(manga: SManga, seriesPage: Document): List<SChapter> {
        val seriesId = SERIES_ID.find(manga.url)?.groupValues?.get(1)
        val all = seriesId?.let { id ->
            runCatching {
                val body = FormBody.Builder()
                    .add("action", "wi_getreleases_pagination")
                    .add("pagenum", "-1")
                    .add("mypostid", id)
                    .build()
                val ajaxHeaders = headers.newBuilder()
                    .set("Referer", getMangaUrl(manga))
                    .add("X-Requested-With", "XMLHttpRequest")
                    .build()
                client.post("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, body).asJsoup()
                    .select("li.toc_w").map(::parseChapter)
            }.getOrNull()
        }
        if (!all.isNullOrEmpty()) return all.sortedByDescending { it.chapter_number }

        val rows = seriesPage.select("li.toc_w").map(::parseChapter).toMutableList()
        val lastPage = TOC_PAGE.findAll(seriesPage.html()).maxOfOrNull { it.groupValues[1].toInt() } ?: 1
        for (toc in 2..lastPage) {
            rows += client.get("${getMangaUrl(manga)}?toc=$toc", headers).asJsoup().select("li.toc_w").map(::parseChapter)
        }
        return rows.distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }

    private fun parseChapter(row: Element): SChapter = SChapter.create().apply {
        val link = row.selectFirst("a.toc_a")!!
        setUrlWithoutDomain(link.absUrl("href"))
        name = link.text()
        chapter_number = row.attr("order").toFloatOrNull() ?: -1f
        date_upload = parseDate(row.selectFirst(".fic_date_pub")?.attr("title"))
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val response = client.get(getChapterUrl(chapter), headers, ensureSuccess = false)
        if (response.code == 401 || response.code == 403) {
            response.close()
            return ChapterContent(html = "", locked = true)
        }
        val document = response.asJsoup()
        val body = document.selectFirst("#chp_raw")
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)
        body.select("script, style, iframe, ins, noscript, .wi_authornotes, .chp_byauthor").remove()
        body.select("[style*=display:none], [style*=display: none]").remove()
        body.select("a").unwrap()
        body.select("span").unwrap()
        // ponytail: tables (stat screens in LitRPG) flatten to text in the reader; render them later.
        body.allElements.forEach { element ->
            val src = if (element.tagName() == "img") element.absUrl("src") else null
            element.clearAttributes()
            if (!src.isNullOrBlank()) element.attr("src", src)
        }
        body.select("p").filter { it.text().isBlank() && it.select("img").isEmpty() }.forEach { it.remove() }
        return ChapterContent(html = body.html(), title = document.selectFirst(".chapter-title")?.text())
    }

    // Helpers

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        RELATIVE.find(text)?.let { match ->
            val amount = match.groupValues[1].toInt()
            val field = when (match.groupValues[2].lowercase().removeSuffix("s")) {
                "min", "minute" -> Calendar.MINUTE
                "hour" -> Calendar.HOUR_OF_DAY
                "day" -> Calendar.DAY_OF_YEAR
                "week" -> Calendar.WEEK_OF_YEAR
                "month" -> Calendar.MONTH
                "year" -> Calendar.YEAR
                else -> return 0L
            }
            return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
        }
        return runCatching { DATE_TIME.parse(text)?.time }.getOrNull()
            ?: runCatching { DATE.parse(text)?.time }.getOrNull()
            ?: 0L
    }

    companion object {
        private val SERIES_ID = Regex("""/series/(\d+)/""")
        private val TOC_PAGE = Regex("""[?&]toc=(\d+)""")
        private val STATUS = Regex("""^(Ongoing|Completed|Hiatus|Dropped)\b""")
        private val RELATIVE = Regex("""(\d+)\s+(mins?|minutes?|hours?|days?|weeks?|months?|years?)\s+ago""", RegexOption.IGNORE_CASE)
        private val DATE_TIME = SimpleDateFormat("MMM d, yyyy hh:mm a", Locale.ENGLISH)
        private val DATE = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)
    }
}
