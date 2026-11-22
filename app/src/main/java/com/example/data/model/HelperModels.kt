package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

/** Quick Helper: a nearby helper for 30 / 60 / 90 minutes. Plain classes read by hand from the server's JSON, like Stores. */
data class HelperConfig(
    val enabled: Boolean = false,
    val ratePer30: Double = 0.0,
    val durations: List<Int> = listOf(30, 60, 90),
    val maxPhotos: Int = 3,
    val taskTypes: List<String> = emptyList(),
    val bannedNote: String = "",
    val prices: Map<Int, Double> = emptyMap()
) {
    fun priceFor(minutes: Int): Double = prices[minutes] ?: (ratePer30 * minutes / 30.0)

    companion object {
        fun from(o: JSONObject): HelperConfig {
            val d = o.optJSONArray("durations")
            val t = o.optJSONArray("task_types")
            val p = o.optJSONObject("prices")
            val prices = HashMap<Int, Double>()
            p?.keys()?.forEach { k -> k.toIntOrNull()?.let { prices[it] = p.optDouble(k, 0.0) } }
            return HelperConfig(
                enabled = o.optBoolean("enabled", false),
                ratePer30 = o.optDouble("rate_per_30", 0.0),
                durations = if (d == null) listOf(30, 60, 90) else (0 until d.length()).map { d.getInt(it) },
                maxPhotos = o.optInt("max_photos", 3),
                taskTypes = if (t == null) emptyList() else (0 until t.length()).map { t.getString(it) },
                bannedNote = o.optString("banned_note", ""),
                prices = prices
            )
        }
    }
}

data class HelperPerson(val name: String, val phone: String?, val avgRating: Double?, val totalRatings: Int)

data class HelperBooking(
    val id: String,
    val bookingNumber: String,
    val status: String,
    val taskType: String,
    val taskDescription: String,
    val photoPaths: List<String>,
    val durationMinutes: Int,
    val addressLine: String,
    val priceTotal: Double,
    val paymentCollected: Boolean,
    val startOtp: String?,
    val endOtp: String?,
    val endsAt: String?,
    val cancelReason: String?,
    val rating: Int?,
    val review: String?,
    val createdAt: String,
    val helper: HelperPerson?
) {
    val isActive get() = status in setOf("requested", "accepted", "on_the_way", "started")
    val statusLabel get() = when (status) {
        "requested" -> "Finding a helper"
        "accepted" -> "Helper accepted"
        "on_the_way" -> "Helper is on the way"
        "started" -> "Work in progress"
        "completed" -> "Completed"
        "cancelled" -> "Cancelled"
        "expired" -> "No helper available"
        else -> status
    }

    companion object {
        private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }

        fun from(o: JSONObject): HelperBooking {
            val ph = o.optJSONArray("photo_paths")
            val h = o.optJSONObject("helper")
            return HelperBooking(
                id = o.getString("id"),
                bookingNumber = o.optString("booking_number"),
                status = o.optString("status"),
                taskType = o.optString("task_type"),
                taskDescription = o.optString("task_description"),
                photoPaths = if (ph == null) emptyList() else (0 until ph.length()).map { ph.getString(it) },
                durationMinutes = o.optInt("duration_minutes", 30),
                addressLine = o.optString("address_line"),
                priceTotal = o.optDouble("price_total", 0.0),
                paymentCollected = o.optBoolean("payment_collected", false),
                startOtp = str(o, "start_otp"),
                endOtp = str(o, "end_otp"),
                endsAt = str(o, "ends_at"),
                cancelReason = str(o, "cancel_reason"),
                rating = if (o.isNull("rating")) null else o.optInt("rating"),
                review = str(o, "review"),
                createdAt = o.optString("created_at"),
                helper = h?.let { HelperPerson(it.optString("name"), str(it, "phone"), if (it.isNull("avg_rating")) null else it.optDouble("avg_rating"), it.optInt("total_ratings", 0)) }
            )
        }

        fun list(raw: String): List<HelperBooking> {
            val a = JSONArray(raw)
            return (0 until a.length()).map { from(a.getJSONObject(it)) }
        }
    }
}
