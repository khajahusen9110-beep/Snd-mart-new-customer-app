package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.*
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.AddressPickerDialog
import com.example.ui.components.ErrorCard
import com.example.ui.components.PromoKind
import com.example.ui.components.cartPromoDiscount
import com.example.ui.components.promoFor
import com.example.ui.theme.*
import com.example.util.RazorpayPaymentManager
import com.example.util.RazorpayPaymentResult
import com.example.util.CASHBACK_NOTE_KANNADA
import com.example.util.rupees
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** "Use wallet balance" is remembered while the app is open (default: unchecked). */
private object CheckoutWalletChoice {
    var useWallet = false
}

private val CashbackTeal = Color(0xFF00796B)

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    isHotel: Boolean,
    couponCode: String?,
    cityId: String?,
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onBack: () -> Unit,
    onOrderPlacedSuccess: (orderId: String) -> Unit,
    onRequireLogin: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val userId = sessionManager.userId.collectAsState().value
    val isLoggedIn = sessionManager.isLoggedIn.collectAsState().value

    // Address & City resolution
    var addresses by remember { mutableStateOf<List<CustomerAddress>>(emptyList()) }
    var selectedAddressId by remember { mutableStateOf<String?>(null) }
    var isLoadingAddresses by remember { mutableStateOf(true) }

    val selectedAddress = addresses.find { it.id == selectedAddressId }
    var effectiveCityId by remember { mutableStateOf(cityId ?: sessionManager.selectedCity.value?.id) }
    var effectiveCityName by remember { mutableStateOf(sessionManager.selectedCity.value?.name ?: "Current City") }
    var addressDistanceKm by remember { mutableStateOf<Double?>(null) }

    // Live Cart items and Subtotal (fresh fetch)
    var freshItems by remember { mutableStateOf<List<CartItemUi>>(emptyList()) }
    var isLoadingFreshCart by remember { mutableStateOf(true) }
    val subtotal = freshItems.sumOf { it.totalPrice }
    val unavailableItems = freshItems.filter { !it.isPurchasable }
    val hotelClosedMessage = if (isHotel) {
        freshItems.firstOrNull { it.stateMessage == CART_HOTEL_CLOSED_MESSAGE }?.stateMessage
    } else null

    // ONE Delivery System: "Express Delivery" Only
    var calculatedDeliveryFee by remember { mutableStateOf<Double?>(null) }
    var isLoadingDeliveryFee by remember { mutableStateOf(false) }
    var deliveryFeeError by remember { mutableStateOf<String?>(null) }
    var handlingFee by remember { mutableStateOf(5.0) }
    var cityDeliverySettings by remember { mutableStateOf<CityDeliverySettings?>(null) }
    var isCityActive by remember { mutableStateOf(true) }

    // Coupon handling (authoritative Supabase validation)
    var couponInput by remember { mutableStateOf(couponCode ?: "") }
    var appliedCoupon by remember { mutableStateOf<Coupon?>(null) }
    var authoritativeDiscount by remember { mutableStateOf(0.0) }
    var isValidatingCoupon by remember { mutableStateOf(false) }
    var couponErrorMessage by remember { mutableStateOf<String?>(null) }

    // Cashback coupon (credited to the wallet after delivery) and wallet use, from the server preview.
    // The server calculates everything; these only drive what the bill shows.
    var appliedCashback by remember { mutableStateOf(0.0) }
    var appliedCashbackDays by remember { mutableStateOf<Int?>(null) }
    var walletInfo by remember { mutableStateOf<WalletUsable?>(null) }
    var useWallet by remember { mutableStateOf(CheckoutWalletChoice.useWallet) }
    // Server total differed from the estimate: shown before Razorpay opens.
    var confirmPayOrder by remember { mutableStateOf<Order?>(null) }

    // Payment methods
    val paymentMethods = listOf(
        "cod" to "Cash on Delivery (COD)",
        "upi" to "UPI / Instant Pay"
    )
    var selectedPaymentMethod by remember { mutableStateOf("cod") }

    // Order placement state
    val context = LocalContext.current
    var isPlacingOrder by remember { mutableStateOf(false) }
    var placingOrderMessage by remember { mutableStateOf("Placing Order...") }
    var activePaymentOrder by remember { mutableStateOf<Order?>(null) }
    var activePaymentRpData by remember { mutableStateOf<RazorpayOrderResponse?>(null) }
    var placementError by remember { mutableStateOf<String?>(null) }
    var activeMaintenanceMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingMaintenanceOnRetry by remember { mutableStateOf(false) }

    // Listen for Razorpay payment callback results
    LaunchedEffect(Unit) {
        RazorpayPaymentManager.paymentResult.collectLatest { result ->
            val currentOrder = activePaymentOrder ?: return@collectLatest
            val currentRpData = activePaymentRpData
            when (result) {
                is RazorpayPaymentResult.Error -> {
                    val orderId = currentOrder.id ?: ""
                    isPlacingOrder = false
                    activePaymentOrder = null
                    activePaymentRpData = null
                    coroutineScope.launch {
                        repository.syncRazorpayPayment(orderId)
                    }
                    val isDismissOrCancelled = result.code == 0 ||
                        result.response?.contains("cancelled", ignoreCase = true) == true
                    if (isDismissOrCancelled) {
                        snackbarHostState.showSnackbar("Payment pending. Complete payment within the time limit.")
                    } else {
                        val errMsg = result.response?.takeIf { it.isNotBlank() }
                            ?: "Payment not completed."
                        snackbarHostState.showSnackbar(errMsg)
                    }
                    if (orderId.isNotBlank()) {
                        onOrderPlacedSuccess(orderId)
                    }
                }
                is RazorpayPaymentResult.Success -> {
                    // Step 4 — Verify the payment server-side and sync
                    placingOrderMessage = "Verifying payment..."
                    isPlacingOrder = true
                    val rzpPaymentId = result.paymentData?.paymentId ?: result.razorpayPaymentId ?: ""
                    val rzpOrderId = result.paymentData?.orderId?.takeIf { it.isNotBlank() }
                        ?: currentRpData?.razorpayOrderId
                        ?: ""
                    val rzpSignature = result.paymentData?.signature
                        ?: result.paymentData?.data?.optString("razorpay_signature")
                        ?: ""

                    val orderId = currentOrder.id ?: ""
                    var isVerified = false
                    if (rzpPaymentId.isNotBlank() && rzpOrderId.isNotBlank()) {
                        val verifyResult = repository.verifyRazorpayPayment(
                            orderId = orderId,
                            razorpayOrderId = rzpOrderId,
                            razorpayPaymentId = rzpPaymentId,
                            razorpaySignature = rzpSignature
                        )
                        isVerified = verifyResult.isSuccess && verifyResult.getOrNull()?.success == true
                    }
                    repository.syncRazorpayPayment(orderId)

                    isPlacingOrder = false
                    activePaymentOrder = null
                    activePaymentRpData = null

                    if (!isVerified) {
                        snackbarHostState.showSnackbar("Payment received, confirming with your bank... you can check status from your order.")
                    }
                    onOrderPlacedSuccess(orderId)
                }
            }
        }
    }

    // Add Address Modal state
    var showAddAddressDialog by remember { mutableStateOf(false) }

    if (activeMaintenanceMessage != null) {
        MaintenanceScreen(
            message = activeMaintenanceMessage!!,
            isChecking = isCheckingMaintenanceOnRetry,
            onRetry = {
                coroutineScope.launch {
                    isCheckingMaintenanceOnRetry = true
                    val m = repository.checkMaintenanceMode()
                    isCheckingMaintenanceOnRetry = false
                    if (!m.enabled) {
                        activeMaintenanceMessage = null
                    } else {
                        activeMaintenanceMessage = m.message ?: "Service temporarily unavailable. Please try again shortly."
                    }
                }
            },
            onDismiss = { activeMaintenanceMessage = null }
        )
        return
    }

    var newRecipientName by remember { mutableStateOf(sessionManager.userName.value ?: "") }
    var newPhone by remember { mutableStateOf(sessionManager.userPhone.value ?: "") }
    var newAddressLine by remember { mutableStateOf("") }
    var newLandmark by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("Home") }
    var newLat by remember { mutableStateOf("") }
    var newLng by remember { mutableStateOf("") }

    fun loadAddresses() {
        if (userId.isNullOrBlank()) {
            isLoadingAddresses = false
            return
        }
        coroutineScope.launch {
            isLoadingAddresses = true
            val res = repository.getAddresses(userId)
            if (res.isSuccess) {
                addresses = res.getOrNull() ?: emptyList()
                if (addresses.isNotEmpty() && selectedAddressId == null) {
                    val defaultAddr = addresses.find { it.isDefault } ?: addresses.first()
                    selectedAddressId = defaultAddr.id
                }
            }
            isLoadingAddresses = false
        }
    }

    LaunchedEffect(userId) {
        loadAddresses()
    }

    LaunchedEffect(effectiveCityId) {
        if (!effectiveCityId.isNullOrBlank()) {
            val cityRes = repository.getCity(effectiveCityId!!)
            isCityActive = cityRes.getOrNull()?.status?.lowercase() != "inactive"
        }
    }

    // Fresh price re-fetch
    fun fetchFreshCart() {
        val cid = effectiveCityId ?: return
        coroutineScope.launch {
            isLoadingFreshCart = true
            val res = repository.getFreshCartItems(isHotel = isHotel, cityId = cid)
            if (res.isSuccess) {
                freshItems = res.getOrNull() ?: emptyList()
            }
            isLoadingFreshCart = false
        }
    }

    LaunchedEffect(effectiveCityId, isHotel) {
        fetchFreshCart()
    }

    fun clearCoupon() {
        appliedCoupon = null
        authoritativeDiscount = 0.0
        appliedCashback = 0.0
        appliedCashbackDays = null
    }

    // Uses one server preview result for the coupon (instant discount or wallet cashback).
    fun applyCouponPreview(requestedCode: String, c: CouponPreview?) {
        if (c != null && c.valid) {
            appliedCoupon = Coupon(
                code = c.code.ifBlank { requestedCode.trim().uppercase() },
                rewardType = c.rewardType,
                cashbackValidDays = c.cashbackValidDays
            )
            authoritativeDiscount = if (c.isCashback) 0.0 else c.discount
            appliedCashback = if (c.isCashback) c.cashback else 0.0
            appliedCashbackDays = if (c.isCashback) c.cashbackValidDays else null
            couponErrorMessage = null
        } else {
            clearCoupon()
            couponErrorMessage = c?.error ?: "Coupon is not valid for this order"
        }
    }

    // Coupon check through the server preview (same rules as checkout)
    fun applyCouponCode(code: String) {
        if (effectiveCityId.isNullOrBlank()) return
        if (code.isBlank()) return

        coroutineScope.launch {
            isValidatingCoupon = true
            couponErrorMessage = null
            val payable = ((subtotal - authoritativeDiscount).coerceAtLeast(0.0) + (calculatedDeliveryFee ?: 0.0) + handlingFee)
                .coerceAtLeast(0.0)
            val res = repository.previewCheckoutRewards(subtotal, code, payable)
            if (res.isSuccess) {
                val preview = res.getOrNull()!!
                walletInfo = preview.wallet
                applyCouponPreview(code, preview.coupon)
            } else {
                clearCoupon()
                couponErrorMessage = res.exceptionOrNull()?.message ?: "Coupon validation failed"
            }
            isValidatingCoupon = false
        }
    }

    // Initial coupon validation if passed from cart
    LaunchedEffect(couponCode, effectiveCityId, subtotal) {
        if (!couponCode.isNullOrBlank() && !effectiveCityId.isNullOrBlank() && subtotal > 0.0 && appliedCoupon == null) {
            applyCouponCode(couponCode)
        }
    }

    val isAddressCityMismatch = selectedAddress != null &&
            !selectedAddress.cityId.isNullOrBlank() &&
            !effectiveCityId.isNullOrBlank() &&
            selectedAddress.cityId?.removePrefix("eq.")?.trim() != effectiveCityId?.removePrefix("eq.")?.trim()

    val isDeliveryAvailable = selectedAddress != null && !isAddressCityMismatch && isCityActive
    val distanceKm = addressDistanceKm ?: 0.0
    val expressMinutes = cityDeliverySettings?.expressDeliveryMinutes ?: 30
    val freeDeliveryMinOrder = cityDeliverySettings?.effectiveFreeDeliveryMinOrder ?: 99.0
    // Promotional offer (backend-driven) — mirrors the server: computed on the raw subtotal, then
    // subtracted along with any coupon before delivery/handling. The server stays authoritative;
    // after placing, order.totalAmount is what is charged (the differs-check below guards it).
    val checkoutPromo = promoFor(if (isHotel) PromoKind.FOOD else PromoKind.GROCERY)
    val promoDiscount = cartPromoDiscount(subtotal, checkoutPromo)
    val subtotalAfterDiscount = (subtotal - authoritativeDiscount - promoDiscount).coerceAtLeast(0.0)

    fun calculateFees() {
        val cid = effectiveCityId ?: return
        val addr = selectedAddress ?: return
        if (!isDeliveryAvailable) {
            calculatedDeliveryFee = null
            deliveryFeeError = null
            return
        }

        coroutineScope.launch {
            isLoadingDeliveryFee = true
            deliveryFeeError = null

            val hotelVendorId = if (isHotel) {
                repository.hotelCart.value.firstOrNull()?.vendorId
                    ?: freshItems.firstOrNull { !it.cartItem.vendorId.isNullOrBlank() }?.cartItem?.vendorId
                    ?: freshItems.firstOrNull { !it.product.vendorId.isNullOrBlank() }?.product?.vendorId
            } else {
                null
            }

            val dist = repository.resolveServerDistanceKm(
                address = addr,
                cityId = cid,
                vendorId = hotelVendorId,
                isHotel = isHotel
            )
            addressDistanceKm = dist

            val settingsRes = repository.getCityDeliverySettings(cid)
            cityDeliverySettings = settingsRes.getOrNull()

            val handlingRes = repository.getHandlingFee(cid)
            if (handlingRes.isSuccess) {
                handlingFee = handlingRes.getOrNull() ?: 5.0
            }

            val feeRes = repository.calculateDeliveryFee(
                cityId = cid,
                distanceKm = dist,
                subtotalAfterDiscount = subtotalAfterDiscount
            )
            if (feeRes.isSuccess) {
                calculatedDeliveryFee = feeRes.getOrNull()
                deliveryFeeError = null
            } else {
                calculatedDeliveryFee = null
                deliveryFeeError = "Could not calculate delivery fee - tap to retry"
            }
            isLoadingDeliveryFee = false
        }
    }

    LaunchedEffect(selectedAddressId, freshItems, authoritativeDiscount, effectiveCityId, isHotel, isDeliveryAvailable) {
        if (isDeliveryAvailable && selectedAddress != null && !effectiveCityId.isNullOrBlank()) {
            calculateFees()
        } else {
            calculatedDeliveryFee = null
            deliveryFeeError = null
        }
    }

    // Items - instant discount + delivery + handling, before the wallet.
    val payableBeforeWallet = (subtotalAfterDiscount + (calculatedDeliveryFee ?: 0.0) + handlingFee).coerceAtLeast(0.0)
    val wallet = walletInfo
    val showWalletRow = wallet != null && wallet.enabled && wallet.balance > 0.0
    val walletApplied = if (wallet != null && showWalletRow && useWallet) minOf(wallet.usable, payableBeforeWallet) else 0.0

    // Estimate only: after placing, the server's order.totalAmount is what is charged.
    val totalAmount = if (calculatedDeliveryFee != null && isDeliveryAvailable) {
        (subtotalAfterDiscount + calculatedDeliveryFee!! + handlingFee - walletApplied).coerceAtLeast(0.0)
    } else null

    // Refresh the coupon/wallet preview when the cart, coupon or bill changes (debounced 400 ms).
    LaunchedEffect(subtotal, appliedCoupon?.code, payableBeforeWallet, effectiveCityId) {
        if (subtotal <= 0.0 || effectiveCityId.isNullOrBlank()) return@LaunchedEffect
        delay(400)
        val code = appliedCoupon?.code
        val preview = repository.previewCheckoutRewards(subtotal, code, payableBeforeWallet).getOrNull()
            ?: return@LaunchedEffect
        walletInfo = preview.wallet
        if (code != null && appliedCoupon?.code == code && preview.coupon != null) {
            applyCouponPreview(code, preview.coupon)
        }
    }

    // Opens Razorpay (UPI only) for an order that is already placed (never creates a new order).
    suspend fun startUpiPayment(order: Order) {
        placingOrderMessage = "Initiating UPI payment..."
        val rpRes = repository.createRazorpayOrder(order.id ?: "")
        val rpData = rpRes.getOrNull()
        if (rpRes.isFailure || rpData == null) {
            isPlacingOrder = false
            val err = "Could not start payment. Please try again."
            placementError = err
            snackbarHostState.showSnackbar(err)
            return
        }

        val activity = context.findActivity()
        if (activity == null) {
            isPlacingOrder = false
            snackbarHostState.showSnackbar("Unable to open payment screen. Please try again.")
            return
        }

        activePaymentOrder = order
        activePaymentRpData = rpData

        val openRes = RazorpayPaymentManager.startUpiCheckout(
            activity = activity,
            keyId = rpData.keyId,
            amountInPaise = rpData.amountInPaise,
            currency = rpData.currency,
            razorpayOrderId = rpData.razorpayOrderId,
            orderNumber = order.orderNumber.ifBlank { order.id ?: "" },
            userPhone = sessionManager.userPhone.value,
            userEmail = sessionManager.userEmail.value
        )

        if (openRes.isFailure) {
            isPlacingOrder = false
            activePaymentOrder = null
            activePaymentRpData = null
            val err = "Could not start payment. Please try again."
            placementError = err
            snackbarHostState.showSnackbar(err)
        }
    }

    val isCheckoutDisabled = isPlacingOrder ||
            isLoadingDeliveryFee ||
            calculatedDeliveryFee == null ||
            !isDeliveryAvailable ||
            selectedAddressId == null ||
            effectiveCityId == null ||
            subtotal <= 0.0 ||
            hotelClosedMessage != null

    // While unavailable items are in the cart, the button only needs to remove them - skip the
    // address/delivery-fee/subtotal gating above so the customer can always clear a dead cart
    // (e.g. every item turned out to be unavailable, which would otherwise make subtotal 0 and
    // permanently disable the button).
    val isPlaceOrderButtonDisabled = if (unavailableItems.isNotEmpty()) {
        isPlacingOrder || hotelClosedMessage != null
    } else {
        isCheckoutDisabled
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checkout", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (placementError != null) {
                        Text(
                            text = placementError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    } else if (deliveryFeeError != null) {
                        Text(
                            text = deliveryFeeError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { calculateFees() }
                                .padding(bottom = 8.dp)
                        )
                    } else if (!isDeliveryAvailable && !isLoadingAddresses && addresses.isNotEmpty()) {
                        Text(
                            text = "Delivery isn't currently available in your area",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Button(
                        onClick = {
                            if (unavailableItems.isNotEmpty()) {
                                repository.removeCartItems(
                                    unavailableItems.map { it.cartItem.productId to it.cartItem.variantId },
                                    isHotel
                                )
                                fetchFreshCart()
                                return@Button
                            }
                            if (!isLoggedIn || userId.isNullOrBlank()) {
                                onRequireLogin()
                                return@Button
                            }
                            if (selectedAddressId == null) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Please select or add a delivery address")
                                }
                                return@Button
                            }
                            if (effectiveCityId == null) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Please select a serviceable city")
                                }
                                return@Button
                            }
                            if (!isDeliveryAvailable) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Delivery isn't currently available in your area")
                                }
                                return@Button
                            }

                            coroutineScope.launch {
                                isPlacingOrder = true
                                placingOrderMessage = "Placing Order..."
                                placementError = null

                                // Pre-checkout maintenance check: prevent order attempt if maintenance mode was turned on
                                val maintenance = repository.checkMaintenanceMode()
                                if (maintenance.enabled) {
                                    isPlacingOrder = false
                                    activeMaintenanceMessage = maintenance.message ?: "Service temporarily unavailable. Please try again shortly."
                                    return@launch
                                }

                                val hotelVendorId = if (isHotel) {
                                    repository.hotelCart.value.firstOrNull()?.vendorId
                                        ?: freshItems.firstOrNull { !it.cartItem.vendorId.isNullOrBlank() }?.cartItem?.vendorId
                                        ?: freshItems.firstOrNull { !it.product.vendorId.isNullOrBlank() }?.product?.vendorId
                                } else {
                                    null
                                }

                                val res = repository.placeOrder(
                                    userId = userId,
                                    isHotel = isHotel,
                                    vendorId = hotelVendorId,
                                    cityId = effectiveCityId!!,
                                    addressId = selectedAddressId!!,
                                    paymentMethod = selectedPaymentMethod,
                                    coupon = appliedCoupon,
                                    useWallet = walletApplied > 0.0
                                )

                                if (res.isSuccess) {
                                    val order = res.getOrNull()
                                    if (order == null) {
                                        isPlacingOrder = false
                                        val err = "Failed to process order details. Please try again."
                                        placementError = err
                                        snackbarHostState.showSnackbar(err)
                                        return@launch
                                    }
                                    // The server's total is the truth; if it differs from the estimate,
                                    // show it before opening Razorpay.
                                    val estimate = totalAmount
                                    val differs = estimate != null && order.totalAmount > 0.0 &&
                                        kotlin.math.abs(order.totalAmount - estimate) > 1.0
                                    if (selectedPaymentMethod == "upi" && differs) {
                                        isPlacingOrder = false
                                        confirmPayOrder = order
                                    } else if (selectedPaymentMethod == "upi") {
                                        // Step 2/3 — Create the Razorpay order for this Sndmart order and open UPI
                                        startUpiPayment(order)
                                    } else {
                                        isPlacingOrder = false
                                        onOrderPlacedSuccess(order.id ?: "")
                                    }
                                } else {
                                    isPlacingOrder = false
                                    val err = res.exceptionOrNull()?.message ?: "Failed to place order"
                                    if (err.contains("coupon", ignoreCase = true) || err.contains("minimum order amount", ignoreCase = true)) {
                                        clearCoupon()
                                        couponErrorMessage = err
                                    }
                                    // An item changed in the last few seconds (RPC re-validates server-side
                                    // even though our own fresh-cart check just passed) - re-sync so it shows
                                    // greyed with the one-tap remove option instead of just a dead error.
                                    if (err.contains("no longer available", ignoreCase = true) ||
                                        err.contains("not available in your city", ignoreCase = true)
                                    ) {
                                        fetchFreshCart()
                                    }
                                    placementError = err
                                    snackbarHostState.showSnackbar(err)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("place_order_button"),
                        enabled = !isPlaceOrderButtonDisabled,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NaturalPrimary,
                            disabledContainerColor = NaturalPrimary.copy(alpha = 0.4f)
                        )
                    ) {
                        if (isPlacingOrder) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(placingOrderMessage)
                        } else if (unavailableItems.isNotEmpty()) {
                            Text("Remove unavailable items & continue", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        } else if (isLoadingDeliveryFee) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Calculating Delivery...")
                        } else {
                            val totalText = if (totalAmount != null) " • ₹${"%.0f".format(totalAmount)}" else ""
                            val actionLabel = if (selectedPaymentMethod == "upi") "Pay via UPI" else "Confirm & Place Order"
                            Text("$actionLabel$totalText", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isLoggedIn) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = NaturalPrimaryContainer),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = NaturalPrimary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sign in to complete order", fontWeight = FontWeight.Bold, color = NaturalOnPrimaryContainer)
                                Text("Verify your mobile/email for live order tracking", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            Button(
                                onClick = onRequireLogin,
                                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.testTag("checkout_signin_button")
                            ) {
                                Text("Sign In")
                            }
                        }
                    }
                }
            }

            if (placementError != null) {
                item {
                    ErrorCard(message = placementError!!, onRetry = { placementError = null })
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
                            .testTag("hotel_closed_checkout_banner")
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = NaturalBadgeRed, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "This hotel is currently closed. You can't place this order right now.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = NaturalBadgeRed
                            )
                        }
                    }
                }
            } else if (unavailableItems.isNotEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = NaturalBadgeRed.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NaturalBadgeRed.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("unavailable_items_checkout_banner")
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = NaturalBadgeRed, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "${unavailableItems.size} item(s) in your cart are not available right now. Tap the button below to remove them and continue.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = NaturalBadgeRed
                            )
                        }
                    }
                }
            }

            // City Indicator
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Delivering in: $effectiveCityName",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    if (addressDistanceKm != null && addressDistanceKm!! > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(~${"%.1f".format(addressDistanceKm)} km away)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Delivery Address Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Delivery Address",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { showAddAddressDialog = true },
                                    modifier = Modifier.testTag("detect_address_button"),
                                    colors = ButtonDefaults.textButtonColors(contentColor = NaturalPrimary)
                                ) {
                                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Detect GPS", fontWeight = FontWeight.Bold)
                                }
                                TextButton(
                                    onClick = { showAddAddressDialog = true },
                                    modifier = Modifier.testTag("add_address_button"),
                                    colors = ButtonDefaults.textButtonColors(contentColor = NaturalPrimary)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add New", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (isLoadingAddresses) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .align(Alignment.CenterHorizontally),
                                color = NaturalPrimary
                            )
                        } else if (addresses.isEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(
                                    "No address added yet. Use GPS to detect exact doorstep location.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { showAddAddressDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                ) {
                                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Detect My Location", fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            addresses.forEach { addr ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedAddressId = addr.id }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    RadioButton(
                                        selected = selectedAddressId == addr.id,
                                        onClick = { selectedAddressId = addr.id },
                                        colors = RadioButtonDefaults.colors(selectedColor = NaturalPrimary),
                                        modifier = Modifier.testTag("address_radio_${addr.id}")
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                color = NaturalPrimaryContainer,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = addr.label.uppercase(),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NaturalOnPrimaryContainer
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(addr.recipientName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(addr.addressLine, style = MaterialTheme.typography.bodySmall)
                                        if (!addr.landmark.isNullOrBlank()) {
                                            Text("Landmark: ${addr.landmark}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                        }
                                        Text("Phone: ${addr.phone}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Delivery Method Section - ONE Delivery System: "Express Delivery" Only
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("delivery_method_section"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Delivery Option",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (isLoadingDeliveryFee) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = NaturalPrimary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Calculating...", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (!isDeliveryAvailable) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        "Delivery isn't currently available in your area",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        } else {
                            // Fixed Card: "Express Delivery - arrives in about {N} min", plus fee ("FREE" when 0)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.5.dp,
                                    color = NaturalPrimary.copy(alpha = 0.6f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("express_delivery_card")
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFFFF9800).copy(alpha = 0.15f),
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        Icons.Default.Bolt,
                                                        contentDescription = null,
                                                        tint = Color(0xFFE65100),
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    "Express Delivery",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    "Arrives in about $expressMinutes min",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondary
                                                )
                                            }
                                        }

                                        if (isLoadingDeliveryFee) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = NaturalPrimary)
                                        } else if (deliveryFeeError != null) {
                                            TextButton(
                                                onClick = { calculateFees() },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    "Retry",
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else if (calculatedDeliveryFee == 0.0) {
                                            Surface(
                                                color = NaturalPrimary.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    "FREE",
                                                    color = NaturalPrimary,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                        } else if (calculatedDeliveryFee != null) {
                                            Text(
                                                "₹${"%.0f".format(calculatedDeliveryFee)}",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = TextPrimary
                                            )
                                        }
                                    }

                                    if (deliveryFeeError != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = deliveryFeeError!!,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.clickable { calculateFees() }
                                        )
                                    }

                                    if (addressDistanceKm != null && addressDistanceKm!! > 0.0) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Outlined.Navigation,
                                                contentDescription = null,
                                                tint = TextSecondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                "Distance: ~${"%.1f".format(distanceKm)} km from ${if (isHotel) "hotel" else "city dispatch hub"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }

                                    // Free-delivery hint
                                    if (freeDeliveryMinOrder > 0.0) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        val isFreeUnlocked = subtotalAfterDiscount >= freeDeliveryMinOrder
                                        val neededMore = (freeDeliveryMinOrder - subtotalAfterDiscount).coerceAtLeast(0.0)
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isFreeUnlocked) PastelSage else PastelPeach.copy(alpha = 0.7f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (isFreeUnlocked) Icons.Default.CheckCircle else Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = if (isFreeUnlocked) DarkGreenText else Color(0xFFBF360C),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (isFreeUnlocked) {
                                                        "FREE Express Delivery unlocked! (Order > ₹${"%.0f".format(freeDeliveryMinOrder)})"
                                                    } else {
                                                        "Add ₹${"%.0f".format(neededMore)} more for FREE Express Delivery"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isFreeUnlocked) DarkGreenText else Color(0xFFBF360C),
                                                    fontWeight = FontWeight.SemiBold
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

            // Coupon Code Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Have a Coupon?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (appliedCoupon != null) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = NaturalPrimaryContainer.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NaturalPrimary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                "Coupon ${appliedCoupon!!.code} Applied!",
                                                fontWeight = FontWeight.Bold,
                                                color = NaturalOnPrimaryContainer,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            if (appliedCoupon!!.isCashback) {
                                                Text(
                                                    "🎁 ${rupees(appliedCashback)} cashback after delivery" +
                                                        (appliedCashbackDays?.let { " (valid $it days)" } ?: ""),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = CashbackTeal
                                                )
                                            } else {
                                                Text(
                                                    "You save ${rupees(authoritativeDiscount)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = NaturalPrimary
                                                )
                                            }
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            clearCoupon()
                                            couponInput = ""
                                            couponErrorMessage = null
                                        },
                                        modifier = Modifier.testTag("remove_coupon_button")
                                    ) {
                                        Text("Remove", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            if (appliedCoupon!!.isCashback) {
                                // Kannada note: the bill is not reduced now; cashback comes after delivery.
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = CASHBACK_NOTE_KANNADA,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp,
                                    modifier = Modifier.testTag("cashback_kannada_note")
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = couponInput,
                                    onValueChange = {
                                        couponInput = it.uppercase()
                                        couponErrorMessage = null
                                    },
                                    placeholder = { Text("Enter Coupon Code") },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("coupon_input"),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    trailingIcon = {
                                        if (isValidatingCoupon) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        }
                                    }
                                )
                                Button(
                                    onClick = { applyCouponCode(couponInput) },
                                    enabled = couponInput.isNotBlank() && !isValidatingCoupon,
                                    modifier = Modifier.testTag("apply_coupon_checkout_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Apply")
                                }
                            }
                            if (couponErrorMessage != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = couponErrorMessage!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // Payment Selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Payment Method",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        paymentMethods.forEach { (methodKey, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedPaymentMethod = methodKey }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedPaymentMethod == methodKey,
                                    onClick = { selectedPaymentMethod = methodKey },
                                    colors = RadioButtonDefaults.colors(selectedColor = NaturalPrimary),
                                    modifier = Modifier.testTag("payment_radio_$methodKey")
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selectedPaymentMethod == methodKey) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            // Use Wallet Balance (only when the wallet is on and has money)
            if (showWalletRow && wallet != null) {
                item {
                    val canUse = wallet.usable > 0.0
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canUse) {
                                    useWallet = !useWallet
                                    CheckoutWalletChoice.useWallet = useWallet
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Checkbox(
                                checked = useWallet && canUse,
                                onCheckedChange = {
                                    useWallet = it
                                    CheckoutWalletChoice.useWallet = it
                                },
                                enabled = canUse,
                                colors = CheckboxDefaults.colors(checkedColor = NaturalPrimary),
                                modifier = Modifier.testTag("use_wallet_checkbox")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.AccountBalanceWallet,
                                        contentDescription = null,
                                        tint = NaturalPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Use Wallet Balance", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                }
                                Text(
                                    if (canUse) "Use ${rupees(wallet.usable)} from wallet (Balance ${rupees(wallet.balance)})"
                                    else "Balance ${rupees(wallet.balance)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (!canUse && !wallet.reason.isNullOrBlank()) {
                                    Text(wallet.reason, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                                Text(
                                    "Up to ${wallet.maxUsePercent.toInt()}% of item total per order",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }

            // Bill Summary
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
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Item total", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                            Text("₹${"%.0f".format(subtotal)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        }

                        if (authoritativeDiscount > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Coupon discount (${appliedCoupon?.code ?: ""})", color = NaturalPrimary, style = MaterialTheme.typography.bodyMedium)
                                Text("-₹${"%.0f".format(authoritativeDiscount)}", color = NaturalPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        if (promoDiscount > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Offer ${Math.round(checkoutPromo?.percent ?: 0.0).toInt()}% OFF", color = SuccessGreen, style = MaterialTheme.typography.bodyMedium)
                                Text("−₹${"%.0f".format(promoDiscount)}", color = SuccessGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Delivery Fee", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                            if (isLoadingDeliveryFee) {
                                Text("Calculating...", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            } else if (!isDeliveryAvailable) {
                                Text("Unavailable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            } else if (deliveryFeeError != null) {
                                Text(
                                    "Error (tap to retry)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.clickable { calculateFees() }
                                )
                            } else if (calculatedDeliveryFee == 0.0) {
                                Text("FREE", color = NaturalPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            } else if (calculatedDeliveryFee != null) {
                                Text("₹${"%.0f".format(calculatedDeliveryFee)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text("--", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Handling Fee", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                            Text("₹${"%.0f".format(handlingFee)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        }

                        if (walletApplied > 0.0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Wallet", color = NaturalPrimary, style = MaterialTheme.typography.bodyMedium)
                                Text("−${rupees(walletApplied)}", color = NaturalPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("To Pay", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            if (totalAmount != null && isDeliveryAvailable) {
                                Text(
                                    "₹${"%.0f".format(totalAmount)}",
                                    fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = NaturalPrimary
                                )
                            } else {
                                Text("--", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            }
                        }

                        if (appliedCashback > 0.0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "🎁 ${rupees(appliedCashback)} cashback after delivery",
                                color = CashbackTeal,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    // Final amount from the server differs from the estimate: confirm before opening UPI.
    confirmPayOrder?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                confirmPayOrder = null
                onOrderPlacedSuccess(pending.id ?: "")
            },
            title = { Text("Amount to pay") },
            text = {
                Text("Your order ${pending.orderNumber} is placed. The final amount to pay is ${rupees(pending.totalAmount)}.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmPayOrder = null
                        coroutineScope.launch {
                            isPlacingOrder = true
                            startUpiPayment(pending)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary)
                ) {
                    Text("Pay ${rupees(pending.totalAmount)} via UPI")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmPayOrder = null
                    onOrderPlacedSuccess(pending.id ?: "")
                }) {
                    Text("Later")
                }
            }
        )
    }

    // Add Address Dialog with Google Maps and Places Autocomplete
    if (showAddAddressDialog) {
        val userName = sessionManager.userName.collectAsState().value ?: ""
        val userPhone = sessionManager.userPhone.collectAsState().value ?: ""
        val selectedCity = sessionManager.selectedCity.collectAsState().value
        AddressPickerDialog(
            repository = repository,
            userId = userId ?: "user",
            defaultRecipientName = newRecipientName.ifBlank { userName },
            defaultPhone = newPhone.ifBlank { userPhone },
            cityCenter = selectedCity?.centerLat?.let { lat ->
                selectedCity.centerLng?.let { lng -> com.example.ui.components.map.GeoLatLng(lat, lng) }
            },
            onDismiss = { showAddAddressDialog = false },
            onSaved = { saved ->
                showAddAddressDialog = false
                loadAddresses()
                selectedAddressId = saved.id
            }
        )
    }
}
