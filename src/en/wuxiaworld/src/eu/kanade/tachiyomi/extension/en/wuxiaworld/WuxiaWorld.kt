package eu.kanade.tachiyomi.extension.en.wuxiaworld

import dokusho.annotation.Source
import dokusho.network.post
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.sanitizedChapterHtml
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * The official WuxiaWorld site. Data comes from its gRPC-web API. A chapter is read only when the API
 * serves its full text; karma, VIP and early-access chapters it answers with a teaser are returned locked.
 * The site authorises API calls with an OIDC bearer token kept in the web app's localStorage, not with
 * cookies; [WuxiaWorldAuth] reads it from the shared WebView so chapters the user unlocked read in full.
 */
@Source
abstract class WuxiaWorld : DokushoSource() {

    private val auth = WuxiaWorldAuth(API_HOST)

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(auth.interceptor)
        .rateLimit(1, 2.seconds) { it.host == API_HOST }

    // Search pages use a cursor (the last novel id), so remember it per query and page; capped below.
    private val cursors = ConcurrentHashMap<String, Int>()

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage = searchNovels(page, query = "", sort = SORT_POPULAR)

    override suspend fun getLatestUpdates(page: Int): MangasPage = searchNovels(page, query = "", sort = SORT_NEW)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = searchNovels(page, query, SORT_POPULAR)

    private suspend fun searchNovels(page: Int, query: String, sort: Int): MangasPage {
        val key = "$sort|$query"
        val after = if (page > 1) cursors["$key|${page - 1}"] else null
        if (page > 1 && after == null) return MangasPage(emptyList(), false)
        val request = ProtoWriter().apply {
            if (query.isNotBlank()) message(1) { string(1, query) }
            int(3, STATUS_ALL)
            int(4, sort)
            int(5, SORT_DESC)
            if (after != null) message(6) { int(1, after) }
            int(7, PAGE_SIZE)
        }
        val items = grpc("Novels/SearchNovels", request).messages(1)
        items.lastOrNull()?.let {
            if (cursors.size >= MAX_CURSORS) cursors.clear()
            cursors["$key|$page"] = it.long(1).toInt()
        }
        val novels = items.map { item ->
            SManga.create().apply {
                url = "/novel/${item.string(3)}"
                title = item.string(2).orEmpty()
                thumbnail_url = item.wrappedString(10)
                author = item.wrappedString(13)
            }
        }
        return MangasPage(novels, hasNextPage = items.size == PAGE_SIZE)
    }

    // Details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.removePrefix("/novel/").trim('/')
        val novel = grpc("Novels/GetNovel", ProtoWriter().string(2, slug)).message(1)
            ?: return SMangaUpdate(manga, chapters)
        val details = if (!fetchDetails) {
            manga
        } else {
            SManga.create().apply {
                url = manga.url
                title = novel.string(2) ?: manga.title
                author = novel.wrappedString(13)
                description = (novel.wrappedString(9) ?: novel.wrappedString(8))
                    ?.let { Jsoup.parse(it).wholeText().trim() }
                genre = (novel.strings(16) + novel.strings(15)).distinct().joinToString()
                thumbnail_url = novel.wrappedString(10) ?: manga.thumbnail_url
                status = when (novel.long(4).toInt()) {
                    0 -> SManga.COMPLETED
                    1 -> SManga.ONGOING
                    2 -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
                initialized = true
            }
        }
        val chapterList = if (!fetchChapters) chapters else fetchChapterList(novel, slug)
        return SMangaUpdate(details, chapterList)
    }

    private suspend fun fetchChapterList(novel: ProtoMessage, slug: String): List<SChapter> {
        val novelIsFree = novel.message(14)?.bool(2) == true
        val groups = grpc("Chapters/GetChapterList", ProtoWriter().int(1, novel.long(1).toInt())).messages(1)
        return groups.flatMap { it.messages(6) }
            .mapNotNull { item ->
                val chapterSlug = item.string(3) ?: return@mapNotNull null
                val free = novelIsFree || item.message(20)?.bool(1) == true
                // relatedUserInfo: the user's own unlocks and purchases, only sent with a bearer token.
                val owned = item.message(16)?.let { info -> USER_ACCESS.any { info.message(it)?.bool(1) == true } } == true
                val locked = !free && !owned && (item.message(13)?.bool(2) == true || item.message(15)?.bool(1) == true)
                val number = item.message(4)?.let { it.long(1) + it.long(2) / 1_000_000_000.0 } ?: 0.0
                SChapter.create().apply {
                    url = "/novel/$slug/$chapterSlug"
                    name = (if (locked) "$LOCK " else "") + item.string(2).orEmpty()
                    chapter_number = number.toFloat()
                    date_upload = (item.message(18)?.long(1) ?: 0L) * 1000
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    // Chapter text

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val parts = chapter.url.removePrefix("/novel/").split('/')
        if (parts.size < 2) throw Exception("Chapter not found")
        val request = ProtoWriter().message(1) {
            message(2) {
                string(1, parts[0])
                string(2, parts[1])
            }
        }
        // A chapter that does not exist comes back as an empty message, not a teaser.
        val item = grpc("Chapters/GetChapter", request).message(1) ?: throw Exception("Chapter not found")
        // A locked chapter comes back as a short teaser; never show it as the chapter.
        if (item.bool(8)) return ChapterContent(html = "", title = item.string(2), locked = true)
        val html = item.wrappedString(5) ?: return ChapterContent(html = "", title = item.string(2), locked = true)
        val body = Jsoup.parseBodyFragment(html, baseUrl).body()
        body.select("sup.footnote, a.footnote-ref").remove()
        return ChapterContent(html = body.sanitizedChapterHtml(), title = item.string(2))
    }

    // gRPC-web transport

    private suspend fun grpc(method: String, request: ProtoWriter): ProtoMessage {
        val message = request.toByteArray()
        val frame = ByteBuffer.allocate(5 + message.size).put(0).putInt(message.size).put(message).array()
        val grpcHeaders = headers.newBuilder()
            .set("Content-Type", GRPC_TYPE)
            .set("X-Grpc-Web", "1")
            .build()
        suspend fun call(): Pair<ByteArray, String?> {
            auth.refresh()
            return client.post("$API/wuxiaworld.api.v2.$method", grpcHeaders, frame.toRequestBody(GRPC_TYPE.toMediaType()))
                .use { response -> response.body.bytes().let { it to (response.header("grpc-status") ?: trailerStatus(it)) } }
        }
        var (bytes, status) = call()
        // A rejected token: let the site renew it, then retry once.
        if (status == UNAUTHENTICATED && auth.hasToken) {
            auth.refresh(renew = true)
            call().let { (b, s) ->
                bytes = b
                status = s
            }
        }
        // Response: a data frame (flag 0) followed by a trailer frame (flag 0x80).
        if (bytes.size < 5 || bytes[0].toInt() != 0) error("WuxiaWorld API returned no data for $method (grpc-status ${status ?: "none"})")
        val length = ByteBuffer.wrap(bytes, 1, 4).int
        if (length < 0 || length > bytes.size - 5) error("WuxiaWorld API returned a truncated frame for $method")
        return ProtoMessage(bytes.copyOfRange(5, 5 + length))
    }

    /** grpc-status from the trailer frame (flag 0x80), where the server sends it after a data frame. */
    private fun trailerStatus(bytes: ByteArray): String? {
        var i = 0
        while (i + 5 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, i + 1, 4).int
            if (length < 0 || length > bytes.size - i - 5) return null
            if (bytes[i].toInt() and 0x80 != 0) {
                return Regex("grpc-status:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(String(bytes, i + 5, length))?.groupValues?.get(1)
            }
            i += 5 + length
        }
        return null
    }

    companion object {
        private const val API_HOST = "api2.wuxiaworld.com"
        private const val API = "https://$API_HOST"
        private const val UNAUTHENTICATED = "16"
        private const val MAX_CURSORS = 100
        private const val GRPC_TYPE = "application/grpc-web+proto"
        private const val PAGE_SIZE = 20
        private const val STATUS_ALL = -1
        private const val SORT_POPULAR = 1
        private const val SORT_NEW = 2
        private const val SORT_DESC = 1
        private const val LOCK = "🔒"

        /** RelatedChapterUserInfo: isChapterUnlocked, isNovelUnlocked, isNovelOwned, isChapterOwned. */
        private val USER_ACCESS = intArrayOf(1, 2, 4, 5)
    }
}
