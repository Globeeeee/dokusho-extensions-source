package dokusho.utils

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

@Deprecated(
    message = "Use Instant.tryParse, DateTimeFormatter.tryParseDate, tryParseDateTime, or tryParseZonedDateTime instead.",
)
fun SimpleDateFormat.tryParse(date: String?): Long {
    date ?: return 0L

    return parse(date, ParsePosition(0))?.time ?: 0L
}

/**
 * Parses [date] using a date only pattern, for example `yyyy-MM-dd` or `yyyy/MM/dd`, then
 * resolves it to the start of that day in [zone]. Applies when the source only sends a
 * date with no time of day.
 *
 * Any offset or zone id present in [date] is ignored, since only date fields are extracted.
 * Returns 0 if [date] is null or cannot be parsed.
 *
 * @see tryParseDateTime
 * @see tryParseZonedDateTime
 */
fun DateTimeFormatter.tryParseDate(
    date: String?,
    zone: ZoneId? = null,
): Long {
    date ?: return 0L

    return runCatching {
        LocalDate.parse(date, this)
            .atStartOfDay(zone ?: this.zone ?: ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrDefault(0L)
}

/**
 * Parses [date] using a pattern that includes both date and time, for example
 * `yyyy-MM-dd HH:mm:ss` or `yyyy-MM-dd'T'HH:mm:ss`, then resolves it to an instant in
 * [zone]. Applies when the source sends a local date and time with no reliable offset, or
 * when an offset is present in the text but [zone] should decide the result instead.
 *
 * Any offset or zone id present in [date] is ignored, since only local date and time
 * fields are extracted; [zone] always decides the resulting instant. Returns 0 if [date]
 * is null or cannot be parsed.
 *
 * @see tryParseDate
 * @see tryParseZonedDateTime
 */
fun DateTimeFormatter.tryParseDateTime(
    date: String?,
    zone: ZoneId? = null,
): Long {
    date ?: return 0L

    return runCatching {
        LocalDateTime.parse(date, this)
            .atZone(zone ?: this.zone ?: ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrDefault(0L)
}

/**
 * Parses [date] using a pattern whose own offset or zone id should decide the result, for
 * example `yyyy-MM-dd'T'HH:mm:ssXXX` or a pattern ending in `'['VV']'`. Applies when
 * the source's offset or zone id is trustworthy and no separate [zone] needs to be
 * supplied.
 *
 * The pattern must actually produce an offset or zone id, using X, XXX, a five letter Z,
 * or VV, or parsing fails. Returns 0 if [date] is null or cannot be parsed.
 *
 * @see tryParseDate
 * @see tryParseDateTime
 */
fun DateTimeFormatter.tryParseZonedDateTime(date: String?): Long {
    date ?: return 0L

    return runCatching {
        ZonedDateTime.parse(date, this)
            .toInstant()
            .toEpochMilli()
    }.getOrDefault(0L)
}

/**
 * Parses [date] as a full ISO 8601 instant string, for example
 * `1999-03-22T05:06:07.000Z` or `1999-03-22T05:06:07+01:00`. Applies when the source
 * already sends a self describing instant string and no [DateTimeFormatter] pattern is
 * needed.
 *
 * Returns 0 if [date] is null or cannot be parsed.
 */
fun Instant.Companion.tryParse(date: String?): Long {
    date ?: return 0L

    return parseOrNull(date)?.toEpochMilliseconds() ?: 0L
}

private val RELATIVE_AGO = Regex(
    """\b(\d{1,6}|an?)\s+(min|minute|hour|day|week|month|year)s?\s+ago""",
    RegexOption.IGNORE_CASE,
)

/** `+0000` at the end of an ISO string, which [java.time.Instant] will not parse. */
private val OFFSET_WITHOUT_COLON = Regex("""([+-])(\d{2})(\d{2})$""")

private val LOCAL_DATE_TIMES = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy/MM/dd HH:mm",
    "MMM d, yyyy hh:mm a",
    "MMMM d, yyyy",
    "MMM d, yyyy",
    "yyyy-MM-dd",
).map { DateTimeFormatter.ofPattern(it, java.util.Locale.ENGLISH) }

/**
 * Parses a chapter upload date from whatever text a source puts on the chapter row: an ISO instant
 * (`2025-03-17T08:13:41.000Z` or `2025-01-29T12:45:00.000+0000`), a local date-time, or a relative
 * phrase such as "4 years ago". Returns 0 when [raw] is blank or none of the forms match, so the
 * app can tell a missing date from a real one.
 */
fun parseChapterDate(raw: String?, zone: ZoneId = ZoneId.systemDefault()): Long {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
    relativeDate(text)?.let { return it }

    val candidates = buildList {
        add(text)
        val withColon = OFFSET_WITHOUT_COLON.replace(text) {
            "${it.groupValues[1]}${it.groupValues[2]}:${it.groupValues[3]}"
        }
        if (withColon != text) add(withColon)
        if (' ' in text && 'T' !in text) add(text.replaceFirst(' ', 'T'))
    }
    for (candidate in candidates) {
        runCatching { java.time.Instant.parse(candidate).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { java.time.OffsetDateTime.parse(candidate).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { ZonedDateTime.parse(candidate).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        for (formatter in LOCAL_DATE_TIMES) {
            formatter.tryParseDateTime(candidate, zone).takeIf { it != 0L }?.let { return it }
            formatter.tryParseDate(candidate, zone).takeIf { it != 0L }?.let { return it }
        }
    }
    return 0L
}

private fun relativeDate(text: String): Long? {
    when (text.lowercase()) {
        "today", "just now" -> return System.currentTimeMillis()
        "yesterday" -> return System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    }
    val match = RELATIVE_AGO.find(text) ?: return null
    val amount = match.groupValues[1].toIntOrNull() ?: 1
    val field = when (match.groupValues[2].lowercase()) {
        "min", "minute" -> java.util.Calendar.MINUTE
        "hour" -> java.util.Calendar.HOUR_OF_DAY
        "day" -> java.util.Calendar.DAY_OF_YEAR
        "week" -> java.util.Calendar.WEEK_OF_YEAR
        "month" -> java.util.Calendar.MONTH
        "year" -> java.util.Calendar.YEAR
        else -> return null
    }
    return java.util.Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
}
