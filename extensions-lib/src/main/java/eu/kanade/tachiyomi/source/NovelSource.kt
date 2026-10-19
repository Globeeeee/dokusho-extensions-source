package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.SChapter

/**
 * A source whose chapters are text rather than page images. Mirrors dokusho/source-api.
 */
interface NovelSource {

    /**
     * Get the text of a chapter. A chapter the user's account cannot read must come back with
     * [ChapterContent.locked] set and empty [ChapterContent.html], never fetched from elsewhere.
     */
    suspend fun getChapterContent(chapter: SChapter): ChapterContent
}
