package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import okhttp3.Request
import okhttp3.Response

/**
 * [HttpSource] for websites that publish chapters as text. Mirrors dokusho/source-api.
 */
@Suppress("UNUSED")
abstract class HttpNovelSource :
    HttpSource(),
    NovelSource {

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent = throw Exception("Stub!")

    protected open fun chapterContentRequest(chapter: SChapter): Request = throw Exception("Stub!")

    protected abstract fun chapterContentParse(response: Response): ChapterContent

    override suspend fun getPageList(chapter: SChapter): List<Page> = throw Exception("Stub!")
}
