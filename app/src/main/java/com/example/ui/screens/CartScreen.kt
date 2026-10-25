package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.CART_HOTEL_CLOSED_MESSAGE
import com.example.data.model.CartAvailabilityState
import com.example.data.model.CartItemUi
import com.example.data.model.ResolvedProduct
import com.example.data.repository.SndmartRepository
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(
    cityId: String?,
    repository: SndmartRepository,
    onBack: () -> Unit,
    onProceedToCheckout: (isHotel: Boolean, couponCode: String?) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Toggle between Grocery Cart and Hotel Cart (strictly separate checkouts!)
    var isHotelCartSelected by remember { mutableStateOf(false) }

    val rawGroceryCart by repository.groceryCart.collectAsState()
    val rawHotelCart by repository.hotelCart.collectAsState()

    // Free delivery threshold from city_delivery_settings
    var freeDeliveryThreshold by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(cityId) {
        if (!cityId.isNullOrBlank()) {
            freeDeliveryThreshold = repository.getFreeDeliveryThreshold(cityId)
        }
    }

    // Fresh resolved items for current render
    var freshItems by remember { mutableStateOf<List<CartItemUi>>(emptyList()) }
    var isLoadingFreshPrices by remember { mutableStateOf(true) }
    var errorLoadingPrices by remember { mutableStateOf<String?>(null) }

    // Function to re-fetch live prices at render time
    fun fetchFreshCart() {
        if (cityId == null) return
        coroutineScope.launch {
            isLoadingFreshPrices = true
            errorLoadingPrices = null
            val res = repository.getFreshCartItems(isHotel = isHotelCartSelected, cityId = cityId)
            if (res.isSuccess) {
                freshItems = res.getOrNull() ?: emptyList()
            } else {
                errorLoadingPrices = res.exceptionOrNull()?.message
            }
            isLoadingFreshPrices = false
        }
    }

    // Trigger fresh price re-fetch whenever the selected cart changes or raw cart changes
    LaunchedEffect(isHotelCartSelected, rawGroceryCart, rawHotelCart, cityId) {
        fetchFreshCart()
    }

    // Also re-check availability on resume, so an item turned off/on while the app was
    // backgrounded is reflected without the customer having to do anything.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fetchFreshCart()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // "Add more items" recommendations: popular items from the same hotel not already in the cart.
    var recommendations by remember { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    val hotelVendorId = rawHotelCart.firstOrNull { !it.vendorId.isNullOrBlank() }?.vendorId
    val hotelCartIdsKey = rawHotelCart.joinToString(",") { it.productId }
    LaunchedEffect(isHotelCartSelected, hotelVendorId, hotelCartIdsKey, cityId) {
        recommendations = emptyList()
        if (isHotelCartSelected && !hotelVendorId.isNullOrBlank() && !cityId.isNullOrBlank()) {
            val res = repository.getHotelProducts(vendorId = hotelVendorId, cityId = cityId, limit = 20)
            if (res.isSuccess) {
                val inCart = rawHotelCart.map { it.productId }.toSet()
                recommendations = (res.getOrNull() ?: emptyList())
                    .filter { it.id !in inCart && it.effectivePrice > 0 && it.isHotelItemAvailable }
                    .take(10)
            }
        }
    }

    // Calculate totals from fresh prices - unavailable/out-of-stock rows contribute 0
    // (CartItemUi.totalPrice already excludes them), so this naturally matches rule 2.
    val subtotal = freshItems.sumOf { it.totalPrice }
    // Promotional offer (backend-driven). Vendor is paid in full; Sndmart bears the discount.
    val cartPromo = promoFor(if (isHotelCartSelected) PromoKind.FOOD else PromoKind.GROCERY)
    val promoDiscount = cartPromoDiscount(subtotal, cartPromo)
    val payable = (subtotal - promoDiscount).coerceAtLeast(0.0)
    val unavailableItems = freshItems.filter { !it.isPurchasable }
    val hotelClosedMessage = if (isHotelCartSelected) {
        freshItems.firstOrNull { it.stateMessage == CART_HOTEL_CLOSED_MESSAGE }?.stateMessage
    } else null
    // When the whole hotel is closed every item carries the same sentinel reason - show one
    // banner instead of labelling each row individually.
    val perItemUnavailable = if (hotelClosedMessage != null) emptyList() else unavailableItems

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Cart", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (freshItems.isNotEmpty()) {
                        IconButton(
                            onClick = { fetchFreshCart() },
                            modifier = Modifier.testTag("refresh_cart_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Cart")
                        }
                        IconButton(
                            onClick = {
                                repository.clearCart(isHotelCartSelected)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Cart cleared")
                                }
                            },
                            modifier = Modifier.testTag("clear_cart_button")
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Clear Cart", tint = NaturalBadgeRed)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (freshItems.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Total to Pay",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                Text(
                                    "₹${"%.0f".format(payable)}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NaturalPrimary
                                )
                            }
                            Button(
                                onClick = {
                                    if (unavailableItems.isNotEmpty()) {
                                        repository.removeCartItems(
                                            unavailableItems.map { it.cartItem.productId to it.cartItem.variantId },
                                            isHotelCartSelected
                                        )
                                    } else {
                                        onProceedToCheckout(isHotelCartSelected, null)
                                    }
                                },
                                enabled = hotelClosedMessage == null,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("proceed_to_checkout_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                if (unavailableItems.isNotEmpty()) {
                                    Text("Remove unavailable items & continue", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                } else {
                                    Text("Proceed to Checkout", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Cart Mode Selector: Grocery vs Hotel Cart
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .testTag("cart_type_tabs")
                ) {
                    val groceryCount = rawGroceryCart.sumOf { it.quantity }
                    val hotelCount = rawHotelCart.sumOf { it.quantity }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (!isHotelCartSelected) NaturalPrimaryContainer else Color.Transparent)
                            .clickable { isHotelCartSelected = false }
                            .padding(vertical = 10.dp)
                            .testTag("tab_grocery_cart"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Grocery Cart ($groceryCount)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (!isHotelCartSelected) NaturalOnPrimaryContainer else TextSecondary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isHotelCartSelected) NaturalPrimaryContainer else Color.Transparent)
                            .clickable { isHotelCartSelected = true }
                            .padding(vertical = 10.dp)
                            .testTag("tab_hotel_cart"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Hotel Cart ($hotelCount)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isHotelCartSelected) NaturalOnPrimaryContainer else TextSecondary
                        )
                    }
                }
            }

            if (isLoadingFreshPrices && freshItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NaturalPrimary)
                }
            } else if (errorLoadingPrices != null && freshItems.isEmpty()) {
                ErrorCard(message = errorLoadingPrices!!, onRetry = { fetchFreshCart() })
            } else if (freshItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.ShoppingCart,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (isHotelCartSelected) "Your hotel cart is empty" else "Your grocery cart is empty",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Explore items in your city and add them to cart.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("Start Shopping")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (errorLoadingPrices != null) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Could not update prices: $errorLoadingPrices",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { fetchFreshCart() }) {
                                        Text("Retry", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PastelSage,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = DarkGreenText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Prices verified live with ${cityId ?: "your city"}'s current stock",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = DarkGreenText,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    if (hotelClosedMessage != null) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = NaturalBadgeRed.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, NaturalBadgeRed.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("hotel_closed_cart_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = NaturalBadgeRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "This hotel is currently closed. You can't place this order right now.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = NaturalBadgeRed
                                    )
                                }
                            }
                        }
                    } else if (perItemUnavailable.isNotEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = NaturalBadgeRed.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, NaturalBadgeRed.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("unavailable_items_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = NaturalBadgeRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "${perItemUnavailable.size} item(s) are not available right now",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NaturalBadgeRed,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            repository.removeCartItems(
                                                perItemUnavailable.map { it.cartItem.productId to it.cartItem.variantId },
                                                isHotelCartSelected
                                            )
                                        },
                                        modifier = Modifier.testTag("remove_unavailable_items_button")
                                    ) {
                                        Text("Remove unavailable items", color = NaturalBadgeRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Free Delivery Hint: "Add Rs X more for FREE Express Delivery"
                    if (freeDeliveryThreshold != null && freeDeliveryThreshold!! > 0.0) {
                        val threshold = freeDeliveryThreshold!!
                        item {
                            val isFreeUnlocked = subtotal >= threshold
                            val amountNeeded = (threshold - subtotal).coerceAtLeast(0.0)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isFreeUnlocked) PastelSage else PastelPeach,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("free_delivery_hint_cart")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isFreeUnlocked) Icons.Default.CheckCircle else Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = if (isFreeUnlocked) DarkGreenText else Color(0xFFE65100),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = if (isFreeUnlocked) {
                                            "You've unlocked FREE Express Delivery!"
                                        } else {
                                            "Add ₹${"%.0f".format(amountNeeded)} more for FREE Express Delivery"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isFreeUnlocked) DarkGreenText else Color(0xFFBF360C)
                                    )
                                }
                            }
                        }
                    }

                    // Cart Items List
                    items(freshItems, key = { "${it.cartItem.productId}_${it.cartItem.variantId ?: "base"}" }) { itemUi ->
                        CartItemRow(
                            item = itemUi,
                            onIncrease = {
                                repository.updateCartItemQuantity(
                                    productId = itemUi.cartItem.productId,
                                    isHotel = isHotelCartSelected,
                                    newQty = itemUi.cartItem.quantity + 1,
                                    variantId = itemUi.cartItem.variantId
                                )
                            },
                            onDecrease = {
                                repository.updateCartItemQuantity(
                                    productId = itemUi.cartItem.productId,
                                    isHotel = isHotelCartSelected,
                                    newQty = itemUi.cartItem.quantity - 1,
                                    variantId = itemUi.cartItem.variantId
                                )
                            },
                            onRemove = {
                                repository.removeCartItems(
                                    listOf(itemUi.cartItem.productId to itemUi.cartItem.variantId),
                                    isHotelCartSelected
                                )
                            }
                        )
                    }

                    // "Add more items" — popular items from the same hotel not in the cart
                    if (recommendations.isNotEmpty()) {
                        item {
                            Column {
                                Text(
                                    "Add more items 🍽️",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(recommendations, key = { it.id }) { p ->
                                        RecommendedFoodCard(
                                            product = p,
                                            onAdd = {
                                                repository.addToCart(
                                                    productId = p.id,
                                                    vendorId = hotelVendorId,
                                                    cityId = cityId,
                                                    isHotel = true,
                                                    productForSnapshot = p
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bill Breakdown
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Bill Details",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                BillRow("Item Subtotal", "₹${"%.2f".format(subtotal)}")
                                if (promoDiscount > 0) {
                                    BillRow(
                                        "Offer ${Math.round(cartPromo?.percent ?: 0.0).toInt()}% OFF",
                                        "−₹${"%.2f".format(promoDiscount)}",
                                        color = SuccessGreen
                                    )
                                }
                                BillRow("Delivery Fee", "Calculated at checkout", color = TextSecondary)
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                BillRow(
                                    "Total to Pay",
                                    "₹${"%.2f".format(payable)}",
                                    isBold = true,
                                    fontSize = 16.sp,
                                    color = NaturalPrimary
                                )
                                if (promoDiscount > 0) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "You save ₹${"%.0f".format(promoDiscount)} 🎉",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = SuccessGreen
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

@Composable
fun CartItemRow(
    item: CartItemUi,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onRemove: () -> Unit
) {
    val tagSuffix = "${item.cartItem.productId}${item.cartItem.variantId?.let { "_$it" } ?: ""}"
    val isPurchasable = item.isPurchasable
    // The hotel-closed sentinel is shown once at the top of the cart, not repeated per row.
    val rowMessage = item.stateMessage?.takeIf { it != CART_HOTEL_CLOSED_MESSAGE }
    val messageColor = when (item.availabilityState) {
        CartAvailabilityState.QTY_REDUCED -> Color(0xFFE65100)
        else -> NaturalBadgeRed
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isPurchasable) 1f else 0.6f)
            .testTag("cart_item_$tagSuffix"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (!isPurchasable) NaturalBadgeRed.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                ProductImage(
                    url = item.product.imageUrl,
                    contentDescription = item.product.name,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isPurchasable) {
                    Text(
                        text = "₹${"%.0f".format(item.effectivePrice)}${if (item.variant != null) "" else if (!item.product.unit.isNullOrBlank()) " / ${item.product.unit}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "Total: ₹${"%.0f".format(item.totalPrice)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = NaturalPrimary
                    )
                }
                if (rowMessage != null) {
                    Text(
                        text = rowMessage,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = messageColor
                    )
                }
            }

            if (isPurchasable) {
                QuantityStepper(
                    quantity = item.cartItem.quantity,
                    onIncrease = onIncrease,
                    onDecrease = onDecrease,
                    testTagPrefix = "cart_item_$tagSuffix"
                )
            } else {
                OutlinedButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NaturalBadgeRed),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NaturalBadgeRed.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("remove_cart_item_$tagSuffix")
                ) {
                    Text("Remove", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


@Composable
private fun RecommendedFoodCard(product: ResolvedProduct, onAdd: () -> Unit) {
    var added by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.width(132.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            ProductImage(
                url = product.imageUrl,
                contentDescription = product.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = product.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "₹${"%.0f".format(product.effectivePrice)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                TextButton(
                    onClick = { onAdd(); added = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        if (added) "Added" else "+ Add",
                        color = NaturalPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
