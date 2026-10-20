package eu.kanade.tachiyomi.extension.en.novellive

import dokusho.annotation.Source
import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

/**
 * novellive.com fronts novellive.app, which serves every page. Behind an interactive Cloudflare
 * check, so the app's WebView solves it once and the cookie is reused.
 */
@Source
abstract class NovelLive : ReadNovelFull() {
    override val popularPath = "list/most-popular-novels"
    override val latestPath = "list/latest-novels"

    override fun listingUrl(path: String, page: Int): String = "$baseUrl/$path/$page"

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val url = "$baseUrl/search".toHttpUrl().newBuilder().addQueryParameter("keyword", query).build()
        return MangasPage(listing(url.toString()).mangas, false)
    }

    // Chapter URLs are a stable /book/<slug>/chapter-N.
    override suspend fun fetchChapterList(document: Document, manga: SManga): List<SChapter> {
        val latest = latestFromIndexSelect(document).takeIf { it > 0 }
            ?: document.selectFirst("div.m-newest1 a[href*=/chapter-]")?.attr("href")
                ?.let { Regex("""chapter-(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: 0
        return numberedChapters(manga.url, latest)
    }
}
