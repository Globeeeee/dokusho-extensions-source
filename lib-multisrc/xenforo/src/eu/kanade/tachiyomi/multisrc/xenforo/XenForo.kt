package eu.kanade.tachiyomi.multisrc.xenforo

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
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * XenForo 2 forums with the threadmarks add-on (SpaceBattles, Sufficient Velocity). A story is a
 * thread, its chapters are the threadmarked posts of the main category.
 */
abstract class XenForo : DokushoSource() {

    /** Forum the listings browse, e.g. `/forums/creative-writing.18/`. */
    protected abstract val fictionForumPath: String

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(1)

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = forumListing("reply_count", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = forumListing("last_threadmark", page)

    private suspend fun forumListing(order: String, page: Int): MangasPage {
        val pagePath = if (page > 1) "page-$page" else ""
        val listingUrl = "$baseUrl$fictionForumPath$pagePath".toHttpUrl().newBuilder()
            .addQueryParameter("order", order)
            .addQueryParameter("direction", "desc")
            .build()
        val document = client.get(listingUrl, headers).asJsoup()
        // Sticky threads sit in their own group and are rules or catalogues, not stories.
        val threads = document.select(".js-threadList > .structItem--thread").mapNotNull { row ->
            val link = row.selectFirst(".structItem-title a[data-tp-primary]") ?: return@mapNotNull null
            SManga.create().apply {
                url = threadPath(link.attr("href")) ?: return@mapNotNull null
                title = link.text()
                author = row.attr("data-author").ifBlank { null }
            }
        }
        return MangasPage(threads, document.selectFirst(".pageNav-jump--next") != null)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchUrl = "$baseUrl/search/search".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", query)
            .addQueryParameter("t", "post")
            .addQueryParameter("c[threadmarks_only]", "1")
            .addQueryParameter("c[title_only]", "1")
            .addQueryParameter("o", "relevance")
            .build()
        // The search redirects to /search/<id>/; the same query reuses that id, later pages hang off it.
        var document = client.get(searchUrl, headers).asJsoup()
        if (page > 1) {
            val resultsUrl = document.location().toHttpUrl().newBuilder().setQueryParameter("page", page.toString()).build()
            document = client.get(resultsUrl, headers).asJsoup()
        }
        val threads = document.select("li.block-row").mapNotNull { row ->
            val link = row.selectFirst(".contentRow-title a[href*=/threads/]") ?: return@mapNotNull null
            SManga.create().apply {
                url = threadPath(link.attr("href")) ?: return@mapNotNull null
                title = link.text()
                author = row.attr("data-author").ifBlank { null }
            }
        }.distinctBy { it.url }
        return MangasPage(threads, document.selectFirst(".pageNav-jump--next") != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val path = threadPath(url.encodedPath) ?: return null
        val manga = SManga.create().apply { this.url = path }
        return parseDetails(client.get(getMangaUrl(manga), headers).asJsoup(), null, manga)
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val firstIndex = client.get(threadmarksUrl(manga, 1), headers).asJsoup()
        val details = if (fetchDetails) {
            parseDetails(client.get(getMangaUrl(manga), headers).asJsoup(), firstIndex, manga)
        } else {
            manga
        }
        val chapterList = if (fetchChapters) fetchChapters(manga, firstIndex) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun threadmarksUrl(manga: SManga, page: Int): String = "$baseUrl${manga.url}threadmarks?threadmark_category=1&per_page=200&page=$page"

    private fun parseDetails(thread: Document, threadmarks: Document?, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = thread.selectFirst("h1.p-title-value")?.let { heading ->
            heading.clone().apply { select(".label, .labelLink, .label-append").remove() }.text()
        }?.ifBlank { null } ?: manga.title
        author = thread.selectFirst(".p-description a.username")?.text()
            ?: thread.selectFirst("article.message--post")?.attr("data-author")
        description = thread.selectFirst("article.message--post .bbWrapper")?.let(::plainText)?.take(DESCRIPTION_LIMIT)
        genre = thread.select(".p-description .tagList .tagItem").eachText().distinct().joinToString()
        val statusText = threadmarks?.select("dl.pairs")
            ?.firstOrNull { it.selectFirst("> dt")?.text() == "Status" }
            ?.selectFirst("> dd")?.text().orEmpty()
        status = when (statusText.lowercase()) {
            "complete", "completed" -> SManga.COMPLETED
            "ongoing", "incomplete" -> SManga.ONGOING
            "hiatus" -> SManga.ON_HIATUS
            "dropped", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private suspend fun fetchChapters(manga: SManga, firstIndex: Document): List<SChapter> {
        val rows = mutableListOf<SChapter>()
        var document = firstIndex
        var page = 1
        while (true) {
            document.select(".block-body--threadmarkBody .structItem--threadmark").mapNotNullTo(rows, ::parseThreadmark)
            if (document.selectFirst(".pageNav-jump--next") == null || page >= MAX_INDEX_PAGES) break
            page++
            document = client.get(threadmarksUrl(manga, page), headers).asJsoup()
        }
        if (rows.isEmpty()) {
            // A thread without threadmarks reads as its opening post.
            val thread = client.get(getMangaUrl(manga), headers).asJsoup()
            val first = thread.selectFirst("article.message--post[data-content^=post-]") ?: return emptyList()
            return listOf(
                SChapter.create().apply {
                    url = "/posts/${first.attr("data-content").removePrefix("post-")}/"
                    name = manga.title
                    chapter_number = 1f
                    date_upload = parseChapterDate(first.selectFirst("time[datetime]")?.attr("datetime"))
                },
            )
        }
        return rows.distinctBy { it.url }
            .onEachIndexed { index, chapter -> chapter.chapter_number = (index + 1).toFloat() }
            .reversed()
    }

    private fun parseThreadmark(row: Element): SChapter? {
        val link = row.selectFirst(".structItem-title a[href]") ?: return null
        val postId = POST_ID.find(link.attr("data-preview-url"))?.groupValues?.get(1)
            ?: POST_ANCHOR.find(link.attr("href"))?.groupValues?.get(1)
            ?: return null
        return SChapter.create().apply {
            url = "/posts/$postId/"
            name = link.text()
            date_upload = row.attr("data-content-date").toLongOrNull()?.times(1000)
                ?: parseChapterDate(row.selectFirst("time[datetime]")?.attr("datetime"))
        }
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val postId = POST_ID.find(chapter.url)?.groupValues?.get(1) ?: error("Not a post: ${chapter.url}")
        val document = client.get("$baseUrl/posts/$postId/", headers).asJsoup()
        val post = document.selectFirst("article.message[data-content=post-$postId]")
        // A post the guest cannot see (members-only forum) is not on the page.
        val body = post?.selectFirst(".message-body .bbWrapper")
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)
        cleanPost(body)
        val title = post.selectFirst(".message-cell--threadmark-header .threadmarkLabel")?.text()
        return ChapterContent(
            html = body.sanitizedChapterHtml(imageUrl = { img -> img.absUrl("data-src").ifBlank { img.absUrl("src") } }),
            title = title ?: chapter.name,
        )
    }

    /** Drops forum chrome inside the post, opens spoilers and quotes, and turns `<br><br>` runs into paragraphs. */
    protected open fun cleanPost(body: Element) {
        body.select(
            ".bbCodeSpoiler-button, .bbCodeBlock-title, .bbCodeBlock-expandLink, .bbCodeBlock-shrinkLink, " +
                ".js-selectToQuoteEnd, .message-signature, .reactionsBar",
        ).remove()
        body.select("img.smilie").forEach { it.replaceWith(TextNode(it.attr("alt"))) }
        // The outer spoiler div stays as a block so its text does not run into the next paragraph.
        body.select(".bbCodeSpoiler-content, .bbCodeBlock--spoiler, .bbCodeBlock-content, .bbCodeBlock-expandContent").unwrap()
        (listOf(body) + body.select("div, blockquote, td"))
            .filter { container -> container.children().any { it.normalName() == "br" } }
            .forEach(::splitParagraphs)
    }

    private fun splitParagraphs(container: Element) {
        val groups = mutableListOf(mutableListOf<Node>())
        var pendingBreak: Node? = null
        for (node in container.childNodes().toList()) {
            if (node is Element && node.normalName() == "br") {
                if (pendingBreak != null) {
                    groups += mutableListOf<Node>()
                    pendingBreak = null
                } else {
                    pendingBreak = node
                }
                continue
            }
            if (node.isBlankText()) {
                groups.last() += node
                continue
            }
            if (node is Element && node.normalName() in BLOCK_TAGS) {
                // A lone break next to a block is spacing, not a line.
                pendingBreak = null
                groups += mutableListOf(node)
                groups += mutableListOf<Node>()
                continue
            }
            pendingBreak?.let { if (groups.last().any { n -> !n.isBlankText() }) groups.last() += it }
            pendingBreak = null
            groups.last() += node
        }
        container.empty()
        for (group in groups) {
            val single = group.singleOrNull { !it.isBlankText() }
            if (single is Element && single.normalName() in BLOCK_TAGS) {
                container.appendChild(single)
            } else if (group.any { !it.isBlankText() }) {
                val paragraph = container.appendElement("p")
                group.forEach(paragraph::appendChild)
            }
        }
    }

    private fun Node.isBlankText(): Boolean = this is TextNode && isBlank

    private fun plainText(body: Element): String {
        val copy = body.clone()
        copy.select(".bbCodeSpoiler-button, .bbCodeBlock-title, .bbCodeBlock-expandLink, .bbCodeBlock-shrinkLink").remove()
        copy.select("br").forEach { it.replaceWith(TextNode("\n")) }
        return copy.wholeText().lines().joinToString("\n") { it.trim() }.replace(BLANK_RUNS, "\n\n").trim()
    }

    /** `/threads/<slug>.<id>/` from any thread, threadmarks or page link. */
    protected fun threadPath(href: String): String? = THREAD_PATH.find(href)?.let { "/threads/${it.groupValues[1]}/" }

    companion object {
        private val THREAD_PATH = Regex("""/threads/([^/?#]*\.\d+|\d+)(?:[/?#]|$)""")
        private val POST_ID = Regex("""/posts/(\d+)""")
        private val POST_ANCHOR = Regex("""post-(\d+)""")
        private val BLANK_RUNS = Regex("""\n{3,}""")
        private val BLOCK_TAGS = setOf("p", "div", "blockquote", "ol", "ul", "table", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "pre")
        private const val DESCRIPTION_LIMIT = 3000
        private const val MAX_INDEX_PAGES = 50
    }
}
