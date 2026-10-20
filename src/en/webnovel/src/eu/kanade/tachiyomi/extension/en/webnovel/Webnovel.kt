package eu.kanade.tachiyomi.extension.en.webnovel

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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import kotlin.time.Duration.Companion.seconds

/**
 * The official Webnovel site. Listings, search and chapter text come from the `/go/pcm` JSON API the web
 * app uses; the chapter list comes from the mobile catalog page, whose server-rendered state carries every
 * chapter with its lock state. Login happens in the app's WebView; the shared cookie jar sends the session.
 *
 * A chapter is readable when it is free (`isVip` 0) or the account unlocked it (`isAuth` 1). Anything else is
 * shown locked and its text is never requested: the API answers a locked chapter with a teaser, which is
 * discarded. Chapter text the site sends encrypted (`encryptType` other than 0) is not decrypted.
 */
@Source
abstract class Webnovel : DokushoSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(1, 2.seconds) { it.host in SITE_HOSTS }
        .rateLimit(4)

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = category(page, ORDER_POPULAR)

    override suspend fun getLatestUpdates(page: Int): MangasPage = category(page, ORDER_UPDATED)

    private suspend fun category(page: Int, orderBy: Int): MangasPage {
        val url = api("category/categoryAjax")
            .addQueryParameter("pageIndex", page.toString())
            .addQueryParameter("categoryId", "0")
            .addQueryParameter("categoryType", CATEGORY_NOVEL)
            .addQueryParameter("bookStatus", "0")
            .addQueryParameter("orderBy", orderBy.toString())
            .build()
        val data = client.get(url, headers).parseAs<ApiResponse<CategoryData>>().orThrow()
        return MangasPage(data.items.mapNotNull { it.toSManga() }, hasNextPage = data.isLast == 0 && data.items.isNotEmpty())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = api("search/result")
            .addQueryParameter("pageIndex", page.toString())
            .addQueryParameter("type", "novel")
            .addQueryParameter("keywords", query.trim())
            .build()
        val books = client.get(url, headers).parseAs<ApiResponse<SearchData>>().orThrow().bookInfo
            ?: return MangasPage(emptyList(), false)
        return MangasPage(books.bookItems.mapNotNull { it.toSManga() }, hasNextPage = books.isLast == 0 && books.bookItems.isNotEmpty())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith(DOMAIN)) return null
        val id = bookId(url.encodedPath) ?: return null
        val state = catalogState(id)
        return state.books[id]?.toSManga(id)
    }

    private fun BookItem.toSManga(): SManga? {
        val id = bookId?.takeIf { it.isNotBlank() } ?: return null
        return SManga.create().apply {
            url = "/book/$id"
            title = bookName.orEmpty()
            author = authorName
            description = this@toSManga.description
            thumbnail_url = cover(id)
        }
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = bookId(manga.url) ?: throw Exception("Not a Webnovel book: ${manga.url}")
        val state = catalogState(id)
        val book = state.books[id] ?: throw Exception("Book not found on Webnovel")
        val details = if (fetchDetails) book.toSManga(id) else manga
        val chapterList = if (fetchChapters) chapterList(id, state) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun BookEntity.toSManga(id: String) = SManga.create().apply {
        url = "/book/$id"
        title = bookName.orEmpty()
        author = authorName?.takeIf { it.isNotBlank() } ?: authorItems.mapNotNull { it.name }.joinToString()
        description = this@toSManga.description?.trim()
        genre = (listOfNotNull(categoryName) + tagInfos.mapNotNull { it.tagName?.lowercase()?.replaceFirstChar(Char::uppercase) })
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()
        status = when (actionStatus) {
            STATUS_COMPLETE -> SManga.COMPLETED
            STATUS_SUSPENDED -> SManga.ON_HIATUS
            0 -> SManga.UNKNOWN
            else -> SManga.ONGOING
        }
        thumbnail_url = cover(id)
        initialized = true
    }

    /**
     * Chapters in reading order, newest first. `isAuth` is the site's verdict for the signed-in account, so a
     * chapter the user bought or unlocked is listed as readable.
     */
    private fun chapterList(id: String, state: CatalogEntities): List<SChapter> {
        val volumes = state.catalog[id]?.volumeItems.orEmpty().mapNotNull { state.volume[it] }
        val ids = volumes.flatMap { it.chapterItems }.ifEmpty { state.chapter.keys.toList() }
        return ids.mapNotNull { state.chapter[it] }
            .filter { !it.chapterId.isNullOrBlank() }
            .map { item ->
                SChapter.create().apply {
                    url = "/book/$id/${item.chapterId}"
                    name = (if (item.isLocked) "$LOCK " else "") + item.chapterName.orEmpty().trim()
                    chapter_number = if (item.chapterIndex > 0) item.chapterIndex.toFloat() else -1f
                    date_upload = item.publishTime.takeIf { it > 0 } ?: parseChapterDate(item.publishTimeFormat)
                }
            }
            .reversed()
    }

    private val ChapterEntity.isLocked get() = isVip != 0 && isAuth == 0

    private suspend fun catalogState(id: String): CatalogEntities {
        val document = client.get("$MOBILE/book/$id/catalog", headers).asJsoup()
        val json = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Webnovel catalog page had no data; open it in WebView once")
        return json.parseAs<NextData>().props.initialState.entities
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val parts = chapter.url.removePrefix("/book/").split('/')
        val bookId = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: throw Exception("Chapter not found")
        val chapterId = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: throw Exception("Chapter not found")

        val url = api("chapter/getContent")
            .addQueryParameter("bookId", bookId)
            .addQueryParameter("chapterId", chapterId)
            .build()
        val info = client.get(url, headers).parseAs<ApiResponse<ContentData>>().orThrow().chapterInfo
            ?: throw Exception("Chapter not found")
        // A paid or privilege chapter this account has not unlocked comes back as a teaser; never show it.
        if (info.vipStatus != 0 && info.isAuth == 0) return ChapterContent(html = "", title = info.chapterName, locked = true)
        if (info.encryptType != 0) throw Exception("Webnovel sent this chapter encrypted, which Dokusho does not read. Open it in WebView.")

        val body = Jsoup.parseBodyFragment("", baseUrl).body()
        info.contents.forEach { body.append(it.toHtml()) }
        body.select("pirate").remove()
        body.select("p").filter { PROMO.containsMatchIn(it.text()) }.forEach(Element::remove)
        return ChapterContent(html = body.sanitizedChapterHtml(), title = info.chapterName)
    }

    /** Rich chapters send `<p>` markup per paragraph, older ones plain text with line breaks. */
    private fun Paragraph.toHtml(): String = if ('<' in content) {
        content
    } else {
        content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("") { "<p>${Entities.escape(it)}</p>" }
    }

    // API helpers

    /** `/go/pcm` URL with the site's CSRF token, read from the `_csrfToken` cookie a normal page visit sets. */
    private suspend fun api(path: String): HttpUrl.Builder = "$baseUrl/go/pcm/$path".toHttpUrl().newBuilder()
        .addQueryParameter("_csrfToken", csrfToken())

    private suspend fun csrfToken(): String {
        val site = baseUrl.toHttpUrl()
        fun stored() = client.cookieJar.loadForRequest(site).firstOrNull { it.name == CSRF_COOKIE }?.value
        stored()?.let { return it }
        client.get(site, headers, ensureSuccess = false).close()
        return stored().orEmpty()
    }

    private fun <T> ApiResponse<T>.orThrow(): T {
        if (code != 0) throw Exception("Webnovel error $code${msg?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
        return data ?: throw Exception("Webnovel returned no data")
    }

    private fun bookId(path: String): String? = BOOK_ID.find(path)?.groupValues?.get(1)

    private fun cover(id: String) = "https://$COVER_HOST/bookcover/$id?imageMogr2/thumbnail/600"

    companion object {
        private const val DOMAIN = "webnovel.com"
        private const val MOBILE = "https://m.webnovel.com"
        private const val COVER_HOST = "book-pic.webnovel.com"
        private val SITE_HOSTS = setOf("www.webnovel.com", "m.webnovel.com")
        private const val CSRF_COOKIE = "_csrfToken"
        private const val CATEGORY_NOVEL = "1"
        private const val ORDER_POPULAR = 1
        private const val ORDER_UPDATED = 5
        private const val STATUS_SUSPENDED = 40
        private const val STATUS_COMPLETE = 50
        private const val LOCK = "🔒"

        /** `/book/<id>`, `/book/<slug>_<id>` and the chapter paths under them. */
        private val BOOK_ID = Regex("""/book/(?:[^/]*_)?(\d{8,})""")

        /** Lines the site injects into chapter text to point readers back at it. */
        private val PROMO = Regex(
            """(?i)^\s*(find authorized novels in webnovel|.*please click www\.webnovel\.com|this chapter (is )?(upload|update)d? first at webnovel|read (the )?latest chapters? (at|on) webnovel)""",
        )
    }
}
