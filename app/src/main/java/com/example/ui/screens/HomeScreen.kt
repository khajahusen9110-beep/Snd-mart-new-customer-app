package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalClipboardManager
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
    val unreadNotificationCount by repository.sessionManager.unreadNotificationCount.collectAsState()

    var browsingMode by rememberSaveable { mutableStateOf(BrowsingMode.GROCERY) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    // Sndmart Wallet balance for the top-bar chip (cached for a minute in the repository).
    var walletBalance by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        if (!repository.sessionManager.getUserId().isNullOrBlank()) {
            walletBalance = repository.getMyWallet().getOrNull()?.balance
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
    var searchQuery by remember { mutableStateOf("") }

    // Intercept back navigation when on the Hotels tab to return to the Home screen (Grocery view)
    BackHandler(enabled = browsingMode == BrowsingMode.HOTELS) {
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
            val openVendorIds = hotels.filter { it.isOpenNow }.map { it.id }.toSet()
            displayedFoodCategories = pickRotatingFoodCategories(hotelCategoriesPool, openVendorIds)
            delay(5 * 60 * 1000L)
        }
    }

    val context = LocalContext.current
    val sessionManager = repository.sessionManager

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
                val filtered = list.filter { c ->
                    val n = c.name.lowercase()
                    n.contains("veg") || n.contains("fruit")
                }.ifEmpty { list }
                categories = filtered
                val vegCategory = filtered.firstOrNull { it.name.lowercase().contains("veg") }
                    ?: filtered.firstOrNull()
                if (vegCategory != null && (selectedCategoryId == null || filtered.none { it.id == selectedCategoryId })) {
                    selectedCategoryId = vegCategory.id
                }
                Log.d("HomeScreen", "Loaded ${filtered.size} categories: ${filtered.map { it.name }}")
            } else {
                val error = catRes.exceptionOrNull()
                Log.e("HomeScreen", "Categories fetch error: ${error?.message}", error)
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

    Scaffold(
        topBar = {
            SndmartTopBar(
                currentCity = selectedCity,
                cartCount = totalCartCount,
                deliveryAddressLabel = deliveryAddressLabel,
                unreadNotificationCount = unreadNotificationCount,
                navigationIcon = if (browsingMode == BrowsingMode.HOTELS) {
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
                    Text(
                        if (browsingMode == BrowsingMode.GROCERY) "Search groceries or hotels..."
                        else "Search hotels, restaurants, cuisines...",
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
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceVariantLight,
                    unfocusedContainerColor = SurfaceVariantLight,
                    focusedBorderColor = NaturalPrimary,
                    unfocusedBorderColor = OutlineBorder
                ),
                singleLine = true
            )

            // Natural Tones Segmented Mode Pill
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = NaturalPrimaryContainer,
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
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isGrocery) NaturalPrimary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { browsingMode = BrowsingMode.GROCERY }
                            .testTag("toggle_grocery")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = "Fresh Groceries & Fruits",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                color = if (isGrocery) Color.White else TextSecondary
                            )
                        }
                    }

                    val isHotel = browsingMode == BrowsingMode.HOTELS
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isHotel) NaturalPrimary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { browsingMode = BrowsingMode.HOTELS }
                            .testTag("toggle_hotels")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = "Hotel Food & Dining",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                color = if (isHotel) Color.White else TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

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
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (searchQuery.isBlank()) {
                            item(span = { GridItemSpan(2) }) {
                                OfferBanner(kind = PromoKind.GROCERY, modifier = Modifier.padding(top = 6.dp))
                            }
                        }

                        freeDeliveryThreshold?.let { threshold ->
                            item(span = { GridItemSpan(2) }) {
                                FreeDeliveryBanner(threshold = threshold)
                            }
                        }

                        if (searchQuery.isBlank()) {
                            // Super-Admin banners, else the default banner
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

                            // Horizontal scroll of grocery category image cards (image_url + name)
                            item(span = { GridItemSpan(2) }) {
                                GroceryCategoryList(
                                    categories = categories,
                                    selectedCategoryId = selectedCategoryId,
                                    onCategorySelected = { selectedCategoryId = it }
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

                    if (filteredHotels.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No hotels or restaurants available in this city.",
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
                            if (searchQuery.isBlank()) {
                                item(key = "food_offer_banner") {
                                    OfferBanner(kind = PromoKind.FOOD)
                                }
                            }

                            if (searchQuery.isBlank() && promoBanners.isNotEmpty()) {
                                item(key = "food_promo_banners") {
                                    PromoBannerCarousel(
                                        banners = promoBanners,
                                        onOpen = { b -> openPromoBanner(b) }
                                    )
                                }
                            }

                            if (searchQuery.isBlank() && displayedFoodCategories.isNotEmpty()) {
                                item {
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

                            selectedFoodCategoryName?.let { name ->
                                item(key = "active_food_category_filter") {
                                    ActiveCategoryFilterChip(
                                        categoryName = name,
                                        onClear = { selectedFoodCategoryName = null }
                                    )
                                }
                            }

                            freeDeliveryThreshold?.let { threshold ->
                                item {
                                    FreeDeliveryBanner(threshold = threshold)
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
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = NaturalOceanBlue),
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Silhouette decorations from Natural Tones design
            Text(
                text = "🥬",
                fontSize = 58.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 8.dp)
                    .alpha(0.25f)
            )
            Text(
                text = "🍅",
                fontSize = 44.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-36).dp, y = 4.dp)
                    .alpha(0.20f)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
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

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White
                ) {
                    Text(
                        text = "SHOP NOW",
                        color = NaturalOceanBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}


@Composable
fun GroceryProductCard(
    product: ResolvedProduct,
    quantityInCart: Int,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onSelectSize: () -> Unit = {}
) {
    val isInStockAndActive = product.isInStockAndActive

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isInStockAndActive) 1f else 0.65f)
            .testTag("product_card_${product.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isInStockAndActive) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                ProductImage(
                    url = product.imageUrl,
                    contentDescription = product.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isInStockAndActive) 1f else 0.7f)
                )
                if (isInStockAndActive && product.isFeatured) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = NaturalOceanBlue,
                        contentColor = Color.White
                    ) {
                        Text(
                            text = "FEATURED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (!isInStockAndActive) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        color = Color.Transparent
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = NaturalBadgeRed,
                                contentColor = Color.White
                            ) {
                                Text(
                                    text = "OUT OF STOCK",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isInStockAndActive) TextPrimary else TextMuted
            )

            if (product.hasVariants) {
                val startingPrice = product.startingPrice ?: product.effectivePrice
                val gPromo = promoFor(PromoKind.GROCERY)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .alpha(if (isInStockAndActive) 1f else 0.7f)
                ) {
                    if (gPromo != null && gPromo.percent > 0) {
                        Text(
                            text = "Starting from ₹${"%.0f".format(offerPrice(startingPrice, gPromo.percent))}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = PriceGreen
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "₹${"%.0f".format(startingPrice)}",
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = TextDecoration.LineThrough,
                            color = TextMuted
                        )
                    } else {
                        Text(
                            text = "Starting from ₹${"%.0f".format(startingPrice)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = PriceGreen
                        )
                    }
                }
            } else {
                PriceDisplay(
                    price = product.effectivePrice,
                    mrp = product.effectiveMrp,
                    unit = product.unit,
                    kind = PromoKind.GROCERY,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .alpha(if (isInStockAndActive) 1f else 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (isInStockAndActive) {
                if (product.hasVariants) {
                    Button(
                        onClick = onSelectSize,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .testTag("select_size_${product.id}"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (quantityInCart > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
                            contentColor = if (quantityInCart > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = if (quantityInCart > 0) "Select Size ($quantityInCart)" else "Select Size",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Out of Stock",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted
                        )
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
                                Text(
                                    text = "₹${"%.0f".format(variant.price)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isVariantInStock) PriceGreen else TextMuted
                                )
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
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
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
            color = if (isSelected) PastelSage else MaterialTheme.colorScheme.surfaceVariant,
            border = if (isSelected) BorderStroke(2.5.dp, NaturalPrimary)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
            color = if (isSelected) NaturalPrimary else TextPrimary,
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
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = TextPrimary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isOpen) 1f else 0.75f)
            .clickable { onClick() }
            .testTag("hotel_card_${vendor.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOpen) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(135.dp)
            ) {
                ProductImage(
                    url = vendor.bannerUrl,
                    contentDescription = vendor.name,
                    modifier = Modifier.fillMaxSize(),
                    colorFilter = if (isOpen) null else grayscaleFilter
                )
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

            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = vendor.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (vendor.isActive) TextPrimary else TextMuted
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "View Menu",
                        tint = NaturalPrimary
                    )
                }

                if (avgRating > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = TangerineOrange,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "%.1f".format(avgRating),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = TextPrimary
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

                val hoursText = when {
                    operatingSlots.isNotEmpty() ->
                        "Hours: " + operatingSlots.joinToString(", ") { "${it.startTime}-${it.endTime}" }
                    !vendor.openingTime.isNullOrBlank() && !vendor.closingTime.isNullOrBlank() ->
                        "Hours: ${vendor.openingTime} - ${vendor.closingTime}"
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("free_delivery_banner")
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFFFF9800), Color(0xFFFF5722))
                )
            )
    ) {
        // Decorative translucent circle, Swiggy-style banner touch.
        Box(
            modifier = Modifier
                .size(90.dp)
                .align(Alignment.CenterEnd)
                .offset(x = 24.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.LocalShipping,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "FREE DELIVERY",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Text(
                    text = "On orders above ₹${threshold.toInt()}",
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.92f)
                )
            }
        }
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

