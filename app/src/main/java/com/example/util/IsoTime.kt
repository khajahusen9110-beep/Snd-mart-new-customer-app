package com.example.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val ISO_REGEX =
    Regex("""^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}(?::?\d{2})?)?$""")

/**
 * Parses Postgres / PostgREST timestamps such as `2026-10-01T03:26:00.123456+00:00`,
 * `2026-10-01T03:26:00Z` or `2026-10-01 08:56:00+05:30` into epoch millis.
 * A missing offset is treated as UTC. Returns null for blank or unparseable input.
 */
fun parseIsoMillis(value: String?): Long? {
    val match = ISO_REGEX.matchEntire(value?.trim().orEmpty()) ?: return null
    val (date, time, fraction, zone) = match.destructured
    val base = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse("$date $time")?.time
    } catch (e: Exception) {
        null
    } ?: return null

    val millis = fraction.drop(1).padEnd(3, '0').take(3).toIntOrNull() ?: 0

    val offsetMs = when {
        zone.isEmpty() || zone == "Z" -> 0L
        else -> {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.drop(1).replace(":", "")
            val hours = digits.take(2).toIntOrNull() ?: 0
            val minutes = digits.drop(2).take(2).toIntOrNull() ?: 0
            sign * (hours * 60L + minutes) * 60_000L
        }
    }
    return base + millis - offsetMs
}
