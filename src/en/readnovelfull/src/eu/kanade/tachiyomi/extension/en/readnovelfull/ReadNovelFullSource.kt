package eu.kanade.tachiyomi.extension.en.readnovelfull

import dokusho.annotation.Source
import dokusho.network.get
import dokusho.utils.asJsoup
import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class ReadNovelFullSource : ReadNovelFull() {
    override val popularPath = "novel-list/most-popular-novel"
    override val latestPath = "novel-list/latest-release-novel"

    // Listing thumbnails are 200x89 crops; the 300x439 size of the same file is the real cover.
    override fun listingRow(row: Element): SManga? = super.listingRow(row)?.apply {
        thumbnail_url = thumbnail_url?.replace("/t-200x89/", "/t-300x439/")
    }

    // Full list from the archive endpoint, oldest first; titles already carry the chapter number.
    override suspend fun fetchChapterList(document: Document, manga: SManga): List<SChapter> {
        val novelId = document.selectFirst("div#rating")?.attr("data-novel-id")?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val archive = client.get("$baseUrl/ajax/chapter-archive?novelId=$novelId", headers).asJsoup()
        return archive.select("ul.list-chapter li a").mapIndexed { index, link ->
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.attr("title").ifBlank { link.text() }
                chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }
}
