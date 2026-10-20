package eu.kanade.tachiyomi.extension.en.webnovel

import kotlinx.serialization.Serializable

/** Envelope of every `/go/pcm/...` answer; `code` 0 means success. */
@Serializable
internal class ApiResponse<T>(
    val code: Int = -1,
    val msg: String? = null,
    val data: T? = null,
)

@Serializable
internal class CategoryData(
    val items: List<BookItem> = emptyList(),
    val isLast: Int = 1,
)

@Serializable
internal class SearchData(
    val bookInfo: SearchBooks? = null,
)

@Serializable
internal class SearchBooks(
    val bookItems: List<BookItem> = emptyList(),
    val isLast: Int = 1,
)

@Serializable
internal class BookItem(
    val bookId: String? = null,
    val bookName: String? = null,
    val authorName: String? = null,
    val description: String? = null,
)

@Serializable
internal class ContentData(
    val chapterInfo: ChapterInfo? = null,
)

@Serializable
internal class ChapterInfo(
    val chapterName: String? = null,
    val vipStatus: Int = 0,
    val isAuth: Int = 0,
    val encryptType: Int = 0,
    val contents: List<Paragraph> = emptyList(),
)

@Serializable
internal class Paragraph(
    val content: String = "",
)

// Server-rendered state of the mobile catalog page (`m.webnovel.com/book/<id>/catalog`).

@Serializable
internal class NextData(
    val props: NextProps,
)

@Serializable
internal class NextProps(
    val initialState: NextState,
)

@Serializable
internal class NextState(
    val entities: CatalogEntities,
)

@Serializable
internal class CatalogEntities(
    val books: Map<String, BookEntity> = emptyMap(),
    val chapter: Map<String, ChapterEntity> = emptyMap(),
    val volume: Map<String, VolumeEntity> = emptyMap(),
    val catalog: Map<String, CatalogEntity> = emptyMap(),
)

@Serializable
internal class BookEntity(
    val bookName: String? = null,
    val authorName: String? = null,
    val authorItems: List<Named> = emptyList(),
    val description: String? = null,
    val categoryName: String? = null,
    val tagInfos: List<Tag> = emptyList(),
    val actionStatus: Int = 0,
)

@Serializable
internal class Named(
    val name: String? = null,
)

@Serializable
internal class Tag(
    val tagName: String? = null,
)

@Serializable
internal class CatalogEntity(
    val volumeItems: List<String> = emptyList(),
)

@Serializable
internal class VolumeEntity(
    val chapterItems: List<String> = emptyList(),
)

@Serializable
internal class ChapterEntity(
    val chapterId: String? = null,
    val chapterIndex: Int = 0,
    val chapterName: String? = null,
    val isVip: Int = 0,
    val isAuth: Int = 0,
    val publishTime: Long = 0,
    val publishTimeFormat: String? = null,
)
