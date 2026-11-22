package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

// Stores: local shops (kirana, vegetables, bakery, electronics ...) that sell through SND Mart next to Food.
// Plain classes read from the server's JSON by hand, like Tiffin. When Stores is off for a city the server answers
// "nothing" and the app looks exactly as before.

private fun JSONObject.sOrNull(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
private fun JSONObject.dOrNull(k: String): Double? = if (isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

data class StoreCategory(
    val id: String, val key: String, val name: String, val nameKn: String?, val imageUrl: String?, val template: String, val storeCount: Int
)

data class StoresConfig(
    val enabled: Boolean,
    /** true: the old Groceries tab is replaced by Stores, which starts with the "SND Mart Store" card */
    val replaceGrocery: Boolean,
    val radiusKm: Double,
    val categories: List<StoreCategory>,
    /** Express / Scheduled options of the city; null on an older server (Express only) */
    val delivery: StoreDeliveryCfg? = null
) {
    companion object {
        val OFF = StoresConfig(false, false, 5.0, emptyList())
        fun from(o: JSONObject): StoresConfig {
            val cats = o.optJSONArray("categories") ?: JSONArray()
            return StoresConfig(
                enabled = o.optBoolean("stores_enabled", false),
                replaceGrocery = o.optBoolean("replace_grocery", false),
                radiusKm = o.dOrNull("radius_km") ?: 5.0,
                categories = (0 until cats.length()).map { i ->
                    val c = cats.getJSONObject(i)
                    StoreCategory(c.getString("id"), c.optString("key"), c.optString("name"), c.sOrNull("name_kn"), c.sOrNull("image_url"), c.optString("template", "standard"), c.optInt("store_count", 0))
                },
                delivery = o.optJSONObject("delivery")?.let { StoreDeliveryCfg.from(it) }
            )
        }
    }
}

data class StoreDeliveryCfg(
    val expressEnabled: Boolean, val expressExtraFee: Double,
    val scheduledEnabled: Boolean, val scheduledDiscount: Double,
    val slotFirstHour: Int, val slotLastHour: Int, val slotMinutes: Int, val leadMinutes: Int, val maxDays: Int
) {
    companion object {
        /** Older server / nothing configured: Express only, as before. */
        val EXPRESS_ONLY = StoreDeliveryCfg(true, 0.0, false, 0.0, 8, 21, 120, 120, 2)
        fun from(o: JSONObject) = StoreDeliveryCfg(
            expressEnabled = o.optBoolean("express_enabled", true),
            expressExtraFee = o.dOrNull("express_extra_fee") ?: 0.0,
            scheduledEnabled = o.optBoolean("scheduled_enabled", false),
            scheduledDiscount = o.dOrNull("scheduled_discount") ?: 0.0,
            slotFirstHour = o.optInt("slot_first_hour", 8), slotLastHour = o.optInt("slot_last_hour", 21),
            slotMinutes = o.optInt("slot_minutes", 120), leadMinutes = o.optInt("lead_minutes", 120), maxDays = o.optInt("max_days", 2)
        )
    }
}

data class StoreDeliveryChoice(val scheduled: Boolean, /** ISO-8601 UTC start of the chosen window (scheduled only) */ val scheduledFor: String?)

data class StoreSlot(val startIso: String, val label: String)
data class StoreSlotDay(val key: String, val label: String, val slots: List<StoreSlot>)

/** Days + time windows the customer can pick (India time). The server checks the same rules again. */
fun buildStoreSlots(cfg: StoreDeliveryCfg, nowMs: Long = System.currentTimeMillis()): List<StoreSlotDay> {
    val ist = java.util.TimeZone.getTimeZone("Asia/Kolkata")
    val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    fun hm(mins: Int): String {
        val h = (mins / 60) % 24; val m = mins % 60
        return "${if (h % 12 == 0) 12 else h % 12}${if (m != 0) ":%02d".format(m) else ""} ${if (h < 12) "AM" else "PM"}"
    }
    val out = ArrayList<StoreSlotDay>()
    for (d in 0..cfg.maxDays) {
        val cal = java.util.Calendar.getInstance(ist).apply { timeInMillis = nowMs; add(java.util.Calendar.DAY_OF_YEAR, d); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val dayStart = cal.timeInMillis
        val slots = ArrayList<StoreSlot>()
        var m = cfg.slotFirstHour * 60
        while (m + cfg.slotMinutes <= cfg.slotLastHour * 60) {
            val start = dayStart + m * 60_000L
            if (start >= nowMs + cfg.leadMinutes * 60_000L) slots.add(StoreSlot(iso.format(java.util.Date(start)), "${hm(m)} - ${hm(m + cfg.slotMinutes)}"))
            m += cfg.slotMinutes
        }
        if (slots.isEmpty()) continue
        val label = when (d) { 0 -> "Today"; 1 -> "Tomorrow"; else -> java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.ENGLISH).apply { timeZone = ist }.format(java.util.Date(dayStart)) }
        out.add(StoreSlotDay(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = ist }.format(java.util.Date(dayStart)), label, slots))
    }
    return out
}

data class StoreListItem(
    val storeId: String, val name: String, val bannerUrl: String?, val address: String?,
    val categoryId: String, val categoryName: String, val distanceKm: Double?, val isOpen: Boolean, val isNearest: Boolean
) {
    companion object {
        fun listFrom(a: JSONArray): List<StoreListItem> = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            StoreListItem(o.getString("store_id"), o.optString("name"), o.sOrNull("banner_url"), o.sOrNull("address"), o.optString("category_id"),
                o.optString("category_name"), o.dOrNull("distance_km"), o.optBoolean("is_open", false), o.optBoolean("is_nearest", false))
        }
    }
}

data class StoreProductHit(
    val productId: String, val name: String, val price: Double, val mrp: Double?, val unit: String?, val unitPrice: Double?, val unitLabel: String?,
    val imageUrl: String?, val storeId: String, val storeName: String, val distanceKm: Double?
) {
    companion object {
        fun listFrom(a: JSONArray): List<StoreProductHit> = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            StoreProductHit(o.getString("product_id"), o.optString("name"), o.optDouble("price", 0.0), o.dOrNull("mrp"), o.sOrNull("unit"), o.dOrNull("unit_price"), o.sOrNull("unit_label"),
                o.sOrNull("image_url"), o.getString("store_id"), o.optString("store_name"), o.dOrNull("distance_km"))
        }
    }
}

data class StoreMenuProduct(
    val id: String, val name: String, val imageUrl: String?, val price: Double, val mrp: Double?, val unit: String?, val unitPrice: Double?,
    val unitLabel: String?, val categoryId: String?, val isAvailable: Boolean
)

data class StoreMenu(
    val id: String, val name: String, val bannerUrl: String?, val address: String?, val categoryName: String?, val isOpen: Boolean, val prepMinutes: Int?,
    val categories: List<Pair<String, String>>, val products: List<StoreMenuProduct>
) {
    companion object {
        fun from(o: JSONObject): StoreMenu {
            val s = o.getJSONObject("store")
            val cats = o.optJSONArray("categories") ?: JSONArray()
            val prods = o.optJSONArray("products") ?: JSONArray()
            return StoreMenu(
                id = s.getString("id"), name = s.optString("name"), bannerUrl = s.sOrNull("banner_url"), address = s.sOrNull("address"),
                categoryName = s.sOrNull("category_name"), isOpen = s.optBoolean("is_open", false), prepMinutes = if (s.isNull("prep_minutes")) null else s.optInt("prep_minutes"),
                categories = (0 until cats.length()).map { cats.getJSONObject(it).let { c -> c.getString("id") to c.optString("name") } },
                products = (0 until prods.length()).map { i ->
                    val p = prods.getJSONObject(i)
                    StoreMenuProduct(p.getString("id"), p.optString("name"), p.sOrNull("image_url"), p.optDouble("price", 0.0), p.dOrNull("mrp"), p.sOrNull("unit"), p.dOrNull("unit_price"),
                        p.sOrNull("unit_label"), p.sOrNull("category_id"), p.optBoolean("is_available", true))
                }
            )
        }
    }
}

data class StoreLoc(val lat: Double, val lng: Double)
