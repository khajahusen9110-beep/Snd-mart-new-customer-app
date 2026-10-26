package com.example.util

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Checks whether current time falls within the vendor's operating hours.
 * Returns true if no hours are set or parsing fails (to not incorrectly block ordering).
 * Handles:
 *  - "11:00", "11:00:00" (24-hour)
 *  - "11:00 AM", "11:00:00 PM" (12-hour AM/PM)
 *  - Overnight windows, e.g. 18:00 to 02:00
 */
fun isVendorWithinOperatingHours(
    openingTime: String?,
    closingTime: String?,
    now: LocalTime = LocalTime.now()
): Boolean {
    if (openingTime.isNullOrBlank() || closingTime.isNullOrBlank()) return true // no hours set = always open

    return try {
        val open = parseTimeString(openingTime.trim()) ?: return true
        val close = parseTimeString(closingTime.trim()) ?: return true

        if (open <= close) {
            now in open..close
        } else {
            // Handles overnight windows, e.g. 18:00 to 02:00
            now >= open || now <= close
        }
    } catch (e: Exception) {
        true // if parsing fails, don't incorrectly block ordering
    }
}

/**
 * City Admin's Operating Hours cap: outside this window, or while Emergency Closed, nothing in
 * the city — hotel or grocery — can be ordered, regardless of any vendor's own hours.
 */
fun isCityOpenNow(city: com.example.data.model.City?, now: LocalTime = LocalTime.now()): Boolean {
    if (city == null) return true
    if (city.emergencyClosed) return false
    return isVendorWithinOperatingHours(city.openTime, city.closeTime, now)
}

/**
 * Checks if current time falls within ANY of the vendor's operating hour slots.
 * Missing or empty slots are treated as unrestricted (always open) to match existing behavior
 * for hotels that haven't set slot hours yet.
 */
fun isWithinAnySlot(
    slots: List<com.example.data.model.OperatingSlot>,
    now: LocalTime = LocalTime.now()
): Boolean {
    if (slots.isEmpty()) return true
    return slots.any { slot ->
        isVendorWithinOperatingHours(slot.startTime, slot.endTime, now)
    }
}

/**
 * Parses time strings in common 24h or 12h formats:
 * - HH:mm, HH:mm:ss, H:mm, H:mm:ss
 * - hh:mm a, hh:mm:ss a, h:mm a, h:mm:ss a (e.g. "11:00 AM", "6:30 PM")
 */
fun parseTimeString(timeStr: String): LocalTime? {
    val clean = timeStr.trim().replace("\\s+".toRegex(), " ")
    val formats = listOf(
        // 24-hour formats
        DateTimeFormatter.ofPattern("HH:mm[:ss]", Locale.US),
        DateTimeFormatter.ofPattern("H:mm[:ss]", Locale.US),
        // 12-hour formats
        DateTimeFormatter.ofPattern("hh:mm[:ss] a", Locale.US),
        DateTimeFormatter.ofPattern("h:mm[:ss] a", Locale.US),
        DateTimeFormatter.ofPattern("hh:mma", Locale.US),
        DateTimeFormatter.ofPattern("h:mma", Locale.US)
    )

    for (formatter in formats) {
        try {
            return LocalTime.parse(clean, formatter)
        } catch (_: Exception) {
            // Try next pattern
        }
    }
    return null
}
