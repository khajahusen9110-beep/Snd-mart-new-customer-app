package com.example.data.repository

import android.util.Log
import com.example.data.model.*
import com.example.data.remote.ApiException
import com.example.data.remote.SessionExpiredException
import com.example.data.remote.SupabaseApi
import com.example.data.remote.SupabaseClient
import com.example.data.session.UserSessionManager
import com.example.util.PhoneUtils
import com.example.util.VersionUtils
import com.example.util.isVendorWithinOperatingHours
import com.example.util.isWithinAnySlot
import com.example.util.parseTimeString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed class AddToCartResult {
    object Success : AddToCartResult()
    data class HotelConflict(val existingVendorId: String, val newVendorId: String, val pendingItem: CartItem) : AddToCartResult()
}

class SndmartRepository(
    private val api: SupabaseApi = SupabaseClient.api,
    val sessionManager: UserSessionManager
) {
    private val TAG = "SndmartRepository"

    companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes short-lived cache
    }

    val unreadNotificationCount: StateFlow<Int>
        get() = sessionManager.unreadNotificationCount

    val currentCity: com.example.data.model.City?
        get() = sessionManager.selectedCity.value

    val currentUserId: String?
        get() = sessionManager.userId.value

    // In-memory cart items (strictly NO price column, only productId, vendorId, cityId, quantity)
    private val _groceryCart = MutableStateFlow<List<CartItem>>(emptyList())
    val groceryCart: StateFlow<List<CartItem>> = _groceryCart.asStateFlow()

    private val _hotelCart = MutableStateFlow<List<CartItem>>(emptyList())
    val hotelCart: StateFlow<List<CartItem>> = _hotelCart.asStateFlow()

    // --- PROMOTIONAL OFFER (backend-driven) ---
    // The active offer per section, fetched from public.get_active_promo. null = no offer, so the
    // UI shows normal prices. One admin change to promo_campaigns updates both of these within a
    // minute (MainActivity refreshes on city change + periodically).
    private val _foodPromo = MutableStateFlow<ActivePromoRow?>(null)
    val foodPromo: StateFlow<ActivePromoRow?> = _foodPromo.asStateFlow()

    private val _groceryPromo = MutableStateFlow<ActivePromoRow?>(null)
    val groceryPromo: StateFlow<ActivePromoRow?> = _groceryPromo.asStateFlow()

    private suspend fun fetchActivePromo(cityId: String, type: String): ActivePromoRow? {
        if (!SupabaseClient.isKeyConfigured()) return null
        return try {
            val response = api.getActivePromo(mapOf("p_city_id" to cityId, "p_type" to type))
            val row = response.body()?.firstOrNull()
            if (response.isSuccessful && row != null && row.percent > 0) row else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "get_active_promo($type) failed: ${e.message}")
            null
        }
    }

    /** Refresh both section offers for the given city. Clears them when no city is selected. */
    suspend fun refreshPromos(cityId: String?) {
        val clean = cityId?.removePrefix("eq.")?.takeIf { it.isNotBlank() }
        if (clean == null) {
            _foodPromo.value = null
            _groceryPromo.value = null
            return
        }
        _foodPromo.value = fetchActivePromo(clean, "food")
        _groceryPromo.value = fetchActivePromo(clean, "grocery")
    }

    // Background scope used to mirror the in-memory cart to the cart_items table.
    private val cartSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistJob: Job? = null

    // Local in-memory state for offline/demo operation when Supabase key is unconfigured or offline
    private val _localAddresses = MutableStateFlow<List<CustomerAddress>>(emptyList())

    // In-memory cache entry with short-lived TTL
    data class CacheEntry<T>(
        val data: List<T>,
        val timestamp: Long = System.currentTimeMillis(),
        val hasMore: Boolean = false
    ) {
        fun isExpired(ttlMs: Long = CACHE_TTL_MS): Boolean =
            System.currentTimeMillis() - timestamp > ttlMs
    }

    // Rule 6: In-memory caches for slow-changing data with short-lived TTL
    private var cachedCities: List<City>? = null
    private var cachedGroceryCategories: List<Category>? = null
    private var cachedGroceryCategoriesTimestamp: Long = 0L
    private val cachedProfiles = java.util.concurrent.ConcurrentHashMap<String, Profile>()
    private val cachedVendorNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val cachedHotelsByCity = java.util.concurrent.ConcurrentHashMap<String, List<Vendor>>()
    private val cachedHotelCategoriesByCity = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<Category>>()

    // Smart in-memory caches for products, hotels, and hotel menus
    private val groceryProductsCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<ResolvedProduct>>()
    private val hotelsCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<Vendor>>()
    private val hotelCategoriesCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<Category>>()
    private val hotelProductsCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<ResolvedProduct>>()

    fun getCachedHotels(cityId: String?): List<Vendor>? {
        if (cityId.isNullOrBlank()) return null
        val cached = hotelsCache["$cityId:"]
        if (cached != null && !cached.isExpired()) {
            return cached.data
        }
        return cachedHotelsByCity[cityId]
    }

    fun getCachedGroceryProducts(cityId: String?, categoryId: String?, searchQuery: String? = null): List<ResolvedProduct>? {
        if (cityId.isNullOrBlank() || categoryId.isNullOrBlank()) return null
        val cleanQuery = searchQuery?.trim()?.lowercase().orEmpty()
        // A search is cached across all categories (key "all"); browsing is per-category.
        val cacheKey = "$cityId:${if (cleanQuery.isNotEmpty()) "all" else categoryId}:$cleanQuery"
        val cached = groceryProductsCache[cacheKey]
        if (cached != null && !cached.isExpired()) {
            return cached.data
        }
        return null
    }

    fun getCachedGroceryCategories(): List<Category>? {
        if (cachedGroceryCategories != null && (System.currentTimeMillis() - cachedGroceryCategoriesTimestamp < CACHE_TTL_MS)) {
            return cachedGroceryCategories
        }
        return null
    }

    fun getCachedHotelCategories(vendorId: String?): List<Category>? {
        if (vendorId.isNullOrBlank()) return null
        val cached = hotelCategoriesCache[vendorId]
        if (cached != null && !cached.isExpired()) {
            return cached.data
        }
        return null
    }

    fun getCachedHotelProducts(vendorId: String?, cityId: String?, categoryId: String?): List<ResolvedProduct>? {
        if (vendorId.isNullOrBlank() || cityId.isNullOrBlank()) return null
        val cacheKey = "$vendorId:$cityId:${categoryId.orEmpty()}"
        val cached = hotelProductsCache[cacheKey]
        if (cached != null && !cached.isExpired()) {
            return cached.data
        }
        return null
    }

    fun invalidateGroceryProductsCache(cityId: String? = null, categoryId: String? = null) {
        if (cityId == null) {
            groceryProductsCache.clear()
        } else if (categoryId == null) {
            groceryProductsCache.keys.filter { it.startsWith("$cityId:") }.forEach { groceryProductsCache.remove(it) }
        } else {
            groceryProductsCache.keys.filter { it.startsWith("$cityId:$categoryId:") }.forEach { groceryProductsCache.remove(it) }
        }
    }

    fun invalidateHotelsCache(cityId: String? = null) {
        if (cityId == null) {
            hotelsCache.clear()
            cachedHotelsByCity.clear()
        } else {
            hotelsCache.keys.filter { it.startsWith("$cityId:") }.forEach { hotelsCache.remove(it) }
            cachedHotelsByCity.remove(cityId)
        }
    }

    fun invalidateHotelMenuCache(vendorId: String? = null) {
        if (vendorId == null) {
            hotelProductsCache.clear()
            hotelCategoriesCache.clear()
        } else {
            hotelCategoriesCache.remove(vendorId)
            hotelProductsCache.keys.filter { it.startsWith("$vendorId:") }.forEach { hotelProductsCache.remove(it) }
        }
    }

    fun clearCaches() {
        cachedCities = null
        cachedGroceryCategories = null
        cachedGroceryCategoriesTimestamp = 0L
        cachedProfiles.clear()
        cachedVendorNames.clear()
        cachedHotelsByCity.clear()
        groceryProductsCache.clear()
        hotelsCache.clear()
        hotelCategoriesCache.clear()
        hotelProductsCache.clear()
    }

    // --- SESSION MANAGEMENT ---
    suspend fun refreshSession(): Result<Boolean> {
        val refreshToken = sessionManager.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            sessionManager.notifySessionExpired("Your session expired, please log in again")
            return Result.failure(SessionExpiredException("No refresh token stored"))
        }
        val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SupabaseClient.refreshTokensBlocking()
        }
        return when (outcome) {
            SupabaseClient.RefreshOutcome.SUCCESS -> {
                Log.i(TAG, "Session refreshed successfully via SupabaseClient.")
                Result.success(true)
            }
            SupabaseClient.RefreshOutcome.INVALID -> {
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Session expired, please log in again"))
            }
            SupabaseClient.RefreshOutcome.NETWORK_ERROR -> {
                Log.w(TAG, "Session refresh failed due to network error; not expiring session.")
                Result.failure(java.io.IOException("Network error during session refresh"))
            }
        }
    }

    suspend fun ensureValidSession(): Result<Boolean> {
        if (!sessionManager.isLoggedIn.value) {
            return Result.success(false)
        }
        if (sessionManager.isSessionExpiredOrExpiringSoon(bufferMs = 120_000L)) {
            Log.d(TAG, "Session token is expired or expiring soon; proactively refreshing session...")
            val res = refreshSession()
            if (!res.isSuccess) {
                return Result.failure(res.exceptionOrNull() ?: SessionExpiredException())
            }
        }
        // Verify active device session
        checkStillActiveDevice()
        return Result.success(true)
    }

    private fun getFallbackGroceryProducts(categoryId: String?, searchQuery: String? = null): List<ResolvedProduct> {
        val targetCatId = categoryId
            ?: demoGroceryCategories.firstOrNull { it.name.contains("veg", ignoreCase = true) }?.id
            ?: "cat_veg"
        val searching = !searchQuery.isNullOrBlank()
        val filtered = DemoCatalog.GROCERY_PRODUCTS.filter { prod ->
            // A search spans every category; browsing stays within the chosen category.
            val matchesCategory = searching || prod.categoryId == targetCatId
            val matchesQuery = !searching || prod.name.contains(searchQuery!!, ignoreCase = true)
            matchesCategory && matchesQuery
        }
        return filtered.map { prod ->
            ResolvedProduct(
                baseProduct = prod,
                effectivePrice = prod.price,
                effectiveMrp = prod.mrp,
                effectiveStock = prod.stockQty ?: 50,
                effectiveIsAvailable = prod.isAvailable,
                variants = resolveProductVariants(prod, "city_sindhanur")
            )
        }.sortedWith(
            compareByDescending<ResolvedProduct> { it.isInStockAndActive }
                .thenBy { it.name.lowercase() }
        )
    }

    // Grocery categories: vendor_type IN (grocery,vegetable,fruit), vendor_id IS NULL.
    // Only "Vegitables" and "Fruits" categories remain, defaulting to Vegetables first.
    private val demoGroceryCategories: List<Category>
        get() = DemoCatalog.CATEGORIES.filter {
            val vt = it.vendorType?.lowercase()
            val n = it.name.lowercase()
            (n.contains("veg") || n.contains("fruit") || vt in listOf("vegetable", "fruit")) &&
                !n.contains("dairy") && it.vendorId == null && it.isActive
        }.sortedBy { if (it.name.lowercase().contains("veg")) 0 else 1 }

    private fun getFallbackHotelMenu(vendorId: String): Pair<List<Category>, List<ResolvedProduct>> {
        val cats = DemoCatalog.HOTEL_CATEGORIES.filter { it.vendorId == vendorId }
        val prods = DemoCatalog.HOTEL_PRODUCTS.filter { it.vendorId == vendorId || vendorId.isBlank() }
        val resolved = prods.map { prod ->
            ResolvedProduct(
                baseProduct = prod,
                effectivePrice = prod.price,
                effectiveMrp = prod.mrp,
                effectiveStock = prod.stockQty ?: 50,
                effectiveIsAvailable = prod.isAvailable
            )
        }.sortedWith(
            compareBy<ResolvedProduct> { prod ->
                val isAvail = prod.isHotelItemAvailable
                when {
                    isAvail && prod.isFeatured -> 0
                    isAvail -> 1
                    else -> 2
                }
            }.thenBy { it.name.lowercase() }
        )
        return Pair(cats, resolved)
    }

    private fun findFallbackProduct(productId: String): ResolvedProduct? {
        val p = (DemoCatalog.GROCERY_PRODUCTS + DemoCatalog.HOTEL_PRODUCTS).firstOrNull { it.id == productId }
            ?: return null
        return ResolvedProduct(
            baseProduct = p,
            effectivePrice = p.price,
            effectiveMrp = p.mrp,
            effectiveStock = p.stockQty ?: 50,
            effectiveIsAvailable = p.isAvailable,
            variants = resolveProductVariants(p, "city_sindhanur")
        )
    }

    // --- CITIES ---
    suspend fun getActiveCities(): Result<List<City>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured. Configure key to load live cities."))
        }
        return try {
            val response = api.getCities(status = "eq.active", order = "name.asc")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Failed to fetch cities from backend: $error")
                Result.failure(Exception(error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching cities from backend: ${e.message}", e)
            Result.failure(e)
        }
    }

    // --- CITY DETECTION (BACKEND RPC) ---
    suspend fun findCityForLocation(lat: Double, lng: Double): Result<City?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val response = api.findCityForLocation(mapOf("p_lat" to lat, "p_lng" to lng))
            if (response.isSuccessful && response.body() != null) {
                val results = response.body()!!
                if (results.isNotEmpty()) {
                    val r = results.first()
                    Result.success(City(id = r.cityId, name = r.cityName, status = "active"))
                } else {
                    Result.success(null) // Location not serviceable
                }
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "find_city_for_location RPC failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling find_city_for_location: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getCities(forceRefresh: Boolean = false): Result<List<City>> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(emptyList())
        if (!forceRefresh && cachedCities != null) {
            return Result.success(cachedCities!!)
        }
        return try {
            val response = api.getCities(status = "eq.active", order = "name.asc")
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                cachedCities = list
                Result.success(list)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "getCities failed HTTP ${response.code()}: $error")
                Result.failure(Exception(error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching cities: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun findCityAndDistanceForLocation(lat: Double, lng: Double): Result<CityLocationResult?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val response = api.findCityForLocation(mapOf("p_lat" to lat, "p_lng" to lng))
            if (response.isSuccessful && response.body() != null) {
                val results = response.body()!!
                Result.success(results.firstOrNull())
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "find_city_for_location RPC failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling find_city_for_location: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun updateProfileCityId(userId: String, cityId: String): Result<Unit> {
        return try {
            val response = api.updateProfile("eq.$userId", mapOf("city_id" to cityId))
            if (response.isSuccessful) {
                cachedProfiles.remove(userId)
                Result.success(Unit)
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception updating profile city_id: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Edit profile (name + phone) via PATCH /rest/v1/profiles?id=eq.{userId}.
    suspend fun updateProfile(userId: String, fullName: String?, phone: String?): Result<Unit> {
        return try {
            val body = mutableMapOf<String, Any?>()
            if (fullName != null) body["full_name"] = fullName
            if (phone != null) {
                body["phone"] = if (phone.isNotBlank()) PhoneUtils.toE164(phone) else null
            }
            val response = api.updateProfile("eq.$userId", body)
            if (response.isSuccessful) {
                cachedProfiles.remove(userId)
                Result.success(Unit)
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- PHONE OTP AUTH & PROFILES ---

    suspend fun signInWithPhoneOtp(rawPhone: String): Result<Unit> {
        val e164Phone = PhoneUtils.toE164(rawPhone)
        return try {
            val response = api.signInWithOtp(mapOf("phone" to e164Phone))
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "signInWithOtp failed for $e164Phone: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in signInWithPhoneOtp: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun verifyPhoneOtp(rawPhone: String, token: String): Result<SupabaseAuthResponse> {
        val e164Phone = PhoneUtils.toE164(rawPhone)
        return try {
            val response = api.verifyOtp(
                mapOf(
                    "type" to "sms",
                    "phone" to e164Phone,
                    "token" to token
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "verifyOtp failed for $e164Phone: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in verifyPhoneOtp: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getProfile(userId: String, forceRefresh: Boolean = false): Result<Profile?> {
        if (!forceRefresh) {
            val cached = cachedProfiles[userId]
            if (cached != null) return Result.success(cached)
        }
        return try {
            val response = api.getProfile("eq.$userId")
            if (response.isSuccessful) {
                val profile = response.body()?.firstOrNull()
                if (profile != null) {
                    cachedProfiles[userId] = profile
                }
                Result.success(profile)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "getProfile failed for $userId: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting profile for $userId: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun createProfile(profile: Profile): Result<Profile> {
        val normalizedProfile = if (!profile.phone.isNullOrBlank()) {
            profile.copy(phone = PhoneUtils.toE164(profile.phone))
        } else {
            profile
        }
        return try {
            val response = api.createProfile(normalizedProfile)
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else if (response.isSuccessful) {
                Result.success(normalizedProfile)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "createProfile failed for ${profile.id}: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating profile for ${profile.id}: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Single Device Login:
     * On successful login, write this device id to profiles.current_device_session
     * and revoke refresh tokens for every other active session of this account via signOut(scope = "others").
     */
    suspend fun registerDeviceSession(userId: String): Result<Unit> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(Unit)
        return try {
            val deviceId = sessionManager.getOrCreateDeviceId()
            Log.i(TAG, "Registering device session $deviceId for user $userId")

            val updateRes = api.updateProfile(
                idQuery = "eq.$userId",
                profile = mapOf("current_device_session" to deviceId)
            )
            if (!updateRes.isSuccessful) {
                Log.w(TAG, "updateProfile current_device_session returned ${updateRes.code()}: ${updateRes.errorBody()?.string()}")
            }

            // Revoke refresh tokens for every OTHER active session of this account
            try {
                val signOutOthersRes = api.signOut(scope = "others")
                if (!signOutOthersRes.isSuccessful) {
                    Log.w(TAG, "signOut(scope=others) returned ${signOutOthersRes.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "signOut(scope=others) exception: ${e.message}")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in registerDeviceSession: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Signs out the current session or other sessions on Supabase backend.
     */
    suspend fun signOut(scope: String? = null): Result<Unit> {
        if (!SupabaseClient.isKeyConfigured()) {
            resetInMemoryCart()
            return Result.success(Unit)
        }
        return try {
            val res = api.signOut(scope = scope)

            // Always clear in-memory cart state on sign-out, regardless of API result,
            // so no leftover items can leak into the next user's session on this device.
            resetInMemoryCart()

            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(res)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during signOut(scope=$scope): ${e.message}")
            // Still clear cart state even if the network call itself failed -
            // the user is leaving this session either way.
            resetInMemoryCart()
            Result.failure(e)
        }
    }

    /**
     * Checks whether THIS device is still the account's registered device.
     * If someone logged in on a different device (current_device_session !== myDeviceId),
     * forces logout on this device with a clear notification message.
     */
    suspend fun checkStillActiveDevice(): Boolean {
        val uid = sessionManager.getUserId()
        if (uid.isNullOrBlank() || !sessionManager.isLoggedIn.value) return true
        if (!SupabaseClient.isKeyConfigured()) return true

        return try {
            val profileRes = getProfile(uid)
            val profile = profileRes.getOrNull()
            val myDeviceId = sessionManager.getOrCreateDeviceId()

            if (profile != null && !profile.currentDeviceSession.isNullOrBlank() && profile.currentDeviceSession != myDeviceId) {
                Log.w(TAG, "Device session conflict! Active device: ${profile.currentDeviceSession}, This device: $myDeviceId. Forcing logout.")
                try {
                    signOut(scope = "local")
                } catch (e: Exception) {
                    Log.w(TAG, "Error in local signOut: ${e.message}")
                }
                sessionManager.notifySessionExpired("You were logged out because your account was used on another device.")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkStillActiveDevice check failed: ${e.message}")
            true
        }
    }

    /**
     * Reads the logged-in user's profiles.city_id and resolves it to an active City
     * (with name). Returns null when the user has no city assigned yet (first-time flow)
     * or when the backend is unavailable — the caller then falls back to GPS detection.
     */
    suspend fun resolveUserCity(userId: String): Result<City?> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(null)
        return try {
            val profileRes = api.getProfile("eq.$userId")
            if (!profileRes.isSuccessful || profileRes.body().isNullOrEmpty()) {
                return Result.success(null)
            }
            val cityId = profileRes.body()!!.first().cityId
                ?: return Result.success(null)
            val citiesRes = api.getCities(status = "eq.active", order = "name.asc")
            if (!citiesRes.isSuccessful || citiesRes.body() == null) {
                return Result.success(null)
            }
            val city = citiesRes.body()!!.firstOrNull { it.id == cityId }
            Result.success(city)
        } catch (e: Exception) {
            Log.w(TAG, "Exception resolving user city from profile: ${e.message}", e)
            Result.success(null)
        }
    }

    // --- CATEGORIES ---
    suspend fun getCategories(): Result<List<Category>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(DemoCatalog.CATEGORIES)
        }
        return try {
            val response = api.getCategories()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Could not fetch categories: $error")
                if (response.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                Result.failure(ApiException(response.code(), error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching categories: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Grocery categories: vendor_type IN (grocery,vegetable,fruit),
    // vendor_id IS NULL, is_active=true, ordered by sort_order.
    suspend fun getGroceryCategories(forceRefresh: Boolean = false): Result<List<Category>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(demoGroceryCategories)
        }
        if (!forceRefresh && cachedGroceryCategories != null && (System.currentTimeMillis() - cachedGroceryCategoriesTimestamp < CACHE_TTL_MS)) {
            return Result.success(cachedGroceryCategories!!)
        }
        return try {
            val response = api.getCategories(
                select = "id,name,image_url,sort_order",
                isActive = "eq.true",
                order = "sort_order.asc",
                vendorType = "in.(grocery,vegetable,fruit)",
                vendorId = "is.null",
                cityId = null
            )
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                cachedGroceryCategories = list
                cachedGroceryCategoriesTimestamp = System.currentTimeMillis()
                Log.i(TAG, "Categories fetched successfully: ${list.size} categories found: ${list.map { it.name }}")
                Result.success(list)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "Categories fetch error: HTTP ${response.code()} $error")
                if (response.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                // Fallback attempt without select parameter in case of schema column selection difference
                val fallbackResponse = api.getCategories(
                    isActive = "eq.true",
                    order = "sort_order.asc",
                    vendorType = "in.(grocery,vegetable,fruit)",
                    vendorId = "is.null",
                    cityId = null
                )
                if (fallbackResponse.isSuccessful && fallbackResponse.body() != null) {
                    val fallbackList = fallbackResponse.body()!!
                    cachedGroceryCategories = fallbackList
                    cachedGroceryCategoriesTimestamp = System.currentTimeMillis()
                    Log.i(TAG, "Categories fetched via fallback: ${fallbackList.size} categories found: ${fallbackList.map { it.name }}")
                    Result.success(fallbackList)
                } else {
                    val fallbackError = SupabaseClient.parseErrorMessage(fallbackResponse)
                    Log.e(TAG, "Categories fetch error: $fallbackError")
                    Result.failure(ApiException(response.code(), error))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Categories fetch error: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Overload for backward compatibility with existing callers passing cityId
    suspend fun getGroceryCategories(cityId: String): Result<List<Category>> = getGroceryCategories()

    // Hotel menu categories across all hotels in a city (vendor_type=hotel, each row's
    // vendor_id identifies which hotel it belongs to). Used for the "What's on your mind?"
    // rotating category picker on the Food Delivery hotel listing — no new backend needed,
    // this is the same categories table/endpoint getGroceryCategories already queries.
    //
    // Category rows don't carry their own city_id (it's always null — a category belongs to a
    // vendor, and the vendor has the city), so this can't filter categories by city_id directly.
    // Instead it first resolves which hotel vendor ids are in this city, then fetches categories
    // for those vendor ids.
    suspend fun getHotelCategoriesForCity(cityId: String, forceRefresh: Boolean = false): Result<List<Category>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(emptyList())
        }
        val cached = cachedHotelCategoriesByCity[cityId]
        if (!forceRefresh && cached != null && !cached.isExpired()) {
            return Result.success(cached.data)
        }
        return try {
            val vendorsResponse = api.getVendors(
                cityId = "eq.$cityId",
                vendorType = "eq.hotel",
                select = "id",
                limit = 500
            )
            if (!vendorsResponse.isSuccessful || vendorsResponse.body() == null) {
                val error = SupabaseClient.parseErrorMessage(vendorsResponse)
                Log.w(TAG, "getHotelCategoriesForCity (vendors) error: $error")
                return Result.failure(ApiException(vendorsResponse.code(), error))
            }
            val vendorIds = vendorsResponse.body()!!.map { it.id }.filter { it.isNotBlank() }
            if (vendorIds.isEmpty()) {
                cachedHotelCategoriesByCity[cityId] = CacheEntry(data = emptyList())
                return Result.success(emptyList())
            }

            val response = api.getCategories(
                select = "id,name,image_url,sort_order,vendor_id,vendor_type,city_id,is_active",
                isActive = "eq.true",
                order = "sort_order.asc",
                vendorType = "eq.hotel",
                vendorId = "in.(${vendorIds.joinToString(",")})"
            )
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                cachedHotelCategoriesByCity[cityId] = CacheEntry(data = list)
                Result.success(list)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "getHotelCategoriesForCity error: $error")
                Result.failure(ApiException(response.code(), error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching hotel categories: ${e.message}", e)
            Result.failure(e)
        }
    }

    // --- VENDORS (HOTELS) ---
    // Fetches hotels (vendor_type=hotel) for a city.
    // Rule 1: Hotel list: 20 at a time.
    // Rule 2: Selective column fetching.
    suspend fun getHotels(
        cityId: String,
        searchQuery: String? = null,
        limit: Int = 20,
        offset: Int = 0,
        forceRefresh: Boolean = false
    ): Result<List<Vendor>> {
        val cleanQuery = searchQuery?.trim()?.lowercase().orEmpty()
        val cacheKey = "$cityId:$cleanQuery"

        if (offset == 0 && !forceRefresh) {
            val cached = hotelsCache[cacheKey]
            if (cached != null && !cached.isExpired()) {
                cachedHotelsByCity[cityId] = cached.data
                return Result.success(cached.data)
            }
        }

        if (!SupabaseClient.isKeyConfigured()) {
            val demo = if (!searchQuery.isNullOrBlank())
                DemoCatalog.HOTELS.filter { it.name.contains(searchQuery, ignoreCase = true) }
            else DemoCatalog.HOTELS
            val sortedDemo = demo.sortedWith(HotelComparator)
            val paged = sortedDemo.drop(offset).take(limit)
            if (offset == 0) {
                hotelsCache[cacheKey] = CacheEntry(data = sortedDemo.take(limit), hasMore = sortedDemo.size > limit)
                if (searchQuery.isNullOrBlank()) {
                    cachedHotelsByCity[cityId] = sortedDemo.take(limit)
                }
            } else {
                val existing = hotelsCache[cacheKey]?.data ?: emptyList()
                val combined = (existing + paged).distinctBy { it.id }.sortedWith(HotelComparator)
                hotelsCache[cacheKey] = CacheEntry(data = combined, hasMore = sortedDemo.size > offset + limit)
                if (searchQuery.isNullOrBlank()) {
                    cachedHotelsByCity[cityId] = combined
                }
            }
            return Result.success(paged)
        }
        return try {
            // Free-text search: match hotels by their name OR by a menu item (dish) they serve.
            if (cleanQuery.isNotEmpty()) {
                return searchHotelsByNameOrDish(cityId, cleanQuery, searchQuery!!.trim(), limit, offset, cacheKey)
            }
            val nameQuery = searchQuery?.takeIf { it.isNotBlank() }?.let {
                "ilike.*" + java.net.URLEncoder.encode(it.trim(), "UTF-8").replace("+", "%20") + "*"
            }
            val response = api.getVendors(
                cityId = "eq.$cityId",
                vendorType = "eq.hotel",
                approvalStatus = "eq.approved",
                name = nameQuery,
                select = "id,name,banner_url,is_active,is_featured,is_open,address,latitude,longitude,opening_time,closing_time",
                order = "is_active.desc,is_featured.desc.nullslast,name.asc",
                limit = limit,
                offset = offset
            )
            if (response.isSuccessful && response.body() != null) {
                val sorted = response.body()!!.sortedWith(HotelComparator)
                if (offset == 0) {
                    hotelsCache[cacheKey] = CacheEntry(data = sorted, hasMore = sorted.size >= limit)
                    if (searchQuery.isNullOrBlank()) {
                        cachedHotelsByCity[cityId] = sorted
                    }
                } else {
                    val existing = hotelsCache[cacheKey]?.data ?: emptyList()
                    val combined = (existing + sorted).distinctBy { it.id }.sortedWith(HotelComparator)
                    hotelsCache[cacheKey] = CacheEntry(data = combined, hasMore = sorted.size >= limit)
                    if (searchQuery.isNullOrBlank()) {
                        cachedHotelsByCity[cityId] = combined
                    }
                }
                Result.success(sorted)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Could not fetch hotels: $error")
                if (response.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                Result.failure(ApiException(response.code(), error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching hotels: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Food search: return hotels whose own name matches the query OR that serve a menu item
    // whose name matches it (so "biryani" finds hotels cooking biryani, not only one named that).
    private suspend fun searchHotelsByNameOrDish(
        cityId: String,
        cleanQuery: String,
        rawQuery: String,
        limit: Int,
        offset: Int,
        cacheKey: String
    ): Result<List<Vendor>> {
        return try {
            val allResp = api.getVendors(
                cityId = "eq.$cityId",
                vendorType = "eq.hotel",
                approvalStatus = "eq.approved",
                name = null,
                select = "id,name,banner_url,is_active,is_featured,is_open,address,latitude,longitude,opening_time,closing_time",
                order = "is_active.desc,is_featured.desc.nullslast,name.asc",
                limit = 500,
                offset = 0
            )
            if (!allResp.isSuccessful || allResp.body() == null) {
                if (allResp.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                return Result.failure(ApiException(allResp.code(), SupabaseClient.parseErrorMessage(allResp)))
            }
            val allHotels = allResp.body()!!

            // Primary: server RPC does exact (hotel/dish name) match first, then a
            // typo-tolerant fuzzy fallback, so "Biriyani" still finds "Chicken Biryani".
            val rpcVendorIds: Set<String> = try {
                val rpcResp = api.searchHotelVendorIds(
                    mapOf("p_city_id" to cityId, "p_query" to rawQuery)
                )
                if (rpcResp.isSuccessful && rpcResp.body() != null)
                    rpcResp.body()!!.mapNotNull { it["vendor_id"]?.takeIf { v -> v.isNotBlank() } }.toSet()
                else null
            } catch (e: Exception) {
                Log.w(TAG, "Hotel search RPC notice: ${e.message}")
                null
            } ?: run {
                // Fallback: client-side name + dish ILIKE match (older behaviour).
                val hotelIds = allHotels.map { it.id }.filter { it.isNotBlank() }
                val nameEnc = "ilike.*" + java.net.URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20") + "*"
                val itemVendorIds: Set<String> = if (hotelIds.isNotEmpty()) {
                    val itemsResp = api.getProducts(
                        isActive = "eq.true",
                        vendorId = "in.(${hotelIds.joinToString(",")})",
                        name = nameEnc,
                        select = "vendor_id",
                        limit = 1000
                    )
                    if (itemsResp.isSuccessful && itemsResp.body() != null)
                        itemsResp.body()!!.mapNotNull { it.vendorId?.takeIf { v -> v.isNotBlank() } }.toSet()
                    else emptySet()
                } else emptySet()
                allHotels.filter { it.name.contains(rawQuery, ignoreCase = true) || it.id in itemVendorIds }
                    .map { it.id }.toSet()
            }

            val matched = allHotels
                .filter { it.id in rpcVendorIds }
                .sortedWith(HotelComparator)
            val paged = matched.drop(offset).take(limit)
            val hasMore = matched.size > offset + limit
            if (offset == 0) {
                hotelsCache[cacheKey] = CacheEntry(data = paged, hasMore = hasMore)
            } else {
                val existing = hotelsCache[cacheKey]?.data ?: emptyList()
                val combined = (existing + paged).distinctBy { it.id }.sortedWith(HotelComparator)
                hotelsCache[cacheKey] = CacheEntry(data = combined, hasMore = hasMore)
            }
            Result.success(paged)
        } catch (e: Exception) {
            Log.w(TAG, "Exception searching hotels: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Average vendor rating computed client-side from vendor_reviews (select=rating).
    // Returns 0.0 when there are no reviews yet or the backend is unavailable.
    suspend fun getVendorAverageRating(vendorId: String): Result<Double> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(0.0)
        return try {
            val response = api.getVendorReviews(vendorId = "eq.$vendorId", select = "rating")
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val ratings = response.body()!!.mapNotNull { it.rating.takeIf { r -> r > 0 } }
                Result.success(if (ratings.isNotEmpty()) ratings.average() else 0.0)
            } else {
                Result.success(0.0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching vendor rating: ${e.message}", e)
            Result.success(0.0)
        }
    }

    // Rule 3: Batch resolve vendor names to avoid N+1 queries when rendering order lists
    suspend fun getVendorNames(vendorIds: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val missing = mutableListOf<String>()
        for (vid in vendorIds) {
            val name = cachedVendorNames[vid]
            if (name != null) {
                result[vid] = name
            } else {
                missing.add(vid)
            }
        }
        if (missing.isNotEmpty()) {
            val distinctMissing = missing.distinct()
            try {
                val res = api.getVendorsByIds(
                    idInQuery = "in.(${distinctMissing.joinToString(",")})",
                    select = "id,name"
                )
                if (res.isSuccessful && res.body() != null) {
                    for (v in res.body()!!) {
                        cachedVendorNames[v.id] = v.name
                        result[v.id] = v.name
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception batch fetching vendor names: ${e.message}")
            }
        }
        return result
    }

    // Fetch a single vendor's display name with caching
    suspend fun getVendorName(vendorId: String): Result<String?> {
        val cached = cachedVendorNames[vendorId]
        if (cached != null) return Result.success(cached)
        return try {
            val response = api.getVendorById(idQuery = "eq.$vendorId", select = "id,name")
            val name = response.body()?.firstOrNull()?.name
            if (name != null) {
                cachedVendorNames[vendorId] = name
            }
            Result.success(name)
        } catch (e: Exception) {
            Result.success(null)
        }
    }

    suspend fun getVendor(vendorId: String): Result<Vendor?> {
        return try {
            val response = api.getVendorById(
                idQuery = "eq.$vendorId",
                select = "id,name,banner_url,is_active,is_featured,is_open,address,latitude,longitude,opening_time,closing_time,default_category_id"
            )
            Result.success(response.body()?.firstOrNull())
        } catch (e: Exception) {
            Result.success(null)
        }
    }

    suspend fun getVendorOperatingSlots(vendorId: String): Result<List<OperatingSlot>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(emptyList())
        }
        return try {
            val response = api.getVendorOperatingHours(
                vendorId = "eq.$vendorId",
                isActive = "eq.true"
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching vendor operating hours: ${e.message}", e)
            Result.success(emptyList())
        }
    }

    fun resolveProductVariants(prod: Product, cityId: String): List<ResolvedVariant> {
        val variants = prod.productVariants?.filter { it.isActive } ?: return emptyList()
        val list = mutableListOf<ResolvedVariant>()
        for (v in variants) {
            val stockRow = v.cityStock?.firstOrNull { it.cityId == cityId }
            val isAvail = stockRow?.isAvailable == true
            val price = stockRow?.price ?: 0.0
            val stockQty = if (isAvail) (stockRow?.stockQty ?: 0) else 0
            list.add(
                ResolvedVariant(
                    id = v.id,
                    label = v.label,
                    price = price,
                    stockQty = stockQty,
                    isAvailable = isAvail
                )
            )
        }
        return list.sortedBy { it.price }
    }

    // --- PRODUCTS & CITY STOCK RESOLUTION ---
    // Rule 1: Grocery product grid: 30 at a time.
    // Rule 2: Selective column fetching.
    // Rule 3: Batch fetch city stock overrides in ONE single request using .in().
    private val topGroceryCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<ResolvedProduct>>()

    /**
     * Up to 10 most-selling grocery products in this city (by units sold). The RPC ranks product
     * ids + units sold; one products + city-stock request then resolves price/stock to render the
     * same grocery card, with soldCount attached for the "N sold" badge. Empty on any failure.
     */
    suspend fun getTopSellingGroceryProducts(cityId: String, forceRefresh: Boolean = false): Result<List<ResolvedProduct>> {
        if (!forceRefresh) {
            val cached = topGroceryCache[cityId]
            if (cached != null && !cached.isExpired()) return Result.success(cached.data)
        }
        if (!SupabaseClient.isKeyConfigured()) return Result.success(emptyList())
        return try {
            val rankRes = api.getTopGroceryProducts(mapOf("p_city_id" to cityId, "p_limit" to 10))
            val ranked = rankRes.body().orEmpty()
            if (!rankRes.isSuccessful || ranked.isEmpty()) return Result.success(emptyList())
            val soldById = ranked.associate { it.productId to it.unitsSold.toInt() }
            val ids = ranked.map { it.productId }.filter { it.isNotBlank() }
            if (ids.isEmpty()) return Result.success(emptyList())

            val prodRes = api.getProductsByIds(
                idInQuery = "in.(${ids.joinToString(",")})",
                select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured,product_variants(id,label,is_active,product_variant_city_stock(price,stock_qty,is_available,city_id))"
            )
            val products = (prodRes.body().orEmpty()).filter { it.vendorId == null }
            val stockRes = api.getProductCityStockBatch(
                cityId = "eq.$cityId",
                productIdsQuery = "in.(${ids.joinToString(",")})",
                select = "product_id,price,mrp,stock_qty,is_available"
            )
            val stockMap = if (stockRes.isSuccessful) stockRes.body().orEmpty().associateBy { it.productId } else emptyMap()
            val stockLoadFailed = !stockRes.isSuccessful

            val resolved = products.mapNotNull { prod ->
                val override = stockMap[prod.id]
                val stock = override?.stockQty ?: 0
                val isAvail = if (stockLoadFailed) false else (override?.isAvailable == true)
                val rp = ResolvedProduct(
                    baseProduct = prod,
                    effectivePrice = override?.price ?: prod.price,
                    effectiveMrp = override?.mrp ?: prod.mrp,
                    effectiveStock = stock,
                    effectiveIsAvailable = isAvail,
                    variants = resolveProductVariants(prod, cityId),
                    soldCount = soldById[prod.id] ?: 0
                )
                if (rp.isInStockAndActive) rp else null
            }.sortedByDescending { it.soldCount }

            topGroceryCache[cityId] = CacheEntry(data = resolved)
            Result.success(resolved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching top grocery products: ${e.message}")
            Result.success(emptyList())
        }
    }

    private val topFoodCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<ResolvedProduct>>()

    /**
     * Up to 10 most-ordered hotel dishes in this city (by units sold). The RPC ranks product ids +
     * their hotel + units sold; one products + city-stock request resolves price. Each item carries
     * soldCount and soldVendorName; the card taps through to that hotel. Empty on any failure.
     */
    /**
     * Food search by dish: returns matching hotel ITEMS directly (not just the hotels), so results
     * show dishes. Exact name match first, then a typo-tolerant fuzzy fallback ("Biriyani" ->
     * "Chicken Biryani"). Each dish carries its hotel name (soldVendorName) and open state.
     */
    suspend fun searchHotelDishes(cityId: String, query: String): Result<List<ResolvedProduct>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        if (!SupabaseClient.isKeyConfigured()) return Result.success(emptyList())
        return try {
            val rpcRes = api.searchHotelDishes(mapOf("p_city_id" to cityId, "p_query" to q))
            val rows = rpcRes.body().orEmpty()
            if (!rpcRes.isSuccessful || rows.isEmpty()) return Result.success(emptyList())
            data class Meta(val vendorName: String?, val open: Boolean, val order: Int)
            val metaById = HashMap<String, Meta>()
            rows.forEachIndexed { i, r ->
                val pid = r["product_id"] as? String ?: return@forEachIndexed
                metaById[pid] = Meta(
                    vendorName = r["vendor_name"] as? String,
                    open = (r["vendor_is_open"] as? Boolean) ?: false,
                    order = i
                )
            }
            val ids = metaById.keys.toList()
            if (ids.isEmpty()) return Result.success(emptyList())

            val prodRes = api.getProductsByIds(
                idInQuery = "in.(${ids.joinToString(",")})",
                select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured,available_from,available_until,product_variants(id,label,is_active,product_variant_city_stock(price,stock_qty,is_available,city_id))"
            )
            val products = (prodRes.body().orEmpty()).filter { it.vendorId != null }
            val stockRes = api.getProductCityStockBatch(
                cityId = "eq.$cityId",
                productIdsQuery = "in.(${ids.joinToString(",")})",
                select = "product_id,price,mrp,stock_qty,is_available"
            )
            val stockMap = if (stockRes.isSuccessful) stockRes.body().orEmpty().associateBy { it.productId } else emptyMap()

            val resolved = products.mapNotNull { prod ->
                if (prod.isActive != true) return@mapNotNull null
                val override = stockMap[prod.id]
                val meta = metaById[prod.id]
                ResolvedProduct(
                    baseProduct = prod,
                    effectivePrice = override?.price ?: prod.price,
                    effectiveMrp = override?.mrp ?: prod.mrp,
                    effectiveStock = override?.stockQty ?: (prod.stockQty ?: (prod.stockQuantity ?: 0)),
                    effectiveIsAvailable = override?.isAvailable ?: prod.isAvailable,
                    variants = resolveProductVariants(prod, cityId),
                    soldVendorName = meta?.vendorName,
                    hotelOpen = meta?.open ?: false
                )
            }.sortedBy { metaById[it.id]?.order ?: Int.MAX_VALUE }

            Result.success(resolved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception searching dishes: ${e.message}")
            Result.success(emptyList())
        }
    }

    suspend fun getTopSellingFoodProducts(cityId: String, forceRefresh: Boolean = false): Result<List<ResolvedProduct>> {
        if (!forceRefresh) {
            val cached = topFoodCache[cityId]
            if (cached != null && !cached.isExpired()) return Result.success(cached.data)
        }
        if (!SupabaseClient.isKeyConfigured()) return Result.success(emptyList())
        return try {
            val rankRes = api.getTopFoodProducts(mapOf("p_city_id" to cityId, "p_limit" to 10))
            val ranked = rankRes.body().orEmpty()
            if (!rankRes.isSuccessful || ranked.isEmpty()) return Result.success(emptyList())
            val metaById = ranked.associateBy { it.productId }
            val ids = ranked.map { it.productId }.filter { it.isNotBlank() }
            if (ids.isEmpty()) return Result.success(emptyList())

            val prodRes = api.getProductsByIds(
                idInQuery = "in.(${ids.joinToString(",")})",
                select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured,available_from,available_until,product_variants(id,label,is_active,product_variant_city_stock(price,stock_qty,is_available,city_id))"
            )
            val products = (prodRes.body().orEmpty()).filter { it.vendorId != null }
            val stockRes = api.getProductCityStockBatch(
                cityId = "eq.$cityId",
                productIdsQuery = "in.(${ids.joinToString(",")})",
                select = "product_id,price,mrp,stock_qty,is_available"
            )
            val stockMap = if (stockRes.isSuccessful) stockRes.body().orEmpty().associateBy { it.productId } else emptyMap()

            val resolved = products.mapNotNull { prod ->
                if (prod.isActive != true) return@mapNotNull null
                val override = stockMap[prod.id]
                val meta = metaById[prod.id]
                ResolvedProduct(
                    baseProduct = prod,
                    effectivePrice = override?.price ?: prod.price,
                    effectiveMrp = override?.mrp ?: prod.mrp,
                    effectiveStock = override?.stockQty ?: (prod.stockQty ?: (prod.stockQuantity ?: 0)),
                    effectiveIsAvailable = override?.isAvailable ?: prod.isAvailable,
                    variants = resolveProductVariants(prod, cityId),
                    soldCount = meta?.unitsSold?.toInt() ?: 0,
                    soldVendorName = meta?.vendorName
                )
            }.sortedByDescending { it.soldCount }

            topFoodCache[cityId] = CacheEntry(data = resolved)
            Result.success(resolved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching top food products: ${e.message}")
            Result.success(emptyList())
        }
    }

    suspend fun getResolvedGroceryProducts(
        cityId: String,
        categoryId: String? = null,
        searchQuery: String? = null,
        limit: Int = 30,
        offset: Int = 0,
        forceRefresh: Boolean = false
    ): Result<List<ResolvedProduct>> {
        val effectiveCatId = categoryId
            ?: cachedGroceryCategories?.firstOrNull { it.name.contains("veg", ignoreCase = true) }?.id
            ?: demoGroceryCategories.firstOrNull { it.name.contains("veg", ignoreCase = true) }?.id
            ?: "cat_veg"

        val cleanQuery = searchQuery?.trim()?.lowercase().orEmpty()
        val searching = cleanQuery.isNotEmpty()
        val cacheKey = "$cityId:${if (searching) "all" else effectiveCatId}:$cleanQuery"

        if (offset == 0 && !forceRefresh) {
            val cached = groceryProductsCache[cacheKey]
            if (cached != null && !cached.isExpired()) {
                return Result.success(cached.data)
            }
        }

        if (!SupabaseClient.isKeyConfigured()) {
            val demo = getFallbackGroceryProducts(if (searching) null else effectiveCatId, searchQuery)
            val paged = demo.drop(offset).take(limit)
            if (offset == 0) {
                groceryProductsCache[cacheKey] = CacheEntry(data = paged, hasMore = demo.size > limit)
            } else {
                val existing = groceryProductsCache[cacheKey]?.data ?: emptyList()
                groceryProductsCache[cacheKey] = CacheEntry(data = (existing + paged).distinctBy { it.id }, hasMore = demo.size > offset + limit)
            }
            return Result.success(paged)
        }
        return try {
            // Browsing is scoped to the selected category; a search spans every category.
            val catQuery = if (searching) null else "eq.$effectiveCatId"
            val nameQuery = searchQuery?.takeIf { it.isNotBlank() }?.let {
                "ilike.*" + java.net.URLEncoder.encode(it.trim(), "UTF-8").replace("+", "%20") + "*"
            }
            val prodResponse = api.getProducts(
                isActive = "eq.true",
                categoryId = catQuery,
                vendorId = "is.null",
                name = nameQuery,
                select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured,product_variants(id,label,is_active,product_variant_city_stock(price,stock_qty,is_available,city_id))",
                limit = limit,
                offset = offset
            )
            if (!prodResponse.isSuccessful || prodResponse.body() == null) {
                val error = SupabaseClient.parseErrorMessage(prodResponse)
                Log.w(TAG, "Could not fetch products: $error")
                if (prodResponse.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                return Result.failure(ApiException(prodResponse.code(), error))
            }
            val products = prodResponse.body()!!

            // Rule 3: Single batch request for ONLY the products returned on this page
            val productIds = products.map { it.id }.filter { it.isNotBlank() }
            var stockLoadFailed = false
            val stockMap = if (productIds.isNotEmpty()) {
                val stockResponse = api.getProductCityStockBatch(
                    cityId = "eq.$cityId",
                    productIdsQuery = "in.(${productIds.joinToString(",")})",
                    select = "product_id,price,mrp,stock_qty,is_available"
                )
                if (stockResponse.isSuccessful && stockResponse.body() != null) {
                    stockResponse.body()!!.associateBy { it.productId }
                } else {
                    stockLoadFailed = !stockResponse.isSuccessful
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val resolved = products.map { prod ->
                val override = stockMap[prod.id]
                val isGrocery = prod.vendorId == null
                val price = override?.price ?: prod.price
                val mrp = override?.mrp ?: prod.mrp
                val stock = if (isGrocery) (override?.stockQty ?: 0) else (override?.stockQty ?: (prod.stockQty ?: (prod.stockQuantity ?: 0)))
                val isAvail = if (isGrocery) {
                    if (stockLoadFailed) false else (override?.isAvailable == true)
                } else {
                    override?.isAvailable ?: prod.isAvailable
                }
                val resVariants = resolveProductVariants(prod, cityId)
                ResolvedProduct(
                    baseProduct = prod,
                    effectivePrice = price,
                    effectiveMrp = mrp,
                    effectiveStock = stock,
                    effectiveIsAvailable = isAvail,
                    variants = resVariants
                )
            }.sortedWith(
                compareByDescending<ResolvedProduct> { it.isInStockAndActive }
                    .thenBy { it.name.lowercase() }
            )

            if (offset == 0) {
                groceryProductsCache[cacheKey] = CacheEntry(data = resolved, hasMore = resolved.size >= limit)
            } else {
                val existing = groceryProductsCache[cacheKey]?.data ?: emptyList()
                val combined = (existing + resolved).distinctBy { it.id }
                groceryProductsCache[cacheKey] = CacheEntry(data = combined, hasMore = resolved.size >= limit)
            }
            Result.success(resolved)
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching grocery products: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Hotel menu categories: cached per vendorId
    suspend fun getHotelCategories(vendorId: String, forceRefresh: Boolean = false): Result<List<Category>> {
        if (!forceRefresh) {
            val cached = hotelCategoriesCache[vendorId]
            if (cached != null && !cached.isExpired()) {
                return Result.success(cached.data)
            }
        }
        if (!SupabaseClient.isKeyConfigured()) {
            val cats = DemoCatalog.HOTEL_CATEGORIES.filter { it.vendorId == vendorId }
            hotelCategoriesCache[vendorId] = CacheEntry(cats)
            return Result.success(cats)
        }
        return try {
            val response = api.getCategories(
                select = "id,name,image_url,vendor_id,is_active,sort_order",
                vendorId = "eq.$vendorId",
                isActive = "eq.true",
                order = "sort_order.asc"
            )
            if (response.isSuccessful && response.body() != null) {
                val cats = response.body()!!.filter { (it.vendorId == null || it.vendorId == vendorId) && it.isActive }
                hotelCategoriesCache[vendorId] = CacheEntry(cats)
                Result.success(cats)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Could not fetch hotel categories: $error")
                if (response.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                Result.failure(ApiException(response.code(), error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching hotel categories: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Hotel menu items: paginated (20-30 at a time, default 25), cached per vendorId + categoryId
    suspend fun getHotelProducts(
        vendorId: String,
        cityId: String,
        categoryId: String? = null,
        limit: Int = 25,
        offset: Int = 0,
        forceRefresh: Boolean = false
    ): Result<List<ResolvedProduct>> {
        val cacheKey = "$vendorId:$cityId:${categoryId.orEmpty()}"

        if (offset == 0 && !forceRefresh) {
            val cached = hotelProductsCache[cacheKey]
            if (cached != null && !cached.isExpired()) {
                return Result.success(cached.data)
            }
        }

        if (!SupabaseClient.isKeyConfigured()) {
            val prods = DemoCatalog.HOTEL_PRODUCTS.filter {
                (it.vendorId == vendorId || vendorId.isBlank()) &&
                (categoryId == null || it.categoryId == categoryId)
            }
            val resolved = prods.map { prod ->
                ResolvedProduct(
                    baseProduct = prod,
                    effectivePrice = prod.price,
                    effectiveMrp = prod.mrp,
                    effectiveStock = prod.stockQty ?: 50,
                    effectiveIsAvailable = prod.isAvailable
                )
            }.sortedWith(
                compareBy<ResolvedProduct> { prod ->
                    val isAvail = prod.isHotelItemAvailable
                    when {
                        isAvail && prod.isFeatured -> 0
                        isAvail -> 1
                        else -> 2
                    }
                }.thenBy { it.name.lowercase() }
            )
            val paged = resolved.drop(offset).take(limit)
            if (offset == 0) {
                hotelProductsCache[cacheKey] = CacheEntry(data = paged, hasMore = resolved.size > limit)
            } else {
                val existing = hotelProductsCache[cacheKey]?.data ?: emptyList()
                hotelProductsCache[cacheKey] = CacheEntry(data = (existing + paged).distinctBy { it.id }, hasMore = resolved.size > offset + limit)
            }
            return Result.success(paged)
        }

        return try {
            val catQuery = categoryId?.takeIf { it.isNotBlank() }?.let { "eq.$it" }
            val prodResponse = api.getProducts(
                isActive = "eq.true",
                vendorId = "eq.$vendorId",
                categoryId = catQuery,
                select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured,available_from,available_until",
                order = "is_featured.desc",
                limit = limit,
                offset = offset
            )
            if (!prodResponse.isSuccessful || prodResponse.body() == null) {
                val error = SupabaseClient.parseErrorMessage(prodResponse)
                Log.w(TAG, "Could not fetch hotel products: $error")
                if (prodResponse.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                return Result.failure(ApiException(prodResponse.code(), error))
            }
            val products = prodResponse.body()!!

            val productIds = products.map { it.id }.filter { it.isNotBlank() }
            val stockMap = if (productIds.isNotEmpty()) {
                val stockResponse = api.getProductCityStockBatch(
                    cityId = "eq.$cityId",
                    productIdsQuery = "in.(${productIds.joinToString(",")})",
                    select = "product_id,price,mrp,stock_qty,is_available"
                )
                if (stockResponse.isSuccessful && stockResponse.body() != null) {
                    stockResponse.body()!!.associateBy { it.productId }
                } else {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val resolved = products.map { prod ->
                val override = stockMap[prod.id]
                val price = override?.price ?: prod.price
                val mrp = override?.mrp ?: prod.mrp
                val stock = override?.stockQty ?: (prod.stockQty ?: (prod.stockQuantity ?: 0))
                val isAvail = override?.isAvailable ?: prod.isAvailable
                val resVariants = resolveProductVariants(prod, cityId)
                ResolvedProduct(
                    baseProduct = prod,
                    effectivePrice = price,
                    effectiveMrp = mrp,
                    effectiveStock = stock,
                    effectiveIsAvailable = isAvail,
                    variants = resVariants
                )
            }.sortedWith(
                compareBy<ResolvedProduct> { prod ->
                    val isAvail = prod.isHotelItemAvailable
                    when {
                        isAvail && prod.isFeatured -> 0
                        isAvail -> 1
                        else -> 2
                    }
                }.thenBy { it.name.lowercase() }
            )

            if (offset == 0) {
                hotelProductsCache[cacheKey] = CacheEntry(data = resolved, hasMore = resolved.size >= limit)
            } else {
                val existing = hotelProductsCache[cacheKey]?.data ?: emptyList()
                val combined = (existing + resolved).distinctBy { it.id }
                hotelProductsCache[cacheKey] = CacheEntry(data = combined, hasMore = resolved.size >= limit)
            }
            Result.success(resolved)
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching hotel products: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getHotelMenu(vendorId: String, cityId: String): Result<Pair<List<Category>, List<ResolvedProduct>>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(getFallbackHotelMenu(vendorId))
        }
        return try {
            // Rule 4: Run independent category and product requests in parallel
            val (categories, products) = kotlinx.coroutines.coroutineScope {
                val catDeferred = async {
                    api.getCategories(
                        select = "id,name,image_url,vendor_id,is_active,sort_order",
                        vendorId = "eq.$vendorId",
                        isActive = "eq.true",
                        order = "sort_order.asc"
                    )
                }
                val prodDeferred = async {
                    api.getProducts(
                        isActive = "eq.true",
                        vendorId = "eq.$vendorId",
                        select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured,is_veg,food_type,available_from,available_until",
                        order = "is_featured.desc"
                    )
                }
                val catResponse = catDeferred.await()
                if (!catResponse.isSuccessful) {
                    val error = SupabaseClient.parseErrorMessage(catResponse)
                    Log.w(TAG, "Could not fetch hotel categories: $error")
                    if (catResponse.code() == 401) {
                        sessionManager.notifySessionExpired("Your session expired, please log in again")
                        throw SessionExpiredException("Your session expired, please log in again")
                    }
                    throw ApiException(catResponse.code(), error)
                }
                val cats = catResponse.body()?.filter { (it.vendorId == null || it.vendorId == vendorId) && it.isActive } ?: emptyList()

                val prodResponse = prodDeferred.await()
                if (!prodResponse.isSuccessful || prodResponse.body() == null) {
                    val error = SupabaseClient.parseErrorMessage(prodResponse)
                    Log.w(TAG, "Could not fetch hotel products: $error")
                    if (prodResponse.code() == 401) {
                        sessionManager.notifySessionExpired("Your session expired, please log in again")
                        throw SessionExpiredException("Your session expired, please log in again")
                    }
                    throw ApiException(prodResponse.code(), error)
                }
                val prods = prodResponse.body()!!
                Pair(cats, prods)
            }

            // Rule 3: Single batch request for products of this hotel
            val productIds = products.map { it.id }.filter { it.isNotBlank() }
            val stockMap = if (productIds.isNotEmpty()) {
                val stockResponse = api.getProductCityStockBatch(
                    cityId = "eq.$cityId",
                    productIdsQuery = "in.(${productIds.joinToString(",")})",
                    select = "product_id,price,mrp,stock_qty,is_available"
                )
                if (stockResponse.isSuccessful && stockResponse.body() != null) {
                    stockResponse.body()!!.associateBy { it.productId }
                } else {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val resolved = products.map { prod ->
                val override = stockMap[prod.id]
                val price = override?.price ?: prod.price
                val mrp = override?.mrp ?: prod.mrp
                val stock = override?.stockQty ?: (prod.stockQty ?: (prod.stockQuantity ?: 0))
                val isAvail = override?.isAvailable ?: prod.isAvailable
                val resVariants = resolveProductVariants(prod, cityId)
                ResolvedProduct(
                    baseProduct = prod,
                    effectivePrice = price,
                    effectiveMrp = mrp,
                    effectiveStock = stock,
                    effectiveIsAvailable = isAvail,
                    variants = resVariants
                )
            }.sortedWith(
                compareBy<ResolvedProduct> { prod ->
                    val isAvail = prod.isHotelItemAvailable
                    when {
                        isAvail && prod.isFeatured -> 0
                        isAvail -> 1
                        else -> 2
                    }
                }.thenBy { it.name.lowercase() }
            )

            Result.success(Pair(categories, resolved))
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching hotel menu", e)
            Result.failure(e)
        }
    }

    // --- FRESH PRICING FOR CART ITEMS ---
    // Rule: Every render: re-fetch current price per item fresh — never trust a previously-fetched price.
    // Rule 3 & 4: Batch fetch products and city stock overrides in parallel instead of looping N times!
    private fun formatAvailableFromHint(availableFrom: String?): String {
        val parsed = availableFrom?.let(::parseTimeString) ?: return "No longer available"
        return "Available from ${parsed.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))}"
    }

    /** Builds the "we lost the live row" fallback: either the product couldn't be resolved at
     * all (RLS hides a deactivated product from the customer's own queries - verified directly
     * against the DB), or it resolved but failed one of its own availability checks below. Falls
     * back to the locally cached name/image from when the item was added, so the row still shows
     * something recognizable instead of a blank "Item". */
    private fun unavailableCartItem(
        item: CartItem,
        prod: Product?,
        reason: String
    ): CartItemUi {
        val snapshot = if (prod == null) sessionManager.getCachedCartProductSnapshot(item.productId, item.variantId) else null
        val baseProduct = prod ?: Product(
            id = item.productId,
            vendorId = item.vendorId,
            name = snapshot?.name ?: "Item",
            imageUrl = snapshot?.imageUrl,
            unit = snapshot?.unit,
            isActive = false,
            isAvailable = false
        )
        return CartItemUi(
            cartItem = item,
            product = ResolvedProduct(
                baseProduct = baseProduct,
                effectivePrice = 0.0,
                effectiveMrp = null,
                effectiveStock = 0,
                effectiveIsAvailable = false
            ),
            variant = null,
            availabilityState = CartAvailabilityState.UNAVAILABLE,
            stateMessage = reason
        )
    }

    // Recomputes live availability for every row in the (grocery or hotel) cart in one batched
    // round trip - never drops a row even if its product can no longer be read, so the cart can
    // still show it (greyed, with a reason) and let the customer remove it. Uses the exact same
    // availability rules the product lists/hotel menu already use (ResolvedProduct.isHotelItemAvailable,
    // product_city_stock / product_variant_city_stock is_active+is_available), so the cart never
    // disagrees with what's shown while browsing.
    suspend fun getFreshCartItems(isHotel: Boolean, cityId: String): Result<List<CartItemUi>> {
        val rawCart = (if (isHotel) _hotelCart.value else _groceryCart.value).filter { it.quantity > 0 }
        if (rawCart.isEmpty()) {
            return Result.success(emptyList())
        }

        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Service not configured"))
        }

        return try {
            val productIds = rawCart.map { it.productId }.filter { it.isNotBlank() }.distinct()
            if (productIds.isEmpty()) {
                return Result.success(emptyList())
            }
            val hotelVendorId = if (isHotel) rawCart.firstOrNull { !it.vendorId.isNullOrBlank() }?.vendorId else null

            var productsMap: Map<String, Product> = emptyMap()
            var stockMap: Map<String, ProductCityStock> = emptyMap()
            var vendor: Vendor? = null
            var vendorSlots: List<OperatingSlot> = emptyList()

            kotlinx.coroutines.coroutineScope {
                val prodDeferred = async {
                    api.getProductsByIds(
                        idInQuery = "in.(${productIds.joinToString(",")})",
                        select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured,available_from,available_until,product_variants(id,label,is_active,product_variant_city_stock(price,stock_qty,is_available,is_active,city_id))"
                    )
                }
                val stockDeferred = if (!isHotel) async {
                    api.getProductCityStockBatch(
                        cityId = "eq.$cityId",
                        productIdsQuery = "in.(${productIds.joinToString(",")})",
                        select = "product_id,price,mrp,stock_qty,is_available,is_active"
                    )
                } else null
                val vendorDeferred = if (isHotel && !hotelVendorId.isNullOrBlank()) async { getVendor(hotelVendorId) } else null
                val slotsDeferred = if (isHotel && !hotelVendorId.isNullOrBlank()) async { getVendorOperatingSlots(hotelVendorId) } else null

                val pRes = prodDeferred.await()
                productsMap = if (pRes.isSuccessful && pRes.body() != null) pRes.body()!!.associateBy { it.id } else emptyMap()
                val sRes = stockDeferred?.await()
                stockMap = if (sRes != null && sRes.isSuccessful && sRes.body() != null) sRes.body()!!.associateBy { it.productId } else emptyMap()
                vendor = vendorDeferred?.await()?.getOrNull()
                vendorSlots = slotsDeferred?.await()?.getOrNull() ?: emptyList()
            }

            val effectiveSlots = when {
                vendorSlots.isNotEmpty() -> vendorSlots
                vendor != null && !vendor!!.openingTime.isNullOrBlank() && !vendor!!.closingTime.isNullOrBlank() -> listOf(
                    OperatingSlot(vendorId = vendor!!.id, startTime = vendor!!.openingTime!!, endTime = vendor!!.closingTime!!)
                )
                else -> emptyList()
            }
            val isHotelOpen = !isHotel || (vendor != null && vendor!!.isActive && isWithinAnySlot(effectiveSlots))

            val list = mutableListOf<CartItemUi>()
            for (item in rawCart) {
                val prod = productsMap[item.productId]

                if (prod == null) {
                    list.add(unavailableCartItem(item, null, "No longer available"))
                    continue
                }

                // Keep the local fallback snapshot fresh while the product is still resolvable.
                sessionManager.cacheCartProductSnapshot(item.productId, item.variantId, prod.name, prod.imageUrl, prod.unit)

                if (isHotel) {
                    val resolvedProd = ResolvedProduct(
                        baseProduct = prod,
                        effectivePrice = prod.price,
                        effectiveMrp = prod.mrp,
                        effectiveStock = prod.stockQty ?: prod.stockQuantity ?: 0,
                        effectiveIsAvailable = prod.isAvailable
                    )
                    when {
                        !isHotelOpen -> list.add(
                            CartItemUi(item, resolvedProd, null, CartAvailabilityState.UNAVAILABLE, CART_HOTEL_CLOSED_MESSAGE)
                        )
                        !resolvedProd.isHotelItemAvailable(effectiveSlots) -> {
                            val hasOwnWindow = !prod.availableFrom.isNullOrBlank() && !prod.availableUntil.isNullOrBlank()
                            val reason = if (hasOwnWindow && !isVendorWithinOperatingHours(prod.availableFrom, prod.availableUntil)) {
                                formatAvailableFromHint(prod.availableFrom)
                            } else {
                                "No longer available"
                            }
                            list.add(CartItemUi(item, resolvedProd, null, CartAvailabilityState.UNAVAILABLE, reason))
                        }
                        else -> list.add(CartItemUi(item, resolvedProd, null, CartAvailabilityState.OK, null))
                    }
                    continue
                }

                // Grocery
                if (!prod.isActive) {
                    list.add(unavailableCartItem(item, prod, "No longer available"))
                    continue
                }

                if (!item.variantId.isNullOrBlank()) {
                    val rawVariant = prod.productVariants?.firstOrNull { it.id == item.variantId }
                    val cityStock = rawVariant?.cityStock?.firstOrNull { it.cityId == cityId }
                    if (rawVariant == null || !rawVariant.isActive || cityStock == null || !cityStock.isActive || !cityStock.isAvailable) {
                        list.add(unavailableCartItem(item, prod, "No longer available"))
                        continue
                    }
                    val resolvedProd = ResolvedProduct(
                        baseProduct = prod,
                        effectivePrice = cityStock.price,
                        effectiveMrp = null,
                        effectiveStock = cityStock.stockQty ?: 0,
                        effectiveIsAvailable = true,
                        variants = resolveProductVariants(prod, cityId)
                    )
                    val resolvedVariant = ResolvedVariant(
                        id = rawVariant.id,
                        label = rawVariant.label,
                        price = cityStock.price,
                        stockQty = cityStock.stockQty ?: 0,
                        isAvailable = true
                    )
                    val stock = cityStock.stockQty ?: 0
                    when {
                        stock <= 0 -> list.add(CartItemUi(item, resolvedProd, resolvedVariant, CartAvailabilityState.OUT_OF_STOCK, "Out of stock"))
                        item.quantity > stock -> {
                            updateCartItemQuantity(item.productId, isHotel = false, newQty = stock, variantId = item.variantId)
                            list.add(CartItemUi(item.copy(quantity = stock), resolvedProd, resolvedVariant, CartAvailabilityState.QTY_REDUCED, "Only $stock left - quantity updated"))
                        }
                        else -> list.add(CartItemUi(item, resolvedProd, resolvedVariant, CartAvailabilityState.OK, null))
                    }
                } else {
                    val override = stockMap[prod.id]
                    if (override == null || !override.isActive || !override.isAvailable) {
                        list.add(unavailableCartItem(item, prod, "No longer available"))
                        continue
                    }
                    val resolvedProd = ResolvedProduct(
                        baseProduct = prod,
                        effectivePrice = override.price,
                        effectiveMrp = override.mrp,
                        effectiveStock = override.stockQty ?: 0,
                        effectiveIsAvailable = true,
                        variants = resolveProductVariants(prod, cityId)
                    )
                    val stock = override.stockQty ?: 0
                    when {
                        stock <= 0 -> list.add(CartItemUi(item, resolvedProd, null, CartAvailabilityState.OUT_OF_STOCK, "Out of stock"))
                        item.quantity > stock -> {
                            updateCartItemQuantity(item.productId, isHotel = false, newQty = stock, variantId = item.variantId)
                            list.add(CartItemUi(item.copy(quantity = stock), resolvedProd, null, CartAvailabilityState.QTY_REDUCED, "Only $stock left - quantity updated"))
                        }
                        else -> list.add(CartItemUi(item, resolvedProd, null, CartAvailabilityState.OK, null))
                    }
                }
            }
            Result.success(list)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Exception getting fresh cart items", e)
            Result.failure(e)
        }
    }

    // --- CART BACKEND SYNC ---
    // The in-memory StateFlows are the UI source of truth. When a Supabase key is
    // configured we mirror the cart to the cart_items table (so it survives across
    // sessions / is visible to the backend) and rehydrate it on session start.
    // Prices are NEVER stored in cart_items — they are always re-fetched fresh
    // (see getFreshCartItems); cart_items only holds user_id/product_id/variant_id/
    // vendor_id/city_id/quantity.
    private val cartWriteMutex = Mutex()

    private suspend fun writeCartSnapshot(userId: String, snapshot: List<CartItem>) {
        cartWriteMutex.withLock {
            // Once the delete starts, the write must finish - never cancel between delete and insert
            withContext(NonCancellable) {
                val del = api.clearCartForUser(userIdQuery = "eq.$userId")
                if (!del.isSuccessful) {
                    Log.w(TAG, "Cart clear failed: HTTP ${del.code()}")
                    return@withContext
                }
                if (snapshot.isNotEmpty()) {
                    val ins = api.insertCartItemsBulk(snapshot.map { it.copy(id = null) })
                    if (!ins.isSuccessful) {
                        Log.w(TAG, "Cart bulk insert failed: HTTP ${ins.code()}")
                    }
                }
            }
        }
    }

    private fun persistCartToBackend() {
        if (!SupabaseClient.isKeyConfigured()) return
        val userId = sessionManager.userId.value ?: return
        persistJob?.cancel()
        persistJob = cartSyncScope.launch {
            try {
                delay(300) // only this part is cancellable - coalesces rapid taps
            } catch (e: CancellationException) {
                return@launch
            }
            val latest = _groceryCart.value + _hotelCart.value // read latest state after the debounce
            try {
                writeCartSnapshot(userId, latest)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist cart to backend: ${e.message}")
            }
        }
    }

    suspend fun syncCartFromBackend() {
        if (!SupabaseClient.isKeyConfigured()) return
        val userId = sessionManager.userId.value ?: return
        try {
            val res = api.getCartItems(userId = "eq.$userId")
            if (res.isSuccessful && res.body() != null) {
                val items = res.body()!!
                _groceryCart.value = items.filter { it.vendorId == null }
                _hotelCart.value = items.filter { it.vendorId != null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync cart from backend", e)
        }
    }

    // --- CART OPERATIONS ---
    fun addToCart(
        productId: String,
        vendorId: String?,
        cityId: String?,
        quantityDelta: Int = 1,
        isHotel: Boolean,
        variantId: String? = null,
        // Optional: caller's already-resolved product, used only to cache a display snapshot
        // (name/image/unit) for this cart row. A product can only be added here while it's
        // visible/active, so this is the one reliable place to capture that - once a product
        // is turned off later, RLS stops returning that row for this customer at all, and
        // without this cached snapshot the cart would have nothing left to show but "No longer
        // available" with no name attached.
        productForSnapshot: ResolvedProduct? = null
    ): AddToCartResult {
        val currentUserId = sessionManager.userId.value ?: "guest"
        if (productForSnapshot != null) {
            val variantLabel = variantId?.let { vid -> productForSnapshot.variants.firstOrNull { it.id == vid }?.label }
            sessionManager.cacheCartProductSnapshot(
                productId = productId,
                variantId = variantId,
                name = if (!variantLabel.isNullOrBlank()) "${productForSnapshot.name} - $variantLabel" else productForSnapshot.name,
                imageUrl = productForSnapshot.imageUrl,
                unit = productForSnapshot.unit
            )
        }
        if (isHotel) {
            val currentList = _hotelCart.value
            val existingVendor = currentList.firstOrNull { it.vendorId != null }?.vendorId
            if (existingVendor != null && vendorId != null && existingVendor != vendorId) {
                // Single-hotel rule conflict!
                val pending = CartItem(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    productId = productId,
                    variantId = variantId,
                    vendorId = vendorId,
                    cityId = cityId,
                    quantity = quantityDelta
                )
                return AddToCartResult.HotelConflict(existingVendor, vendorId, pending)
            }
            val existing = currentList.find { it.productId == productId && it.variantId == variantId }
            if (existing != null) {
                val newQty = existing.quantity + quantityDelta
                if (newQty <= 0) {
                    _hotelCart.value = currentList.filter { !(it.productId == productId && it.variantId == variantId) }
                } else {
                    _hotelCart.value = currentList.map {
                        if (it.productId == productId && it.variantId == variantId) it.copy(quantity = newQty) else it
                    }
                }
            } else if (quantityDelta > 0) {
                val newItem = CartItem(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    productId = productId,
                    variantId = variantId,
                    vendorId = vendorId,
                    cityId = cityId,
                    quantity = quantityDelta
                )
                _hotelCart.value = currentList + newItem
            }
        } else {
            val currentList = _groceryCart.value
            val existing = currentList.find { it.productId == productId && it.variantId == variantId }
            if (existing != null) {
                val newQty = existing.quantity + quantityDelta
                if (newQty <= 0) {
                    _groceryCart.value = currentList.filter { !(it.productId == productId && it.variantId == variantId) }
                } else {
                    _groceryCart.value = currentList.map {
                        if (it.productId == productId && it.variantId == variantId) it.copy(quantity = newQty) else it
                    }
                }
            } else if (quantityDelta > 0) {
                val newItem = CartItem(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    productId = productId,
                    variantId = variantId,
                    vendorId = null,
                    cityId = cityId,
                    quantity = quantityDelta
                )
                _groceryCart.value = currentList + newItem
            }
        }
        persistCartToBackend()
        return AddToCartResult.Success
    }

    fun forceClearHotelCartAndAdd(item: CartItem) {
        _hotelCart.value = listOf(item)
        persistCartToBackend()
    }

    fun updateCartItemQuantity(productId: String, isHotel: Boolean, newQty: Int, variantId: String? = null) {
        if (isHotel) {
            val current = _hotelCart.value
            if (newQty <= 0) {
                _hotelCart.value = current.filter { !(it.productId == productId && it.variantId == variantId) }
            } else {
                _hotelCart.value = current.map {
                    if (it.productId == productId && it.variantId == variantId) it.copy(quantity = newQty) else it
                }
            }
        } else {
            val current = _groceryCart.value
            if (newQty <= 0) {
                _groceryCart.value = current.filter { !(it.productId == productId && it.variantId == variantId) }
            } else {
                _groceryCart.value = current.map {
                    if (it.productId == productId && it.variantId == variantId) it.copy(quantity = newQty) else it
                }
            }
        }
        persistCartToBackend()
    }

    fun clearCart(isHotel: Boolean) {
        if (isHotel) {
            _hotelCart.value = emptyList()
        } else {
            _groceryCart.value = emptyList()
        }
        persistCartToBackend()
    }

    /** Removes several cart rows (by productId+variantId) in one state update - used by the
     * "Remove unavailable items" bulk action, so it's one persist instead of N. */
    fun removeCartItems(keys: List<Pair<String, String?>>, isHotel: Boolean) {
        if (keys.isEmpty()) return
        val keySet = keys.toSet()
        if (isHotel) {
            _hotelCart.value = _hotelCart.value.filter { (it.productId to it.variantId) !in keySet }
        } else {
            _groceryCart.value = _groceryCart.value.filter { (it.productId to it.variantId) !in keySet }
        }
        persistCartToBackend()
    }

    suspend fun clearCartDirectly(isHotel: Boolean) {
        if (isHotel) _hotelCart.value = emptyList() else _groceryCart.value = emptyList()
        val userId = sessionManager.userId.value ?: return
        if (!SupabaseClient.isKeyConfigured()) return
        persistJob?.cancel()
        try {
            writeCartSnapshot(userId, _groceryCart.value + _hotelCart.value)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear cart items on backend: ${e.message}")
        }
    }

    fun resetInMemoryCart() {
        persistJob?.cancel()
        _groceryCart.value = emptyList()
        _hotelCart.value = emptyList()
    }

    fun clearCartInMemory(isHotel: Boolean? = null) {
        if (isHotel == null) {
            resetInMemoryCart()
        } else {
            persistJob?.cancel()
            if (isHotel) {
                _hotelCart.value = emptyList()
            } else {
                _groceryCart.value = emptyList()
            }
        }
    }

    fun clearAllCarts() {
        _groceryCart.value = emptyList()
        _hotelCart.value = emptyList()
        persistCartToBackend()
    }

    fun getCartCount(): Int {
        val g = _groceryCart.value.sumOf { it.quantity }
        val h = _hotelCart.value.sumOf { it.quantity }
        return g + h
    }

    // --- ONE DELIVERY SYSTEM: "Express Delivery" Only ---

    suspend fun getCityDeliverySettings(cityId: String): Result<CityDeliverySettings?> {
        val cleanCityId = cityId.removePrefix("eq.")
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(CityDeliverySettings(cityId = cleanCityId, freeDeliveryMinOrderAmount = 99.0, expressDeliveryMinutes = 30))
        }
        return try {
            val response = api.getCityDeliverySettings(cityId = "eq.$cleanCityId")
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.firstOrNull())
            } else {
                Result.success(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception getting city delivery settings: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getFreeDeliveryThreshold(cityId: String): Double? {
        val cleanCityId = cityId.removePrefix("eq.")
        if (!SupabaseClient.isKeyConfigured()) {
            return 99.0
        }
        return try {
            val response = api.getCityDeliverySettings(cityId = "eq.$cleanCityId")
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val settings = response.body()!!.first()
                settings.effectiveFreeDeliveryMinOrder
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception getting free delivery threshold: ${e.message}")
            null
        }
    }

    suspend fun calculateDeliveryFee(
        cityId: String,
        distanceKm: Double,
        subtotalAfterDiscount: Double
    ): Result<Double> {
        val cleanCityId = cityId.removePrefix("eq.")
        if (!SupabaseClient.isKeyConfigured()) {
            // Sindhanur formula: free if order after discount >= Rs 99 AND distance <= 5 km;
            // otherwise Rs 30 for the first 3 km + Rs 10 per extra km; capped at Rs 500
            val fee = if (subtotalAfterDiscount >= 99.0 && distanceKm <= 5.0) {
                0.0
            } else {
                val base = 30.0
                val extra = if (distanceKm > 3.0) (distanceKm - 3.0) * 10.0 else 0.0
                (base + extra).coerceAtMost(500.0)
            }
            return Result.success(fee)
        }
        return try {
            val body = mapOf(
                "p_city_id" to cleanCityId,
                "p_distance_km" to distanceKm,
                "p_subtotal" to subtotalAfterDiscount
            )
            val response = api.calculateDeliveryFee(body)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "calculate_delivery_fee failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling calculate_delivery_fee: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getHandlingFee(cityId: String): Result<Double> {
        val cleanCityId = cityId.removePrefix("eq.")
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(5.0)
        }
        return try {
            val body = mapOf("p_city_id" to cleanCityId)
            val response = api.getHandlingFee(body)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(5.0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling get_handling_fee: ${e.message}", e)
            Result.success(5.0)
        }
    }

    /**
     * Whether this customer may pay Cash on Delivery. Blocked after repeated cancelled orders
     * (or by an admin). Fails open — the checkout RPC also enforces it server-side.
     */
    suspend fun getMyCodAllowed(): Boolean {
        if (!SupabaseClient.isKeyConfigured()) return true
        return try {
            val response = api.myCodStatus(emptyMap())
            if (response.isSuccessful && response.body() != null) {
                response.body()!!["cod_allowed"] != false
            } else true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling my_cod_status: ${e.message}", e)
            true
        }
    }

    /**
     * Distance calculation exactly like the server:
     * - Grocery cart: pickup = delivery_zones (city, is_active = true, oldest created_at) center_latitude, center_longitude.
     * - Hotel cart: pickup = hotel vendors.latitude, vendors.longitude.
     * - distanceKm = sqrt((addrLat - pickupLat)^2 + (addrLng - pickupLng)^2) * 111.0; use 0.0 if any coordinate is missing.
     */
    suspend fun resolveServerDistanceKm(
        address: CustomerAddress?,
        cityId: String?,
        vendorId: String?,
        isHotel: Boolean
    ): Double {
        if (address == null || address.lat == null || address.lng == null) {
            return 0.0
        }
        val addrLat = address.lat
        val addrLng = address.lng
        if (addrLat == 0.0 && addrLng == 0.0) return 0.0

        val cleanCityId = cityId?.removePrefix("eq.")?.trim()
        val cleanVendorId = vendorId?.removePrefix("eq.")?.trim()

        val pickupCoords: Pair<Double, Double>? = if (isHotel && !cleanVendorId.isNullOrBlank()) {
            val vendor = getVendor(cleanVendorId).getOrNull()
            if (vendor?.latitude != null && vendor.longitude != null) {
                Pair(vendor.latitude, vendor.longitude)
            } else null
        } else if (!cleanCityId.isNullOrBlank()) {
            val zonesRes = try {
                if (SupabaseClient.isKeyConfigured()) {
                    api.getDeliveryZones(cityId = "eq.$cleanCityId")
                } else null
            } catch (e: Exception) {
                null
            }
            val activeZone = zonesRes?.body()?.filter { it.isActive != false }?.minByOrNull { it.createdAt ?: "" }
            if (activeZone?.centerLatitude != null && activeZone.centerLongitude != null) {
                Pair(activeZone.centerLatitude, activeZone.centerLongitude)
            } else {
                val city = getCity(cleanCityId).getOrNull()
                if (city?.centerLat != null && city.centerLng != null) {
                    Pair(city.centerLat, city.centerLng)
                } else null
            }
        } else null

        if (pickupCoords == null) return 0.0
        val pickupLat = pickupCoords.first
        val pickupLng = pickupCoords.second

        val dLat = addrLat - pickupLat
        val dLng = addrLng - pickupLng
        val dist = kotlin.math.sqrt(dLat * dLat + dLng * dLng) * 111.0
        return dist.coerceAtLeast(0.0)
    }

    suspend fun getCity(cityId: String): Result<City?> {
        val cleanId = cityId.removePrefix("eq.")
        val cached = getCities().getOrNull()?.find { it.id == cleanId }
        if (cached != null && cached.centerLat != null && cached.centerLng != null) {
            return Result.success(cached)
        }
        if (!SupabaseClient.isKeyConfigured()) return Result.success(cached)
        return try {
            val response = api.getCityById(idQuery = "eq.$cleanId")
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else {
                Result.success(cached)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching city $cleanId: ${e.message}", e)
            Result.success(cached)
        }
    }

    /**
     * Resolves the pickup point according to the Express Delivery distance calculation rule:
     * - Hotel/food orders (vendor_id is set): measure distance from the hotel's own location (vendors.latitude, vendors.longitude).
     * - Grocery orders (vendor_id is null): measure distance from the city's center point (cities.center_lat, cities.center_lng for the customer's city_id).
     *   Treat this as the city's virtual dispatch hub, since grocery items aren't tied to one physical store.
     */
    suspend fun getPickupPoint(vendorId: String?, cityId: String?): PickupPoint? {
        val cleanVendorId = vendorId?.removePrefix("eq.")?.takeIf { it.isNotBlank() }
        if (!cleanVendorId.isNullOrBlank()) {
            val vendorRes = getVendor(cleanVendorId)
            val vendor = vendorRes.getOrNull()
            if (vendor?.latitude != null && vendor.longitude != null) {
                return PickupPoint(lat = vendor.latitude, lng = vendor.longitude)
            }
        }

        val cleanCityId = cityId?.removePrefix("eq.")?.takeIf { it.isNotBlank() }
        if (!cleanCityId.isNullOrBlank()) {
            val cityRes = getCity(cleanCityId)
            val city = cityRes.getOrNull()
            if (city?.centerLat != null && city.centerLng != null) {
                return PickupPoint(lat = city.centerLat, lng = city.centerLng)
            }
        }

        return null
    }

    suspend fun getPickupPoint(order: Order): PickupPoint? {
        return getPickupPoint(vendorId = order.vendorId, cityId = order.cityId)
    }

    suspend fun resolveDeliveryDistanceKm(
        address: CustomerAddress?,
        cityId: String?,
        vendorId: String?,
        isHotel: Boolean
    ): Double {
        if (address == null || address.lat == null || address.lng == null) {
            return 1.0
        }
        val userLat = address.lat
        val userLng = address.lng

        // Authoritative pickup point resolution:
        // Hotel/food orders (vendor_id is set): measure distance from hotel's own location
        // Grocery orders (vendor_id is null): measure distance from city's center point (virtual dispatch hub)
        val effectiveVendorId = if (isHotel) vendorId?.takeIf { it.isNotBlank() } else null
        val pickup = getPickupPoint(vendorId = effectiveVendorId, cityId = cityId)
        if (pickup != null) {
            val dist = calculateHaversineDistanceKm(userLat, userLng, pickup.lat, pickup.lng)
            if (dist > 0.0) {
                return dist
            }
        }

        // Fallback to RPC findCityAndDistanceForLocation if available
        val locRes = findCityAndDistanceForLocation(userLat, userLng).getOrNull()
        if (locRes != null && locRes.distanceKm != null && locRes.distanceKm > 0.0) {
            return locRes.distanceKm
        }

        // Fallback to regional hub coordinates (e.g. Sindhanur fulfillment warehouse: 15.7667, 76.7583)
        val cleanCityId = cityId?.removePrefix("eq.")
        val cities = getCities().getOrNull() ?: emptyList()
        val city = cities.find { it.id == cleanCityId } ?: cities.firstOrNull()
        if (city != null && city.centerLat != null && city.centerLng != null) {
            return calculateHaversineDistanceKm(userLat, userLng, city.centerLat, city.centerLng)
        }

        val knownCoords = mapOf(
            "sindhanur" to Pair(15.7667, 76.7583),
            "raichur" to Pair(16.2120, 77.3439),
            "bellary" to Pair(15.1394, 76.9214),
            "ballari" to Pair(15.1394, 76.9214),
            "gangavathi" to Pair(15.4326, 76.5312),
            "manvi" to Pair(15.9922, 77.0506),
            "koppal" to Pair(15.3524, 76.1557)
        )
        val cityName = city?.name?.trim()?.lowercase(java.util.Locale.ROOT) ?: "sindhanur"
        val hubCoords = knownCoords[cityName] ?: knownCoords["sindhanur"]
        if (hubCoords != null) {
            return calculateHaversineDistanceKm(userLat, userLng, hubCoords.first, hubCoords.second)
        }

        return 1.0
    }

    suspend fun validateAndApplyCoupon(
        code: String,
        cityId: String,
        subtotal: Double,
        userId: String?
    ): Result<CouponValidationResult> {
        val cleanCityId = if (cityId.startsWith("eq.")) cityId.removePrefix("eq.") else cityId
        val trimmedCode = code.trim().uppercase()
        if (trimmedCode.isBlank()) {
            return Result.success(CouponValidationResult(isValid = false, errorMessage = "Please enter a coupon code"))
        }

        // 1. Authoritative check with Supabase coupons table
        return try {
            val response = api.getCoupons(cityId = "eq.$cleanCityId", code = "eq.$trimmedCode")
            val coupon = response.body()?.firstOrNull()
            if (coupon == null) {
                return Result.success(
                    CouponValidationResult(
                        isValid = false,
                        errorMessage = "Invalid or inactive coupon for this city"
                    )
                )
            }

            if (!coupon.isActive) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "This coupon is no longer active")
                )
            }

            val now = System.currentTimeMillis()
            val starts = parseIsoTime(coupon.startsAt)
            if (starts != null && now < starts) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "Coupon is not active yet")
                )
            }

            val expires = parseIsoTime(coupon.expiresAt)
            if (expires != null && now > expires) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "Coupon has expired")
                )
            }

            val minOrder = coupon.minOrderAmount ?: 0.0
            if (subtotal < minOrder) {
                return Result.success(
                    CouponValidationResult(
                        isValid = false,
                        coupon = coupon,
                        errorMessage = "Minimum order of ₹${"%.0f".format(minOrder)} required for this coupon"
                    )
                )
            }

            val limit = coupon.usageLimit
            if (limit != null && (coupon.usedCount ?: 0) >= limit) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "Coupon usage limit reached")
                )
            }

            val isPercent = coupon.discountType.equals("percent", ignoreCase = true) ||
                            coupon.discountType.equals("percentage", ignoreCase = true)
            var discount = if (isPercent) {
                (subtotal * coupon.discountValue) / 100.0
            } else {
                coupon.discountValue
            }
            if (coupon.maxDiscountAmount != null && discount > coupon.maxDiscountAmount) {
                discount = coupon.maxDiscountAmount
            }
            discount = discount.coerceAtMost(subtotal)

            Result.success(
                CouponValidationResult(
                    isValid = true,
                    coupon = coupon,
                    discountAmount = discount
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Coupon validation failed", e)
            Result.failure(e)
        }
    }

    suspend fun getCoupons(cityId: String): Result<List<Coupon>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Service not configured"))
        }
        return try {
            val response = api.getCoupons(cityId = "eq.$cityId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    // Look up a single active coupon by code for a city (server-side filter).
    suspend fun getCouponByCode(code: String, cityId: String): Result<Coupon?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Service not configured"))
        }
        return try {
            val response = api.getCoupons(cityId = "eq.$cityId", code = "eq.$code")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.firstOrNull())
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    // Full client-side validation before applying/using a coupon. Returns an error
    // message when invalid, or null when the coupon may be applied.
    fun validateCoupon(coupon: Coupon, subtotal: Double): String? {
        val now = System.currentTimeMillis()
        val starts = parseIsoTime(coupon.startsAt)
        if (starts != null && now < starts) return "Coupon is not active yet"
        val expires = parseIsoTime(coupon.expiresAt)
        if (expires != null && now > expires) return "Coupon has expired"
        val limit = coupon.usageLimit
        if (limit != null && (coupon.usedCount ?: 0) >= limit) return "Coupon usage limit reached"
        val min = coupon.minOrderAmount ?: 0.0
        if (subtotal < min) return "Minimum order ₹${"%.0f".format(min)} required"
        return null
    }

    private fun parseIsoTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            val core = if (s.length >= 19) s.substring(0, 19) else s
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(core)?.time
        } catch (e: Exception) { null }
    }

    suspend fun recordCouponUsage(couponId: String, userId: String, orderId: String) {
        if (!SupabaseClient.isKeyConfigured()) return
        try {
            api.insertCouponUsage(CouponUsage(couponId = couponId, userId = userId, orderId = orderId))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record coupon usage", e)
        }
    }

    // --- CUSTOMER ADDRESSES ---
    suspend fun getCustomerAddressCount(userId: String): Result<Int> {
        return try {
            val response = api.getCustomerAddresses(userId = "eq.$userId")
            if (response.isSuccessful) Result.success(response.body()?.size ?: 0) else Result.failure(Exception("Failed to check addresses"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAddresses(userId: String): Result<List<CustomerAddress>> {
        return try {
            val response = api.getAddresses(userId = "eq.$userId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else if (response.code() == 401) {
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Your session expired, please log in again"))
            } else {
                Result.success(_localAddresses.value)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAddressById(addressId: String): Result<CustomerAddress?> {
        return try {
            val response = api.getAddressById(idQuery = "eq.$addressId")
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else {
                Result.success(_localAddresses.value.find { it.id == addressId })
            }
        } catch (e: Exception) {
            Result.success(_localAddresses.value.find { it.id == addressId })
        }
    }

    suspend fun addAddress(address: CustomerAddress): Result<CustomerAddress> {
        return try {
            val response = api.insertAddress(address)
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val saved = response.body()!!.first()
                _localAddresses.value = _localAddresses.value + saved
                Result.success(saved)
            } else if (response.isSuccessful) {
                _localAddresses.value = _localAddresses.value + address
                Result.success(address)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "insertAddress failed HTTP ${response.code()}: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception adding address: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun deleteAddress(id: String): Result<Unit> {
        return try {
            val response = api.deleteAddress(idQuery = "eq.$id")
            _localAddresses.value = _localAddresses.value.filter { it.id != id }
            Result.success(Unit)
        } catch (e: Exception) {
            _localAddresses.value = _localAddresses.value.filter { it.id != id }
            Result.success(Unit)
        }
    }

    // Edit an existing address.
    suspend fun updateAddress(id: String, fields: Map<String, Any?>): Result<Unit> {
        return try {
            val response = api.updateAddress(idQuery = "eq.$id", body = fields)
            if (response.isSuccessful) {
                response.body()?.firstOrNull()?.let { updated ->
                    _localAddresses.value = _localAddresses.value.map { if (it.id == id) updated else it }
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Set one address as default: unset the previous default first so only one stays true.
    suspend fun setDefaultAddress(userId: String, addressId: String): Result<Unit> {
        return try {
            val res = api.getAddresses(userId = "eq.$userId")
            val current = res.body().orEmpty()
            // 1. Unset any existing default (other than the one being promoted).
            current.filter { it.isDefault && it.id != addressId }.forEach { prev ->
                prev.id?.let { api.updateAddress(idQuery = "eq.$it", body = mapOf("is_default" to false)) }
            }
            // 2. Set the chosen address as default.
            api.updateAddress(idQuery = "eq.$addressId", body = mapOf("is_default" to true))
            _localAddresses.value = _localAddresses.value.map {
                it.copy(isDefault = (it.id == addressId))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- SAFE CONCURRENT CHECKOUT & ORDER PLACEMENT ---
    // Direct customer inserts into `orders` or `order_items` are strictly disabled per RLS security policy.
    // Instead, backend-authoritative RPC functions (checkout_grocery_order / checkout_food_order)
    // are used exclusively. They compute all pricing, totals, and commission atomically on the database.
    suspend fun placeOrder(
        userId: String,
        isHotel: Boolean,
        vendorId: String?,
        cityId: String,
        addressId: String,
        paymentMethod: String,
        coupon: Coupon? = null,
        // Pay part of the order from the Sndmart Wallet (the server decides how much).
        useWallet: Boolean = false,
        // Cooking (shown to the vendor) + delivery (shown to the partner) instructions.
        cookingInstructions: String? = null,
        deliveryInstructions: String? = null
    ): Result<Order> {
        return try {
            val rawCart = (if (isHotel) _hotelCart.value else _groceryCart.value).filter { it.quantity > 0 }
            if (rawCart.isEmpty()) {
                return Result.failure(Exception("Cart is empty"))
            }

            val cleanAddressId = addressId.removePrefix("eq.").trim()
            if (cleanAddressId.isBlank()) {
                return Result.failure(Exception("Please select a delivery address"))
            }

            if (paymentMethod.lowercase().trim() == "card") {
                return Result.failure(Exception("Card payment is not available. Please choose UPI or Cash on Delivery."))
            }

            val mappedPaymentMethod = when (paymentMethod.lowercase().trim()) {
                "cod", "cash" -> "cash"
                "upi" -> "upi"
                else -> "cash"
            }

            // Demo fallback if Supabase key is unconfigured
            if (!SupabaseClient.isKeyConfigured()) {
                return Result.failure(Exception("Service not configured"))
            }

            // Re-check maintenance mode right before checkout
            val maintenance = checkMaintenanceMode()
            if (maintenance.enabled) {
                val msg = maintenance.message ?: "Service temporarily unavailable. Please try again shortly."
                return Result.failure(Exception(msg))
            }

            // Build p_items array: ONLY product_id, variant_id (or null), and quantity
            val itemsArray = JSONArray()
            for (item in rawCart) {
                val itemObj = JSONObject().apply {
                    put("product_id", item.productId)
                    if (!item.variantId.isNullOrBlank()) {
                        put("variant_id", item.variantId)
                    } else {
                        put("variant_id", JSONObject.NULL)
                    }
                    put("quantity", item.quantity)
                }
                itemsArray.put(itemObj)
            }

            val payloadJson = JSONObject().apply {
                if (isHotel) {
                    val effectiveVendorId = vendorId?.takeIf { it.isNotBlank() }
                        ?: rawCart.firstOrNull { !it.vendorId.isNullOrBlank() }?.vendorId
                        ?: return Result.failure(Exception("Hotel / Vendor ID is missing for this order"))
                    put("p_vendor_id", effectiveVendorId)
                }
                put("p_address_id", cleanAddressId)
                put("p_payment_method", mappedPaymentMethod)
                put("p_items", itemsArray)
                put("p_notes", JSONObject.NULL)
                val couponCode = coupon?.code?.trim()?.uppercase()
                if (!couponCode.isNullOrBlank()) {
                    put("p_coupon_code", couponCode)
                } else {
                    put("p_coupon_code", JSONObject.NULL)
                }
                put("p_use_wallet", useWallet)
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = payloadJson.toString().toRequestBody(mediaType)

            val rpcResult = callCheckoutRpcWithRetry(isHotel, requestBody)
            if (rpcResult.isSuccess) {
                val placedOrder = rpcResult.getOrNull()!!
                // Cooking (vendor) + delivery (partner) instructions — best-effort, never fail the order.
                setOrderInstructions(placedOrder.id, cookingInstructions, deliveryInstructions)
                // Clear the checked-out cart_items rows directly from backend & memory
                clearCartDirectly(isHotel)
                cachedWallet = null // wallet used / cashback pending changed
                Result.success(placedOrder)
            } else {
                Result.failure(rpcResult.exceptionOrNull() ?: Exception("Failed to place order"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exception during placeOrder", e)
            Result.failure(e)
        }
    }

    /** Saves the customer's cooking/delivery instructions on their just-placed order. */
    private suspend fun setOrderInstructions(orderId: String?, cooking: String?, delivery: String?) {
        if (orderId.isNullOrBlank() || !SupabaseClient.isKeyConfigured()) return
        val c = cooking?.trim()?.takeIf { it.isNotEmpty() }
        val d = delivery?.trim()?.takeIf { it.isNotEmpty() }
        if (c == null && d == null) return
        try {
            val body = HashMap<String, Any>()
            body["p_order_id"] = orderId
            if (c != null) body["p_cooking"] = c
            if (d != null) body["p_delivery"] = d
            api.setOrderInstructions(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "set_order_instructions failed: ${e.message}")
        }
    }

    /**
     * Executes the checkout RPC function (checkout_food_order or checkout_grocery_order)
     * with transient network retry. Real errors (e.g. stock, invalid address) return immediately.
     */
    private suspend fun callCheckoutRpcWithRetry(
        isHotel: Boolean,
        requestBody: okhttp3.RequestBody,
        maxRetries: Int = 1
    ): Result<Order> {
        var lastException: Exception = Exception("Checkout failed")

        for (attempt in 0..maxRetries) {
            try {
                val response = if (isHotel) {
                    api.checkoutFoodOrder(requestBody)
                } else {
                    api.checkoutGroceryOrder(requestBody)
                }

                if (response.isSuccessful && response.body() != null) {
                    val bodyStr = response.body()!!.string()
                    val order = parseOrderFromRpcResponse(bodyStr)
                    return Result.success(order)
                } else {
                    val rawError = response.errorBody()?.string().orEmpty()
                    val cleanMessage = extractCleanErrorMessage(rawError, response.code())
                    Log.w(TAG, "Checkout RPC error HTTP ${response.code()}: $cleanMessage (raw: $rawError)")
                    // HTTP errors are authoritative database/validation responses — do not retry
                    return Result.failure(Exception(cleanMessage))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Checkout RPC network exception (attempt $attempt): ${e.message}")
                if (attempt == maxRetries) {
                    return Result.failure(e)
                }
                lastException = e
            }

            delay(400L * (attempt + 1))
        }

        return Result.failure(lastException)
    }

    private fun parseOrderFromRpcResponse(bodyStr: String): Order {
        val trimmed = bodyStr.trim()
        if (trimmed.isEmpty()) {
            return Order(id = "ord-${System.currentTimeMillis()}")
        }

        // Check if response is a plain UUID or quoted UUID string
        val unquoted = trimmed.removeSurrounding("\"").trim()
        if (unquoted.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) {
            return Order(id = unquoted, status = "confirmed")
        }

        val moshi = SupabaseClient.moshi
        try {
            if (trimmed.startsWith("[")) {
                val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, Order::class.java)
                val adapter = moshi.adapter<List<Order>>(listType)
                val parsed = adapter.fromJson(trimmed)?.firstOrNull()
                if (parsed != null && !parsed.id.isNullOrBlank()) {
                    return parsed
                }
            } else if (trimmed.startsWith("{")) {
                val adapter = moshi.adapter(Order::class.java)
                val parsed = adapter.fromJson(trimmed)
                if (parsed != null && !parsed.id.isNullOrBlank()) {
                    return parsed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Moshi failed to parse order from RPC response: ${e.message}, parsing with JSONObject")
        }

        return try {
            val obj = if (trimmed.startsWith("[")) {
                JSONArray(trimmed).optJSONObject(0)
            } else if (trimmed.startsWith("{")) {
                JSONObject(trimmed)
            } else null

            if (obj != null) {
                Order(
                    id = obj.optString("id", obj.optString("order_id", unquoted)),
                    orderNumber = obj.optString("order_number", ""),
                    customerId = obj.optString("customer_id", ""),
                    vendorId = if (obj.has("vendor_id") && !obj.isNull("vendor_id")) obj.optString("vendor_id") else null,
                    addressId = if (obj.has("address_id") && !obj.isNull("address_id")) obj.optString("address_id") else null,
                    status = obj.optString("status", "confirmed"),
                    paymentMethod = obj.optString("payment_method", "cash"),
                    paymentStatus = obj.optString("payment_status", "pending"),
                    subtotal = obj.optDouble("subtotal", 0.0),
                    discountAmount = obj.optDouble("discount_amount", 0.0),
                    deliveryFee = obj.optDouble("delivery_fee", 0.0),
                    handlingFee = obj.optDouble("handling_fee", 0.0),
                    totalAmount = obj.optDouble("total_amount", 0.0),
                    walletUsedAmount = obj.optDouble("wallet_used_amount", 0.0),
                    cashbackAmount = obj.optDouble("cashback_amount", 0.0),
                    placedAt = if (obj.has("placed_at") && !obj.isNull("placed_at")) obj.optString("placed_at") else null
                )
            } else {
                Order(id = if (unquoted.isNotBlank()) unquoted else "ord-${System.currentTimeMillis()}")
            }
        } catch (e: Exception) {
            Order(id = if (unquoted.isNotBlank()) unquoted else "ord-${System.currentTimeMillis()}")
        }
    }

    private fun extractCleanErrorMessage(errorBody: String, httpCode: Int = 0): String {
        if (errorBody.isBlank()) {
            return if (httpCode > 0) "Server error ($httpCode). Please try again." else "Order placement failed. Please try again."
        }
        return try {
            val json = JSONObject(errorBody)
            val msg = listOf("message", "msg", "error_description", "error", "details")
                .mapNotNull { key ->
                    if (json.has(key) && !json.isNull(key)) {
                        val v = json.optString(key).trim()
                        v.takeIf { it.isNotBlank() && it != "null" }
                    } else null
                }.firstOrNull()

            if (!msg.isNullOrBlank()) msg else errorBody
        } catch (e: Exception) {
            errorBody
        }
    }

    // --- RAZORPAY PAYMENT (EDGE FUNCTIONS) ---

    suspend fun createRazorpayOrder(orderId: String): Result<RazorpayOrderResponse> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val request = CreateRazorpayOrderRequest(orderId = orderId)
            val response = api.createRazorpayOrder(request)
            if (response.isSuccessful && response.body() != null) {
                val bodyStr = response.body()!!.string()
                Log.d(TAG, "createRazorpayOrder response: $bodyStr")
                val json = JSONObject(bodyStr)
                val keyId = json.optString("key_id", json.optString("key", ""))
                val amount = json.optDouble("amount", 0.0)
                val currency = json.optString("currency", "INR")
                val rzpOrderId = json.optString("razorpay_order_id", json.optString("order_id", json.optString("id", "")))
                val error = if (json.has("error") && !json.isNull("error")) json.optString("error") else null
                val message = if (json.has("message") && !json.isNull("message")) json.optString("message") else null

                if (rzpOrderId.isNotBlank()) {
                    Result.success(
                        RazorpayOrderResponse(
                            keyId = keyId,
                            amount = amount,
                            currency = currency,
                            razorpayOrderId = rzpOrderId,
                            error = error,
                            message = message
                        )
                    )
                } else {
                    val errMsg = message ?: error ?: "Could not create payment order"
                    Result.failure(Exception(errMsg))
                }
            } else {
                val errorStr = response.errorBody()?.string() ?: ""
                val cleanMsg = extractCleanErrorMessage(errorStr, response.code())
                Log.e(TAG, "createRazorpayOrder failed HTTP ${response.code()}: $cleanMsg")
                Result.failure(Exception("Could not start payment. Please try again."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating Razorpay order: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun verifyRazorpayPayment(
        orderId: String,
        razorpayOrderId: String,
        razorpayPaymentId: String,
        razorpaySignature: String
    ): Result<VerifyRazorpayPaymentResponse> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val request = VerifyRazorpayPaymentRequest(
                orderId = orderId,
                razorpayOrderId = razorpayOrderId,
                razorpayPaymentId = razorpayPaymentId,
                razorpaySignature = razorpaySignature
            )
            val response = api.verifyRazorpayPayment(request)
            if (response.isSuccessful && response.body() != null) {
                val bodyStr = response.body()!!.string()
                Log.d(TAG, "verifyRazorpayPayment response: $bodyStr")
                val json = JSONObject(bodyStr)
                val success = json.optBoolean("success", false)
                val message = if (json.has("message") && !json.isNull("message")) json.optString("message") else null
                val error = if (json.has("error") && !json.isNull("error")) json.optString("error") else null
                Result.success(
                    VerifyRazorpayPaymentResponse(
                        success = success,
                        message = message,
                        error = error
                    )
                )
            } else {
                val errorStr = response.errorBody()?.string() ?: ""
                val cleanMsg = extractCleanErrorMessage(errorStr, response.code())
                Log.e(TAG, "verifyRazorpayPayment failed HTTP ${response.code()}: $cleanMsg")
                Result.failure(Exception(cleanMsg.ifBlank { "Payment could not be verified. If money was deducted, it will be refunded shortly - contact support if this persists." }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in verifyRazorpayPayment: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun syncRazorpayPayment(orderId: String): Result<SyncRazorpayPaymentResponse> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val request = SyncRazorpayPaymentRequest(orderId = orderId)
            val response = api.syncRazorpayPayment(request)
            if (response.isSuccessful && response.body() != null) {
                val bodyStr = response.body()!!.string()
                Log.d(TAG, "syncRazorpayPayment response: $bodyStr")
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                val success = json.optBoolean("success", true)
                val status = if (json.has("status") && !json.isNull("status")) json.optString("status") else null
                val paymentStatus = if (json.has("payment_status") && !json.isNull("payment_status")) json.optString("payment_status") else null
                val message = if (json.has("message") && !json.isNull("message")) json.optString("message") else null
                val error = if (json.has("error") && !json.isNull("error")) json.optString("error") else null
                Result.success(
                    SyncRazorpayPaymentResponse(
                        success = success,
                        status = status,
                        paymentStatus = paymentStatus,
                        message = message,
                        error = error
                    )
                )
            } else {
                val errorStr = response.errorBody()?.string() ?: ""
                val cleanMsg = extractCleanErrorMessage(errorStr, response.code())
                Log.e(TAG, "syncRazorpayPayment failed HTTP ${response.code()}: $cleanMsg")
                Result.failure(Exception(cleanMsg.ifBlank { "Failed to sync payment status" }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in syncRazorpayPayment: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getOrderCancellation(orderId: String): Result<OrderCancellation?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(null)
        }
        return try {
            val res = api.getOrderCancellation(orderId = "eq.$orderId")
            if (res.isSuccessful) {
                Result.success(res.body()?.firstOrNull())
            } else {
                Result.failure(Exception("Failed to fetch cancellation details"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPendingUpiOrders(userId: String): Result<List<Order>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(emptyList())
        }
        return try {
            val response = api.getOrders(customerId = "eq.$userId", order = "created_at.desc", limit = 10)
            if (response.isSuccessful && response.body() != null) {
                val orders = response.body()!!.filter {
                    it.paymentMethod.lowercase() == "upi" &&
                    (it.paymentStatus.lowercase() == "pending" || it.paymentStatus.lowercase() == "failed") &&
                    it.status.lowercase() == "pending"
                }
                Result.success(orders)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- ORDERS (LIST & DETAIL) ---
    suspend fun getOrders(userId: String, limit: Int? = null, offset: Int? = null): Result<List<Order>> {
        return try {
            val response = api.getOrders(customerId = "eq.$userId", order = "placed_at.desc", limit = limit, offset = offset)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else if (response.code() == 401) {
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Your session expired, please log in again"))
            } else if (response.code() == 400) {
                // If placed_at column is not recognized, fallback to created_at.desc
                val fallback = api.getOrders(customerId = "eq.$userId", order = "created_at.desc", limit = limit, offset = offset)
                if (fallback.isSuccessful && fallback.body() != null) {
                    Result.success(fallback.body()!!)
                } else if (fallback.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    Result.failure(SessionExpiredException("Your session expired, please log in again"))
                } else {
                    Result.failure(Exception("Failed to fetch orders: ${fallback.code()} ${fallback.message()}"))
                }
            } else {
                Result.failure(Exception("Failed to fetch orders: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMyOrders(userId: String, limit: Int? = null, offset: Int? = null): Result<List<Order>> =
        getOrders(userId, limit, offset)

    suspend fun getCompletedOrderCount(userId: String): Result<Int> {
        return try {
            val response = api.getOrders(customerId = "eq.$userId", status = "eq.delivered")
            if (response.isSuccessful) {
                Result.success(response.body()?.size ?: 0)
            } else {
                Result.failure(Exception("Failed to load order count: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrderById(orderId: String): Result<Order> {
        return try {
            val selectJoin = "*,delivery_partners(id,name,phone,latitude,longitude,vehicle_type,vehicle_number)"
            val response = api.getOrderById(idQuery = "eq.$orderId", select = selectJoin)
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else if (response.code() == 401) {
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Your session expired, please log in again"))
            } else {
                // Fallback without join
                val fallback = api.getOrderById(idQuery = "eq.$orderId")
                if (fallback.isSuccessful && !fallback.body().isNullOrEmpty()) {
                    Result.success(fallback.body()!!.first())
                } else if (fallback.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    Result.failure(SessionExpiredException("Your session expired, please log in again"))
                } else {
                    Result.failure(Exception("Order not found: $orderId"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrderItems(orderId: String): Result<List<OrderItem>> {
        return try {
            val response = api.getOrderItems(orderId = "eq.$orderId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrderStatusHistory(orderId: String): Result<List<OrderStatusHistory>> {
        return try {
            val response = api.getOrderStatusHistory(orderId = "eq.$orderId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeliveryAssignment(orderId: String): Result<DeliveryAssignment?> {
        return try {
            val selectFields = "status,accepted_at,estimated_delivery_minutes,estimated_delivery_at,delivery_otp,delivery_partner_id,order_id"
            val response = api.getDeliveryAssignment(
                orderId = "eq.$orderId",
                order = "created_at.desc",
                limit = 1,
                select = selectFields
            )
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else {
                val fallback = api.getDeliveryAssignment(orderId = "eq.$orderId", order = "created_at.desc", limit = 1)
                if (fallback.isSuccessful && !fallback.body().isNullOrEmpty()) {
                    Result.success(fallback.body()!!.first())
                } else {
                    Result.success(null)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeliveryPartner(partnerId: String): Result<DeliveryPartner?> {
        return try {
            val response = api.getDeliveryPartner(partnerId = "eq.$partnerId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.firstOrNull())
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Reorder: copies order_items back into cart_items for this user (re-check is_active/is_available first,
    // skip unavailable items with a summary message; apply single-hotel-cart rule if relevant)
    // Rule 3 & 4: Batch fetch products and city stock overrides in parallel instead of looping
    suspend fun reorder(orderItems: List<OrderItem>, cityId: String): Result<String> {
        return try {
            val productIds = orderItems.map { it.productId }.filter { it.isNotBlank() }.distinct()
            if (productIds.isEmpty()) {
                return Result.success("No items to reorder.")
            }

            val (productsMap, stockMap) = kotlinx.coroutines.coroutineScope {
                val prodDeferred = async {
                    api.getProductsByIds(
                        idInQuery = "in.(${productIds.joinToString(",")})",
                        select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured"
                    )
                }
                val stockDeferred = async {
                    api.getProductCityStockBatch(
                        cityId = "eq.$cityId",
                        productIdsQuery = "in.(${productIds.joinToString(",")})",
                        select = "product_id,price,mrp,stock_qty,is_available"
                    )
                }
                val pRes = prodDeferred.await()
                val sRes = stockDeferred.await()
                val pMap = if (pRes.isSuccessful && pRes.body() != null) {
                    pRes.body()!!.associateBy { it.id }
                } else emptyMap()
                val sMap = if (sRes.isSuccessful && sRes.body() != null) {
                    sRes.body()!!.associateBy { it.productId }
                } else emptyMap()
                Pair(pMap, sMap)
            }

            var addedCount = 0
            val skipped = mutableListOf<String>()

            for (item in orderItems) {
                val prod = productsMap[item.productId]
                if (prod != null) {
                    val override = stockMap[prod.id]
                    val isGrocery = prod.vendorId == null
                    val isAvail = if (isGrocery) (override?.isAvailable == true) else (override?.isAvailable ?: prod.isAvailable)
                    if (prod.isActive && isAvail) {
                        val isHotel = item.vendorId != null
                        val result = addToCart(
                            productId = item.productId,
                            vendorId = item.vendorId,
                            cityId = cityId,
                            quantityDelta = item.quantity,
                            isHotel = isHotel
                        )
                        if (result is AddToCartResult.HotelConflict) {
                            skipped.add("'${item.productName}' conflicts with your current hotel cart.")
                        } else {
                            addedCount++
                        }
                    } else {
                        skipped.add("'${item.productName}' is no longer available.")
                    }
                } else {
                    skipped.add("'${item.productName}' is no longer available.")
                }
            }

            val total = orderItems.size
            val msg = buildString {
                append("$addedCount of $total items added to cart.")
                if (skipped.isNotEmpty()) {
                    append(" ${skipped.joinToString(" ")}")
                }
            }
            Result.success(msg)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- REVIEWS ---
    suspend fun submitVendorReview(review: VendorReview): Result<Unit> {
        return try {
            val response = api.submitVendorReview(review)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitDeliveryPartnerReview(review: DeliveryPartnerReview): Result<Unit> {
        return try {
            val response = api.submitDeliveryPartnerReview(review)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Reviews the customer has written (read-only lists for "My Reviews").
    // Rule 1: Paginate by 20 at a time.
    suspend fun getMyVendorReviews(customerId: String, limit: Int = 20, offset: Int = 0): Result<List<VendorReview>> {
        return try {
            val response = api.getMyReviews(customerId = "eq.$customerId", limit = limit, offset = offset)
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMyDeliveryPartnerReviews(customerId: String, limit: Int = 20, offset: Int = 0): Result<List<DeliveryPartnerReview>> {
        return try {
            val response = api.getMyDeliveryPartnerReviews(customerId = "eq.$customerId", limit = limit, offset = offset)
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Order ids the customer has already reviewed (vendor + delivery partner), used to
    // decide whether to show the "Rate this order" prompt on a delivered order.
    suspend fun getReviewedOrderIds(customerId: String): Result<Set<String>> {
        return try {
            val ids = mutableSetOf<String>()
            getMyVendorReviews(customerId).getOrNull()?.forEach { it.orderId?.let { id -> ids.add(id) } }
            getMyDeliveryPartnerReviews(customerId).getOrNull()?.forEach { it.orderId?.let { id -> ids.add(id) } }
            Result.success(ids)
        } catch (e: Exception) {
            Result.success(emptySet())
        }
    }

    // --- SNDMART WALLET & CASHBACK (server-calculated, read-only for the app) ---

    private fun jsonBody(obj: JSONObject) =
        obj.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

    private fun JSONObject.doubleOr(key: String, def: Double = 0.0): Double =
        if (has(key) && !isNull(key)) optDouble(key, def) else def

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() && it != "null" } else null

    /** Coupon + wallet preview for the checkout bill (call debounced). */
    suspend fun previewCheckoutRewards(
        subtotal: Double,
        couponCode: String?,
        payableBeforeWallet: Double
    ): Result<RewardsPreview> {
        if (!SupabaseClient.isKeyConfigured()) return Result.failure(Exception("Service not configured"))
        return try {
            val body = JSONObject().apply {
                put("p_subtotal", Math.round(subtotal.coerceAtLeast(0.0) * 100) / 100.0)
                val code = couponCode?.trim()?.uppercase()
                put("p_coupon_code", if (code.isNullOrBlank()) JSONObject.NULL else code)
                put("p_payable_before_wallet", Math.round(payableBeforeWallet.coerceAtLeast(0.0) * 100) / 100.0)
            }
            val res = api.previewCheckoutRewards(jsonBody(body))
            if (!res.isSuccessful || res.body() == null) {
                return Result.failure(Exception(SupabaseClient.parseErrorMessage(res)))
            }
            val obj = JSONObject(res.body()!!.string())
            val c = obj.optJSONObject("coupon")
            val w = obj.optJSONObject("wallet") ?: JSONObject()
            val coupon = c?.let {
                CouponPreview(
                    valid = it.optBoolean("valid", false),
                    code = it.optString("code", ""),
                    rewardType = it.stringOrNull("reward_type"),
                    discount = it.doubleOr("discount"),
                    cashback = it.doubleOr("cashback"),
                    cashbackValidDays = if (it.has("cashback_valid_days") && !it.isNull("cashback_valid_days")) it.optInt("cashback_valid_days") else null,
                    message = it.stringOrNull("message"),
                    error = it.stringOrNull("error")
                )
            }
            Result.success(
                RewardsPreview(
                    coupon = coupon,
                    wallet = WalletUsable(
                        enabled = w.optBoolean("enabled", false),
                        balance = w.doubleOr("balance"),
                        usable = w.doubleOr("usable"),
                        maxUsePercent = w.doubleOr("max_use_percent"),
                        minOrder = w.doubleOr("min_order"),
                        reason = w.stringOrNull("reason")
                    )
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    // --- REFER & EARN ---

    data class ReferralStats(
        val referralCode: String?,
        val totalReferred: Int,
        val totalRewarded: Int,
        val totalEarned: Double,
        // This city's current reward amounts (City-Admin-editable).
        val referrerReward: Double = 50.0,
        val friendReward: Double = 30.0,
        val minOrderAmount: Double = 99.0
    )

    /** The caller's referral code + how many friends they've earned from. Creates a code if missing. */
    suspend fun getMyReferralStats(): Result<ReferralStats> {
        if (!SupabaseClient.isKeyConfigured()) return Result.failure(Exception("Service not configured"))
        return try {
            val res = api.getMyReferralStats(emptyMap())
            if (!res.isSuccessful || res.body().isNullOrEmpty()) {
                return Result.failure(Exception(SupabaseClient.parseErrorMessage(res)))
            }
            val row = res.body()!!.first()
            var code = row["referral_code"] as? String
            if (code.isNullOrBlank()) {
                code = getMyReferralCode().getOrNull()
            }
            Result.success(
                ReferralStats(
                    referralCode = code,
                    totalReferred = (row["total_referred"] as? Number)?.toInt() ?: 0,
                    totalRewarded = (row["total_rewarded"] as? Number)?.toInt() ?: 0,
                    totalEarned = (row["total_earned"] as? Number)?.toDouble() ?: 0.0,
                    referrerReward = (row["referrer_reward"] as? Number)?.toDouble() ?: 50.0,
                    friendReward = (row["friend_reward"] as? Number)?.toDouble() ?: 30.0,
                    minOrderAmount = (row["min_order_amount"] as? Number)?.toDouble() ?: 99.0
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /** Ensures the caller has a referral code, generating one on first call. */
    suspend fun getMyReferralCode(): Result<String> {
        if (!SupabaseClient.isKeyConfigured()) return Result.failure(Exception("Service not configured"))
        return try {
            val res = api.getMyReferralCode(emptyMap())
            if (!res.isSuccessful || res.body() == null) {
                return Result.failure(Exception(SupabaseClient.parseErrorMessage(res)))
            }
            Result.success(res.body()!!.string().trim('"'))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /** Applies a friend's referral code to the caller's own account (one-time, before first order). */
    suspend fun applyReferralCode(code: String): Result<Unit> {
        if (!SupabaseClient.isKeyConfigured()) return Result.failure(Exception("Service not configured"))
        return try {
            val res = api.applyReferralCode(mapOf("p_code" to code))
            if (!res.isSuccessful) {
                return Result.failure(Exception(SupabaseClient.parseErrorMessage(res)))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    @Volatile private var cachedWallet: Pair<Long, MyWallet>? = null

    /** The customer's wallet summary (cached for a minute; force = true on the Wallet screen). */
    suspend fun getMyWallet(force: Boolean = false): Result<MyWallet> {
        if (!SupabaseClient.isKeyConfigured()) return Result.failure(Exception("Service not configured"))
        val hit = cachedWallet
        if (!force && hit != null && System.currentTimeMillis() - hit.first < 60_000L) return Result.success(hit.second)
        return try {
            val res = api.getMyWallet(jsonBody(JSONObject()))
            if (!res.isSuccessful || res.body() == null) {
                return Result.failure(Exception(SupabaseClient.parseErrorMessage(res)))
            }
            val w = JSONObject(res.body()!!.string())
            val soon = w.optJSONObject("expiring_soon")
            val lots = mutableListOf<WalletLot>()
            w.optJSONArray("lots")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val l = arr.optJSONObject(i) ?: continue
                    lots.add(WalletLot(amount = l.doubleOr("amount"), expiresAt = l.stringOrNull("expires_at")))
                }
            }
            val pending = mutableListOf<WalletPendingCashback>()
            w.optJSONArray("pending")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    pending.add(
                        WalletPendingCashback(
                            amount = p.doubleOr("amount"),
                            orderId = p.stringOrNull("order_id"),
                            orderNumber = p.stringOrNull("order_number"),
                            createdAt = p.stringOrNull("created_at")
                        )
                    )
                }
            }
            val wallet = MyWallet(
                enabled = w.optBoolean("enabled", false),
                balance = w.doubleOr("balance"),
                pendingCashback = w.doubleOr("pending_cashback"),
                totalCashbackEarned = w.doubleOr("total_cashback_earned"),
                totalUsed = w.doubleOr("total_used"),
                maxUsePercent = w.doubleOr("max_use_percent"),
                minOrder = w.doubleOr("min_order"),
                expiringSoonAmount = soon?.doubleOr("amount") ?: 0.0,
                expiringSoonFirstExpiry = soon?.stringOrNull("first_expiry"),
                lots = lots,
                pending = pending
            )
            cachedWallet = System.currentTimeMillis() to wallet
            Result.success(wallet)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /** Wallet history, newest first; pass the last row's createdAt for the next page. */
    suspend fun getMyWalletHistory(limit: Int = 30, before: String? = null): Result<List<WalletLedgerEntry>> {
        if (!SupabaseClient.isKeyConfigured()) return Result.failure(Exception("Service not configured"))
        return try {
            val body = JSONObject().apply {
                put("p_limit", limit)
                put("p_before", before ?: JSONObject.NULL)
            }
            val res = api.getMyWalletHistory(jsonBody(body))
            if (!res.isSuccessful || res.body() == null) {
                return Result.failure(Exception(SupabaseClient.parseErrorMessage(res)))
            }
            val arr = JSONArray(res.body()!!.string())
            val out = mutableListOf<WalletLedgerEntry>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                out.add(
                    WalletLedgerEntry(
                        id = e.optString("id", "$i"),
                        entryType = e.optString("entry_type", ""),
                        amount = e.doubleOr("amount"),
                        note = e.stringOrNull("note"),
                        orderId = e.stringOrNull("order_id"),
                        orderNumber = e.stringOrNull("order_number"),
                        createdAt = e.optString("created_at", "")
                    )
                )
            }
            Result.success(out)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    // --- PROMO BANNERS (set by the Super Admin) ---

    private val bannerCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<PromoBanner>>>()

    /** Last loaded banners for this city/tab (shown at once while fresh ones load). */
    fun getCachedPromoBanners(cityId: String?, placement: String): List<PromoBanner>? =
        cityId?.let { bannerCache["$it:$placement"]?.second?.let(::liveBanners) }

    private fun liveBanners(list: List<PromoBanner>): List<PromoBanner> {
        val now = System.currentTimeMillis()
        return list.filter { b ->
            val starts = parseIsoTime(b.startsAt)
            val ends = parseIsoTime(b.endsAt)
            (starts == null || starts <= now) && (ends == null || ends > now) && b.imageUrl.startsWith("https://")
        }
    }

    /**
     * Banners for this city (or all cities) on the grocery home ("grocery") or food tab ("food").
     * The database only returns banners that are switched on and inside their time window.
     * Cached for 5 minutes. Any error (e.g. no banners table yet) = no banners.
     */
    suspend fun getPromoBanners(cityId: String, placement: String, force: Boolean = false): List<PromoBanner> {
        val key = "$cityId:$placement"
        val hit = bannerCache[key]
        if (!force && hit != null && System.currentTimeMillis() - hit.first < 5 * 60_000L) return liveBanners(hit.second)
        if (!SupabaseClient.isKeyConfigured()) return emptyList()
        return try {
            val res = api.getPromoBanners(
                placementIn = "in.($placement,both)",
                cityFilter = "(city_id.is.null,city_id.eq.$cityId)"
            )
            if (res.isSuccessful && res.body() != null) {
                val list = res.body()!!
                bannerCache[key] = System.currentTimeMillis() to list
                liveBanners(list)
            } else {
                hit?.second?.let(::liveBanners) ?: emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            hit?.second?.let(::liveBanners) ?: emptyList()
        }
    }

    // --- LOCAL ADS (paid banners from local businesses, set per-city by City Admin) ---

    private val localAdCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<LocalAd>>>()

    /** Last loaded local ads for this city/placement (shown at once while fresh ones load). */
    fun getCachedLocalAds(cityId: String?, placement: String): List<LocalAd>? =
        cityId?.let { localAdCache["$it:$placement"]?.second?.let(::liveLocalAds) }

    private fun liveLocalAds(list: List<LocalAd>): List<LocalAd> {
        val now = System.currentTimeMillis()
        return list.filter { a ->
            val starts = parseIsoTime(a.startsAt)
            val ends = parseIsoTime(a.endsAt)
            (starts == null || starts <= now) && (ends == null || ends > now) && a.imageUrl.startsWith("https://")
        }
    }

    /**
     * Paid local-business ads for a placement ('home' top banner, or the bottom of the food /
     * grocery lists — "food_bottom" / "grocery_bottom"). Cached for 5 minutes.
     */
    suspend fun getLocalAds(cityId: String, placement: String, force: Boolean = false): List<LocalAd> {
        val key = "$cityId:$placement"
        val hit = localAdCache[key]
        if (!force && hit != null && System.currentTimeMillis() - hit.first < 5 * 60_000L) return liveLocalAds(hit.second)
        if (!SupabaseClient.isKeyConfigured()) return emptyList()
        return try {
            val res = api.getLocalAds(cityIdEq = "eq.$cityId", placementIn = "in.($placement,all)")
            if (res.isSuccessful && res.body() != null) {
                val list = res.body()!!
                localAdCache[key] = System.currentTimeMillis() to list
                liveLocalAds(list)
            } else {
                hit?.second?.let(::liveLocalAds) ?: emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            hit?.second?.let(::liveLocalAds) ?: emptyList()
        }
    }

    // --- WALLET (READ-ONLY) ---
    // Rule 1: Paginate by 20 at a time.
    suspend fun getWalletTransactions(customerId: String, limit: Int = 20, offset: Int = 0): Result<List<CustomerWalletTransaction>> {
        return try {
            val response = api.getWalletTransactions(customerId = "eq.$customerId", limit = limit, offset = offset)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWalletBalance(customerId: String): Result<Double> {
        return try {
            val res = api.getWalletAmountsForBalance(customerId = "eq.$customerId")
            if (res.isSuccessful && res.body() != null) {
                val balance = res.body()!!.fold(0.0) { acc, t ->
                    if (t.type.lowercase() == "credit") acc + t.amount else acc - t.amount
                }
                Result.success(balance.coerceAtLeast(0.0))
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(res)))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    // --- DEVICE TOKENS (PUSH NOTIFICATIONS) ---
    suspend fun registerCustomerFcmToken(token: String): Result<Unit> {
        val userId = sessionManager.userId.value ?: sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            Log.d(TAG, "registerCustomerFcmToken: No authenticated user present, skipping")
            return Result.failure(IllegalStateException("No current user logged in"))
        }
        return registerDeviceToken(userId, token)
    }

    suspend fun registerDeviceToken(userId: String, token: String): Result<Unit> {
        return try {
            val deviceToken = DeviceToken(
                userId = userId,
                userType = "customer",
                fcmToken = token,
                active = true,
                firebaseProject = "native" // CRITICAL - must be exactly native
            )
            val response = api.registerDeviceToken(deviceToken)
            if (response.isSuccessful) {
                Log.d(TAG, "Device token registered successfully for $userId with firebase_project=native")
                Result.success(Unit)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "Failed to register device token: $error")
                // Carries the HTTP code (ApiException.code) so callers like PushTokenManager can
                // special-case 401 (expired session) without parsing the error text.
                Result.failure(ApiException(response.code(), error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception registering device token", e)
            Result.failure(e)
        }
    }

    /** Reports why push registration failed on this device (best effort, never throws). */
    suspend fun reportPushRegistrationError(stage: String, message: String) {
        try {
            val res = api.reportPushRegistrationError(
                mapOf(
                    "stage" to stage.take(100),
                    "message" to message.take(2000),
                    "app_version" to com.example.BuildConfig.VERSION_NAME,
                    "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}".take(200)
                )
            )
            if (!res.isSuccessful) Log.w(TAG, "push error report returned ${res.code()}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "push error report failed: ${e.message}")
        }
    }

    /** Marks this customer's device_tokens row inactive so no more pushes go to it. Call this
     * BEFORE clearing the session on logout - it needs the user id to target the row. */
    suspend fun deactivateDeviceToken(userId: String): Result<Unit> {
        if (userId.isBlank()) return Result.success(Unit)
        return try {
            val response = api.deactivateDeviceToken(userIdQuery = "eq.$userId")
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(ApiException(response.code(), SupabaseClient.parseErrorMessage(response)))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Exception deactivating device token: ${e.message}")
            Result.failure(e)
        }
    }

    // --- CUSTOMER NOTIFICATIONS INBOX ---
    // Rule 1: Paginate by 20 at a time.
    suspend fun getCustomerNotifications(userId: String, limit: Int = 20, offset: Int = 0): Result<List<CustomerNotification>> {
        return try {
            val response = api.getCustomerNotifications(userIdQuery = "eq.$userId", limit = limit, offset = offset)
            if (response.isSuccessful) {
                val list = response.body() ?: emptyList()
                Result.success(list)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "Failed to get customer notifications: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting customer notifications", e)
            Result.failure(e)
        }
    }

    suspend fun markNotificationAsRead(notificationId: String): Result<Unit> {
        return try {
            val response = api.markNotificationAsRead(idQuery = "eq.$notificationId")
            if (response.isSuccessful) {
                sessionManager.decrementUnreadNotificationCount()
                Result.success(Unit)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception marking notification as read", e)
            Result.failure(e)
        }
    }

    suspend fun markAllNotificationsAsRead(userId: String): Result<Unit> {
        return try {
            val response = api.markAllNotificationsAsRead(userIdQuery = "eq.$userId")
            if (response.isSuccessful) {
                sessionManager.setUnreadNotificationCount(0)
                Result.success(Unit)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception marking all notifications as read", e)
            Result.failure(e)
        }
    }

    suspend fun refreshUnreadNotificationCount(userId: String) {
        try {
            val res = api.countUnreadNotifications(userId = "eq.$userId")
            val total = res.headers()["Content-Range"]?.substringAfter("/")?.toIntOrNull()
            if (res.isSuccessful && total != null) {
                sessionManager.setUnreadNotificationCount(total)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Unread count refresh failed: ${e.message}")
        }
    }

    // --- STARTUP CHECKS: APP VERSION & MAINTENANCE MODE ---

    fun parseMaintenanceSettings(jsonString: String?): MaintenanceSettings {
        if (jsonString.isNullOrBlank()) return MaintenanceSettings(enabled = false)
        return try {
            val trimmed = jsonString.trim()
            val jsonArray = JSONArray(trimmed)
            if (jsonArray.length() == 0) return MaintenanceSettings(enabled = false)
            val firstObj = jsonArray.getJSONObject(0)
            if (!firstObj.has("value")) return MaintenanceSettings(enabled = false)

            val valueObj = firstObj.optJSONObject("value")
            if (valueObj != null) {
                val enabled = valueObj.optBoolean("enabled", false)
                val message = if (valueObj.has("message") && !valueObj.isNull("message")) {
                    valueObj.optString("message").takeIf { it.isNotBlank() && it != "null" }
                } else null
                MaintenanceSettings(enabled = enabled, message = message)
            } else {
                val boolVal = firstObj.optBoolean("value", false)
                MaintenanceSettings(enabled = boolVal)
            }
        } catch (e: Exception) {
            MaintenanceSettings(enabled = false)
        }
    }

    suspend fun checkMaintenanceMode(): MaintenanceSettings {
        if (!SupabaseClient.isKeyConfigured()) {
            return MaintenanceSettings(enabled = false)
        }
        return try {
            val response = api.getAppSetting(key = "eq.maintenance_mode", limit = 1)
            if (response.isSuccessful && response.body() != null) {
                val str = response.body()!!.string()
                parseMaintenanceSettings(str)
            } else {
                MaintenanceSettings(enabled = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkMaintenanceMode error: ${e.message}")
            MaintenanceSettings(enabled = false)
        }
    }

    // --- MAP PROVIDER (dual Google/OSM) SETTINGS ---

    fun parseMapProviderSetting(jsonString: String?): com.example.data.maps.RemoteMapProviderFlag {
        if (jsonString.isNullOrBlank()) return com.example.data.maps.RemoteMapProviderFlag.AUTO
        return try {
            val jsonArray = JSONArray(jsonString.trim())
            if (jsonArray.length() == 0) return com.example.data.maps.RemoteMapProviderFlag.AUTO
            val firstObj = jsonArray.getJSONObject(0)
            if (!firstObj.has("value")) return com.example.data.maps.RemoteMapProviderFlag.AUTO
            val raw: String? = when (val v = firstObj.opt("value")) {
                is String -> v
                is JSONObject -> v.optString("provider").takeIf { it.isNotBlank() }
                    ?: v.optString("value").takeIf { it.isNotBlank() }
                null, JSONObject.NULL -> null
                else -> v.toString()
            }
            when (raw?.trim()?.lowercase()) {
                "google" -> com.example.data.maps.RemoteMapProviderFlag.GOOGLE
                "osm", "openstreetmap" -> com.example.data.maps.RemoteMapProviderFlag.OSM
                else -> com.example.data.maps.RemoteMapProviderFlag.AUTO
            }
        } catch (e: Exception) {
            com.example.data.maps.RemoteMapProviderFlag.AUTO
        }
    }

    suspend fun getMapProviderRemoteFlag(): com.example.data.maps.RemoteMapProviderFlag {
        if (!SupabaseClient.isKeyConfigured()) return com.example.data.maps.RemoteMapProviderFlag.AUTO
        return try {
            val response = api.getAppSetting(key = "eq.map_provider", limit = 1)
            if (response.isSuccessful && response.body() != null) {
                parseMapProviderSetting(response.body()!!.string())
            } else {
                com.example.data.maps.RemoteMapProviderFlag.AUTO
            }
        } catch (e: Exception) {
            Log.w(TAG, "getMapProviderRemoteFlag error: ${e.message}")
            com.example.data.maps.RemoteMapProviderFlag.AUTO
        }
    }

    /** Generic single-string app_setting reader, e.g. for `osm_tile_url` / `osm_satellite_tile_url`. */
    fun parseAppSettingStringValue(jsonString: String?): String? {
        if (jsonString.isNullOrBlank()) return null
        return try {
            val jsonArray = JSONArray(jsonString.trim())
            if (jsonArray.length() == 0) return null
            val firstObj = jsonArray.getJSONObject(0)
            if (!firstObj.has("value")) return null
            when (val v = firstObj.opt("value")) {
                is String -> v.takeIf { it.isNotBlank() }
                is JSONObject -> v.optString("url").takeIf { it.isNotBlank() }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getAppSettingStringValue(key: String): String? {
        if (!SupabaseClient.isKeyConfigured()) return null
        return try {
            val response = api.getAppSetting(key = "eq.$key", limit = 1)
            if (response.isSuccessful && response.body() != null) {
                parseAppSettingStringValue(response.body()!!.string())
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "getAppSettingStringValue($key) error: ${e.message}")
            null
        }
    }

    suspend fun runStartupChecks(): StartupCheckResult {
        if (!SupabaseClient.isKeyConfigured()) {
            return StartupCheckResult.Passed
        }

        // 1. App version check
        try {
            val versionResponse = api.getAppVersionInfo(platform = "eq.customer_app", limit = 1)
            if (versionResponse.isSuccessful) {
                val versionInfo = versionResponse.body()?.firstOrNull()
                if (versionInfo != null) {
                    val minVersion = versionInfo.minimumSupportedVersion
                    val isBelowMinimum = !minVersion.isNullOrBlank() &&
                            VersionUtils.compareVersions(VersionUtils.CURRENT_APP_VERSION, minVersion) < 0
                    val isForceUpdate = versionInfo.forceUpdate
                    if (isBelowMinimum) {
                        val msg = versionInfo.updateMessage
                            ?: "A new version of Sndmart is available. Please update the app to continue."
                        return StartupCheckResult.BlockingUpdate(
                            updateMessage = msg
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Version check failed to query backend: ${e.message}")
        }

        // 2. Maintenance mode check
        try {
            val maintenance = checkMaintenanceMode()
            if (maintenance.enabled) {
                val msg = maintenance.message ?: "Service temporarily unavailable. Please try again shortly."
                return StartupCheckResult.Maintenance(msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Maintenance check failed: ${e.message}")
        }

        return StartupCheckResult.Passed
    }
}

fun calculateHaversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0 // Earth's radius in kilometers
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return r * c
}
