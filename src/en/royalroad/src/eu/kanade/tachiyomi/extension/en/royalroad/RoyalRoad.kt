package eu.kanade.tachiyomi.extension.en.royalroad

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

@Source
abstract class RoyalRoad : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = listing("$baseUrl/fictions/best-rated?page=$page", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing("$baseUrl/fictions/latest-updates?page=$page", page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/fictions/search".toHttpUrl().newBuilder()
            .addQueryParameter("title", query)
            .addQueryParameter("page", page.toString())
            .build()
        return parseListing(client.get(url, headers).asJsoup(), page)
    }

    private suspend fun listing(url: String, page: Int): MangasPage = parseListing(client.get(url, headers).asJsoup(), page)

    private fun parseListing(document: Document, page: Int): MangasPage {
        val novels = document.select(".fiction-list-item").mapNotNull { card ->
            val link = card.selectFirst("h2.fiction-title a") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = card.selectFirst("figure img")?.absUrl("src")?.takeUnless { it.contains("nocover") }
            }
        }
        val hasNext = document.select("ul.pagination a[href*=page=${page + 1}]").isNotEmpty()
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
        val chapterList = if (fetchChapters) parseChapters(document) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst(".fic-title h1")?.text() ?: manga.title
        author = document.selectFirst(".fic-title h4 a")?.text()
        description = document.select(".description p").eachText().joinToString("\n\n")
            .ifBlank { document.selectFirst(".description")?.text() }
        genre = document.select(".tags a").eachText().joinToString()
        thumbnail_url = document.selectFirst(".cover-art-container img")?.absUrl("src")
            ?.takeUnless { it.contains("nocover") }
            ?: manga.thumbnail_url
        val labels = document.select("span.label").eachText().map { it.trim().uppercase() }
        status = when {
            "COMPLETED" in labels -> SManga.COMPLETED
            "ONGOING" in labels -> SManga.ONGOING
            "HIATUS" in labels -> SManga.ON_HIATUS
            "DROPPED" in labels -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private fun parseChapters(document: Document): List<SChapter> {
        val json = document.select("script").firstNotNullOfOrNull { extractArray(it.data(), "window.chapters") }
            ?: return emptyList()
        return json.parseAs<List<ChapterDto>>()
            .filter { it.url.isNotEmpty() }
            .sortedBy { it.order }
            .map { dto ->
                SChapter.create().apply {
                    setUrlWithoutDomain(dto.url)
                    name = dto.title
                    chapter_number = (dto.order + 1).toFloat()
                    date_upload = parseChapterDate(dto.date)
                }
            }
            .reversed()
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val response = client.get(getChapterUrl(chapter), headers, ensureSuccess = false)
        if (response.code == 401 || response.code == 403) {
            response.close()
            return ChapterContent(html = "", locked = true)
        }
        if (!response.isSuccessful) {
            response.close()
            error("HTTP error ${response.code}")
        }
        val document = response.asJsoup()
        val body = document.selectFirst("div.chapter-inner.chapter-content")
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)

        // Royal Road hides an anti-copy paragraph behind a random class set to display:none.
        val hiddenClasses = document.select("style").flatMap { style ->
            HIDDEN_RULE.findAll(style.data()).map { it.groupValues[1] }
        }.toSet()
        hiddenClasses.forEach { body.getElementsByClass(it).remove() }
        body.select(".ads, .adsbygoogle").remove()

        val title = document.selectFirst(".fic-header h1, h1.font-white")?.text()
        return ChapterContent(html = body.sanitizedChapterHtml(), title = title)
    }

    // Helpers

    @Serializable
    private class ChapterDto(
        val title: String = "",
        val url: String = "",
        val date: String = "",
        val order: Int = 0,
    )

    /** Returns the JSON array assigned to [variable] in [script], respecting strings and nesting. */
    private fun extractArray(script: String, variable: String): String? {
        val start = script.indexOf(variable).takeIf { it >= 0 } ?: return null
        val open = script.indexOf('[', start).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until script.length) {
            val c = script[i]
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = inString
                c == '"' -> inString = !inString
                !inString && c == '[' -> depth++
                !inString && c == ']' -> if (--depth == 0) return script.substring(open, i + 1)
            }
        }
        return null
    }

    companion object {
        private val HIDDEN_RULE = Regex("""\.([A-Za-z0-9_-]{8,})\s*\{[^}]*display\s*:\s*none""")
    }
}
