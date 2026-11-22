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
    val categories: List<StoreCategory>
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
                }
            )
        }
    }
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
