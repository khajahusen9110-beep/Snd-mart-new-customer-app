package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

/** Quick Helper: a nearby helper for a short time. Plain classes read by hand from the server's JSON, like Stores. */
data class HelperRuleItem(val text: String, val textKn: String?, val imageUrl: String?)

data class HelperCategory(
    val id: String,
    val name: String,
    val description: String,
    val imageUrl: String?,
    val photosAllowed: Boolean,
    val photoRequired: Boolean,
    val taskPlaceholder: String,
    val ratePer30: Double,
    val durations: List<Int>,
    val prices: Map<Int, Double>,
    val dos: List<HelperRuleItem>,
    val donts: List<HelperRuleItem>,
    val isCustom: Boolean = false
) {
    fun priceFor(minutes: Int): Double = prices[minutes] ?: (ratePer30 * minutes / 30.0)
    val fromPrice: Double get() = durations.minOfOrNull { priceFor(it) } ?: ratePer30
}

data class HelperConfig(
    val enabled: Boolean = false,
    val maxPhotos: Int = 3,
    val bannedNote: String = "",
    val termsText: String = "",
    val supportPhone: String = "",
    val paymentMode: String = "both", // upi | cash | both
    val minLeadMinutes: Int = 30,
    val maxDaysAhead: Int = 7,
    val requireRulesAck: Boolean = true,
    val refundPercentAfterAccept: Int = 100,
    val extensionEnabled: Boolean = true,
    val extensionOptions: List<Int> = listOf(30, 60),
    val maxExtensions: Int = 3,
    val ratePer30: Double = 0.0,
    val categories: List<HelperCategory> = emptyList()
) {
    companion object {
        private fun nz(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }

        private fun rules(a: JSONArray?): List<HelperRuleItem> =
            if (a == null) emptyList() else (0 until a.length()).map { val r = a.getJSONObject(it); HelperRuleItem(r.optString("text"), nz(r, "text_kn"), nz(r, "image_url")) }

        private fun prices(p: JSONObject?): Map<Int, Double> {
            val m = HashMap<Int, Double>()
            p?.keys()?.forEach { k -> k.toIntOrNull()?.let { m[it] = p.optDouble(k, 0.0) } }
            return m
        }

        fun from(o: JSONObject): HelperConfig {
            val cats = o.optJSONArray("categories")
            return HelperConfig(
                enabled = o.optBoolean("enabled", false),
                maxPhotos = o.optInt("max_photos", 3),
                bannedNote = o.optString("banned_note", ""),
                termsText = o.optString("terms_text", ""),
                supportPhone = o.optString("support_phone", ""),
                paymentMode = o.optString("payment_mode", "both"),
                minLeadMinutes = o.optInt("min_lead_minutes", 30),
                maxDaysAhead = o.optInt("max_days_ahead", 7),
                requireRulesAck = o.optBoolean("require_rules_ack", true),
                refundPercentAfterAccept = o.optInt("refund_percent_after_accept", 100),
                extensionEnabled = o.optBoolean("extension_enabled", true),
                extensionOptions = o.optJSONArray("extension_options")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: listOf(30, 60),
                maxExtensions = o.optInt("max_extensions", 3),
                ratePer30 = o.optDouble("rate_per_30", 0.0),
                categories = if (cats == null) emptyList() else (0 until cats.length()).map {
                    val c = cats.getJSONObject(it)
                    val d = c.optJSONArray("durations")
                    HelperCategory(
                        id = c.getString("id"), name = c.optString("name"), description = c.optString("description"), imageUrl = nz(c, "image_url"),
                        photosAllowed = c.optBoolean("photos_allowed", true), photoRequired = c.optBoolean("photo_required", false),
                        taskPlaceholder = c.optString("task_placeholder"), ratePer30 = c.optDouble("rate_per_30", 0.0),
                        durations = if (d == null) listOf(30, 60, 90) else (0 until d.length()).map { i -> d.getInt(i) },
                        prices = prices(c.optJSONObject("prices")), dos = rules(c.optJSONArray("dos")), donts = rules(c.optJSONArray("donts")), isCustom = c.optBoolean("is_custom", false)
                    )
                }
            )
        }
    }
}

data class HelperExtension(val id: String, val minutes: Int, val amount: Double, val status: String, val paymentMethod: String, val paymentStatus: String)

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
    val paymentMethod: String,
    val paymentCollected: Boolean,
    val paymentStatus: String,
    val refundAmount: Double,
    val startOtp: String?,
    val endOtp: String?,
    val endsAt: String?,
    val cancelReason: String?,
    val rating: Int?,
    val review: String?,
    val createdAt: String,
    val categoryId: String?,
    val extensions: List<HelperExtension>,
    val helper: HelperPerson?
) {
    val isActive get() = status in setOf("pending_payment", "requested", "accepted", "on_the_way", "started")
    val statusLabel get() = when (status) {
        "pending_payment" -> "Payment pending"
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
                paymentMethod = o.optString("payment_method", "cash"),
                paymentCollected = o.optBoolean("payment_collected", false),
                paymentStatus = o.optString("payment_status", "unpaid"),
                refundAmount = o.optDouble("refund_amount", 0.0),
                startOtp = str(o, "start_otp"),
                endOtp = str(o, "end_otp"),
                endsAt = str(o, "ends_at"),
                cancelReason = str(o, "cancel_reason"),
                rating = if (o.isNull("rating")) null else o.optInt("rating"),
                review = str(o, "review"),
                createdAt = o.optString("created_at"),
                categoryId = str(o, "category_id"),
                extensions = o.optJSONArray("extensions")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { e ->
                    HelperExtension(e.optString("id"), e.optInt("minutes"), e.optDouble("amount", 0.0), e.optString("status"), e.optString("payment_method", "cash"), e.optString("payment_status", "unpaid")) } } } ?: emptyList(),
                helper = h?.let { HelperPerson(it.optString("name"), str(it, "phone"), if (it.isNull("avg_rating")) null else it.optDouble("avg_rating"), it.optInt("total_ratings", 0)) }
            )
        }

        fun list(raw: String): List<HelperBooking> {
            val a = JSONArray(raw)
            return (0 until a.length()).map { from(a.getJSONObject(it)) }
        }
    }
}
