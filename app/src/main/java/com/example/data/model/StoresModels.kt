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
fun buildStoreSlots(cfg: StoreDeliveryCfg, nowMs: Long = System.currentTimeMillis(), minLeadMinutes: Int = 0): List<StoreSlotDay> {
    val lead = maxOf(cfg.leadMinutes, minLeadMinutes)
    val maxDays = maxOf(cfg.maxDays, (minLeadMinutes + 1439) / 1440 + 1)
    val ist = java.util.TimeZone.getTimeZone("Asia/Kolkata")
    val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    fun hm(mins: Int): String {
        val h = (mins / 60) % 24; val m = mins % 60
        return "${if (h % 12 == 0) 12 else h % 12}${if (m != 0) ":%02d".format(m) else ""} ${if (h < 12) "AM" else "PM"}"
    }
    val out = ArrayList<StoreSlotDay>()
    for (d in 0..maxDays) {
        val cal = java.util.Calendar.getInstance(ist).apply { timeInMillis = nowMs; add(java.util.Calendar.DAY_OF_YEAR, d); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val dayStart = cal.timeInMillis
        val slots = ArrayList<StoreSlot>()
        var m = cfg.slotFirstHour * 60
        while (m + cfg.slotMinutes <= cfg.slotLastHour * 60) {
            val start = dayStart + m * 60_000L
            if (start >= nowMs + lead * 60_000L) slots.add(StoreSlot(iso.format(java.util.Date(start)), "${hm(m)} - ${hm(m + cfg.slotMinutes)}"))
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

/** Stars, how many people gave each, and the latest written reviews of one shop. */
data class StoreRatingSummary(val avg: Double?, val count: Int, val distribution: Map<Int, Int>, val recent: List<RatingComment>) {
    data class RatingComment(val name: String, val rating: Int, val comment: String)
    companion object {
        fun from(o: JSONObject): StoreRatingSummary {
            val d = o.optJSONObject("distribution")
            val r = o.optJSONArray("recent")
            return StoreRatingSummary(
                o.dOrNull("avg_rating"), o.optInt("review_count", 0),
                (1..5).associateWith { d?.optInt(it.toString(), 0) ?: 0 },
                (0 until (r?.length() ?: 0)).map { i -> r!!.getJSONObject(i).let { RatingComment(it.optString("name", "Customer"), it.optInt("rating", 5), it.optString("comment", "")) } }
            )
        }
    }
}

/** A delivered order still waiting for the customer's rating. */
data class PendingReview(val orderId: String, val vendorName: String, val orderNumber: String?) {
    companion object { fun from(o: JSONObject) = PendingReview(o.getString("order_id"), o.optString("vendor_name", "your order"), o.optString("order_number").takeIf { it.isNotBlank() && it != "null" }) }
}

/** A shop with fewer ratings than this shows "New" instead of stars, so one 5-star review cannot outrank an established shop. */
const val MIN_REVIEWS_FOR_STARS = 3

/** Rating, review count and the free-delivery note of a shop card; nothing is invented (the server sends none when there is none). */
data class StoreCardInfo(val storeId: String, val avgRating: Double?, val reviewCount: Int, val freeDelivery: Boolean, val freeOver: Double?) {
    companion object {
        fun mapFrom(a: JSONArray): Map<String, StoreCardInfo> = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            StoreCardInfo(o.getString("store_id"), o.dOrNull("avg_rating"), o.optInt("review_count", 0), o.optBoolean("free_delivery", false), o.dOrNull("free_over"))
        }.associateBy { it.storeId }
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
    val unitLabel: String?, val categoryId: String?, val isAvailable: Boolean,
    /** Made-to-order: the shop asks for a message and/or a photo and may need notice */
    val isCustomizable: Boolean = false, val allowNote: Boolean = true, val allowPhoto: Boolean = false,
    val noteLabel: String? = null, val customRequired: Boolean = false, val leadHours: Int = 0,
    /** Sizes / options with their own price (empty: a plain product) */
    val variants: List<StoreVariant> = emptyList(),
    /** Smallest order (default 1) and the extra charge per piece for customising */
    val minOrderQty: Int = 1, val customExtraCharge: Double = 0.0,
    /** The shop quotes the price: the customer asks first */
    val needsQuote: Boolean = false
) {
    val discountPercent: Int get() = if (mrp != null && mrp > price && !hasVariants) Math.round(((mrp - price) * 100 / mrp)).toInt() else 0
    val availableVariants: List<StoreVariant> get() = variants.filter { it.isAvailable }
    val hasVariants: Boolean get() = availableVariants.isNotEmpty()
    /** The lowest option price when there are options, else the product price */
    val fromPrice: Double get() = availableVariants.minOfOrNull { it.price } ?: price
}

data class StoreVariant(val id: String, val label: String, val price: Double, val isAvailable: Boolean)

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
                        p.sOrNull("unit_label"), p.sOrNull("category_id"), p.optBoolean("is_available", true),
                        isCustomizable = p.optBoolean("is_customizable", false), allowNote = p.optBoolean("custom_allow_note", true), allowPhoto = p.optBoolean("custom_allow_photo", false),
                        noteLabel = p.sOrNull("custom_note_label"), customRequired = p.optBoolean("custom_required", false), leadHours = p.optInt("custom_lead_hours", 0),
                        minOrderQty = maxOf(1, p.optInt("min_order_qty", 1)), customExtraCharge = p.optDouble("custom_extra_charge", 0.0), needsQuote = p.optBoolean("needs_quote", false),
                        variants = (p.optJSONArray("variants") ?: JSONArray()).let { va -> (0 until va.length()).map { k -> va.getJSONObject(k).let { v -> StoreVariant(v.getString("id"), v.optString("label"), v.optDouble("price", 0.0), v.optBoolean("is_available", true)) } } })
                }
            )
        }
    }
}

data class StoreLoc(val lat: Double, val lng: Double)


/** Fees of one cart from the server (City Admin fee rules) and the "add Rs X more" nudges. */
data class FeeNudge(val kind: String, val add: Double, val saves: Double, val text: String)
data class CheckoutFees(val rulesEnabled: Boolean, val deliveryFee: Double, val handlingFee: Double, val platformFee: Double, val nudges: List<FeeNudge>) {
    companion object {
        fun from(o: JSONObject): CheckoutFees {
            val n = o.optJSONArray("nudges") ?: JSONArray()
            return CheckoutFees(
                o.optBoolean("rules_enabled", false), o.optDouble("delivery_fee", 0.0), o.optDouble("handling_fee", 0.0), o.optDouble("platform_fee", 0.0),
                (0 until n.length()).map { i -> n.getJSONObject(i).let { FeeNudge(it.optString("kind"), it.optDouble("add", 0.0), it.optDouble("saves", 0.0), it.optString("text")) } }
            )
        }
    }
}

/** A price quote asked from a shop for custom work. */
data class StoreQuote(
    val id: String, val vendorId: String, val vendorName: String, val productId: String, val productName: String, val variantId: String?, val variantLabel: String?,
    val quantity: Int, val note: String?, val photoPath: String?, val status: String, val quotedUnitPrice: Double?, val vendorNote: String?,
    val expiresAt: String?, val validUntil: String?, val orderId: String?
) {
    val statusLabel get() = when (status) {
        "requested" -> "Waiting for the shop"; "quoted" -> "Price received"; "accepted" -> "Accepted"; "declined" -> "Declined"
        "cancelled" -> "Cancelled"; "expired" -> "Expired"; "used" -> "Ordered"; else -> status
    }
    val total: Double? get() = quotedUnitPrice?.let { it * quantity }
    companion object {
        fun listFrom(a: JSONArray): List<StoreQuote> = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            StoreQuote(o.getString("id"), o.getString("vendor_id"), o.optString("vendor_name"), o.getString("product_id"), o.optString("product_name"), o.sOrNull("variant_id"), o.sOrNull("variant_label"),
                o.optDouble("quantity", 1.0).toInt(), o.sOrNull("note"), o.sOrNull("photo_path"), o.optString("status"), o.dOrNull("quoted_unit_price"), o.sOrNull("vendor_note"),
                o.sOrNull("expires_at"), o.sOrNull("valid_until"), o.sOrNull("order_id"))
        }
    }
}
