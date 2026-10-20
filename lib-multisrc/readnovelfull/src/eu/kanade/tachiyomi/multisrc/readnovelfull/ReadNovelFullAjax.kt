package eu.kanade.tachiyomi.multisrc.readnovelfull

import dokusho.network.get
import dokusho.network.rateLimit
import dokusho.utils.parseAs
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.seconds

/**
 * The newer layout of the engine (novgo.net, novelphoenix.net): novels at `/<slug>.html`, chapter URLs
 * carry the chapter title, so the list comes from the paged `/ajax-chapter-list` TOC.
 */
abstract class ReadNovelFullAjax : ReadNovelFull() {
    override val popularPath = "most-popular"
    override val latestPath = "latest-release-novel"

    override val nextPageSelector = "nav.pages a[rel=next]"

    override val contentSelector = "div#chapter-content"

    // The TOC is 50 chapters a page, so long novels need dozens of requests.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 4, period = 1.seconds)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = listing(
        "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .build()
            .toString(),
    )

    override suspend fun fetchChapterList(document: Document, manga: SManga): List<SChapter> {
        val toc = document.selectFirst("#list-chapter[data-novel-id]") ?: return emptyList()
        val novelId = toc.attr("data-novel-id")
        val totalPages = toc.attr("data-total-page").toIntOrNull() ?: 1
        return (1..totalPages).flatMap { page ->
            val response = client.get("$baseUrl/ajax-chapter-list?novelId=$novelId&page=$page", headers).parseAs<TocPage>()
            Jsoup.parse(response.html, baseUrl).select("a[href]").map { link ->
                SChapter.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    name = link.attr("title").ifBlank { link.text() }.replace(SPACES, " ")
                    CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.toFloatOrNull()?.let { chapter_number = it }
                }
            }
        }.distinctBy { it.url }.reversed()
    }

    @Serializable
    class TocPage(val html: String = "")

    companion object {
        private val CHAPTER_NUMBER = Regex("""^Chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val SPACES = Regex("""\s+""")
    }
}
