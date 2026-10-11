package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.Order
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.ErrorCard
import com.example.ui.components.OrderListSkeleton
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onNavigateToDetail: (orderId: String) -> Unit,
    onRequireLogin: () -> Unit
) {
    val PAGE_SIZE = 20
    val coroutineScope = rememberCoroutineScope()
    val userId = sessionManager.userId.collectAsState().value
    val isLoggedIn = sessionManager.isLoggedIn.collectAsState().value

    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var vendorNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadOrders(reset: Boolean = true) {
        if (!isLoggedIn || userId.isNullOrBlank()) {
            isLoading = false
            return
        }
        coroutineScope.launch {
            if (reset) {
                isLoading = true
                errorMessage = null
            } else {
                isLoadingMore = true
            }

            val offset = if (reset) 0 else orders.size
            val res = repository.getOrders(userId, limit = PAGE_SIZE, offset = offset)
            if (res.isSuccess) {
                val loaded = res.getOrNull() ?: emptyList()
                if (reset) {
                    orders = loaded
                } else {
                    orders = orders + loaded
                }
                hasMore = loaded.size >= PAGE_SIZE

                // Rule 3: Batch resolve hotel names for hotel orders (single query using .in() instead of looping)
                val hotelVendorIds = loaded.mapNotNull { it.vendorId }.distinct()
                if (hotelVendorIds.isNotEmpty()) {
                    val resolved = repository.getVendorNames(hotelVendorIds)
                    vendorNames = vendorNames + resolved
                }
            } else {
                if (reset) {
                    errorMessage = res.exceptionOrNull()?.message
                }
            }
            isLoading = false
            isLoadingMore = false
        }
    }

    LaunchedEffect(userId, isLoggedIn) {
        loadOrders(reset = true)
    }

    // Notifications permission banner: re-checked on resume so it disappears right after the
    // customer grants it from Settings and comes back, with no extra tap needed.
    val context = LocalContext.current
    var permissionRecheckTrigger by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRecheckTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationsDenied = remember(permissionRecheckTrigger) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Orders", fontWeight = FontWeight.Bold) },
                actions = {
                    if (isLoggedIn) {
                        IconButton(onClick = { loadOrders(reset = true) }, modifier = Modifier.testTag("refresh_orders_button")) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Orders")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLoggedIn && notificationsDenied) {
                EnableNotificationsBanner(
                    onOpenSettings = {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        }
                        context.startActivity(intent)
                    }
                )
            }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (!isLoggedIn) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.ReceiptLong,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Sign in to view your orders",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Track active deliveries and view past invoices",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onRequireLogin,
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.testTag("orders_signin_button")
                        ) {
                            Text("Sign In Now")
                        }
                    }
                }
            } else if (isLoading && orders.isEmpty()) {
                // Rule 7: Skeleton loader instead of a single spinner
                OrderListSkeleton(count = 4)
            } else if (errorMessage != null && orders.isEmpty()) {
                ErrorCard(
                    message = errorMessage!!,
                    onRetry = { loadOrders(reset = true) },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (orders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.ShoppingBag,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No orders yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Your placed orders will appear here with live tracking.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(orders, key = { it.id ?: it.orderNumber }) { order ->
                        OrderCardItem(
                            order = order,
                            hotelName = order.vendorId?.let { vendorNames[it] },
                            onClick = { order.id?.let { onNavigateToDetail(it) } }
                        )
                    }

                    if (hasMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.5.dp,
                                        color = NaturalPrimary
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = { loadOrders(reset = false) },
                                        shape = RoundedCornerShape(20.dp),
                                        modifier = Modifier.testTag("load_more_orders_button")
                                    ) {
                                        Text("Load More Orders (20)")
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
fun EnableNotificationsBanner(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("enable_notifications_banner"),
        shape = RoundedCornerShape(12.dp),
        color = PastelSky,
        border = BorderStroke(1.dp, NaturalOceanBlue.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.NotificationsNone,
                contentDescription = null,
                tint = NaturalOceanBlue,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Turn on notifications",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
                Text(
                    text = "Get live order status updates",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("enable_notifications_button")
            ) {
                Text("Enable", fontWeight = FontWeight.Bold, color = NaturalOceanBlue)
            }
        }
    }
}

@Composable
fun OrderCardItem(
    order: Order,
    hotelName: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("order_card_${order.orderNumber}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = order.orderNumber,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (!hotelName.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Store,
                                contentDescription = null,
                                tint = NaturalPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = hotelName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = NaturalPrimary
                            )
                        }
                    }
                    val orderTime = order.placedAt ?: order.createdAt
                    if (!orderTime.isNullOrBlank()) {
                        Text(
                            text = orderTime.take(16).replace("T", " "),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
                val isUpiPaymentPending = order.paymentMethod.lowercase() == "upi" &&
                    (order.paymentStatus.lowercase() == "pending" || order.paymentStatus.lowercase() == "failed") &&
                    order.status.lowercase() == "pending"

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isUpiPaymentPending) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFFE0B2),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "Payment pending",
                                color = Color(0xFFE65100),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .testTag("order_item_payment_pending_badge")
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    OrderStatusBadge(order.status)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Total Amount",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "₹${"%.2f".format(order.totalAmount)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = NaturalPrimary
                    )
                    if ((order.walletUsedAmount ?: 0.0) > 0.0) {
                        Text(
                            text = "Wallet used −${com.example.util.rupees(order.walletUsedAmount ?: 0.0)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    com.example.ui.components.CashbackNote(order)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "View Details",
                        style = MaterialTheme.typography.labelMedium,
                        color = NaturalPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = NaturalPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun OrderStatusBadge(status: String) {
    val (bgColor, textColor, text) = when (status.lowercase()) {
        "pending" -> Triple(PastelPeach, TextPrimary, "Pending")
        "confirmed" -> Triple(PastelSage, DarkGreenText, "Confirmed")
        "preparing" -> Triple(PastelPeach, TextPrimary, "Preparing")
        "ready" -> Triple(PastelMint, DarkGreenText, "Ready")
        "out_for_delivery" -> Triple(PastelSky, NaturalPrimary, "Out for Delivery")
        "delivered" -> Triple(PastelSage, DarkGreenText, "Delivered")
        "cancelled" -> Triple(PastelCoral, NaturalBadgeRed, "Cancelled")
        "rejected" -> Triple(PastelCoral, NaturalBadgeRed, "Rejected")
        else -> Triple(PastelSand, TextPrimary, status.replaceFirstChar { it.uppercase() })
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
