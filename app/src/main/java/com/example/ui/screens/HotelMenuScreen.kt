package com.example.ui.screens

import androidx.activity.compose.BackHandler
import com.example.data.analytics.Analytics
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.*
import com.example.data.repository.AddToCartResult
import com.example.data.repository.SndmartRepository
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.CategoryTabState
import com.example.util.isVendorWithinOperatingHours
import com.example.util.isWithinAnySlot
import com.example.util.orderHotelCategoryTabs
import com.example.util.pickDefaultHotelCategoryId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun getHotelMenuItemTier(prod: ResolvedProduct, isHotelActive: Boolean, vendorSlots: List<OperatingSlot> = emptyList()): Int {
    val isAvail = isHotelActive && prod.isHotelItemAvailable(vendorSlots)
    return when {
        isAvail && prod.isFeatured -> 0
        isAvail -> 1
        else -> 2
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotelMenuScreen(
    vendorId: String,
    vendorName: String,
    cityId: String,
    repository: SndmartRepository,
    onBack: () -> Unit,
    onNavigateToCart: () -> Unit,
    onProceedToCheckout: (isHotel: Boolean) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var vendor by remember { mutableStateOf<Vendor?>(null) }
    var operatingSlots by remember { mutableStateOf<List<OperatingSlot>>(emptyList()) }
    // City Admin's Operating Hours / Emergency Close — caps this hotel's own hours.
    var city by remember(cityId) { mutableStateOf<com.example.data.model.City?>(null) }
    LaunchedEffect(cityId) {
        city = repository.getCity(cityId).getOrNull()
    }
    val effectiveSlots by remember(operatingSlots, vendor) {
        derivedStateOf {
            if (operatingSlots.isNotEmpty()) {
                operatingSlots
            } else if (!vendor?.openingTime.isNullOrBlank() && !vendor?.closingTime.isNullOrBlank()) {
                listOf(
                    OperatingSlot(
                        vendorId = vendor?.id ?: "",
                        startTime = vendor!!.openingTime!!,
                        endTime = vendor!!.closingTime!!
                    )
                )
            } else {
                emptyList()
            }
        }
    }
    var categories by remember(vendorId) {
        mutableStateOf(repository.getCachedHotelCategories(vendorId) ?: emptyList())
    }
    var allProducts by remember(vendorId) { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    var selectedCategoryId by remember(vendorId) {
        mutableStateOf(categories.firstOrNull()?.id)
    }
    var hasUserSelectedCategory by remember(vendorId) { mutableStateOf(false) }
    var dishQuery by remember(vendorId) { mutableStateOf("") }
    var timeTick by remember { mutableStateOf(0L) }
    var isLoadingCategories by remember { mutableStateOf(categories.isEmpty()) }
    var isLoadingProducts by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val menuListState = rememberLazyListState()
    val tabListState = rememberLazyListState()

    val hotelCart by repository.hotelCart.collectAsState()
    var pendingConflict by remember { mutableStateOf<AddToCartResult.HotelConflict?>(null) }

    val thisHotelCartItems = remember(hotelCart, vendorId) {
        hotelCart.filter { it.vendorId == vendorId || it.vendorId.isNullOrBlank() }
    }
    val thisHotelItemCount = thisHotelCartItems.sumOf { it.quantity }

    var freshHotelCartItems by remember { mutableStateOf<List<CartItemUi>>(emptyList()) }
    LaunchedEffect(hotelCart, cityId) {
        if (cityId.isNotBlank() && hotelCart.isNotEmpty()) {
            val res = repository.getFreshCartItems(isHotel = true, cityId = cityId)
            if (res.isSuccess) {
                freshHotelCartItems = res.getOrNull() ?: emptyList()
            }
        } else {
            freshHotelCartItems = emptyList()
        }
    }

    val thisHotelTotalPrice = remember(thisHotelCartItems, freshHotelCartItems, allProducts) {
        val freshForThis = freshHotelCartItems.filter { it.cartItem.vendorId == vendorId || it.cartItem.vendorId.isNullOrBlank() }
        if (freshForThis.isNotEmpty()) {
            freshForThis.sumOf { it.totalPrice }
        } else {
            thisHotelCartItems.sumOf { item ->
                val prod = allProducts.find { it.id == item.productId }
                (prod?.effectivePrice ?: 0.0) * item.quantity
            }
        }
    }

    BackHandler {
        onBack()
    }

    // Option A: fetch ALL of this hotel's menu products once (not paginated per category), so
    // category switching is instant and every tab's "available now" status can be computed
    // upfront (tabs and items share isHotelItemAvailable, so they never disagree).
    fun loadInitialData(forceRefresh: Boolean = false) {
        if (!forceRefresh && categories.isNotEmpty() && vendor != null && allProducts.isNotEmpty()) {
            return
        }
        coroutineScope.launch {
            if (categories.isEmpty()) {
                isLoadingCategories = true
            }
            if (allProducts.isEmpty()) {
                isLoadingProducts = true
            }
            errorMessage = null
            coroutineScope {
                val vDeferred = async { repository.getVendor(vendorId) }
                val sDeferred = async { repository.getVendorOperatingSlots(vendorId) }
                val cDeferred = async { repository.getHotelCategories(vendorId, forceRefresh = forceRefresh) }
                val pDeferred = async {
                    repository.getHotelProducts(
                        vendorId = vendorId,
                        cityId = cityId,
                        categoryId = null,
                        limit = 500,
                        offset = 0,
                        forceRefresh = forceRefresh
                    )
                }

                val vRes = vDeferred.await()
                if (vRes.isSuccess) {
                    vendor = vRes.getOrNull()
                }

                val sRes = sDeferred.await()
                if (sRes.isSuccess) {
                    operatingSlots = sRes.getOrNull() ?: emptyList()
                }

                val cRes = cDeferred.await()
                if (cRes.isSuccess) {
                    categories = cRes.getOrNull() ?: emptyList()
                } else {
                    errorMessage = cRes.exceptionOrNull()?.message
                }

                val pRes = pDeferred.await()
                if (pRes.isSuccess) {
                    allProducts = pRes.getOrNull() ?: emptyList()
                } else if (errorMessage == null) {
                    errorMessage = pRes.exceptionOrNull()?.message
                }
            }
            isLoadingCategories = false
            isLoadingProducts = false
        }
    }

    LaunchedEffect(vendorId, cityId) {
        loadInitialData()
    }

    // Recalculate category availability/order once a minute, and immediately on resume.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            timeTick = System.currentTimeMillis()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                timeTick = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isHotelActive = vendor?.isActive ?: true
    val isWithinHours = isWithinAnySlot(effectiveSlots)
    val isHotelOpen = isHotelActive && isWithinHours && com.example.util.isCityOpenNow(city)

    // Category tab ordering/availability (spec rules 1-2): recomputed whenever the underlying
    // data, hotel-open state, or timeTick (minute tick / resume) changes.
    val orderedTabs = remember(categories, allProducts, effectiveSlots, isHotelOpen, timeTick) {
        orderHotelCategoryTabs(categories, allProducts, effectiveSlots, isHotelOpen)
    }

    // Default-selection (spec rule 3): only while the customer hasn't tapped a tab themselves.
    LaunchedEffect(orderedTabs, vendor?.defaultCategoryId) {
        if (!hasUserSelectedCategory) {
            val defaultId = pickDefaultHotelCategoryId(orderedTabs, vendor?.defaultCategoryId, isHotelOpen)
            if (defaultId != null) {
                selectedCategoryId = defaultId
            }
        }
    }

    // Auto-scroll the tab row so the selected tab is fully visible, and the item list to the top.
    LaunchedEffect(selectedCategoryId, orderedTabs) {
        val idx = orderedTabs.indexOfFirst { it.category.id == selectedCategoryId }
        if (idx >= 0) {
            tabListState.animateScrollToItem(idx)
        }
    }

    LaunchedEffect(selectedCategoryId) {
        if (menuListState.layoutInfo.totalItemsCount > 0) {
            menuListState.animateScrollToItem(0)
        }
    }

    // Single-hotel conflict dialog
    if (pendingConflict != null) {
        val conflict = pendingConflict!!
        AlertDialog(
            onDismissRequest = { pendingConflict = null },
            title = { Text("Replace Hotel Cart Items?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Your cart already contains items from another hotel. Clear existing items and add from $vendorName?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.forceClearHotelCartAndAdd(conflict.pendingItem)
                        pendingConflict = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Cart cleared and item added")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Clear & Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConflict = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = vendorName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Hotel Menu",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("hotel_menu_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            loadInitialData(forceRefresh = true)
                        },
                        modifier = Modifier.testTag("hotel_menu_refresh_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Menu")
                    }
                    IconButton(onClick = onNavigateToCart) {
                        val count = hotelCart.sumOf { it.quantity }
                        BadgedBox(
                            badge = {
                                if (count > 0) {
                                    Badge(containerColor = NaturalPrimary, contentColor = Color.White) {
                                        Text("$count", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = "View Cart")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AnimatedVisibility(
                visible = thisHotelItemCount > 0,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                FloatingCartButton(
                    itemCount = thisHotelItemCount,
                    totalPrice = thisHotelTotalPrice,
                    onClick = { onProceedToCheckout(true) },
                    testTag = "hotel_floating_checkout_button"
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if ((isLoadingCategories || isLoadingProducts) && allProducts.isEmpty()) {
                // Rule 7: Skeleton loading state instead of a spinner
                OrderListSkeleton(count = 5)
            } else if (errorMessage != null && allProducts.isEmpty()) {
                ErrorCard(
                    message = errorMessage!!,
                    onRetry = {
                        loadInitialData(forceRefresh = true)
                    },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                // Filter items reacting to the selected category (Swiggy-style)
                // The item grid should always be filtered to whichever category is currently selected - never show all of this hotel's items unfiltered.
                // Preserving existing sort logic: available+featured first, unavailable last
                val categoryProducts = remember(allProducts, selectedCategoryId, isHotelOpen, effectiveSlots) {
                    allProducts
                        .filter { it.categoryId == selectedCategoryId }
                        .sortedWith(
                            compareBy<ResolvedProduct> { getHotelMenuItemTier(it, isHotelOpen, effectiveSlots) }
                                .thenByDescending { it.isFeatured }
                                .thenBy { it.name.lowercase() }
                        )
                }
                // Filters the already-loaded category's items for THIS hotel only — no server
                // round trip, the category's menu is small enough to filter on every keystroke.
                val trimmedQuery = dishQuery.trim()
                val displayedProducts = remember(categoryProducts, trimmedQuery) {
                    if (trimmedQuery.isBlank()) categoryProducts
                    else categoryProducts.filter { it.name.contains(trimmedQuery, ignoreCase = true) }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    if (!isHotelOpen) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = NaturalBadgeRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, NaturalBadgeRed.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = NaturalBadgeRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                val bannerMessage = when {
                                    !isWithinHours && operatingSlots.isNotEmpty() ->
                                        "This hotel is currently closed outside operating hours (${operatingSlots.joinToString(", ") { "${it.startTime}-${it.endTime}" }}). Ordering is unavailable."
                                    !isWithinHours && !vendor?.openingTime.isNullOrBlank() ->
                                        "This hotel is currently closed. Opens at ${vendor?.openingTime}."
                                    !isWithinHours ->
                                        "This hotel is currently closed outside operating hours. Ordering is unavailable."
                                    else ->
                                        "This restaurant is currently closed. You can browse the menu, but ordering is unavailable."
                                }
                                Text(
                                    text = bannerMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = NaturalBadgeRed
                                )
                            }
                        }
                    }

                    if (categories.isNotEmpty() || allProducts.isNotEmpty()) {
                        OutlinedTextField(
                            value = dishQuery,
                            onValueChange = { dishQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .testTag("hotel_dish_search_input"),
                            placeholder = {
                                Text(
                                    "Search for dishes",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
                            },
                            trailingIcon = {
                                if (dishQuery.isNotEmpty()) {
                                    IconButton(onClick = { dishQuery = "" }) {
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
                    }

                    // 1 & 4: Horizontal category row (fixed at top, does not scroll away)
                    if (categories.isNotEmpty()) {
                        HotelCategoryTabsRow(
                            tabs = orderedTabs,
                            selectedCategoryId = selectedCategoryId,
                            onCategorySelected = {
                                selectedCategoryId = it
                                hasUserSelectedCategory = true
                            },
                            listState = tabListState,
                            modifier = Modifier.fillMaxWidth()
                        )

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            thickness = 1.dp
                        )
                    }

                    // 3 & 4: Single scrollable grid/list of items below reacting to the selected tab
                    if (displayedProducts.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Outlined.RestaurantMenu,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (categories.isEmpty()) "No menu items currently listed for this hotel."
                                    else "No items found in this category.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = menuListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("hotel_menu_items_list"),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item(key = "hotel_menu_offer_banner") {
                                OfferBanner(kind = PromoKind.FOOD)
                            }

                            if (!vendor?.bannerUrl.isNullOrBlank()) {
                                item(key = "hotel_banner_header") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f)
                                            .clip(RoundedCornerShape(16.dp))
                                    ) {
                                        ProductImage(
                                            url = vendor!!.bannerUrl,
                                            contentDescription = vendorName,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }

                            // Swiggy-style 2-column dish grid — a plain chunked-row grid (not a
                            // nested LazyVerticalGrid) since this already sits inside the outer
                            // LazyColumn, matching the pattern used for grocery category chips.
                            items(displayedProducts.chunked(2), key = { row -> row.joinToString(",") { it.id } }) { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    row.forEach { product ->
                                        val inCart = hotelCart.find { it.productId == product.id }
                                        val qty = inCart?.quantity ?: 0
                                        MenuGridCard(
                                            product = product,
                                            quantityInCart = qty,
                                            isHotelActive = isHotelOpen,
                                            vendorSlots = effectiveSlots,
                                            modifier = Modifier.weight(1f),
                                            onIncrease = {
                                                val res = repository.addToCart(
                                                    productId = product.id,
                                                    vendorId = vendorId,
                                                    cityId = cityId,
                                                    quantityDelta = 1,
                                                    isHotel = true,
                                                    productForSnapshot = product
                                                )
                                                if (res is AddToCartResult.HotelConflict) {
                                                    pendingConflict = res
                                                }
                                            },
                                            onDecrease = {
                                                repository.addToCart(
                                                    productId = product.id,
                                                    vendorId = vendorId,
                                                    cityId = cityId,
                                                    quantityDelta = -1,
                                                    isHotel = true
                                                )
                                            },
                                            onView = {
                                                Analytics.trackEvent(
                                                    "product_view",
                                                    mapOf("product_id" to product.id, "category" to "hotel")
                                                )
                                            }
                                        )
                                    }
                                    // Pad an odd-sized last row so the single card keeps half width.
                                    if (row.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
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

/**
 * Swiggy-style horizontal scrollable row of circular category tabs.
 * Only the hotel's real categories (e.g. "Biriyani", "fast food") are displayed (no "All" tab).
 * Selected category tab is visually highlighted with an active orange ring.
 */
@Composable
fun HotelCategoryTabsRow(
    tabs: List<CategoryTabState>,
    selectedCategoryId: String?,
    onCategorySelected: (String) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("hotel_category_tabs_row"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(tabs, key = { it.category.id }) { tab ->
                HotelCategoryCircleTab(
                    title = tab.category.name,
                    imageUrl = tab.category.imageUrl,
                    isSelected = selectedCategoryId == tab.category.id,
                    isAvailable = tab.isAvailableNow,
                    hint = tab.availableFromHint,
                    onClick = { onCategorySelected(tab.category.id) },
                    testTag = "hotel_category_tab_${tab.category.id}"
                )
            }
        }
    }
}

/**
 * Circular Category Tab Item (Swiggy-style):
 * - 64dp circular avatar with active colored ring when selected.
 * - Center-aligned label below with clear bold contrast on selection.
 */
@Composable
fun HotelCategoryCircleTab(
    title: String,
    imageUrl: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isAllOption: Boolean = false,
    isAvailable: Boolean = true,
    hint: String? = null,
    testTag: String = ""
) {
    val activeColor = NaturalPrimary
    val ringColor = if (isSelected) activeColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val ringWidth = if (isSelected) 2.5.dp else 1.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
            .widthIn(min = 64.dp, max = 76.dp)
            .alpha(if (isAvailable) 1f else 0.5f)
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .border(
                    BorderStroke(ringWidth, ringColor),
                    shape = CircleShape
                )
                .padding(if (isSelected) 2.5.dp else 0.dp)
                .clip(CircleShape)
                .background(
                    if (isAllOption && isSelected) activeColor.copy(alpha = 0.14f)
                    else if (isAllOption) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isAllOption) {
                Icon(
                    imageVector = Icons.Outlined.RestaurantMenu,
                    contentDescription = "All items",
                    tint = if (isSelected) activeColor else TextSecondary,
                    modifier = Modifier.size(26.dp)
                )
            } else if (!imageUrl.isNullOrBlank()) {
                ProductImage(
                    url = imageUrl,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Fastfood,
                    contentDescription = title,
                    tint = if (isSelected) activeColor else TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) activeColor else TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp
        )

        if (!isAvailable && !hint.isNullOrBlank()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Swiggy-style dish grid card: photo on top, name, price, Add below — same visual shape as
// HomeScreen.kt's GroceryProductCard, used in the hotel menu's 2-column dish grid.
@Composable
fun MenuGridCard(
    product: ResolvedProduct,
    quantityInCart: Int,
    isHotelActive: Boolean = true,
    vendorSlots: List<OperatingSlot> = emptyList(),
    modifier: Modifier = Modifier,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onView: () -> Unit = {}
) {
    val isAvailable = isHotelActive && product.isHotelItemAvailable(vendorSlots)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isAvailable) 1f else 0.65f)
            .clickable(onClick = onView)
            .testTag("menu_item_${product.id}"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAvailable) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                ProductImage(
                    url = product.imageUrl,
                    contentDescription = product.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isAvailable) 1f else 0.7f)
                )
                if (isAvailable && product.isFeatured) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = PastelPeach,
                        contentColor = DarkGreenText
                    ) {
                        Text(
                            "POPULAR",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (!isAvailable) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Black.copy(alpha = 0.45f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = NaturalBadgeRed,
                                contentColor = Color.White
                            ) {
                                Text(
                                    text = if (!isHotelActive) "CLOSED" else "UNAVAILABLE",
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
                color = if (isAvailable) TextPrimary else TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!product.baseProduct.availableFrom.isNullOrBlank() && !product.baseProduct.availableUntil.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Available ${product.baseProduct.availableFrom} - ${product.baseProduct.availableUntil}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAvailable) NaturalPrimary else NaturalBadgeRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            PriceDisplay(
                price = product.effectivePrice,
                mrp = product.effectiveMrp,
                unit = product.unit,
                kind = PromoKind.FOOD,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .alpha(if (isAvailable) 1f else 0.7f)
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (isAvailable) {
                QuantityStepper(
                    quantity = quantityInCart,
                    onIncrease = onIncrease,
                    onDecrease = onDecrease,
                    modifier = Modifier.fillMaxWidth(),
                    testTagPrefix = "hotel_item_${product.id}"
                )
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
                            text = if (!isHotelActive) "Closed" else "Unavailable",
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
