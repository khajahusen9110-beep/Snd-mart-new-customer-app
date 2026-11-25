package com.example.data.repository

import com.example.data.model.*
import com.example.data.remote.SupabaseClient
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response

/** Stores calls. The server decides everything (what is on, who is in range, the order, cheapest first); this only asks. */
object StoresRepository {
    private val api get() = SupabaseClient.api
    private var cached: Triple<String, Long, StoresConfig>? = null

    private fun readText(res: Response<okhttp3.ResponseBody>): String =
        try { (if (res.isSuccessful) res.body()?.string() else res.errorBody()?.string()).orEmpty() } catch (e: Exception) { "" }

    private suspend fun <T> call(fallback: String, block: suspend () -> Response<okhttp3.ResponseBody>, parse: (String) -> T): Result<T> =
        try {
            val res = block()
            val raw = readText(res)
            if (res.isSuccessful) Result.success(parse(raw))
            else Result.failure(Exception(runCatching { JSONObject(raw).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "$fallback (HTTP ${res.code()})"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** OFF on any problem, so a hiccup can never show a half-working Stores. */
    suspend fun getConfig(cityId: String?, force: Boolean = false): StoresConfig {
        if (cityId.isNullOrBlank()) return StoresConfig.OFF
        cached?.let { (id, at, v) -> if (!force && id == cityId && System.currentTimeMillis() - at < 60_000) return v }
        val v = call("Stores", { api.storesGetConfig(mapOf("p_city_id" to cityId)) }) { StoresConfig.from(JSONObject(it)) }.getOrNull() ?: return StoresConfig.OFF
        cached = Triple(cityId, System.currentTimeMillis(), v)
        return v
    }

    private fun locArgs(loc: StoreLoc?): Map<String, Any> = if (loc == null) emptyMap() else mapOf("p_lat" to loc.lat, "p_lng" to loc.lng)

    suspend fun listStores(cityId: String, loc: StoreLoc?, categoryId: String?): Result<List<StoreListItem>> =
        call("Could not load the shops", {
            api.storesList(buildMap { put("p_city_id", cityId); putAll(locArgs(loc)); if (categoryId != null) put("p_category_id", categoryId) })
        }) { StoreListItem.listFrom(JSONArray(it)) }

    /** Rating and free-delivery note for the shops on the screen; empty on any problem (the card then shows less, never wrong). */
    suspend fun cardInfo(cityId: String, stores: List<StoreListItem>): Map<String, StoreCardInfo> {
        if (stores.isEmpty()) return emptyMap()
        val body = mapOf<String, Any?>(
            "p_city_id" to cityId,
            "p_stores" to stores.map { mapOf("id" to it.storeId, "distance_km" to it.distanceKm) }
        )
        return call("Could not load shop details", { api.storesCardInfo(body) }) { StoreCardInfo.mapFrom(JSONArray(it)) }.getOrDefault(emptyMap())
    }

    suspend fun search(cityId: String, loc: StoreLoc?, query: String, sort: String, categoryId: String? = null): Result<List<StoreProductHit>> =
        call("Search failed", {
            api.storesSearch(buildMap {
                put("p_city_id", cityId); putAll(locArgs(loc)); put("p_query", query); put("p_sort", sort); put("p_limit", 40); put("p_offset", 0)
                if (categoryId != null) put("p_category_id", categoryId)
            })
        }) { StoreProductHit.listFrom(JSONArray(it)) }

    suspend fun getMenu(storeId: String): Result<StoreMenu> =
        call("Could not load this shop", { api.storeGetMenu(mapOf("p_store_id" to storeId)) }) {
            if (it.isBlank() || it.trim() == "null") throw Exception("This shop is not available")
            StoreMenu.from(JSONObject(it))
        }

    /** Raw JSON of the remote control data (banners, flags) for the customer app in this city; null on any problem. */
    suspend fun getRemoteConfigRaw(cityId: String?, versionCode: Int): String? = try {
        val res = api.getRemoteConfig(mapOf("p_app" to "customer", "p_city_id" to cityId, "p_version_code" to versionCode))
        if (res.isSuccessful) res.body()?.string() else null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    /** The fees of a cart (City Admin fee rules) and the "add Rs X more" nudges. null on any problem: the old flat fees then apply. */
    suspend fun getCheckoutFees(cityId: String, distanceKm: Double?, amount: Double): CheckoutFees? =
        call("Fees", { api.getCheckoutFees(mapOf("p_city_id" to cityId, "p_distance_km" to (distanceKm ?: 0.0), "p_amount" to amount.coerceAtLeast(0.0))) }) { CheckoutFees.from(JSONObject(it)) }.getOrNull()

    suspend fun requestQuote(vendorId: String, productId: String, variantId: String?, quantity: Int, note: String?, photoPath: String?): Result<Unit> =
        call("Could not send the request", { api.storeQuoteRequest(mapOf("p_vendor_id" to vendorId, "p_product_id" to productId, "p_variant_id" to variantId, "p_quantity" to quantity, "p_note" to note, "p_photo_path" to photoPath)) }) { }

    suspend fun myQuotes(): Result<List<StoreQuote>> =
        call("Could not load your quotes", { api.storeQuotesMine(emptyMap()) }) { StoreQuote.listFrom(JSONArray(it)) }

    suspend fun answerQuote(id: String, accept: Boolean): Result<Unit> =
        call("Could not answer", { api.storeQuoteRespond(mapOf("p_quote_id" to id, "p_accept" to accept)) }) { }

    suspend fun cancelQuote(id: String): Result<Unit> =
        call("Could not cancel", { api.storeQuoteCancel(mapOf("p_quote_id" to id)) }) { }

    /** Longest notice (hours) that a made-to-order product in this cart needs. 0 on any problem or when none. */
    suspend fun cartLeadHours(productIds: List<String>): Int {
        if (productIds.isEmpty()) return 0
        return call("Notice hours", { api.getProductCustomLead("in.(${productIds.joinToString(",")})") }) { raw ->
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("is_customizable", false) }.maxOfOrNull { it.optInt("custom_lead_hours", 0) } ?: 0
        }.getOrNull() ?: 0
    }

    private val storeVendors = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Is this vendor a store (a local shop) rather than a hotel? On any problem: no, so hotels keep their own checkout. */
    suspend fun isStoreVendor(vendorId: String?): Boolean {
        if (vendorId.isNullOrBlank()) return false
        storeVendors[vendorId]?.let { return it }
        val v = call("Stores", { api.isStoreVendor(mapOf("p_vendor_id" to vendorId)) }) { it.trim() == "true" }.getOrNull() ?: return false
        storeVendors[vendorId] = v
        return v
    }

    fun rememberStore(vendorId: String) { storeVendors[vendorId] = true }

    /** The default saved address with a map pin, or any address with one; null = the list is not ranked by distance. */
    suspend fun customerLocation(repository: SndmartRepository): StoreLoc? {
        val uid = repository.sessionManager.getUserId()
        if (uid.isNullOrBlank()) return null
        val list = repository.getAddresses(uid).getOrNull().orEmpty()
        val a = list.firstOrNull { it.isDefault && it.lat != null && it.lng != null } ?: list.firstOrNull { it.lat != null && it.lng != null }
        return if (a?.lat != null && a.lng != null) StoreLoc(a.lat, a.lng) else null
    }
}
