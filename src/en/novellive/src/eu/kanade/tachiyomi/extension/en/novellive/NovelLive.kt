package eu.kanade.tachiyomi.extension.en.novellive

import dokusho.annotation.Source
import dokusho.network.get
import dokusho.network.post
import dokusho.utils.asJsoup
import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

/**
 * novellive.com serves every page; the old novellive.app host is still accepted for pasted links. Behind an
 * interactive Cloudflare check, so the app's WebView solves it once and the cookie is reused.
 */
@Source
abstract class NovelLive : ReadNovelFull() {
    override val popularPath = "list/most-popular-novels"
    override val latestPath = "list/latest-release-novels"

    override fun listingUrl(path: String, page: Int): String = "$baseUrl/$path/$page"

    // Search is a single POST form without paging.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val body = FormBody.Builder().add("searchkey", query).build()
        val result = parseListing(client.post("$baseUrl/search/", headers, body).asJsoup())
        return MangasPage(result.mangas, false)
    }

    // Chapter URLs carry the title and skip numbers, so walk the TOC pages /book/<slug>/<N>, 40 chapters each.
    override suspend fun fetchChapterList(document: Document, manga: SManga): List<SChapter> {
        val pages = document.select("#indexselect option").size.coerceIn(1, MAX_TOC_PAGES)
        val chapters = LinkedHashMap<String, SChapter>()
        for (page in 1..pages) {
            val toc = if (page == 1) document else client.get("${getMangaUrl(manga).trimEnd('/')}/$page", headers).asJsoup()
            val before = chapters.size
            toc.select("div.m-newest2 ul.ul-list5 li a[href]").forEach { link ->
                val chapter = SChapter.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    name = link.attr("title").ifBlank { link.text() }
                    CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.toFloatOrNull()?.let { chapter_number = it }
                }
                chapters.putIfAbsent(chapter.url, chapter)
            }
            if (chapters.size == before) break
        }
        return chapters.values.reversed()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = super.getMangaByUrl(if (url.host.removePrefix("www.") == OLD_HOST) url.newBuilder().host(baseUrl.toHttpUrl().host).build() else url)

    companion object {
        private const val OLD_HOST = "novellive.app"
        private val CHAPTER_NUMBER = Regex("""^Chapter\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private const val MAX_TOC_PAGES = 1000
    }
}
