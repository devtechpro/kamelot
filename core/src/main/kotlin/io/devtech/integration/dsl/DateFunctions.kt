package io.devtech.integration.dsl

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * DataWeave-inspired date/time functions.
 *
 * The zero-arg `now()` lives in `ImplementBuilder.kt` (returns ISO-8601 string).
 * This file adds formatted variants and date arithmetic.
 *
 * ```kotlin
 * now("yyyy-MM-dd")                              // "2026-02-25"
 * today()                                         // "2026-02-25"
 * formatDate("2026-02-25T10:30:00Z", "dd/MM/yyyy") // "25/02/2026"
 * dateAdd("2026-02-25T10:30:00Z", 7, ChronoUnit.DAYS)  // 7 days later
 * ```
 */

/** Current timestamp formatted with a pattern. */
fun now(pattern: String): String =
    ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern(pattern))

/** Today's date as "yyyy-MM-dd". */
fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

/** Current epoch milliseconds. */
fun epochMs(): Long = System.currentTimeMillis()

/**
 * Reformat an ISO-8601 timestamp string with a new pattern.
 *
 * ```kotlin
 * formatDate("2026-02-25T10:30:00Z", "dd/MM/yyyy")  // "25/02/2026"
 * formatDate("2026-02-25T10:30:00Z", "HH:mm")        // "10:30"
 * ```
 */
fun formatDate(iso: String, pattern: String): String {
    val instant = Instant.parse(iso)
    val zoned = instant.atZone(ZoneOffset.UTC)
    return zoned.format(DateTimeFormatter.ofPattern(pattern))
}

/**
 * Parse a date string with the given pattern and return ISO-8601.
 *
 * ```kotlin
 * parseDate("25/02/2026", "dd/MM/yyyy")  // "2026-02-25T00:00:00Z"
 * ```
 */
fun parseDate(text: String, pattern: String): String {
    val formatter = DateTimeFormatter.ofPattern(pattern)
    return try {
        val zoned = ZonedDateTime.parse(text, formatter.withZone(ZoneOffset.UTC))
        zoned.toInstant().toString()
    } catch (_: Exception) {
        val local = LocalDate.parse(text, formatter)
        local.atStartOfDay(ZoneOffset.UTC).toInstant().toString()
    }
}

/**
 * Add time to an ISO-8601 timestamp.
 *
 * ```kotlin
 * dateAdd("2026-02-25T10:00:00Z", 7, ChronoUnit.DAYS)    // 7 days later
 * dateAdd("2026-02-25T10:00:00Z", -1, ChronoUnit.HOURS)  // 1 hour earlier
 * ```
 */
fun dateAdd(iso: String, amount: Long, unit: ChronoUnit): String =
    Instant.parse(iso).plus(amount, unit).toString()

/**
 * Difference between two ISO-8601 timestamps in the given unit.
 *
 * ```kotlin
 * dateDiff("2026-02-25T10:00:00Z", "2026-02-26T10:00:00Z", ChronoUnit.DAYS)  // 1
 * ```
 */
fun dateDiff(iso1: String, iso2: String, unit: ChronoUnit): Long =
    unit.between(Instant.parse(iso1), Instant.parse(iso2))
