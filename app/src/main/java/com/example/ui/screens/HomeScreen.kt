package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.BuildConfig
import com.example.data.analytics.Analytics
import com.example.data.model.*
import com.example.data.repository.AddToCartResult
import com.example.data.repository.SndmartRepository
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.isVendorWithinOperatingHours
import com.example.util.isWithinAnySlot
import com.example.util.pickRotatingFoodCategories
import com.example.util.vendorIdsForCategoryName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class BrowsingMode {
    GROCERY,
    HOTELS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: SndmartRepository,
    selectedCity: City?,
    deliveryAddressLabel: String? = null,
    onCityChangeRequested: () -> Unit,
    onNavigateToCart: () -> Unit,
    onNavigateToHotelMenu: (vendorId: String, vendorName: String) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onNavigateToNotifications: (() -> Unit)? = null,
    onProceedToCheckout: (isHotel: Boolean) -> Unit = {},
    onNavigateToWallet: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val cityTheme = rememberCityTheme(repository, selectedCity?.id)
    val homeSections = rememberHomeSections(repository, selectedCity?.id)
    // Rotating example in the search box (hint only; typing searches everything as before)
    var searchHintIndex by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2600)
            searchHintIndex += 1
        }
    }
    val unreadNotificationCount by repository.sessionManager.unreadNotificationCount.collectAsState()

    // Launch Mode (set by City Admin): a new city can go live Food-only or Grocery-only
    // before its other catalog is ready — the home tab defaults accordingly, re-derived
    // whenever the selected city changes.
    val serviceMode = selectedCity?.serviceMode ?: "both"
    // City Admin's Operating Hours / Emergency Close — caps grocery & hotel ordering city-wide.
    val cityIsOpen = com.example.util.isCityOpenNow(selectedCity)
    var browsingMode by rememberSaveable(selectedCity?.id) {
        mutableStateOf(if (serviceMode == "food_only") BrowsingMode.HOTELS else BrowsingMode.GROCERY)
    }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    // Sndmart Wallet balance for the top-bar chip (cached for a minute in the repository).
    var walletBalance by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        if (!repository.sessionManager.getUserId().isNullOrBlank()) {
            walletBalance = repository.getMyWallet().getOrNull()?.balance
        }
    }

    // Scratch-card rewards credited on delivery but not yet revealed — a customer who doesn't
    // revisit that order's detail page would otherwise never find out. Checked once per Home
    // load (logged-in only) and shown as a persistent banner until scratched.
    var pendingScratchCards by remember {
        mutableStateOf<List<SndmartRepository.PendingScratchCard>>(emptyList())
    }
    var revealingScratchCard by remember {
        mutableStateOf<SndmartRepository.PendingScratchCard?>(null)
    }
    LaunchedEffect(Unit) {
        if (!repository.sessionManager.getUserId().isNullOrBlank()) {
            pendingScratchCards = repository.getMyPendingScratchCards().getOrNull() ?: emptyList()
        }
    }

    // Super-Admin promo banners for the tab being shown (last loaded ones appear at once).
    val bannerPlacement = if (browsingMode == BrowsingMode.HOTELS) "food" else "grocery"
    var promoBanners by remember(selectedCity?.id, bannerPlacement) {
        mutableStateOf(repository.getCachedPromoBanners(selectedCity?.id, bannerPlacement) ?: emptyList())
    }
    var bannerRefreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(selectedCity?.id, bannerPlacement, bannerRefreshTick) {
        val cid = selectedCity?.id ?: return@LaunchedEffect
        promoBanners = repository.getPromoBanners(cid, bannerPlacement, force = bannerRefreshTick > 0)
    }
    // Local-business paid ads: 'home' banner + bottom-of-list strip for each tab.
    var homeAds by remember(selectedCity?.id) {
        mutableStateOf(repository.getCachedLocalAds(selectedCity?.id, "home") ?: emptyList())
    }
    LaunchedEffect(selectedCity?.id) {
        val cid = selectedCity?.id ?: return@LaunchedEffect
        homeAds = repository.getLocalAds(cid, "home")
    }
    val bottomAdPlacement = if (browsingMode == BrowsingMode.HOTELS) "food_bottom" else "grocery_bottom"
    var bottomAds by remember(selectedCity?.id, bottomAdPlacement) {
        mutableStateOf(repository.getCachedLocalAds(selectedCity?.id, bottomAdPlacement) ?: emptyList())
    }
    LaunchedEffect(selectedCity?.id, bottomAdPlacement) {
        val cid = selectedCity?.id ?: return@LaunchedEffect
        bottomAds = repository.getLocalAds(cid, bottomAdPlacement)
    }

    var searchQuery by remember { mutableStateOf("") }

    // Intercept back navigation when on the Hotels tab to return to the Home screen (Grocery view)
    // — only when Grocery is actually available in this city.
    BackHandler(enabled = browsingMode == BrowsingMode.HOTELS && serviceMode != "food_only") {
        browsingMode = BrowsingMode.GROCERY
    }

    val GROCERY_PAGE_SIZE = 30
    val HOTEL_PAGE_SIZE = 20

    // Smart in-memory cached initialization for categories
    var categories by remember {
        mutableStateOf(repository.getCachedGroceryCategories() ?: emptyList())
    }
    var selectedCategoryId by remember {
        mutableStateOf(
            categories.firstOrNull { it.name.lowercase().contains("veg") }?.id
                ?: categories.firstOrNull()?.id
        )
    }
    var groceryProducts by remember {
        mutableStateOf(
            repository.getCachedGroceryProducts(selectedCity?.id, selectedCategoryId, searchQuery) ?: emptyList()
        )
    }
    var isGroceryLoading by remember { mutableStateOf(groceryProducts.isEmpty()) }
    // "Most Selling" grocery row (by units sold in this city).
    var topSelling by remember(selectedCity?.id) { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    LaunchedEffect(selectedCity?.id) {
        val cid = selectedCity?.id ?: return@LaunchedEffect
        topSelling = repository.getTopSellingGroceryProducts(cid).getOrNull() ?: emptyList()
    }
    // Featured grocery row + the city's promo coupon, loaded outside this (already very large) function.
    val homeExtras = rememberHomeExtras(repository, selectedCity?.id)
    // "Most Ordered" food row (hotel dishes by units sold in this city).
    var topFood by remember(selectedCity?.id) { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    LaunchedEffect(selectedCity?.id) {
        val cid = selectedCity?.id ?: return@LaunchedEffect
        topFood = repository.getTopSellingFoodProducts(cid).getOrNull() ?: emptyList()
    }
    // "99 Store" row (hotel dishes priced ₹99 or under, across the city).
    var budgetFood by remember(selectedCity?.id) { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    LaunchedEffect(selectedCity?.id) {
        val cid = selectedCity?.id ?: return@LaunchedEffect
        budgetFood = repository.getBudgetFoodProducts(cid).getOrNull() ?: emptyList()
    }
    // "Recently Viewed" rows — real product_view history for this logged-in customer only;
    // a signed-out visitor has no server-side history, so this stays empty for them.
    var recentGrocery by remember(selectedCity?.id) { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    var recentFood by remember(selectedCity?.id) { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    LaunchedEffect(selectedCity?.id) {
        val cid = selectedCity?.id ?: return@LaunchedEffect
        val uid = repository.sessionManager.getUserId()
        if (uid.isNullOrBlank()) {
            recentGrocery = emptyList()
            recentFood = emptyList()
            return@LaunchedEffect
        }
        val recent = repository.getRecentlyViewedProducts(cid, uid).getOrNull()
        recentGrocery = recent?.grocery ?: emptyList()
        recentFood = recent?.food ?: emptyList()
    }
    var isLoadingMoreProducts by remember { mutableStateOf(false) }
    var hasMoreProducts by remember { mutableStateOf(true) }
    var groceryError by remember { mutableStateOf<String?>(null) }

    // Hotels data - explicitly empty and NOT loading on startup / grocery mode
    var hotels by remember {
        mutableStateOf<List<Vendor>>(emptyList())
    }
    var isHotelsLoading by remember { mutableStateOf(false) }
    var isLoadingMoreHotels by remember { mutableStateOf(false) }
    var hasMoreHotels by remember { mutableStateOf(true) }
    var hotelsError by remember { mutableStateOf<String?>(null) }

    // Food search: matching dishes shown directly (above the hotels list).
    var dishResults by remember { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    var isDishResultsLoading by remember { mutableStateOf(false) }

    // Smart Food Search: sort + filters (default sort = Price Low-High per spec; "relevance"
    // only takes over once the customer is actually typing a query).
    var foodSort by remember { mutableStateOf("price_asc") }
    var foodVeg by remember { mutableStateOf("all") }
    var foodBudgetOnly by remember { mutableStateOf(false) }
    var foodRatingOnly by remember { mutableStateOf(false) }
    var foodOffersOnly by remember { mutableStateOf(false) }
    var showFoodFilters by remember { mutableStateOf(false) }
    var trendingSearches by remember { mutableStateOf<List<String>>(emptyList()) }
    var recentSearches by remember { mutableStateOf<List<String>>(emptyList()) }
    var loggedQuery by remember { mutableStateOf("") }

    // "What's on your mind?" rotating category picker (Food Delivery hotel listing only).
    var hotelCategoriesPool by remember { mutableStateOf<List<Category>>(emptyList()) }
    var displayedFoodCategories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedFoodCategoryName by remember { mutableStateOf<String?>(null) }

    // Tracking state to prevent duplicate/redundant fetches for same city + category
    var lastLoadedCityId by remember { mutableStateOf<String?>(null) }
    var lastLoadedCategoryId by remember { mutableStateOf<String?>(null) }
    var lastLoadedQuery by remember { mutableStateOf("") }
    var lastLoadedHotelsCityId by remember { mutableStateOf<String?>(null) }
    var lastLoadedHotelsQuery by remember { mutableStateOf("") }

    // Cart state
    val groceryCart by repository.groceryCart.collectAsState()
    val hotelCart by repository.hotelCart.collectAsState()
    val totalCartCount = repository.getCartCount()

    // Fresh cart pricing for floating cart button
    var freshGroceryItems by remember { mutableStateOf<List<CartItemUi>>(emptyList()) }
    LaunchedEffect(groceryCart, selectedCity?.id) {
        val cid = selectedCity?.id
        if (cid != null && groceryCart.isNotEmpty()) {
            val res = repository.getFreshCartItems(isHotel = false, cityId = cid)
            if (res.isSuccess) {
                freshGroceryItems = res.getOrNull() ?: emptyList()
            }
        } else {
            freshGroceryItems = emptyList()
        }
    }

    var freshHotelItems by remember { mutableStateOf<List<CartItemUi>>(emptyList()) }
    LaunchedEffect(hotelCart, selectedCity?.id) {
        val cid = selectedCity?.id
        if (cid != null && hotelCart.isNotEmpty()) {
            val res = repository.getFreshCartItems(isHotel = true, cityId = cid)
            if (res.isSuccess) {
                freshHotelItems = res.getOrNull() ?: emptyList()
            }
        } else {
            freshHotelItems = emptyList()
        }
    }

    val groceryCartCount = groceryCart.sumOf { it.quantity }
    val groceryCartTotal = remember(groceryCart, freshGroceryItems, groceryProducts) {
        if (freshGroceryItems.isNotEmpty()) {
            freshGroceryItems.sumOf { it.totalPrice }
        } else {
            groceryCart.sumOf { item ->
                val p = groceryProducts.find { it.id == item.productId }
                val v = p?.variants?.find { it.id == item.variantId }
                (v?.price ?: p?.effectivePrice ?: 0.0) * item.quantity
            }
        }
    }

    val hotelCartCount = hotelCart.sumOf { it.quantity }
    val hotelCartTotal = remember(hotelCart, freshHotelItems) {
        if (freshHotelItems.isNotEmpty()) {
            freshHotelItems.sumOf { it.totalPrice }
        } else {
            0.0
        }
    }

    val activeCartCount = if (browsingMode == BrowsingMode.GROCERY) groceryCartCount else hotelCartCount
    val activeCartTotal = if (browsingMode == BrowsingMode.GROCERY) groceryCartTotal else hotelCartTotal
    val activeIsHotel = browsingMode == BrowsingMode.HOTELS

    // Conflict Dialog state
    var pendingHotelConflict by remember { mutableStateOf<AddToCartResult.HotelConflict?>(null) }
    var variantPickerProduct by remember { mutableStateOf<ResolvedProduct?>(null) }

    // Free Delivery Threshold for flash banner
    var freeDeliveryThreshold by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(selectedCity?.id) {
        val cid = selectedCity?.id
        if (cid != null) {
            freeDeliveryThreshold = repository.getFreeDeliveryThreshold(cid)
        } else {
            freeDeliveryThreshold = null
        }
    }

    // Hotel menu categories available in this city, for the "What's on your mind?" picker.
    LaunchedEffect(selectedCity?.id) {
        val cid = selectedCity?.id
        hotelCategoriesPool = if (cid != null) {
            repository.getHotelCategoriesForCity(cid).getOrNull() ?: emptyList()
        } else {
            emptyList()
        }
    }

    // Rotates the displayed category tiles every 5 minutes, picking only from currently-open
    // hotels. Re-keyed on `hotels.isNotEmpty()` (not `hotels` itself) so the first load
    // triggers an immediate recompute once hotels are available, but later pagination
    // ("Load More Hotels") doesn't reset the 5-minute timer.
    LaunchedEffect(hotelCategoriesPool, hotels.isNotEmpty()) {
        while (true) {
            val openVendorIds = hotels.filter { it.isEffectivelyOpenNow(selectedCity) }.map { it.id }.toSet()
            displayedFoodCategories = pickRotatingFoodCategories(hotelCategoriesPool, openVendorIds)
            delay(5 * 60 * 1000L)
        }
    }

    val context = LocalContext.current
    val sessionManager = repository.sessionManager
    val recentSearchesPrefs = remember { com.example.data.local.RecentSearchesPrefs(context) }
    LaunchedEffect(Unit) { recentSearches = recentSearchesPrefs.getRecent() }

    // Swiggy-Style Promotional Coupon Popup (shown once per app session on cold start)
    var showCouponPopup by remember { mutableStateOf(false) }
    var activeCoupon by remember { mutableStateOf<Coupon?>(null) }
    val clipboardManager = LocalClipboardManager.current
    var hasShownCouponPopup by rememberSaveable { mutableStateOf(false) }

    // Customer App: "Rate Us" Popup (shows after successful completed orders)
    var showRatingPopup by remember { mutableStateOf(false) }
    var completedOrderCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val currentUserId = sessionManager.getUserId()
        if (!currentUserId.isNullOrBlank()) {
            val result = repository.getCompletedOrderCount(currentUserId)
            completedOrderCount = result.getOrNull() ?: 0
            if (sessionManager.shouldShowRatingPopup(completedOrderCount) && !showCouponPopup) {
                showRatingPopup = true
                sessionManager.recordRatingPopupShown()
            }
        }
    }

    LaunchedEffect(selectedCity?.id) {
        val cid = selectedCity?.id
        if (!hasShownCouponPopup && cid != null) {
            val result = repository.getCoupons(cid)
            val coupon = result.getOrNull()?.firstOrNull { it.isActive }
            if (coupon != null) {
                activeCoupon = coupon
                showCouponPopup = true
                hasShownCouponPopup = true
            }
        }
    }

    // Snackbar host
    val snackbarHostState = remember { SnackbarHostState() }

    // Grocery categories (vendor_type IN grocery/vegetable/fruit, vendor_id IS NULL, is_active=true)
    // Fetched dynamically from Supabase without city restriction, with smart in-memory caching.
    fun loadGroceryCategories(forceRefresh: Boolean = false) {
        if (!forceRefresh && categories.isNotEmpty()) {
            return
        }
        coroutineScope.launch {
            val catRes = repository.getGroceryCategories(forceRefresh = forceRefresh)
            if (catRes.isSuccess) {
                val list = catRes.getOrNull() ?: emptyList()
                // Show every City Admin-created grocery category (not just Vegetables/Fruits),
                // vegetables first, then fruits, then everything else (e.g. Milk, Dairy).
                fun typeOrder(c: Category): Int = when {
                    c.vendorType == "vegetable" || c.name.lowercase().contains("veg") -> 0
                    c.vendorType == "fruit" || c.name.lowercase().contains("fruit") -> 1
                    else -> 2
                }
                val filtered = list.sortedBy(::typeOrder)
                categories = filtered
                val vegCategory = filtered.firstOrNull()
                if (vegCategory != null && (selectedCategoryId == null || filtered.none { it.id == selectedCategoryId })) {
                    selectedCategoryId = vegCategory.id
                }
                Log.d("HomeScreen", "Loaded ${filtered.size} categories: ${filtered.map { it.name }}")
            } else {
                val error = catRes.exceptionOrNull()
                Log.e("HomeScreen", "Categories fetch error: ${error?.message}", error)
                Analytics.trackEvent("app_error", mapOf("context" to "categories_load", "message" to "categories_load_failed"))
                if (categories.isEmpty()) {
                    categories = emptyList()
                    if (groceryProducts.isEmpty()) {
                        groceryError = error?.message
                    }
                }
            }
        }
    }

    val groceryGridState = rememberLazyGridState()
    val hotelsListState = rememberLazyListState()

    // Grocery products depend on city + selected category + search query.
    // Product grid query is always scoped to selectedCategoryId (Vegetables by default), never unfiltered "All".
    // Rule 1: Paginate by 30 at a time.
    // Rule 3: Prevent unnecessary re-fetching when same city + category data is already loaded.
    fun loadGroceryProducts(
        cityId: String,
        query: String,
        categoryId: String? = selectedCategoryId,
        reset: Boolean = true,
        forceRefresh: Boolean = false
    ) {
        val effectiveCatId = categoryId
            ?: selectedCategoryId
            ?: categories.firstOrNull { it.name.lowercase().contains("veg") }?.id
            ?: categories.firstOrNull()?.id
        if (effectiveCatId == null) {
            // Category not resolved yet; do not execute unfiltered "All" query
            return
        }

        // Prevent duplicate re-fetching when the same city + category + query is already loaded
        if (reset && !forceRefresh && groceryProducts.isNotEmpty() &&
            cityId == lastLoadedCityId && effectiveCatId == lastLoadedCategoryId && query == lastLoadedQuery
        ) {
            return
        }

        // Check in-memory cache first for instant category switching (zero network requests)
        if (reset && !forceRefresh) {
            val cached = repository.getCachedGroceryProducts(cityId, effectiveCatId, query.ifBlank { null })
            if (cached != null && cached.isNotEmpty()) {
                groceryProducts = cached
                lastLoadedCityId = cityId
                lastLoadedCategoryId = effectiveCatId
                lastLoadedQuery = query
                isGroceryLoading = false
                hasMoreProducts = cached.size >= GROCERY_PAGE_SIZE
                return
            }
        }

        coroutineScope.launch {
            if (reset) {
                isGroceryLoading = true
                groceryError = null
            } else {
                isLoadingMoreProducts = true
            }

            val offset = if (reset) 0 else groceryProducts.size
            val prodRes = repository.getResolvedGroceryProducts(
                cityId = cityId,
                categoryId = effectiveCatId,
                searchQuery = query.ifBlank { null },
                limit = GROCERY_PAGE_SIZE,
                offset = offset,
                forceRefresh = forceRefresh
            )
            if (prodRes.isSuccess) {
                val newProducts = prodRes.getOrNull() ?: emptyList()
                if (reset) {
                    groceryProducts = newProducts.sortedWith(
                        compareByDescending<ResolvedProduct> { it.isInStockAndActive }
                            .thenBy { it.name.lowercase() }
                    )
                    lastLoadedCityId = cityId
                    lastLoadedCategoryId = effectiveCatId
                    lastLoadedQuery = query
                    if (query.isNotBlank()) {
                        Analytics.trackEvent(
                            "search",
                            mapOf(
                                "query" to query,
                                "result_count" to newProducts.size,
                                "zero_result" to newProducts.isEmpty(),
                                "category" to "grocery"
                            )
                        )
                    }
                } else {
                    groceryProducts = (groceryProducts + newProducts).distinctBy { it.id }.sortedWith(
                        compareByDescending<ResolvedProduct> { it.isInStockAndActive }
                            .thenBy { it.name.lowercase() }
                    )
                }
                hasMoreProducts = newProducts.size >= GROCERY_PAGE_SIZE
            } else {
                if (reset) {
                    groceryProducts = emptyList()
                    groceryError = prodRes.exceptionOrNull()?.message
                }
            }
            isGroceryLoading = false
            isLoadingMoreProducts = false
        }
    }

    // Refresh function for hotels.
    // Rule 1: Hotel list: 20 at a time.
    // Rule 2: Do NOT load hotels during App/Grocery startup. Load only when customer opens Food section.
    // Rule 3: Prevent redundant re-fetching when already loaded.
    fun loadHotelsData(
        cityId: String,
        query: String,
        reset: Boolean = true,
        forceRefresh: Boolean = false
    ) {
        if (browsingMode != BrowsingMode.HOTELS) {
            return
        }

        // Prevent redundant re-fetch if same city + query already loaded
        if (reset && !forceRefresh && hotels.isNotEmpty() &&
            cityId == lastLoadedHotelsCityId && query == lastLoadedHotelsQuery
        ) {
            return
        }

        // Check in-memory cache for instant switch to Food section
        if (reset && !forceRefresh) {
            val cached = repository.getCachedHotels(cityId)
            if (cached != null && cached.isNotEmpty()) {
                hotels = cached
                lastLoadedHotelsCityId = cityId
                lastLoadedHotelsQuery = query
                isHotelsLoading = false
                hasMoreHotels = cached.size >= HOTEL_PAGE_SIZE
                return
            }
        }

        coroutineScope.launch {
            if (reset) {
                isHotelsLoading = true
                hotelsError = null
            } else {
                isLoadingMoreHotels = true
            }

            val offset = if (reset) 0 else hotels.size
            val res = repository.getHotels(
                cityId = cityId,
                searchQuery = query.ifBlank { null },
                limit = HOTEL_PAGE_SIZE,
                offset = offset,
                forceRefresh = forceRefresh
            )
            if (res.isSuccess) {
                val newHotels = res.getOrNull() ?: emptyList()
                if (reset) {
                    hotels = newHotels.sortedWith(HotelComparator)
                    lastLoadedHotelsCityId = cityId
                    lastLoadedHotelsQuery = query
                } else {
                    hotels = (hotels + newHotels).distinctBy { it.id }.sortedWith(HotelComparator)
                }
                hasMoreHotels = newHotels.size >= HOTEL_PAGE_SIZE
            } else {
                if (reset) {
                    hotelsError = res.exceptionOrNull()?.message
                }
            }
            isHotelsLoading = false
            isLoadingMoreHotels = false
        }
    }

    // Auto-paginate grocery grid on scroll near bottom
    val shouldLoadMoreGrocery by remember {
        derivedStateOf {
            val layoutInfo = groceryGridState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 4
        }
    }

    LaunchedEffect(shouldLoadMoreGrocery) {
        if (shouldLoadMoreGrocery && hasMoreProducts && !isLoadingMoreProducts && !isGroceryLoading) {
            val cityId = selectedCity?.id
            if (cityId != null) {
                loadGroceryProducts(cityId, searchQuery, selectedCategoryId, reset = false)
            }
        }
    }

    // Auto-paginate hotels list on scroll near bottom
    val shouldLoadMoreHotels by remember {
        derivedStateOf {
            val layoutInfo = hotelsListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 3
        }
    }

    LaunchedEffect(shouldLoadMoreHotels) {
        if (shouldLoadMoreHotels && hasMoreHotels && !isLoadingMoreHotels && !isHotelsLoading) {
            val cityId = selectedCity?.id
            if (cityId != null) {
                loadHotelsData(cityId, searchQuery, reset = false)
            }
        }
    }

    // Main data loading effect: strictly loads based on active browsingMode and city.
    // Does NOT load Hotels during App/Grocery startup.
    LaunchedEffect(browsingMode, selectedCity?.id, selectedCategoryId) {
        val cityId = selectedCity?.id ?: return@LaunchedEffect
        if (browsingMode == BrowsingMode.HOTELS) {
            loadHotelsData(cityId, searchQuery)
        } else {
            // BrowsingMode.GROCERY
            if (categories.isEmpty()) {
                loadGroceryCategories()
            }
            val catId = selectedCategoryId
            if (catId != null) {
                loadGroceryProducts(cityId, searchQuery, catId)
            }
        }
    }

    // Grocery search query change (debounced so typing doesn't spam backend).
    var lastSearchedGroceryQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery, browsingMode) {
        if (browsingMode != BrowsingMode.GROCERY) return@LaunchedEffect
        if (searchQuery == lastSearchedGroceryQuery) return@LaunchedEffect
        val cityId = selectedCity?.id ?: return@LaunchedEffect
        val catId = selectedCategoryId ?: return@LaunchedEffect
        delay(350)
        lastSearchedGroceryQuery = searchQuery
        loadGroceryProducts(cityId, searchQuery, catId)
    }

    // Hotel search is server-side (name=ilike), debounced, only while on the hotels tab.
    var lastSearchedHotelsQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery, browsingMode) {
        if (browsingMode != BrowsingMode.HOTELS) return@LaunchedEffect
        if (searchQuery == lastSearchedHotelsQuery) return@LaunchedEffect
        val cityId = selectedCity?.id ?: return@LaunchedEffect
        delay(350)
        lastSearchedHotelsQuery = searchQuery
        loadHotelsData(cityId, searchQuery)
    }

    // Smart Food Search: fetch matching dishes directly (shown above the hotels list) —
    // typo-tolerant + partial-word matched + ranked server-side, with Veg/Budget/Rating/Offers
    // filters and 5 sort modes. Every result is already "open + available right now".
    LaunchedEffect(searchQuery, browsingMode, selectedCity?.id, foodSort, foodVeg, foodBudgetOnly, foodRatingOnly, foodOffersOnly) {
        val cityId = selectedCity?.id
        if (browsingMode != BrowsingMode.HOTELS || cityId == null || searchQuery.isBlank()) {
            dishResults = emptyList()
            isDishResultsLoading = false
            return@LaunchedEffect
        }
        delay(280)
        isDishResultsLoading = true
        dishResults = repository.searchFoodItems(
            cityId = cityId,
            query = searchQuery,
            sort = foodSort,
            vegFilter = foodVeg,
            maxPrice = if (foodBudgetOnly) 149.0 else null,
            minRating = if (foodRatingOnly) 4.0 else null,
            offersOnly = foodOffersOnly
        ).getOrDefault(emptyList())
        isDishResultsLoading = false
        Analytics.trackEvent(
            "search",
            mapOf(
                "query" to searchQuery,
                "result_count" to dishResults.size,
                "zero_result" to dishResults.isEmpty()
            )
        )
        // Log once per settled query (not every keystroke) so Trending Searches stays meaningful.
        if (loggedQuery != searchQuery) {
            loggedQuery = searchQuery
            repository.logFoodSearch(cityId, searchQuery, dishResults.size)
            recentSearchesPrefs.addRecent(searchQuery)
            recentSearches = recentSearchesPrefs.getRecent()
        }
    }

    // Trending searches (server) + Recent searches (this device) — shown before the customer
    // has typed anything, so they always have a one-tap starting point.
    LaunchedEffect(selectedCity?.id) {
        val cityId = selectedCity?.id ?: return@LaunchedEffect
        recentSearches = recentSearchesPrefs.getRecent()
        trendingSearches = repository.getTrendingFoodSearches(cityId).getOrDefault(emptyList())
    }

    // Handle single-hotel rule conflict dialog
    if (pendingHotelConflict != null) {
        val conflict = pendingHotelConflict!!
        AlertDialog(
            onDismissRequest = { pendingHotelConflict = null },
            title = { Text("Replace Hotel Cart Items?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Your cart already contains items from another hotel. Sndmart orders items from one hotel at a time. Would you like to clear the existing hotel cart and add this item?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.forceClearHotelCartAndAdd(conflict.pendingItem)
                        pendingHotelConflict = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Cart updated with new hotel item")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TangerineOrange)
                ) {
                    Text("Clear & Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHotelConflict = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Grocery Product Variant Picker Bottom Sheet
    variantPickerProduct?.let { prod ->
        GroceryVariantPickerSheet(
            product = prod,
            groceryCart = groceryCart,
            onAddToCart = { variantId, delta ->
                repository.addToCart(
                    productId = prod.id,
                    vendorId = null,
                    cityId = selectedCity?.id,
                    quantityDelta = delta,
                    isHotel = false,
                    variantId = variantId,
                    productForSnapshot = prod
                )
            },
            onDismiss = { variantPickerProduct = null }
        )
    }

    // Promotional Coupon Dialog
    if (showCouponPopup && activeCoupon != null) {
        CouponPopup(
            coupon = activeCoupon!!,
            onDismiss = {
                showCouponPopup = false
                if (sessionManager.shouldShowRatingPopup(completedOrderCount) && !showRatingPopup) {
                    showRatingPopup = true
                    sessionManager.recordRatingPopupShown()
                }
            },
            onCopyCode = { code ->
                clipboardManager.setText(AnnotatedString(code))
                showCouponPopup = false
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Coupon code $code copied to clipboard!")
                }
                if (sessionManager.shouldShowRatingPopup(completedOrderCount) && !showRatingPopup) {
                    showRatingPopup = true
                    sessionManager.recordRatingPopupShown()
                }
            }
        )
    }

    // Rate Us Popup
    if (showRatingPopup) {
        RateUsPopup(
            onRateNow = {
                showRatingPopup = false
                sessionManager.recordUserRated()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")))
                }
            },
            onMaybeLater = { showRatingPopup = false },
            onDontAskAgain = {
                showRatingPopup = false
                sessionManager.recordRatingPopupDismissedForever()
            }
        )
    }

    // Scratch card reveal: opened by tapping the persistent Home reminder banner.
    revealingScratchCard?.let { card ->
        com.example.ui.components.ScratchCardDialog(
            amount = card.amount,
            onRevealed = { coroutineScope.launch { repository.scratchMyCard(card.orderId) } },
            onDismiss = {
                revealingScratchCard = null
                pendingScratchCards = pendingScratchCards.filter { it.id != card.id }
            }
        )
    }

    // Banner tap: opens a grocery category, a hotel menu or an https link.
    fun openPromoBanner(b: PromoBanner) {
        val target = b.linkValue ?: return
        when (b.linkType) {
            "category" -> {
                searchQuery = ""
                browsingMode = BrowsingMode.GROCERY
                selectedCategoryId = target
            }
            "hotel" -> onNavigateToHotelMenu(target, b.title ?: "Hotel")
            "url" -> if (target.startsWith("https://")) runCatching { uriHandler.openUri(target) }
        }
    }

    // Local ad tap: opens the link (website/map), a WhatsApp chat, or the dialer. No-op for a Fixed ad.
    fun openLocalAd(a: LocalAd) {
        if (!a.isClickable) return
        val target = a.linkValue ?: return
        runCatching { uriHandler.openUri(target) }
    }

    CompositionLocalProvider(LocalCityTheme provides cityTheme) {
    Scaffold(
        topBar = {
            SndmartTopBar(
                currentCity = selectedCity,
                cartCount = totalCartCount,
                deliveryAddressLabel = deliveryAddressLabel,
                unreadNotificationCount = unreadNotificationCount,
                navigationIcon = if (browsingMode == BrowsingMode.HOTELS && serviceMode != "food_only") {
                    {
                        IconButton(
                            onClick = { browsingMode = BrowsingMode.GROCERY },
                            modifier = Modifier.testTag("hotels_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Groceries"
                            )
                        }
                    }
                } else null,
                onCityClick = onCityChangeRequested,
                onCartClick = onNavigateToCart,
                onNotificationsClick = onNavigateToNotifications,
                onSettingsClick = onOpenSettings,
                walletBalance = walletBalance,
                onWalletClick = onNavigateToWallet,
                onRefresh = {
                    val cityId = selectedCity?.id
                    bannerRefreshTick += 1
                    if (cityId != null) {
                        if (browsingMode == BrowsingMode.GROCERY) {
                            loadGroceryCategories(forceRefresh = true)
                            loadGroceryProducts(cityId, searchQuery, selectedCategoryId, reset = true, forceRefresh = true)
                        } else {
                            loadHotelsData(cityId, searchQuery, reset = true, forceRefresh = true)
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AnimatedVisibility(
                visible = activeCartCount > 0,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                FloatingCartButton(
                    itemCount = activeCartCount,
                    totalPrice = activeCartTotal,
                    onClick = { onProceedToCheckout(activeIsHotel) },
                    freeDeliveryAt = freeDeliveryThreshold,
                    testTag = if (activeIsHotel) "hotel_floating_checkout_button" else "grocery_floating_checkout_button"
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Natural Tones Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("home_search_input"),
                placeholder = {
                    val hints = if (browsingMode == BrowsingMode.GROCERY)
                        listOf("tomato", "onion", "fruits", "vegetables")
                    else listOf("biryani", "dosa", "roti", "fried rice")
                    Text(
                        "Search \"${hints[searchHintIndex % hints.size]}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = TextSecondary)
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = SurfaceVariantLight,
                    focusedBorderColor = NaturalPrimary,
                    unfocusedBorderColor = Color.Transparent
                ),
                singleLine = true
            )

            // Natural Tones Segmented Mode Pill
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SurfaceVariantLight,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("home_mode_toggle")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isGrocery = browsingMode == BrowsingMode.GROCERY
                    val groceryAvailable = serviceMode != "food_only"
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = if (isGrocery) Color.White else Color.Transparent,
                        shadowElevation = if (isGrocery) 3.dp else 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .clickable(enabled = groceryAvailable) { browsingMode = BrowsingMode.GROCERY }
                            .testTag("toggle_grocery")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 10.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Fresh Groceries & Fruits",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (!groceryAvailable) TextSecondary.copy(alpha = 0.5f) else if (isGrocery) NaturalPrimary else TextSecondary
                                )
                                if (!groceryAvailable) {
                                    Text("Coming Soon", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = NaturalPrimary)
                                }
                            }
                        }
                    }

                    val isHotel = browsingMode == BrowsingMode.HOTELS
                    val hotelsAvailable = serviceMode != "grocery_only"
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = if (isHotel) Color.White else Color.Transparent,
                        shadowElevation = if (isHotel) 3.dp else 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .clickable(enabled = hotelsAvailable) { browsingMode = BrowsingMode.HOTELS }
                            .testTag("toggle_hotels")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 10.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Hotel Food & Dining",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (!hotelsAvailable) TextSecondary.copy(alpha = 0.5f) else if (isHotel) NaturalPrimary else TextSecondary
                                )
                                if (!hotelsAvailable) {
                                    Text("Coming Soon", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = NaturalPrimary)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (browsingMode == BrowsingMode.HOTELS && searchQuery.isBlank() &&
                (recentSearches.isNotEmpty() || trendingSearches.isNotEmpty())
            ) {
                SearchSuggestRow(
                    recentSearches = recentSearches,
                    trendingSearches = trendingSearches,
                    onPick = { searchQuery = it }
                )
            }

            if (selectedCity == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Please select your delivery city to view live stock & pricing")
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onCityChangeRequested) {
                            Text("Choose City")
                        }
                    }
                }
                return@Scaffold
            }

            if (pendingScratchCards.isNotEmpty()) {
                val first = pendingScratchCards.first()
                androidx.compose.material3.Surface(
                    onClick = { revealingScratchCard = first },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = com.example.ui.theme.NaturalPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Outlined.CardGiftcard,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Text(
                            text = if (pendingScratchCards.size > 1)
                                "You have ${pendingScratchCards.size} scratch card rewards waiting!"
                            else "You have a scratch card reward waiting!",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text("Tap to reveal", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // --- GROCERY VIEW ---
            if (browsingMode == BrowsingMode.GROCERY) {
                if (isGroceryLoading && groceryProducts.isEmpty()) {
                    // Rule 7: Skeleton grid loader instead of a blank screen or single spinner
                    GroceryProductGridSkeleton(count = 6)
                } else if (groceryError != null && groceryProducts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        ErrorCard(
                            message = groceryError!!,
                            onRetry = {
                                selectedCity.id.let {
                                    loadGroceryCategories()
                                    loadGroceryProducts(it, searchQuery, selectedCategoryId, reset = true)
                                }
                            }
                        )
                    }
                } else {
                    // Search is performed server-side (name=ilike); just render the results.
                    val filteredProducts = groceryProducts

                    LazyVerticalGrid(
                        state = groceryGridState,
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        freeDeliveryThreshold?.let { threshold ->
                            item(span = { GridItemSpan(2) }) {
                                FreeDeliveryBanner(threshold = threshold)
                            }
                        }

                        if (searchQuery.isBlank() && homeAds.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                LocalAdStrip(ads = homeAds, onOpen = { a -> openLocalAd(a) }, modifier = Modifier.padding(top = 6.dp))
                            }
                        }

                        if (searchQuery.isBlank()) {
                            // City Admin banners, else the default Deals banner (with its optional admin photo)
                            item(span = { GridItemSpan(2) }) {
                                if (promoBanners.isNotEmpty()) {
                                    PromoBannerCarousel(
                                        banners = promoBanners,
                                        onOpen = { b -> openPromoBanner(b) },
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                } else {
                                    NaturalFeaturedDealsBanner(
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                            }
                            item(span = { GridItemSpan(2) }) {
                                OfferBanner(kind = PromoKind.GROCERY, modifier = Modifier.padding(top = 2.dp))
                            }
                        }

                        if (searchQuery.isBlank() && homeSections.recentlyViewed && recentGrocery.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                MostSellingRow(
                                    products = recentGrocery,
                                    groceryCart = groceryCart,
                                    cityOpen = cityIsOpen,
                                    title = "👀 Recently Viewed",
                                    onIncrease = { prod ->
                                        repository.addToCart(
                                            productId = prod.id, vendorId = null, cityId = selectedCity.id,
                                            quantityDelta = 1, isHotel = false, productForSnapshot = prod
                                        )
                                    },
                                    onDecrease = { prod ->
                                        repository.addToCart(
                                            productId = prod.id, vendorId = null, cityId = selectedCity.id,
                                            quantityDelta = -1, isHotel = false
                                        )
                                    },
                                    onSelectSize = { prod ->
                                        variantPickerProduct = prod
                                        Analytics.trackEvent(
                                            "product_view",
                                            mapOf("product_id" to prod.id, "category" to "grocery")
                                        )
                                    }
                                )
                            }
                        }

                        if (searchQuery.isBlank() && homeSections.mostSelling && topSelling.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                MostSellingRow(
                                    products = topSelling,
                                    groceryCart = groceryCart,
                                    cityOpen = cityIsOpen,
                                    onIncrease = { prod ->
                                        repository.addToCart(
                                            productId = prod.id, vendorId = null, cityId = selectedCity.id,
                                            quantityDelta = 1, isHotel = false, productForSnapshot = prod
                                        )
                                    },
                                    onDecrease = { prod ->
                                        repository.addToCart(
                                            productId = prod.id, vendorId = null, cityId = selectedCity.id,
                                            quantityDelta = -1, isHotel = false
                                        )
                                    },
                                    onSelectSize = { prod ->
                                        variantPickerProduct = prod
                                        Analytics.trackEvent(
                                            "product_view",
                                            mapOf("product_id" to prod.id, "category" to "grocery")
                                        )
                                    }
                                )
                            }
                        }

                        if (searchQuery.isBlank() && homeExtras.featured.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                FeaturedGroceryRow(
                                    products = homeExtras.featured,
                                    groceryCart = groceryCart,
                                    cityOpen = cityIsOpen,
                                    cityId = selectedCity.id,
                                    repository = repository,
                                    onSelectSize = { prod -> variantPickerProduct = prod }
                                )
                            }
                        }

                        if (searchQuery.isBlank()) {
                            // Shop by Category: pill tabs
                            item(span = { GridItemSpan(2) }) {
                                GroceryCategoryList(
                                    categories = categories,
                                    selectedCategoryId = selectedCategoryId,
                                    onCategorySelected = { catId ->
                                        selectedCategoryId = catId
                                        val catName = categories.firstOrNull { it.id == catId }?.name
                                        Analytics.trackEvent(
                                            "category_select",
                                            mapOf("category_id" to catId, "category_name" to catName)
                                        )
                                    }
                                )
                            }
                        }

                        if (filteredProducts.isEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No grocery products found.",
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        } else {
                            items(filteredProducts, key = { it.id }) { product ->
                                val inCartItems = groceryCart.filter { it.productId == product.id }
                                val totalQuantityInCart = inCartItems.sumOf { it.quantity }

                                GroceryProductCard(
                                    product = product,
                                    quantityInCart = totalQuantityInCart,
                                    cityOpen = cityIsOpen,
                                    onIncrease = {
                                        repository.addToCart(
                                            productId = product.id,
                                            vendorId = null,
                                            cityId = selectedCity.id,
                                            quantityDelta = 1,
                                            isHotel = false,
                                            productForSnapshot = product
                                        )
                                    },
                                    onDecrease = {
                                        repository.addToCart(
                                            productId = product.id,
                                            vendorId = null,
                                            cityId = selectedCity.id,
                                            quantityDelta = -1,
                                            isHotel = false
                                        )
                                    },
                                    onSelectSize = {
                                        variantPickerProduct = product
                                        Analytics.trackEvent(
                                            "product_view",
                                            mapOf("product_id" to product.id, "category" to "grocery")
                                        )
                                    }
                                )
                            }

                            if (hasMoreProducts) {
                                item(span = { GridItemSpan(2) }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isLoadingMoreProducts) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(28.dp),
                                                strokeWidth = 2.5.dp,
                                                color = NaturalPrimary
                                            )
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    loadGroceryProducts(selectedCity.id, searchQuery, selectedCategoryId, reset = false)
                                                },
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.testTag("load_more_grocery_button")
                                            ) {
                                                Text("Load More Products (30)")
                                            }
                                        }
                                    }
                                }
                            }

                            if (!hasMoreProducts && bottomAds.isNotEmpty()) {
                                item(span = { GridItemSpan(2) }) {
                                    LocalAdStrip(ads = bottomAds, onOpen = { a -> openLocalAd(a) })
                                }
                            }
                        }
                    }
                }
            }

            // --- HOTELS VIEW ---
            if (browsingMode == BrowsingMode.HOTELS) {
                if (isHotelsLoading && hotels.isEmpty()) {
                    // Rule 7: Skeleton list loader instead of a single spinner
                    HotelsListSkeleton(count = 4)
                } else if (hotelsError != null && hotels.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        ErrorCard(
                            message = hotelsError!!,
                            onRetry = { selectedCity.id.let { loadHotelsData(it, searchQuery, reset = true) } }
                        )
                    }
                } else {
                    // Search is performed server-side (name=ilike); tapping a "What's on your
                    // mind?" category tile filters client-side by that category's hotels instead.
                    val filteredHotels = selectedFoodCategoryName?.let { name ->
                        val matchingVendorIds = vendorIdsForCategoryName(hotelCategoriesPool, name)
                        hotels.filter { it.id in matchingVendorIds }
                    } ?: hotels

                    if (filteredHotels.isEmpty() && dishResults.isEmpty() && !isDishResultsLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (searchQuery.isNotBlank()) "No dishes or hotels match \"${searchQuery.trim()}\"."
                                else "No hotels or restaurants available in this city.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else {
                        LazyColumn(
                            state = hotelsListState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Food search: matching dishes shown directly, each with a quick Add.
                            if (searchQuery.isNotBlank()) {
                                if (isDishResultsLoading && dishResults.isEmpty()) {
                                    item(key = "dish_results_loading") {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(26.dp),
                                                strokeWidth = 2.5.dp,
                                                color = NaturalPrimary
                                            )
                                        }
                                    }
                                } else if (dishResults.isNotEmpty()) {
                                    item(key = "dish_results_header") {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            HotelSectionHeader(title = "Dishes matching \"${searchQuery.trim()}\"")
                                            FoodFilterToggleButton(
                                                active = showFoodFilters,
                                                onClick = { showFoodFilters = !showFoodFilters }
                                            )
                                        }
                                    }
                                    if (showFoodFilters) {
                                        item(key = "dish_results_filters") {
                                            FoodSearchFiltersBar(
                                                sort = foodSort,
                                                onSortChange = { foodSort = it },
                                                veg = foodVeg,
                                                onVegChange = { foodVeg = it },
                                                budgetOnly = foodBudgetOnly,
                                                onBudgetToggle = { foodBudgetOnly = !foodBudgetOnly },
                                                ratingOnly = foodRatingOnly,
                                                onRatingToggle = { foodRatingOnly = !foodRatingOnly },
                                                offersOnly = foodOffersOnly,
                                                onOffersToggle = { foodOffersOnly = !foodOffersOnly }
                                            )
                                        }
                                    }
                                    items(dishResults, key = { "dish_${it.id}" }) { dish ->
                                        val qty = hotelCart.find { it.productId == dish.id }?.quantity ?: 0
                                        SearchDishCard(
                                            product = dish,
                                            quantity = qty,
                                            onOpenHotel = {
                                                onNavigateToHotelMenu(dish.vendorId ?: "", dish.soldVendorName ?: "Hotel")
                                            },
                                            onAdd = { delta ->
                                                val cid = selectedCity?.id
                                                val vid = dish.vendorId
                                                if (cid != null && vid != null) {
                                                    val res = repository.addToCart(
                                                        productId = dish.id,
                                                        vendorId = vid,
                                                        cityId = cid,
                                                        quantityDelta = delta,
                                                        isHotel = true,
                                                        productForSnapshot = dish
                                                    )
                                                    if (res is AddToCartResult.HotelConflict) pendingHotelConflict = res
                                                }
                                            }
                                        )
                                    }
                                    if (filteredHotels.isNotEmpty()) {
                                        item(key = "restaurants_header") {
                                            HotelSectionHeader(title = "Restaurants")
                                        }
                                    }
                                } else if (foodVeg != "all" || foodBudgetOnly || foodRatingOnly || foodOffersOnly) {
                                    item(key = "dish_results_empty_filtered") {
                                        Text(
                                            "No dishes match these filters — try loosening them.",
                                            color = TextSecondary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                        )
                                    }
                                }
                            }

                            freeDeliveryThreshold?.let { threshold ->
                                item(key = "food_free_delivery") {
                                    FreeDeliveryBanner(threshold = threshold)
                                }
                            }

                            // Banner carousel and "What's on your mind?": City Admin chooses which comes first.
                            val bannerBlock: LazyListScope.() -> Unit = {
                                if (searchQuery.isBlank() && promoBanners.isNotEmpty()) {
                                    item(key = "food_promo_banners") {
                                        PromoBannerCarousel(
                                            banners = promoBanners,
                                            onOpen = { b -> openPromoBanner(b) }
                                        )
                                    }
                                }
                            }
                            val categoryBlock: LazyListScope.() -> Unit = {
                                if (searchQuery.isBlank() && displayedFoodCategories.isNotEmpty()) {
                                    item(key = "food_dish_categories") {
                                        FoodCategoryGrid(
                                            categories = displayedFoodCategories,
                                            selectedCategoryName = selectedFoodCategoryName,
                                            onCategoryClick = { name ->
                                                selectedFoodCategoryName =
                                                    if (selectedFoodCategoryName == name) null else name
                                            }
                                        )
                                    }
                                }
                            }
                            if (homeSections.foodBannerFirst) { bannerBlock(); categoryBlock() } else { categoryBlock(); bannerBlock() }

                            selectedFoodCategoryName?.let { name ->
                                item(key = "active_food_category_filter") {
                                    ActiveCategoryFilterChip(
                                        categoryName = name,
                                        onClear = { selectedFoodCategoryName = null }
                                    )
                                }
                            }

                            if (searchQuery.isBlank()) {
                                item(key = "food_offer_banner") {
                                    OfferBanner(kind = PromoKind.FOOD)
                                }
                            }

                            if (searchQuery.isBlank() && homeSections.recentlyViewed && recentFood.isNotEmpty()) {
                                item(key = "food_recently_viewed") {
                                    MostOrderedFoodRow(
                                        products = recentFood,
                                        title = "👀 Recently Viewed",
                                        onOpenHotel = { prod ->
                                            onNavigateToHotelMenu(prod.vendorId ?: "", prod.soldVendorName ?: "Hotel")
                                        }
                                    )
                                }
                            }

                            if (searchQuery.isBlank() && homeSections.mostSelling && topFood.isNotEmpty()) {
                                item(key = "food_most_ordered") {
                                    MostOrderedFoodRow(
                                        products = topFood,
                                        onOpenHotel = { prod ->
                                            onNavigateToHotelMenu(prod.vendorId ?: "", prod.soldVendorName ?: "Hotel")
                                        }
                                    )
                                }
                            }

                            if (searchQuery.isBlank() && budgetFood.isNotEmpty()) {
                                item(key = "food_99_store") {
                                    MostOrderedFoodRow(
                                        products = budgetFood,
                                        title = "💸 99 Store",
                                        onOpenHotel = { prod ->
                                            onNavigateToHotelMenu(prod.vendorId ?: "", prod.soldVendorName ?: "Hotel")
                                        }
                                    )
                                }
                            }

                            // Plain promotion card when City Admin has no image banners for the food tab.
                            if (searchQuery.isBlank() && promoBanners.isEmpty()) {
                                item(key = "food_promo_card") {
                                    FoodPromoBanner(
                                        threshold = freeDeliveryThreshold,
                                        coupon = homeExtras.coupon,
                                        onCopyCode = { code ->
                                            clipboardManager.setText(AnnotatedString(code))
                                            coroutineScope.launch { snackbarHostState.showSnackbar("Coupon code $code copied") }
                                        },
                                        dishImageUrl = displayedFoodCategories.firstOrNull { it.imageUrl != null }?.imageUrl,
                                        onOrder = {
                                            coroutineScope.launch { hotelsListState.animateScrollBy(900f) }
                                        }
                                    )
                                }
                            }

                            // 4 sections, in this order: Featured+Open, Open, Featured+Closed,
                            // Closed. filteredHotels is already sorted by Vendor.sortTier (see
                            // HotelComparator), so grouping by tier preserves that order and the
                            // alphabetical order within each tier.
                            HotelListSections.forEach { section ->
                                val sectionHotels = filteredHotels.filter { it.sortTier == section.tier }
                                if (sectionHotels.isNotEmpty()) {
                                    item(key = "hotel_section_${section.tier}") {
                                        HotelSectionHeader(title = section.title)
                                    }
                                    items(sectionHotels, key = { it.id }) { hotel ->
                                        HotelCard(
                                            vendor = hotel,
                                            repository = repository,
                                            onClick = {
                                                onNavigateToHotelMenu(hotel.id, hotel.name)
                                            }
                                        )
                                    }
                                }
                            }

                            if (hasMoreHotels) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isLoadingMoreHotels) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(28.dp),
                                                strokeWidth = 2.5.dp,
                                                color = NaturalPrimary
                                            )
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    loadHotelsData(selectedCity.id, searchQuery, reset = false)
                                                },
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.testTag("load_more_hotels_button")
                                            ) {
                                                Text("Load More Hotels (20)")
                                            }
                                        }
                                    }
                                }
                            }

                            if (!hasMoreHotels && bottomAds.isNotEmpty()) {
                                item(key = "local_ads_bottom") {
                                    LocalAdStrip(
                                        ads = bottomAds,
                                        onOpen = { a -> openLocalAd(a) },
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
fun NaturalFeaturedDealsBanner(
    modifier: Modifier = Modifier
) {
    val theme = LocalCityTheme.current
    val photo = theme.dealsBannerImageUrl
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .shadow(8.dp, RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(theme.ocean)
    ) {
        if (photo != null) {
            ProductImage(url = photo, contentDescription = null, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.2f)))))
        } else {
            // Silhouette decorations (only without an admin photo)
            Text(
                text = "🥬",
                fontSize = 58.sp,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 10.dp, y = 8.dp).alpha(0.25f)
            )
            Text(
                text = "🍅",
                fontSize = 44.sp,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-36).dp, y = 4.dp).alpha(0.20f)
            )
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "FARM FRESH DAILY",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Organic Farm\nFresh Picks",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 22.sp
                )
            }
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                Text(
                    text = "SHOP NOW",
                    color = theme.ocean,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

class HomeExtras(val featured: List<ResolvedProduct>, val coupon: Coupon?)

/** "Featured Products" (admin-starred, in stock in this city) and the city's active promo coupon. */
@Composable
fun rememberHomeExtras(repository: SndmartRepository, cityId: String?): HomeExtras {
    var featured by remember(cityId) { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    var coupon by remember(cityId) { mutableStateOf<Coupon?>(null) }
    LaunchedEffect(cityId) {
        if (cityId == null) return@LaunchedEffect
        featured = repository.getFeaturedGroceryProducts(cityId).getOrNull() ?: emptyList()
        coupon = try {
            repository.getPromoCoupon(cityId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    return HomeExtras(featured, coupon)
}

@Composable
fun FeaturedGroceryRow(
    products: List<ResolvedProduct>,
    groceryCart: List<CartItem>,
    cityOpen: Boolean,
    cityId: String,
    repository: SndmartRepository,
    onSelectSize: (ResolvedProduct) -> Unit
) {
    MostSellingRow(
        products = products,
        groceryCart = groceryCart,
        cityOpen = cityOpen,
        title = "Featured Products",
        onIncrease = { prod ->
            repository.addToCart(
                productId = prod.id, vendorId = null, cityId = cityId,
                quantityDelta = 1, isHotel = false, productForSnapshot = prod
            )
        },
        onDecrease = { prod ->
            repository.addToCart(
                productId = prod.id, vendorId = null, cityId = cityId,
                quantityDelta = -1, isHotel = false
            )
        },
        onSelectSize = { prod ->
            onSelectSize(prod)
            Analytics.trackEvent("product_view", mapOf("product_id" to prod.id, "category" to "grocery"))
        }
    )
}

/** "50% OFF up to ₹100", "Flat ₹50 OFF", "50% cashback up to ₹500", "Flat ₹100 cashback" (website couponHeadline). */
fun couponHeadline(c: com.example.data.model.Coupon): String {
    val isPercent = c.discountType == "percent" || c.discountType == "percentage"
    val value = c.discountValue.toInt()
    val cap = if (c.maxDiscountAmount != null && isPercent) " up to ₹${c.maxDiscountAmount.toInt()}" else ""
    return if (c.isCashback) {
        if (isPercent) "$value% cashback$cap" else "Flat ₹$value cashback"
    } else {
        if (isPercent) "$value% OFF$cap" else "Flat ₹$value OFF"
    }
}

/**
 * Always-visible food promotion card (food gradient, optional photo: the admin's own, else a dish
 * photo). Honest copy only: the city's free-delivery amount, else a plain invitation.
 */
@Composable
fun FoodPromoBanner(
    threshold: Double?,
    dishImageUrl: String?,
    onOrder: () -> Unit,
    modifier: Modifier = Modifier,
    coupon: com.example.data.model.Coupon? = null,
    onCopyCode: (String) -> Unit = {}
) {
    val theme = LocalCityTheme.current
    val photo = theme.foodPromoBannerImageUrl ?: dishImageUrl
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .shadow(8.dp, RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(theme.foodPromoBrush)
    ) {
        if (!photo.isNullOrBlank()) {
            ProductImage(url = photo, contentDescription = null, modifier = Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF140C06).copy(alpha = 0.88f), Color(0xFF140C06).copy(alpha = 0.55f), Color(0xFF140C06).copy(alpha = 0.1f))
                    )
                )
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (coupon != null) {
                Text("TODAY'S OFFER", color = Color(0xFFFFE2BB), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Text(
                    text = couponHeadline(coupon),
                    color = Color.White,
                    fontSize = 24.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.fillMaxWidth(0.72f)
                )
                val min = coupon.minOrderAmount?.takeIf { it > 0.0 }?.let { " on orders above ₹${it.toInt()}" }.orEmpty()
                Text(
                    text = buildAnnotatedString {
                        append("Use code ")
                        pushStyle(SpanStyle(fontWeight = FontWeight.ExtraBold))
                        append(coupon.code)
                        pop()
                        append(min)
                        if (coupon.isCashback) append(" · cashback to your wallet after delivery")
                    },
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(0.75f)
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    onClick = { onCopyCode(coupon.code) },
                    shape = RoundedCornerShape(10.dp),
                    color = androidx.compose.ui.graphics.lerp(theme.foodPromoStart, Color.Black, 0.14f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                        Text("COPY CODE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp)
                    }
                }
                return@Column
            }
            Text("HOT & FRESH", color = Color(0xFFFFE2BB), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Text(
                text = if (threshold != null) "FREE delivery\nabove ₹${threshold.toInt()}" else "Your favourite hotels, delivered",
                color = Color.White,
                fontSize = 24.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.fillMaxWidth(0.72f)
            )
            Text(
                "Biryani, meals, snacks & more from local hotels",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(0.75f)
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                onClick = onOrder,
                shape = RoundedCornerShape(10.dp),
                color = androidx.compose.ui.graphics.lerp(theme.foodPromoStart, Color.Black, 0.14f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("ORDER NOW", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}


/** Horizontal "Most Selling" grocery row — fixed-width cards with a "N sold" badge. */
@Composable
fun MostSellingRow(
    products: List<ResolvedProduct>,
    groceryCart: List<CartItem>,
    onIncrease: (ResolvedProduct) -> Unit,
    onDecrease: (ResolvedProduct) -> Unit,
    onSelectSize: (ResolvedProduct) -> Unit,
    cityOpen: Boolean = true,
    title: String = "🔥 Most Selling",
    tint: Color = Color.Transparent
) {
    // Flat section (no tinted box): a strong title, then a row of 146dp cards.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp)
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
        ) {
            items(products, key = { it.id }) { product ->
                val qty = groceryCart.filter { it.productId == product.id }.sumOf { it.quantity }
                Box(modifier = Modifier.width(146.dp)) {
                    GroceryProductCard(
                        product = product,
                        quantityInCart = qty,
                        onIncrease = { onIncrease(product) },
                        onDecrease = { onDecrease(product) },
                        onSelectSize = { onSelectSize(product) },
                        cityOpen = cityOpen
                    )
                }
            }
        }
    }
}

/** Horizontal "Most Ordered" food row — dish + hotel + offer price; tapping opens the hotel. */
@Composable
fun MostOrderedFoodRow(
    products: List<ResolvedProduct>,
    onOpenHotel: (ResolvedProduct) -> Unit,
    title: String = "🔥 Most Ordered",
    tint: Color = Color.Transparent
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp)
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
        ) {
            items(products, key = { it.id }) { product ->
                val fPromo = promoFor(PromoKind.FOOD)
                Card(
                    modifier = Modifier
                        .width(146.dp)
                        .clickable { onOpenHotel(product) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, OutlineSoft),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(104.dp)
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            ProductImage(url = product.imageUrl, contentDescription = product.name, modifier = Modifier.fillMaxSize())
                            if (product.soldCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(LocalCityTheme.current.soldBadgeBrush)
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text("🔥 ${product.soldCount} sold", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = product.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = TextPrimary
                        )
                        product.soldVendorName?.let {
                            Text(text = it, style = MaterialTheme.typography.labelSmall, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (fPromo != null && fPromo.percent > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "₹${"%.0f".format(offerPrice(product.effectivePrice, fPromo.percent))}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "₹${"%.0f".format(product.effectivePrice)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    textDecoration = TextDecoration.LineThrough,
                                    color = TextMuted,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Text(
                                text = "₹${"%.0f".format(product.effectivePrice)}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

// A food-search result: the dish itself (image, name, its hotel, price) with a direct Add.
@Composable
fun SearchDishCard(
    product: ResolvedProduct,
    quantity: Int,
    onOpenHotel: () -> Unit,
    onAdd: (Int) -> Unit
) {
    val orderable = product.guaranteedAvailable || (product.hotelOpen && product.isHotelItemAvailable)
    val fPromo = promoFor(PromoKind.FOOD)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, OutlineSoft),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpenHotel() }
            ) {
                ProductImage(url = product.imageUrl, contentDescription = product.name, modifier = Modifier.fillMaxSize())
                if (!orderable) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (product.hotelOpen) "N/A" else "Closed",
                            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                product.isVeg?.let { veg ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .size(12.dp)
                            .background(Color.White, RoundedCornerShape(3.dp))
                            .border(1.5.dp, if (veg) Color(0xFF0F8A3F) else Color(0xFFB91C1C), RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(if (veg) Color(0xFF0F8A3F) else Color(0xFFB91C1C), CircleShape)
                        )
                    }
                }
                product.discountPercent?.takeIf { it > 0 }?.let { pct ->
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFF15803D)
                    ) {
                        Text(
                            "${pct.toInt()}% OFF",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = TextPrimary
                )
                if (product.soldVendorName != null || product.vendorRating != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onOpenHotel() }) {
                        product.soldVendorName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        product.vendorRating?.let { rating ->
                            Spacer(modifier = Modifier.width(5.dp))
                            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(11.dp))
                            Text(
                                text = "%.1f".format(rating),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB45309)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (fPromo != null && fPromo.percent > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "₹${"%.0f".format(offerPrice(product.effectivePrice, fPromo.percent))}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "₹${"%.0f".format(product.effectivePrice)}",
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = TextDecoration.LineThrough,
                            color = TextMuted,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = "₹${"%.0f".format(product.effectivePrice)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            if (orderable) {
                QuantityStepper(
                    quantity = quantity,
                    onIncrease = { onAdd(1) },
                    onDecrease = { onAdd(-1) }
                )
            } else {
                OutlinedButton(
                    onClick = onOpenHotel,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.5.dp, NaturalPrimary.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text("View", maxLines = 1, fontWeight = FontWeight.ExtraBold, color = NaturalPrimary)
                }
            }
        }
    }
}

// Smart Food Search: Recent / Trending pills (below the search bar, shown when empty).
@Composable
fun SearchSuggestRow(
    recentSearches: List<String>,
    trendingSearches: List<String>,
    onPick: (String) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (recentSearches.isNotEmpty()) {
            Text(
                "RECENT",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextMuted,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRowPills {
                recentSearches.take(5).forEach { q ->
                    SearchPill(text = q, trending = false, onClick = { onPick(q) })
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        if (trendingSearches.isNotEmpty()) {
            Text(
                "TRENDING",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextMuted,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRowPills {
                trendingSearches.take(6).forEach { q ->
                    SearchPill(text = q, trending = true, onClick = { onPick(q) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowPills(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

@Composable
private fun SearchPill(text: String, trending: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        shape = shape,
        color = if (trending) Color.Transparent else Color.White,
        border = BorderStroke(1.dp, if (trending) Color(0xFFFDBA74) else OutlineSoft),
        shadowElevation = 1.dp,
        modifier = Modifier.clip(shape).clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (trending) Modifier.background(Brush.verticalGradient(listOf(Color(0xFFFFF7ED), Color(0xFFFFEDD5)))) else Modifier
                )
                .padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (trending) {
                Text("🔥", fontSize = 11.sp)
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (trending) Color(0xFF9A3412) else TextPrimary
            )
        }
    }
}

@Composable
fun FoodFilterToggleButton(active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (active) NaturalPrimary else Color.White,
        border = BorderStroke(1.dp, if (active) NaturalPrimary else com.example.ui.theme.OutlineBorder),
        shadowElevation = 1.dp,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                tint = if (active) Color.White else TextPrimary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text("Filters", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (active) Color.White else TextPrimary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoodSearchFiltersBar(
    sort: String,
    onSortChange: (String) -> Unit,
    veg: String,
    onVegChange: (String) -> Unit,
    budgetOnly: Boolean,
    onBudgetToggle: () -> Unit,
    ratingOnly: Boolean,
    onRatingToggle: () -> Unit,
    offersOnly: Boolean,
    onOffersToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, OutlineSoft),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                FoodChip(label = "All", selected = veg == "all", onClick = { onVegChange("all") })
                FoodChip(label = "🟢 Veg", selected = veg == "veg", activeColor = Color(0xFF0F8A3F), onClick = { onVegChange("veg") })
                FoodChip(label = "🔴 Non-Veg", selected = veg == "nonveg", activeColor = Color(0xFFB91C1C), onClick = { onVegChange("nonveg") })
                FoodChip(label = "Under ₹149", selected = budgetOnly, onClick = onBudgetToggle)
                FoodChip(label = "⭐ 4.0+", selected = ratingOnly, onClick = onRatingToggle)
                FoodChip(label = "% Offers", selected = offersOnly, onClick = onOffersToggle)
            }
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = OutlineSoft)
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sort by", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                Spacer(modifier = Modifier.width(10.dp))
                var expanded by remember { mutableStateOf(false) }
                val sortLabels = mapOf(
                    "price_asc" to "Price: Low to High",
                    "price_desc" to "Price: High to Low",
                    "popular" to "Popular",
                    "rating" to "Best Rated",
                    "relevance" to "Best Match"
                )
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, com.example.ui.theme.OutlineBorder),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expanded = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(sortLabels[sort] ?: sort, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextMuted)
                        }
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        sortLabels.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { onSortChange(key); expanded = false })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodChip(
    label: String,
    selected: Boolean,
    activeColor: Color = NaturalPrimary,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = if (selected) activeColor else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (selected) activeColor else com.example.ui.theme.OutlineBorder),
        modifier = Modifier.clip(shape).clickable { onClick() }
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else TextPrimary
        )
    }
}

@Composable
fun GroceryProductCard(
    product: ResolvedProduct,
    quantityInCart: Int,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onSelectSize: () -> Unit = {},
    cityOpen: Boolean = true
) {
    val isInStockAndActive = cityOpen && product.isInStockAndActive

    val theme = LocalCityTheme.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("product_card_${product.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, OutlineSoft),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                ProductImage(
                    url = product.imageUrl,
                    contentDescription = product.name,
                    modifier = Modifier.fillMaxSize()
                )
                if (isInStockAndActive && product.isFeatured) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = theme.ocean,
                        contentColor = Color.White,
                        shadowElevation = 2.dp
                    ) {
                        Text(
                            text = "FEATURED",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.4.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
                if (isInStockAndActive && product.soldCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(theme.soldBadgeBrush)
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "🔥 ${product.soldCount} sold",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!isInStockAndActive) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(shape = RoundedCornerShape(6.dp), color = NaturalBadgeRed, contentColor = Color.White) {
                            Text(
                                text = "OUT OF STOCK",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = product.name,
                fontSize = 13.5.sp,
                lineHeight = 17.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (isInStockAndActive) TextPrimary else TextMuted
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (product.hasVariants) {
                val startingPrice = product.startingPrice ?: product.effectivePrice
                val gPromo = promoFor(PromoKind.GROCERY)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(if (isInStockAndActive) 1f else 0.7f)
                ) {
                    Text("From", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextMuted)
                    Spacer(modifier = Modifier.width(4.dp))
                    if (gPromo != null && gPromo.percent > 0) {
                        Text(
                            text = "₹${"%.0f".format(offerPrice(startingPrice, gPromo.percent))}",
                            fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary,
                            maxLines = 1, softWrap = false
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "₹${"%.0f".format(startingPrice)}",
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = TextDecoration.LineThrough,
                            color = TextMuted, maxLines = 1, softWrap = false
                        )
                    } else {
                        Text(
                            text = "₹${"%.0f".format(startingPrice)}",
                            fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary,
                            maxLines = 1, softWrap = false
                        )
                    }
                }
            } else {
                PriceDisplay(
                    price = product.effectivePrice,
                    mrp = product.effectiveMrp,
                    unit = product.unit,
                    kind = PromoKind.GROCERY,
                    modifier = Modifier.alpha(if (isInStockAndActive) 1f else 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isInStockAndActive) {
                if (product.hasVariants) {
                    VariantAddButton(
                        quantityInCart = quantityInCart,
                        onClick = onSelectSize,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "select_size_${product.id}"
                    )
                } else {
                    QuantityStepper(
                        quantity = quantityInCart,
                        onIncrease = onIncrease,
                        onDecrease = onDecrease,
                        modifier = Modifier.fillMaxWidth(),
                        testTagPrefix = "grocery_${product.id}"
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, OutlineSoft),
                    modifier = Modifier.fillMaxWidth().height(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Out of Stock", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMuted)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceryVariantPickerSheet(
    product: ResolvedProduct,
    groceryCart: List<CartItem>,
    onAddToCart: (variantId: String, delta: Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("variant_picker_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    ProductImage(
                        url = product.imageUrl,
                        contentDescription = product.name,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Select quantity / size",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            val sortedVariants = remember(product) {
                product.variants.sortedBy { it.price }
            }
            // Offer (backend-driven) so each size shows its discounted price, matching checkout.
            val sheetPromo = promoFor(PromoKind.GROCERY)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                sortedVariants.forEach { variant ->
                    val inCart = groceryCart.find { it.productId == product.id && it.variantId == variant.id }
                    val qtyInCart = inCart?.quantity ?: 0
                    val isVariantInStock = variant.isInStock

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isVariantInStock) 0.35f else 0.15f),
                        border = BorderStroke(
                            1.dp,
                            if (qtyInCart > 0) NaturalPrimary.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("variant_row_${variant.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = variant.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isVariantInStock) TextPrimary else TextMuted
                                )
                                if (sheetPromo != null && sheetPromo.percent > 0 && isVariantInStock) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "₹${"%.0f".format(offerPrice(variant.price, sheetPromo.percent))}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = OfferRed,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "₹${"%.0f".format(variant.price)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            textDecoration = TextDecoration.LineThrough,
                                            color = TextMuted,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "₹${"%.0f".format(variant.price)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isVariantInStock) PriceGreen else TextMuted
                                    )
                                }
                            }

                            if (!isVariantInStock) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = "Out of Stock",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            } else {
                                QuantityStepper(
                                    quantity = qtyInCart,
                                    onIncrease = { onAddToCart(variant.id, 1) },
                                    onDecrease = { onAddToCart(variant.id, -1) },
                                    testTagPrefix = "variant_${variant.id}"
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("close_variant_picker_button"),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary)
            ) {
                Text(
                    text = "Done",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
private fun FoodCategoryGrid(
    categories: List<Category>,
    selectedCategoryName: String?,
    onCategoryClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "What's on your mind?",
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        val rows = categories.chunked(4)
        rows.forEachIndexed { index, rowCategories ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                rowCategories.forEach { category ->
                    FoodCategoryTile(
                        category = category,
                        isSelected = selectedCategoryName?.equals(category.name, ignoreCase = true) == true,
                        onClick = { onCategoryClick(category.name) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Keep the last (possibly partial) row left-aligned instead of stretched.
                repeat(4 - rowCategories.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (index != rows.lastIndex) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun FoodCategoryTile(
    category: Category,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .testTag("food_category_${category.id}")
            .padding(vertical = 2.dp)
    ) {
        Surface(
            shape = CircleShape,
            modifier = Modifier.size(60.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 3.dp,
            border = BorderStroke(3.dp, if (isSelected) Color(0xFFFC8019) else Color.Transparent)
        ) {
            ProductImage(
                url = category.imageUrl,
                contentDescription = category.name,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = category.name,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color(0xFFFC8019) else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private data class HotelListSection(val tier: Int, val title: String)

// Order matches Vendor.sortTier exactly: Featured+Open, Open, Featured+Closed, Closed.
private val HotelListSections = listOf(
    HotelListSection(0, "Featured Hotels – Open Now"),
    HotelListSection(1, "Open Hotels"),
    HotelListSection(2, "Featured Hotels – Closed"),
    HotelListSection(3, "Closed Hotels")
)

@Composable
private fun HotelSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 18.sp,
        fontWeight = FontWeight.ExtraBold,
        color = TextPrimary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

/**
 * Auto-sliding photo+price strip of a hotel's top dishes (Swiggy-style). Caller decides the
 * fallback-to-static-banner condition (topItems is empty); this composable just renders the
 * slides. Reused by [HotelCard]'s own banner and the hero banner on the hotel menu/detail page.
 *
 * Built on [HorizontalPager] rather than swapping a single image's URL on a timer: the pager
 * composes each page's `ProductImage`/Coil request independently and keeps nearby pages composed,
 * so there is no single <img>-style element being re-pointed mid-flight. An earlier version did
 * that src-swap and would leave every slide after the first blank on some devices — this
 * architecture has no such swap to race.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HotelItemsCarousel(
    topItems: List<Product>,
    vendorName: String,
    isOpen: Boolean,
    modifier: Modifier = Modifier,
    intervalMs: Long = 2800L
) {
    if (topItems.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { topItems.size })
    LaunchedEffect(topItems.size) {
        while (true) {
            delay(intervalMs)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % topItems.size)
            }
        }
    }
    val grayscaleFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }
    Box(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = topItems[page]
            Box(modifier = Modifier.fillMaxSize()) {
                ProductImage(
                    url = item.imageUrl,
                    contentDescription = "${item.name} at $vendorName",
                    modifier = Modifier.fillMaxSize(),
                    colorFilter = if (isOpen) null else grayscaleFilter
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.78f),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "${item.name} at ₹${item.price.toInt()}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            topItems.forEachIndexed { i, _ ->
                Box(
                    modifier = Modifier
                        .size(width = if (i == pagerState.currentPage) 14.dp else 5.dp, height = 5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.55f))
                )
            }
        }
    }
}

@Composable
fun HotelCard(
    vendor: Vendor,
    repository: SndmartRepository,
    onClick: () -> Unit
) {
    var operatingSlots by remember(vendor.id) { mutableStateOf<List<OperatingSlot>>(emptyList()) }
    LaunchedEffect(vendor.id) {
        val slotsRes = repository.getVendorOperatingSlots(vendor.id)
        if (slotsRes.isSuccess) {
            operatingSlots = slotsRes.getOrNull() ?: emptyList()
        }
    }

    val isWithinHours = if (operatingSlots.isNotEmpty()) {
        isWithinAnySlot(operatingSlots)
    } else {
        isVendorWithinOperatingHours(vendor.openingTime, vendor.closingTime)
    }
    val isOpen = vendor.isOpen && vendor.isActive && isWithinHours
    val grayscaleFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }

    // Average rating from vendor_reviews (computed client-side). Fetched per card so
    // only visible hotels make the call; 0.0 means "no reviews yet".
    var avgRating by remember(vendor.id) { mutableStateOf(0.0) }
    LaunchedEffect(vendor.id) {
        avgRating = repository.getVendorAverageRating(vendor.id).getOrNull() ?: 0.0
    }

    // Top dish photos for the Swiggy-style auto-sliding banner; falls back to the plain static
    // banner below only when there are none at all.
    var topItems by remember(vendor.id) { mutableStateOf<List<Product>>(emptyList()) }
    LaunchedEffect(vendor.id) {
        topItems = repository.getHotelTopItems(vendor.id).getOrNull() ?: emptyList()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isOpen) 1f else 0.75f)
            .clickable { onClick() }
            .testTag("hotel_card_${vendor.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, OutlineSoft),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                if (topItems.isNotEmpty()) {
                    HotelItemsCarousel(
                        topItems = topItems,
                        vendorName = vendor.name,
                        isOpen = isOpen,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ProductImage(
                        url = vendor.bannerUrl,
                        contentDescription = vendor.name,
                        modifier = Modifier.fillMaxSize(),
                        colorFilter = if (isOpen) null else grayscaleFilter
                    )
                }
                // Status chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (vendor.isFeatured == true) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = NaturalOceanBlue,
                            contentColor = Color.White
                        ) {
                            Text(
                                "FEATURED",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (!vendor.isActive) NaturalBadgeRed else if (isOpen) SuccessGreen else TextMuted,
                        contentColor = Color.White
                    ) {
                        Text(
                            text = if (!vendor.isActive) "CURRENTLY CLOSED" else if (isOpen) "OPEN NOW" else "CLOSED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = vendor.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (vendor.isActive) TextPrimary else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "View Menu",
                        tint = NaturalPrimary
                    )
                }

                if (avgRating > 0) {
                    Spacer(modifier = Modifier.height(5.dp))
                    // Green rating pill (website: .rating-pill)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(SuccessGreen)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "%.1f".format(avgRating),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }

                if (!vendor.address.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = TextMuted
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = vendor.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // "7:00 AM - 10:00 PM" (formatted like the website), not the raw "07:00:00"
                fun pretty(t: String?) = com.example.util.clockLabel(t) ?: t.orEmpty()
                val hoursText = when {
                    operatingSlots.isNotEmpty() ->
                        operatingSlots.joinToString(", ") { "${pretty(it.startTime)} - ${pretty(it.endTime)}" }
                    !vendor.openingTime.isNullOrBlank() && !vendor.closingTime.isNullOrBlank() ->
                        "${pretty(vendor.openingTime)} - ${pretty(vendor.closingTime)}"
                    else -> null
                }

                if (hoursText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = TextMuted
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = hoursText,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shown above the hotel list whenever a "What's on your mind?" category tile is active, so the
 * customer always has a visible, one-tap way to see what's filtering the list and clear it —
 * instead of only the (easy to scroll past) highlighted tile in the grid above.
 */
@Composable
fun ActiveCategoryFilterChip(
    categoryName: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("active_food_category_filter_chip"),
        shape = RoundedCornerShape(10.dp),
        color = NaturalPrimaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FilterAlt,
                    contentDescription = null,
                    tint = NaturalPrimaryDark,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Showing: $categoryName",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = NaturalPrimaryDark
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClear)
                    .testTag("clear_food_category_filter_button")
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Clear",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = NaturalPrimaryDark
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear filter",
                    tint = NaturalPrimaryDark,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun FreeDeliveryBanner(threshold: Double, modifier: Modifier = Modifier) {
    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("free_delivery_banner")
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFF6E5))
            .drawBehind {
                drawRoundRect(
                    color = Color(0xFFF5C57A),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(), pathEffect = dash)
                )
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.LocalShipping, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(20.dp))
        Text(
            text = "FREE Delivery on orders above Rs ${threshold.toInt()}!",
            fontWeight = FontWeight.Bold,
            fontSize = 12.5.sp,
            color = Color(0xFFB45309)
        )
    }
}

@Composable
fun CouponPopup(
    coupon: Coupon,
    onDismiss: () -> Unit,
    onCopyCode: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .testTag("coupon_popup_dialog")
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.LocalOffer,
                    contentDescription = null,
                    tint = Color(0xFFEF6C00),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Special Offer For You!",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = com.example.util.couponTerms(coupon),
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFFF3E0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCopyCode(coupon.code) }
                        .testTag("copy_coupon_code_button")
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = coupon.code,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            letterSpacing = 1.sp,
                            color = Color(0xFFEF6C00)
                        )
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = Color(0xFFEF6C00),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("coupon_popup_dismiss_button")
                ) {
                    Text("Start Shopping")
                }
            }
        }
    }
}

@Composable
fun RateUsPopup(
    onRateNow: () -> Unit,
    onMaybeLater: () -> Unit,
    onDontAskAgain: () -> Unit
) {
    Dialog(onDismissRequest = onMaybeLater) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .testTag("rate_us_popup_dialog")
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(5) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Star",
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(28.dp).padding(horizontal = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Enjoying Sndmart?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your feedback helps us improve and helps other people in Sindhanur discover us. A quick 5-star rating would mean a lot!",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onRateNow,
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rate_us_now_button")
                ) {
                    Text("Rate Us Now")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onDontAskAgain,
                        modifier = Modifier.testTag("rate_us_dont_ask_button")
                    ) {
                        Text("Don't Ask Again", fontSize = 12.sp, color = TextSecondary)
                    }
                    TextButton(
                        onClick = onMaybeLater,
                        modifier = Modifier.testTag("rate_us_maybe_later_button")
                    ) {
                        Text("Maybe Later", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }
        }
    }
}

fun discountSummaryText(coupon: Coupon): String {
    val discountPart = if (coupon.discountType == "flat") {
        "Flat Rs ${coupon.discountValue.toInt()} OFF"
    } else {
        "${coupon.discountValue.toInt()}% OFF"
    }
    val minOrderPart = coupon.minOrderAmount?.let {
        if (it > 0) " on orders above Rs ${it.toInt()}" else ""
    } ?: ""
    return "$discountPart$minOrderPart"
}

