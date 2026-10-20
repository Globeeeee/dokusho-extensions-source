package dokusho.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ChapterDateTest {

    @Test
    fun parsesIsoInstant() {
        assertEquals(1742199221000L, parseChapterDate("2025-03-17T08:13:41.000Z"))
    }

    @Test
    fun parsesOffsetWithoutColon() {
        assertEquals(
            java.time.OffsetDateTime.parse("2025-01-29T12:45:00.000+00:00").toInstant().toEpochMilli(),
            parseChapterDate("2025-01-29T12:45:00.000+0000"),
        )
    }

    @Test
    fun parsesLocalDateTime() {
        val expected = java.time.LocalDateTime.parse("2022-06-02T14:30:56")
            .atZone(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, parseChapterDate("2022-06-02 14:30:56", ZoneOffset.UTC))
    }

    @Test
    fun parsesRelativeAgo() {
        val parsed = parseChapterDate("4 years ago")
        val fourYears = System.currentTimeMillis() - 4L * 365 * 24 * 60 * 60 * 1000
        assertTrue(kotlin.math.abs(parsed - fourYears) < 3L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun parsesIsoWithOffset() {
        assertEquals(
            java.time.OffsetDateTime.parse("2025-03-17T17:13:41+09:00").toInstant().toEpochMilli(),
            parseChapterDate("2025-03-17T17:13:41+09:00"),
        )
    }

    @Test
    fun parsesScribbleHubDateTime() {
        val expected = java.time.LocalDateTime.parse("2023-09-10T22:32:00")
            .atZone(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, parseChapterDate("Sep 10, 2023 10:32 PM", ZoneOffset.UTC))
    }

    @Test
    fun parsesSyosetuDateInTokyo() {
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        val expected = java.time.LocalDateTime.parse("2012-11-22T17:00:00").atZone(tokyo).toInstant().toEpochMilli()
        assertEquals(expected, parseChapterDate("2012/11/22 17:00", tokyo))
    }

    @Test
    fun parsesRelativeWithArticle() {
        val parsed = parseChapterDate("an hour ago")
        val oneHour = System.currentTimeMillis() - 60L * 60 * 1000
        assertTrue(kotlin.math.abs(parsed - oneHour) < 60L * 1000)
    }

    @Test
    fun hugeRelativeAmountIsMissing() {
        assertEquals(0L, parseChapterDate("99999999999 years ago"))
    }

    @Test
    fun blankIsMissing() {
        assertEquals(0L, parseChapterDate(null))
        assertEquals(0L, parseChapterDate("  "))
        assertEquals(0L, parseChapterDate("not a date"))
    }
}
