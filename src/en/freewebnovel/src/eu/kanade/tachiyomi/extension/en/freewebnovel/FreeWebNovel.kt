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

    // Search is a single POST form without paging.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val body = FormBody.Builder().add("searchkey", query).add("keyword", query).build()
        val result = parseListing(client.post("$baseUrl/search", headers, body).asJsoup())
        return MangasPage(result.mangas, false)
    }

    // The TOC pages hold 40 chapters each; chapter URLs are a stable /novel/<slug>/chapter-N.
    override suspend fun fetchChapterList(document: Document, manga: SManga): List<SChapter> {
        val latest = latestFromIndexSelect(document).takeIf { it > 0 }
            ?: document.select("#idData li a").size
        return numberedChapters(manga.url, latest)
    }
}
