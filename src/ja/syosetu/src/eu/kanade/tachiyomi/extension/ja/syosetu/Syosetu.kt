package eu.kanade.tachiyomi.extension.ja.syosetu

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.time.ZoneId

/**
 * 小説家になろう (Shousetsuka ni Narou). Listings and details come from the official novel API;
 * the table of contents and chapter text come from the novel pages.
 */
@Source
abstract class Syosetu : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(1)

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = novels(page) { addQueryParameter("order", "hyoka") }

    override suspend fun getLatestUpdates(page: Int): MangasPage = novels(page) { addQueryParameter("order", "new") }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = novels(page) {
        addQueryParameter("word", query)
        addQueryParameter("order", "hyoka")
    }

    private suspend fun novels(page: Int, params: okhttp3.HttpUrl.Builder.() -> Unit): MangasPage {
        val start = (page - 1) * PAGE_SIZE + 1
        val requestUrl = API.toHttpUrl().newBuilder()
            .addQueryParameter("out", "json")
            .addQueryParameter("of", FIELDS)
            .addQueryParameter("lim", PAGE_SIZE.toString())
            .addQueryParameter("st", start.toString())
            .apply(params)
            .build()
        val (total, items) = apiCall(requestUrl)
        return MangasPage(items.map { it.toManga() }, hasNextPage = start + items.size <= total && items.size == PAGE_SIZE)
    }

    private suspend fun apiCall(url: HttpUrl): Pair<Int, List<JsonObject>> {
        val array = client.get(url, headers).use { Json.parseToJsonElement(it.body.string()).jsonArray }
        val total = (array.firstOrNull() as? JsonObject)?.get("allcount")?.jsonPrimitive?.intOrNull ?: 0
        return total to array.drop(1).mapNotNull { it as? JsonObject }
    }

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.toManga() = SManga.create().apply {
        url = "/${text("ncode").lowercase()}/"
        title = text("title")
        author = text("writer")
        description = text("story")
        genre = text("keyword").split(' ', '　').filter { it.isNotBlank() }.joinToString()
        // end: 0 = finished (or a short story), 1 = still being serialized
        status = if (text("end") == "0") SManga.COMPLETED else SManga.ONGOING
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val ncode = manga.url.trim('/')
        val infoUrl = API.toHttpUrl().newBuilder()
            .addQueryParameter("out", "json")
            .addQueryParameter("of", FIELDS)
            .addQueryParameter("ncode", ncode)
            .build()
        val info = apiCall(infoUrl).second.firstOrNull()
            ?: return SMangaUpdate(manga, chapters)
        val details = if (!fetchDetails) {
            manga
        } else {
            info.toManga().apply {
                url = manga.url
                initialized = true
            }
        }
        val chapterList = when {
            !fetchChapters -> chapters
            // noveltype 2 is a one-shot: the text is on the novel page itself.
            info.text("noveltype") == "2" -> listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = info.text("title")
                    chapter_number = 1f
                },
            )
            else -> fetchToc(ncode, info["general_all_no"]?.jsonPrimitive?.intOrNull ?: 0)
        }
        return SMangaUpdate(details, chapterList)
    }

    private suspend fun fetchToc(ncode: String, count: Int): List<SChapter> {
        val pages = ((count + TOC_PAGE_SIZE - 1) / TOC_PAGE_SIZE).coerceAtLeast(1)
        val rows = (1..pages).flatMap { page ->
            client.get("$baseUrl/$ncode/?p=$page", headers).asJsoup().select(".p-eplist__sublist")
        }
        return rows.mapIndexedNotNull { index, row ->
            val link = row.selectFirst("a.p-eplist__subtitle") ?: return@mapIndexedNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.text()
                chapter_number = (index + 1).toFloat()
                date_upload = parseChapterDate(
                    row.selectFirst(".p-eplist__update")?.ownText()?.trim(),
                    ZoneId.of("Asia/Tokyo"),
                )
            }
        }.reversed()
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val document = client.get(getChapterUrl(chapter), headers).asJsoup()
        // The main text, without the author's preface and afterword blocks.
        val parts = document.select("div.js-novel-text.p-novel__text")
            .filterNot { it.hasClass("p-novel__text--preface") || it.hasClass("p-novel__text--afterword") }
        // Syosetu has no paywall, so a missing text block means the page layout changed.
        if (parts.isEmpty()) throw Exception("Chapter text not found; the site layout may have changed")
        val title = document.selectFirst(".p-novel__title")?.text()
        return ChapterContent(html = parts.joinToString("") { it.sanitizedChapterHtml(keepBlankParagraphs = true) }, title = title)
    }

    companion object {
        private const val API = "https://api.syosetu.com/novelapi/api/"
        private const val FIELDS = "t-n-w-s-k-e-nt-ga"
        private const val PAGE_SIZE = 20
        private const val TOC_PAGE_SIZE = 100
    }
}
