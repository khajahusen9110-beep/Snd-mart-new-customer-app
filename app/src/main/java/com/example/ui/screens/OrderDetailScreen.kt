package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.maps.MapProvider
import com.example.data.maps.rememberMapProviderState
import com.example.data.maps.rememberOsmTileConfig
import com.example.data.model.*
import com.example.util.SupportConstants
import com.example.data.repository.SndmartRepository
import com.example.ui.components.BillRow
import com.example.ui.components.ErrorCard
import com.example.ui.components.map.GeoLatLng
import com.example.ui.components.map.MapProviderSwitchButton
import com.example.ui.components.map.MapTypeToggleButton
import com.example.ui.components.map.MarkerTint
import com.example.ui.components.map.SndmartMap
import com.example.ui.components.map.SndmartMapType
import com.example.ui.components.map.SndmartMarker
import com.example.ui.components.map.SndmartPolylineSpec
import com.example.ui.components.map.rememberSndmartMapState
import com.example.ui.theme.*
import com.example.util.RazorpayPaymentManager
import com.example.util.RazorpayPaymentResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private fun parseOrderTimestampMs(isoString: String?): Long {
    if (isoString.isNullOrBlank()) return System.currentTimeMillis()
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss"
    )
    for (p in patterns) {
        try {
            val sdf = SimpleDateFormat(p, Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val d = sdf.parse(isoString)
            if (d != null) return d.time
        } catch (_: Exception) {}
    }
    return System.currentTimeMillis()
}

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
fun OrderDetailScreen(
    orderId: String,
    cityId: String?,
    repository: SndmartRepository,
    onBack: () -> Unit,
    onNavigateToCart: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var order by remember { mutableStateOf<Order?>(null) }
    var orderItems by remember { mutableStateOf<List<OrderItem>>(emptyList()) }
    var statusHistory by remember { mutableStateOf<List<OrderStatusHistory>>(emptyList()) }
    var deliveryAssignment by remember { mutableStateOf<DeliveryAssignment?>(null) }
    var deliveryPartner by remember { mutableStateOf<DeliveryPartner?>(null) }
    var deliveryAddress by remember { mutableStateOf<CustomerAddress?>(null) }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Rating modal state
    var showReviewDialog by remember { mutableStateOf(false) }
    var vendorRating by remember { mutableStateOf(5) }
    var vendorComment by remember { mutableStateOf("") }
    var partnerRating by remember { mutableStateOf(5) }
    var partnerComment by remember { mutableStateOf("") }
    var reviewSubmitted by remember { mutableStateOf(false) }
    var isSubmittingReview by remember { mutableStateOf(false) }
    var reviewedOrderIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var orderCancellation by remember { mutableStateOf<OrderCancellation?>(null) }

    // Payment retry state
    var isPayingUpi by remember { mutableStateOf(false) }
    var isCheckingPaymentStatus by remember { mutableStateOf(false) }
    var upiPaymentMessage by remember { mutableStateOf("Starting payment...") }
    var activeRpData by remember { mutableStateOf<RazorpayOrderResponse?>(null) }
    var currentTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var scratchCardAmount by remember { mutableStateOf<Double?>(null) }

    // One-time per order: show the scratch-card reward if one was generated at checkout and
    // hasn't been scratched yet.
    LaunchedEffect(orderId) {
        repository.getMyScratchCard(orderId).onSuccess { card ->
            if (card != null && !card.isScratched) scratchCardAmount = card.amount
        }
    }

    // Polling function for active orders
    fun loadOrderData(isSilent: Boolean = false) {
        coroutineScope.launch {
            if (!isSilent) isLoading = true
            errorMessage = null

            val oRes = repository.getOrderById(orderId)
            if (oRes.isSuccess) {
                val fetchedOrder = oRes.getOrNull()
                order = fetchedOrder
                if (fetchedOrder?.status?.lowercase() == "cancelled") {
                    val cancelRes = repository.getOrderCancellation(orderId)
                    if (cancelRes.isSuccess) {
                        orderCancellation = cancelRes.getOrNull()
                    }
                }
                val itemsRes = repository.getOrderItems(orderId)
                if (itemsRes.isSuccess) {
                    orderItems = itemsRes.getOrNull() ?: emptyList()
                }
                val histRes = repository.getOrderStatusHistory(orderId)
                if (histRes.isSuccess) {
                    statusHistory = histRes.getOrNull() ?: emptyList()
                }
                val assignRes = repository.getDeliveryAssignment(orderId)
                val currentAssignment = if (assignRes.isSuccess) assignRes.getOrNull() else null
                deliveryAssignment = currentAssignment

                // Privacy & Accuracy: Only resolve delivery partner details if assignment has genuinely been accepted!
                // If assignment is null or status == 'assigned' (not yet accepted), do NOT resolve partner details.
                val isAccepted = currentAssignment?.isAccepted == true
                if (isAccepted) {
                    val partnerId = currentAssignment?.deliveryPartnerId ?: fetchedOrder?.deliveryPartnerId
                    if (!partnerId.isNullOrBlank()) {
                        val partRes = repository.getDeliveryPartner(partnerId)
                        if (partRes.isSuccess) {
                            deliveryPartner = partRes.getOrNull()
                        } else {
                            deliveryPartner = null
                        }
                    } else if (fetchedOrder?.deliveryPartner != null) {
                        deliveryPartner = fetchedOrder.deliveryPartner
                    } else {
                        deliveryPartner = null
                    }
                } else {
                    // Do NOT show or resolve delivery partner details for an unaccepted assignment
                    deliveryPartner = null
                }

                // Resolve delivery address for map pin
                val addrId = fetchedOrder?.addressId
                if (!addrId.isNullOrBlank()) {
                    val addrRes = repository.getAddressById(addrId)
                    if (addrRes.isSuccess && addrRes.getOrNull() != null) {
                        deliveryAddress = addrRes.getOrNull()
                    } else if (!fetchedOrder.customerId.isNullOrBlank()) {
                        val allAddrs = repository.getAddresses(fetchedOrder.customerId)
                        if (allAddrs.isSuccess) {
                            deliveryAddress = allAddrs.getOrNull()?.find { it.id == addrId }
                                ?: allAddrs.getOrNull()?.firstOrNull()
                        }
                    }
                }
            } else {
                errorMessage = oRes.exceptionOrNull()?.message
            }
            // Resolve which orders the customer has already reviewed (for the delivered
            // "Rate this order" prompt — only show it if no review exists yet).
            val custId = order?.customerId
            if (!custId.isNullOrBlank()) {
                val reviewedRes = repository.getReviewedOrderIds(custId)
                if (reviewedRes.isSuccess) reviewedOrderIds = reviewedRes.getOrNull() ?: emptySet()
            }
            if (!isSilent) isLoading = false
        }
    }

    // Razorpay result callback listener
    LaunchedEffect(Unit) {
        RazorpayPaymentManager.paymentResult.collectLatest { result ->
            if (!isPayingUpi) return@collectLatest
            val currentRpData = activeRpData
            when (result) {
                is RazorpayPaymentResult.Error -> {
                    isPayingUpi = false
                    activeRpData = null
                    coroutineScope.launch {
                        repository.syncRazorpayPayment(orderId)
                        loadOrderData(isSilent = false)
                    }
                    val isDismissOrCancelled = result.code == 0 ||
                        result.response?.contains("cancelled", ignoreCase = true) == true
                    if (isDismissOrCancelled) {
                        snackbarHostState.showSnackbar("Payment pending. Please complete payment within the time limit.")
                    } else {
                        val errMsg = result.response?.takeIf { it.isNotBlank() } ?: "Payment not completed."
                        snackbarHostState.showSnackbar(errMsg)
                    }
                }
                is RazorpayPaymentResult.Success -> {
                    upiPaymentMessage = "Verifying payment..."
                    val rzpPaymentId = result.paymentData?.paymentId ?: result.razorpayPaymentId ?: ""
                    val rzpOrderId = result.paymentData?.orderId?.takeIf { it.isNotBlank() }
                        ?: currentRpData?.razorpayOrderId
                        ?: ""
                    val rzpSignature = result.paymentData?.signature
                        ?: result.paymentData?.data?.optString("razorpay_signature")
                        ?: ""

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

                    isPayingUpi = false
                    activeRpData = null

                    if (isVerified) {
                        snackbarHostState.showSnackbar("Payment completed and verified!")
                    } else {
                        snackbarHostState.showSnackbar("Payment received, confirming with your bank... you can check status below.")
                    }
                    loadOrderData(isSilent = false)
                }
            }
        }
    }

    // App reopened with an order still payment_status = 'pending' -> call sync-razorpay-payment and reload
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                coroutineScope.launch {
                    val o = order
                    if (o != null && o.paymentMethod.lowercase() == "upi" &&
                        (o.paymentStatus.lowercase() == "pending" || o.paymentStatus.lowercase() == "failed") &&
                        o.status.lowercase() == "pending"
                    ) {
                        repository.syncRazorpayPayment(orderId)
                    }
                    loadOrderData(isSilent = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Polling effect: Rule 8: poll every 15-20s while active to detect status changes & partner acceptance automatically
    LaunchedEffect(orderId) {
        loadOrderData(isSilent = false)
        val activeStatuses = listOf("pending", "confirmed", "preparing", "ready", "out_for_delivery")
        while (true) {
            delay(18000) // Rule 8: Polls every 18 seconds (15-20s interval)
            val currentStatus = order?.status?.lowercase()
            if (currentStatus != null && !activeStatuses.contains(currentStatus)) {
                break
            }
            loadOrderData(isSilent = true)
        }
    }

    val isUpiPaymentPending = order != null &&
        order!!.paymentMethod.lowercase() == "upi" &&
        (order!!.paymentStatus.lowercase() == "pending" || order!!.paymentStatus.lowercase() == "failed") &&
        order!!.status.lowercase() == "pending"

    // Poll sync-razorpay-payment every 20 seconds while banner is visible
    LaunchedEffect(isUpiPaymentPending, orderId) {
        if (!isUpiPaymentPending) return@LaunchedEffect
        while (true) {
            delay(20000L) // 20s poll
            repository.syncRazorpayPayment(orderId)
            loadOrderData(isSilent = true)
            val currentStatus = order?.status?.lowercase()
            val currentPaymentStatus = order?.paymentStatus?.lowercase()
            if (currentStatus != "pending" || (currentPaymentStatus != "pending" && currentPaymentStatus != "failed")) {
                break
            }
        }
    }

    // Countdown tick effect every 1 second
    var hasSyncedOnExpiry by remember(orderId) { mutableStateOf(false) }
    LaunchedEffect(isUpiPaymentPending, order?.createdAt) {
        if (!isUpiPaymentPending) return@LaunchedEffect
        val createdAtMs = parseOrderTimestampMs(order?.createdAt)
        val deadlineMs = createdAtMs + (15 * 60 * 1000L)
        while (true) {
            currentTimeMs = System.currentTimeMillis()
            if (currentTimeMs >= deadlineMs && !hasSyncedOnExpiry) {
                hasSyncedOnExpiry = true
                repository.syncRazorpayPayment(orderId)
                loadOrderData(isSilent = true)
            }
            delay(1000L)
        }
    }

    val isAssignmentAccepted = deliveryAssignment?.isAccepted == true
    val isOrderActive = order != null && listOf("pending", "confirmed", "preparing", "ready", "out_for_delivery").contains(order!!.status.lowercase())
    val showLiveTracking = isOrderActive && isAssignmentAccepted && deliveryPartner != null

    // Driver location polling: Rule 8: re-fetch the delivery_partners row every 18s ONLY while partner has accepted
    LaunchedEffect(showLiveTracking, deliveryPartner?.id) {
        val partnerId = deliveryPartner?.id
        while (showLiveTracking && !partnerId.isNullOrBlank()) {
            delay(18000) // Rule 8: Poll partner position every 18 seconds (15-20s interval)
            val partRes = repository.getDeliveryPartner(partnerId)
            if (partRes.isSuccess && partRes.getOrNull() != null) {
                deliveryPartner = partRes.getOrNull()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(order?.orderNumber ?: "Order Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadOrderData(isSilent = false) }, modifier = Modifier.testTag("refresh_detail_button")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (order != null) {
                // Rate Order Button (delivered + no review submitted yet for this order)
                val hasReviewed = reviewSubmitted || reviewedOrderIds.contains(orderId)
                if (order!!.status.lowercase() == "delivered" && !hasReviewed) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Button(
                                onClick = { showReviewDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("rate_order_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Rate Order", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = NaturalPrimary
                )
            } else if (errorMessage != null) {
                ErrorCard(
                    message = errorMessage!!,
                    onRetry = { loadOrderData(isSilent = false) },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (order == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Order details not found")
                }
            } else {
                val currentOrder = order!!

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // UPI Payment Pending Banner
                    if (isUpiPaymentPending) {
                        item {
                            val createdAtMs = remember(currentOrder.createdAt) { parseOrderTimestampMs(currentOrder.createdAt) }
                            val deadlineMs = createdAtMs + (15 * 60 * 1000L)
                            val remainingMs = maxOf(0L, deadlineMs - currentTimeMs)
                            val mm = (remainingMs / 1000) / 60
                            val ss = (remainingMs / 1000) % 60
                            val countdownFormatted = String.format(Locale.US, "%02d:%02d", mm, ss)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("upi_payment_pending_banner"),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFF9800))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(Color(0xFFFFE0B2), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Schedule,
                                                contentDescription = null,
                                                tint = Color(0xFFE65100),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                "Payment Pending",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFE65100)
                                            )
                                            Text(
                                                "Time remaining: $countdownFormatted",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFFD84315)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Text(
                                        "Payment pending - complete payment within $countdownFormatted or the order will be cancelled automatically.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 20.sp
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // "Pay now" (reopens Razorpay for the SAME order - never create a new order)
                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    isPayingUpi = true
                                                    upiPaymentMessage = "Opening UPI payment..."
                                                    val rpRes = repository.createRazorpayOrder(orderId)
                                                    if (rpRes.isFailure) {
                                                        isPayingUpi = false
                                                        snackbarHostState.showSnackbar("Could not start payment. Please try again.")
                                                        return@launch
                                                    }
                                                    val rpData = rpRes.getOrNull()!!
                                                    val activity = context.findActivity()
                                                    if (activity == null) {
                                                        isPayingUpi = false
                                                        snackbarHostState.showSnackbar("Unable to open payment screen. Please try again.")
                                                        return@launch
                                                    }
                                                    activeRpData = rpData
                                                    val openRes = RazorpayPaymentManager.startUpiCheckout(
                                                        activity = activity,
                                                        keyId = rpData.keyId,
                                                        amountInPaise = rpData.amountInPaise,
                                                        currency = rpData.currency,
                                                        razorpayOrderId = rpData.razorpayOrderId,
                                                        orderNumber = currentOrder.orderNumber.ifBlank { currentOrder.id ?: "" },
                                                        userPhone = null,
                                                        userEmail = null
                                                    )
                                                    if (openRes.isFailure) {
                                                        isPayingUpi = false
                                                        activeRpData = null
                                                        snackbarHostState.showSnackbar("Could not start payment. Please try again.")
                                                    }
                                                }
                                            },
                                            enabled = !isPayingUpi && !isCheckingPaymentStatus,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                                .testTag("pay_now_button"),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            if (isPayingUpi) {
                                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Opening...", fontSize = 12.sp)
                                            } else {
                                                Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Pay now", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            }
                                        }

                                        // "I've paid - check status" (calls sync-razorpay-payment)
                                        OutlinedButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    isCheckingPaymentStatus = true
                                                    val syncRes = repository.syncRazorpayPayment(orderId)
                                                    isCheckingPaymentStatus = false
                                                    if (syncRes.isSuccess) {
                                                        val r = syncRes.getOrNull()
                                                        if (r?.paymentStatus == "paid") {
                                                            snackbarHostState.showSnackbar("Payment confirmed!")
                                                        } else {
                                                            snackbarHostState.showSnackbar(r?.message ?: "Payment still pending. If already paid, please allow a moment.")
                                                        }
                                                    } else {
                                                        snackbarHostState.showSnackbar(syncRes.exceptionOrNull()?.message ?: "Could not verify payment status.")
                                                    }
                                                    loadOrderData(isSilent = false)
                                                }
                                            },
                                            enabled = !isPayingUpi && !isCheckingPaymentStatus,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                                .testTag("check_payment_status_button"),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE65100)),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800)),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            if (isCheckingPaymentStatus) {
                                                CircularProgressIndicator(color = Color(0xFFE65100), modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Checking...", fontSize = 11.sp)
                                            } else {
                                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("I've paid - check status", fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // UPI 15-Minute Timeout Cancellation Banner
                    val cancelReason = orderCancellation?.reason
                        ?: statusHistory.find { it.status.lowercase() == "cancelled" }?.note
                        ?: ""
                    val isUpiExpiredCancellation = currentOrder.status.lowercase() == "cancelled" &&
                        cancelReason.contains("UPI payment not received within 15 minutes", ignoreCase = true)

                    if (isUpiExpiredCancellation) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("upi_expired_cancelled_banner"),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = PastelCoral),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, NaturalBadgeRed.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cancel,
                                        contentDescription = null,
                                        tint = NaturalBadgeRed,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            "Order Cancelled",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = NaturalBadgeRed
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Order cancelled because the payment was not completed. If money was deducted, it will be refunded by your bank/UPI app automatically.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = 20.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 1. Delivery OTP Card (Prominently displayed ONLY when out_for_delivery and partner is assigned)
                    if (showLiveTracking && currentOrder.status.lowercase() == "out_for_delivery" && !deliveryAssignment?.deliveryOtp.isNullOrBlank()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("delivery_otp_card"),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = PastelPeach),
                                border = androidx.compose.foundation.BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.2f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            Icons.Default.VerifiedUser,
                                            contentDescription = null,
                                            tint = NaturalPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "DELIVERY VERIFICATION OTP",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = NaturalPrimary,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = deliveryAssignment!!.deliveryOtp!!,
                                        fontSize = 34.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 8.sp,
                                        color = NaturalPrimary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "Share this code with your delivery partner to confirm delivery.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // 2. Delivery Partner / Live Tracking Section
                    // Mandatory Rule:
                    // - If assignment is null OR status == 'assigned' (not yet accepted):
                    //     Show neutral, honest search state: "Finding a delivery partner for your order..." (no name, no map, no ETA, no LIVE badge).
                    // - If assignment status is 'accepted', 'picked_up', or 'out_for_delivery':
                    //     Show the full Live Delivery Tracking section (driver name/phone/vehicle, live map, ETA countdown).
                    if (isOrderActive) {
                        item {
                            if (isAssignmentAccepted) {
                                if (deliveryPartner == null) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("live_tracking_loading_card"),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                color = NaturalPrimary,
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                "Connecting with accepted delivery partner...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                } else {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("live_tracking_card"),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            // A. Live ETA countdown
                                            LiveEtaCountdownBanner(
                                                estimatedDeliveryAt = deliveryAssignment?.estimatedDeliveryAt,
                                                estimatedDeliveryMinutes = deliveryAssignment?.estimatedDeliveryMinutes,
                                                isDelivered = currentOrder.status.lowercase() == "delivered"
                                            )

                                            Spacer(modifier = Modifier.height(14.dp))

                                            // B. Delivery Partner Card
                                            DeliveryPartnerCardContent(
                                                deliveryPartner = deliveryPartner!!,
                                                onCallClick = { phone ->
                                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                                    context.startActivity(intent)
                                                }
                                            )

                                            Spacer(modifier = Modifier.height(14.dp))

                                            // C. Live Map
                                            LiveDeliveryMap(
                                                repository = repository,
                                                deliveryPartner = deliveryPartner!!,
                                                deliveryAddress = deliveryAddress
                                            )
                                        }
                                    }
                                }
                            } else {
                                FindingDeliveryPartnerCard()
                            }
                        }
                    }

                    // 3. Order Progress Stepper
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("order_stepper_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Order Progress",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                OrderStatusStepper(
                                    currentStatus = currentOrder.status,
                                    history = statusHistory,
                                    cancellationReason = orderCancellation?.reason
                                )
                            }
                        }
                    }

                    // 4. Order Items
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Items Ordered (${orderItems.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                if (orderItems.isEmpty()) {
                                    Text(
                                        "No items recorded for this order.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                } else {
                                    orderItems.forEach { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.productName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                                Text(
                                                    "${item.quantity} x ₹${"%.0f".format(item.unitPrice)}${if (!item.variantLabel.isNullOrBlank()) " (${item.variantLabel})" else ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondary
                                                )
                                            }
                                            Text(
                                                "₹${"%.0f".format(item.totalPrice)}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. Bill Summary & Payment Info
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Payment Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                val displayPaymentMethod = when (currentOrder.paymentMethod.lowercase()) {
                                    "cash" -> if (currentOrder.paymentStatus.lowercase() == "cod") "Cash on Delivery (COD)" else "Cash"
                                    "upi" -> "UPI"
                                    "card" -> "Card"
                                    "online" -> "Online"
                                    else -> currentOrder.paymentMethod.uppercase()
                                }
                                BillRow("Delivery", "Express Delivery")
                                BillRow("Payment Method", displayPaymentMethod)
                                BillRow("Payment Status", currentOrder.paymentStatus.capitalize())
                                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                                BillRow("Subtotal", "₹${"%.2f".format(currentOrder.subtotal)}")
                                if (currentOrder.discountAmount > 0) {
                                    BillRow("Discount", "-₹${"%.2f".format(currentOrder.discountAmount)}", color = SuccessGreen)
                                }
                                BillRow("Delivery Fee", "₹${"%.2f".format(currentOrder.deliveryFee)}")
                                BillRow("Handling Fee", "₹${"%.2f".format(currentOrder.handlingFee)}")
                                if ((currentOrder.walletUsedAmount ?: 0.0) > 0.0) {
                                    BillRow("Wallet used", "−₹${"%.2f".format(currentOrder.walletUsedAmount ?: 0.0)}", color = SuccessGreen)
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                                BillRow("Total Paid / Due", "₹${"%.2f".format(currentOrder.totalAmount)}", isBold = true, fontSize = 16.sp, color = NaturalPrimary)
                                com.example.ui.components.CashbackNote(currentOrder)

                                if (currentOrder.paymentMethod.lowercase() == "upi" &&
                                    currentOrder.paymentStatus.lowercase() != "paid" &&
                                    currentOrder.status.lowercase() != "cancelled" &&
                                    currentOrder.status.lowercase() != "rejected"
                                ) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                isPayingUpi = true
                                                upiPaymentMessage = "Initiating UPI payment..."
                                                val rpRes = repository.createRazorpayOrder(orderId)
                                                if (rpRes.isFailure) {
                                                    isPayingUpi = false
                                                    snackbarHostState.showSnackbar("Could not start payment. Please try again.")
                                                    return@launch
                                                }
                                                val rpData = rpRes.getOrNull()!!
                                                val activity = context.findActivity()
                                                if (activity == null) {
                                                    isPayingUpi = false
                                                    snackbarHostState.showSnackbar("Unable to open payment screen. Please try again.")
                                                    return@launch
                                                }
                                                activeRpData = rpData
                                                val openRes = RazorpayPaymentManager.startUpiCheckout(
                                                    activity = activity,
                                                    keyId = rpData.keyId,
                                                    amountInPaise = rpData.amountInPaise,
                                                    currency = rpData.currency,
                                                    razorpayOrderId = rpData.razorpayOrderId,
                                                    orderNumber = currentOrder.orderNumber.ifBlank { currentOrder.id ?: "" },
                                                    userPhone = null,
                                                    userEmail = null
                                                )
                                                if (openRes.isFailure) {
                                                    isPayingUpi = false
                                                    activeRpData = null
                                                    snackbarHostState.showSnackbar("Could not start payment. Please try again.")
                                                }
                                            }
                                        },
                                        enabled = !isPayingUpi,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("retry_upi_payment_button"),
                                        colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        if (isPayingUpi) {
                                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(upiPaymentMessage, color = Color.White, fontSize = 14.sp)
                                        } else {
                                            Icon(Icons.Default.Payment, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Pay Now via UPI (₹${"%.0f".format(currentOrder.totalAmount)})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 6. Need Help? / Customer Support Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Need Help with this Order?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(10.dp))
                                // Placeholder support contacts — swap for real ones before launch.
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${SupportConstants.PHONE}"))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.weight(1f).testTag("help_call_button"),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp), tint = NaturalPrimary)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Call", fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val text = "I need help with order ${currentOrder.orderNumber}"
                                            val encoded = java.net.URLEncoder.encode(text, "UTF-8")
                                            val url = "https://wa.me/${SupportConstants.WHATSAPP}?text=$encoded"
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.weight(1f).testTag("help_whatsapp_button"),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp), tint = SuccessGreen)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("WhatsApp", fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${SupportConstants.EMAIL}?subject=${Uri.encode("Help with Order ${currentOrder.orderNumber}")}"))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.weight(1f).testTag("help_email_button"),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp), tint = NaturalPrimary)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Email", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Rate Order Dialog
    if (showReviewDialog) {
        AlertDialog(
            onDismissRequest = { showReviewDialog = false },
            title = { Text("Rate Your Experience", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Hotel / Vendor Rating", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { star ->
                            Icon(
                                imageVector = if (star <= vendorRating) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = "$star stars",
                                tint = AmberAccent,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { vendorRating = star }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = vendorComment,
                        onValueChange = { vendorComment = it },
                        label = { Text("Food / Item Feedback") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text("Delivery Partner Rating", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { star ->
                            Icon(
                                imageVector = if (star <= partnerRating) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = "$star stars",
                                tint = AmberAccent,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { partnerRating = star }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = partnerComment,
                        onValueChange = { partnerComment = it },
                        label = { Text("Delivery Feedback") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val currentCustId = order?.customerId ?: ""
                        coroutineScope.launch {
                            isSubmittingReview = true
                            var failed = false
                            var errorMessage = ""
                            if (!order?.vendorId.isNullOrBlank()) {
                                val res = repository.submitVendorReview(
                                    VendorReview(
                                        vendorId = order!!.vendorId!!,
                                        customerId = currentCustId,
                                        orderId = orderId,
                                        rating = vendorRating,
                                        comment = vendorComment.takeIf { it.isNotBlank() }
                                    )
                                )
                                if (res.isFailure) {
                                    failed = true
                                    errorMessage = res.exceptionOrNull()?.message ?: "Failed to submit vendor review"
                                }
                            }
                            if (!failed && !order?.deliveryPartnerId.isNullOrBlank()) {
                                val res = repository.submitDeliveryPartnerReview(
                                    DeliveryPartnerReview(
                                        deliveryPartnerId = order!!.deliveryPartnerId!!,
                                        customerId = currentCustId,
                                        orderId = orderId,
                                        rating = partnerRating,
                                        comment = partnerComment.takeIf { it.isNotBlank() }
                                    )
                                )
                                if (res.isFailure) {
                                    failed = true
                                    errorMessage = res.exceptionOrNull()?.message ?: "Failed to submit delivery review"
                                }
                            }
                            isSubmittingReview = false
                            if (failed) {
                                snackbarHostState.showSnackbar(if (errorMessage.isNotBlank()) errorMessage else "Could not submit review. Please try again.")
                            } else {
                                showReviewDialog = false
                                reviewSubmitted = true
                                snackbarHostState.showSnackbar("Thank you for your rating!")
                            }
                        }
                    },
                    enabled = !isSubmittingReview,
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    if (isSubmittingReview) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Submitting...")
                    } else {
                        Text("Submit Review")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showReviewDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    scratchCardAmount?.let { amount ->
        com.example.ui.components.ScratchCardDialog(
            amount = amount,
            onRevealed = { coroutineScope.launch { repository.scratchMyCard(orderId) } },
            onDismiss = { scratchCardAmount = null }
        )
    }
}

@Composable
fun OrderStatusStepper(
    currentStatus: String,
    history: List<OrderStatusHistory>,
    cancellationReason: String? = null
) {
    val steps = listOf(
        "pending" to "Order Placed",
        "confirmed" to "Order Confirmed",
        "preparing" to "Preparing Food / Packing",
        "ready" to "Ready for Dispatch",
        "out_for_delivery" to "Out for Delivery",
        "delivered" to "Delivered"
    )

    val currentStatusNormalized = currentStatus.lowercase()

    // Cancelled / rejected: distinct red state with the cancellation timestamp (if any).
    val isTerminalFailed = currentStatusNormalized == "cancelled" || currentStatusNormalized == "rejected"
    if (isTerminalFailed) {
        val cancelEntry = history.find {
            val s = it.status.lowercase()
            s == "cancelled" || s == "rejected"
        }
        val effectiveReason = cancellationReason ?: cancelEntry?.note ?: ""
        val isUpiTimeout = effectiveReason.contains("UPI payment not received within 15 minutes", ignoreCase = true)

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PastelCoral,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Cancel, contentDescription = null, tint = NaturalBadgeRed)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        "This order was $currentStatusNormalized.",
                        fontWeight = FontWeight.Bold,
                        color = NaturalBadgeRed
                    )
                    if (isUpiTimeout) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Order cancelled because the payment was not completed. If money was deducted, it will be refunded by your bank/UPI app automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NaturalBadgeRed,
                            lineHeight = 16.sp
                        )
                    } else if (effectiveReason.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            effectiveReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = NaturalBadgeRed
                        )
                    }
                    if (!cancelEntry?.createdAt.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            cancelEntry!!.createdAt.take(16).replace("T", " "),
                            fontSize = 11.sp,
                            color = NaturalBadgeRed
                        )
                    }
                }
            }
        }
        return
    }

    // Done-state is driven ENTIRELY by order_status_history rows (the backend writes a
    // row on every status change) — never inferred from orders.status. The history is
    // fetched ordered by created_at.asc, so the last entry is the current/latest step.
    val historyByStatus = remember(history) { history.associateBy { it.status.lowercase() } }
    val latestStatus = history.lastOrNull()?.status?.lowercase() ?: currentStatusNormalized

    Column(modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, (key, label) ->
            val histItem = historyByStatus[key]
            val isDone = histItem != null
            val isCurrent = key == latestStatus

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = if (isDone) NaturalPrimary else TextMuted.copy(alpha = 0.3f),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isDone) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            } else {
                                Text("${index + 1}", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                    }

                    if (index < steps.size - 1) {
                        val nextDone = historyByStatus[steps[index + 1].first] != null
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(32.dp)
                                .background(if (nextDone) NaturalPrimary else TextMuted.copy(alpha = 0.3f))
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = label,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isDone) TextPrimary else TextMuted,
                        fontSize = 14.sp
                    )
                    if (!histItem?.createdAt.isNullOrBlank()) {
                        Text(
                            text = histItem!!.createdAt.take(16).replace("T", " "),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

// Live ETA countdown: estimated_delivery_at - now(), ticking every 30s. Shows
// "Arriving any moment" once the ETA has passed but the order isn't delivered yet.
@Composable
fun EtaCountdown(estimatedDeliveryAt: String, isDelivered: Boolean) {
    LiveEtaCountdownBanner(
        estimatedDeliveryAt = estimatedDeliveryAt,
        estimatedDeliveryMinutes = null,
        isDelivered = isDelivered
    )
}

@Composable
fun LiveEtaCountdownBanner(
    estimatedDeliveryAt: String?,
    estimatedDeliveryMinutes: Int?,
    isDelivered: Boolean
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(estimatedDeliveryAt) {
        while (true) {
            delay(30000) // Ticks down every 30s
            now = System.currentTimeMillis()
        }
    }

    val label: String = when {
        isDelivered -> "Delivered"
        !estimatedDeliveryAt.isNullOrBlank() -> {
            val etaMillis = parseIsoTimestamp(estimatedDeliveryAt)
            if (etaMillis <= 0L) {
                if (estimatedDeliveryMinutes != null && estimatedDeliveryMinutes > 0) {
                    "Arriving in ~$estimatedDeliveryMinutes min"
                } else {
                    "Calculating arrival time..."
                }
            } else {
                val remainingMs = etaMillis - now
                if (remainingMs <= 0) {
                    "Arriving any moment"
                } else {
                    val remainingMin = kotlin.math.ceil(remainingMs / 60000.0).toInt().coerceAtLeast(1)
                    val h = remainingMin / 60
                    val m = remainingMin % 60
                    if (h > 0) "Arriving in ~${h}h ${m}m" else "Arriving in ~$m min"
                }
            }
        }
        estimatedDeliveryMinutes != null && estimatedDeliveryMinutes > 0 -> {
            "Arriving in ~$estimatedDeliveryMinutes min"
        }
        else -> {
            "Calculating arrival time..."
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PastelSage,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("eta_countdown_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.White.copy(alpha = 0.8f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.AccessTime,
                        contentDescription = "Estimated Delivery Time",
                        tint = DarkGreenText,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "ESTIMATED ARRIVAL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = DarkGreenText.copy(alpha = 0.8f),
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DarkGreenText
                    )
                }
            }

            // Live indicator badge
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.9f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(SuccessGreen, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        color = SuccessGreen
                    )
                }
            }
        }
    }
}

@Composable
fun FindingDeliveryPartnerCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("finding_partner_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(NaturalPrimary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    color = NaturalPrimary,
                    strokeWidth = 2.5.dp
                )
                Icon(
                    imageVector = Icons.Default.TwoWheeler,
                    contentDescription = null,
                    tint = NaturalPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Finding a delivery partner for your order...",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "We are assigning the nearest available delivery partner. Driver details, live map, and arrival ETA will appear once accepted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun DeliveryPartnerCardContent(
    deliveryPartner: DeliveryPartner,
    onCallClick: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Photo / Icon placeholder
                Surface(
                    shape = CircleShape,
                    color = NaturalPrimary.copy(alpha = 0.12f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.DeliveryDining,
                            contentDescription = "Delivery Partner Avatar",
                            tint = NaturalPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = deliveryPartner.name.ifBlank { "Delivery Partner" },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val vehicleInfo = listOfNotNull(
                        deliveryPartner.vehicleType?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotBlank() },
                        deliveryPartner.vehicleNumber?.takeIf { it.isNotBlank() }
                    ).joinToString(" • ")

                    Text(
                        text = if (vehicleInfo.isNotBlank()) vehicleInfo else "Delivery Partner",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            if (!deliveryPartner.phone.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onCallClick(deliveryPartner.phone) },
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("call_partner_button")
                ) {
                    Icon(
                        Icons.Default.Phone,
                        contentDescription = "Call Partner",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Call",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun LiveDeliveryMap(
    repository: SndmartRepository,
    deliveryPartner: DeliveryPartner,
    deliveryAddress: CustomerAddress?
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lat = deliveryPartner.latitude
    val lng = deliveryPartner.longitude

    if (lat == null || lng == null) {
        // "Waiting for delivery partner to start sharing location..."
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("map_waiting_location")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.LocationSearching,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Waiting for delivery partner to start sharing location...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                    Text(
                        text = "Live GPS updates will appear automatically",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }
    } else {
        val partnerLatLng = remember(lat, lng) { GeoLatLng(lat, lng) }
        val destLatLng = remember(deliveryAddress?.lat, deliveryAddress?.lng) {
            if (deliveryAddress?.lat != null && deliveryAddress.lng != null && deliveryAddress.lat != 0.0) {
                GeoLatLng(deliveryAddress.lat, deliveryAddress.lng)
            } else null
        }

        val mapState = rememberSndmartMapState(initialCenter = partnerLatLng, initialZoom = 15f)
        val mapProviderState = rememberMapProviderState(repository)
        val osmTileConfig = rememberOsmTileConfig(repository)
        var trackingMapType by remember { mutableStateOf(SndmartMapType.NORMAL) }

        // Auto-fit camera when partner or destination location updates
        LaunchedEffect(partnerLatLng, destLatLng) {
            if (destLatLng != null) {
                mapState.animateToBounds(listOf(partnerLatLng, destLatLng), paddingPx = 120)
            } else {
                mapState.animateTo(partnerLatLng.lat, partnerLatLng.lng, 15f)
            }
        }

        // Live Map Container
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("live_delivery_map")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(SuccessGreen, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Live Delivery Tracking",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SuccessGreen.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "Live GPS (12s)",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SuccessGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Interactive Google Map View
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                ) {
                    SndmartMap(
                        modifier = Modifier.fillMaxSize(),
                        testTag = "google_tracking_map",
                        state = mapState,
                        mapProviderState = mapProviderState,
                        markers = buildList {
                            add(
                                SndmartMarker(
                                    id = "delivery_partner",
                                    position = partnerLatLng,
                                    title = "Delivery Partner: ${deliveryPartner.name}",
                                    snippet = "On the way to deliver your order",
                                    tint = MarkerTint.BLUE
                                )
                            )
                            if (destLatLng != null) {
                                add(
                                    SndmartMarker(
                                        id = "delivery_destination",
                                        position = destLatLng,
                                        title = "Delivery Address (${deliveryAddress?.label ?: "Home"})",
                                        snippet = deliveryAddress?.addressLine ?: "",
                                        tint = MarkerTint.RED
                                    )
                                )
                            }
                        },
                        // Route Polyline connecting Rider to Destination
                        polyline = destLatLng?.let {
                            SndmartPolylineSpec(points = listOf(partnerLatLng, it), color = NaturalPrimary, widthPx = 8f)
                        },
                        mapType = trackingMapType,
                        osmTileUrlBase = osmTileConfig.tileUrlBase,
                        osmSatelliteTileUrlBase = osmTileConfig.satelliteTileUrlBase
                    )

                    // Map provider switch + satellite toggle
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MapProviderSwitchButton(mapProviderState = mapProviderState)
                        MapTypeToggleButton(
                            mapType = trackingMapType,
                            onToggle = { trackingMapType = it },
                            satelliteAvailable = mapProviderState.provider != MapProvider.OSM || !osmTileConfig.satelliteTileUrlBase.isNullOrBlank()
                        )
                    }

                    // Floating Recenter Button
                    SmallFloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                if (destLatLng != null) {
                                    mapState.animateToBounds(listOf(partnerLatLng, destLatLng), paddingPx = 120)
                                } else {
                                    mapState.animateTo(partnerLatLng.lat, partnerLatLng.lng, 16f)
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .size(36.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = NaturalPrimary,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "Recenter Map", modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom bar inside card: GPS details & Open Maps button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.TwoWheeler, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Rider: ${deliveryPartner.name}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                        if (!deliveryAddress?.addressLine.isNullOrBlank()) {
                            Text(
                                text = "To: ${deliveryAddress!!.addressLine}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val mapUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Delivery+Partner)")
                            val intent = Intent(Intent.ACTION_VIEW, mapUri)
                            intent.setPackage("com.google.android.apps.maps")
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng"))
                                context.startActivity(browserIntent)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("open_maps_button")
                    ) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = "Open in Google Maps",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Open in Maps",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

private fun parseIsoTimestamp(s: String): Long {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(s.replace("Z", "+00:00"))?.time ?: 0L
    } catch (e: Exception) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.parse(s.take(19))?.time ?: 0L
        } catch (e2: Exception) {
            0L
        }
    }
}
