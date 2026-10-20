package eu.kanade.tachiyomi.extension.en.freewebnovel

import dokusho.annotation.Source
import dokusho.network.post
import dokusho.utils.asJsoup
import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.FormBody
import org.jsoup.nodes.Document

/** Behind Cloudflare; the app's WebView check clears it once and the cookie is reused. */
@Source
abstract class FreeWebNovel : ReadNovelFull() {
    override val popularPath = "sort/most-popular"
    override val latestPath = "sort/latest-release"

    override fun listingUrl(path: String, page: Int): String = if (page <= 1) "$baseUrl/$path" else "$baseUrl/$path/$page"

    // Search is a single POST form without paging. The site renamed the field from searchkey to keyword in 2026; send both.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val body = FormBody.Builder().add("searchkey", query).add("keyword", query).build()
        val result = parseListing(client.post("$baseUrl/search", headers, body).asJsoup())
        return MangasPage(result.mangas, false)
    }

    // The novel page lists only the first 40 chapters, but chapter URLs are a stable /novel/<slug>/chapter-N,
    // so the list is numbered up to the last range of the #indexselect picker ("C.3201 - C.3212" gives 3212).
    override suspend fun fetchChapterList(document: Document, manga: SManga): List<SChapter> {
        val latest = document.select("#indexselect option").lastOrNull()?.text()
            ?.let { TRAILING_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: document.select("#idData li a").size
        val novelPath = manga.url.trimEnd('/')
        return (latest.coerceAtMost(MAX_CHAPTERS) downTo 1).map { number ->
            SChapter.create().apply {
                url = "$novelPath/chapter-$number"
                name = "Chapter $number"
                chapter_number = number.toFloat()
            }
        }
    }

    companion object {
        private val TRAILING_NUMBER = Regex("""(\d+)\D*$""")
        private const val MAX_CHAPTERS = 50_000
    }
}
