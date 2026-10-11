package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomerNotification
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.ErrorCard
import com.example.ui.components.NotificationListSkeleton
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onNavigateToOrder: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateToWallet: () -> Unit = {}
) {
    val PAGE_SIZE = 20
    val coroutineScope = rememberCoroutineScope()
    val userId = sessionManager.userId.collectAsState().value
    val snackbarHostState = remember { SnackbarHostState() }

    var notifications by remember { mutableStateOf<List<CustomerNotification>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val unreadCount = remember(notifications) {
        notifications.count { !it.isRead }
    }

    fun loadNotifications(reset: Boolean = true) {
        if (userId.isNullOrBlank()) {
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

            val offset = if (reset) 0 else notifications.size
            val result = repository.getCustomerNotifications(userId, limit = PAGE_SIZE, offset = offset)
            isLoading = false
            isLoadingMore = false

            result.onSuccess { list ->
                if (reset) {
                    notifications = list
                } else {
                    notifications = notifications + list
                }
                hasMore = list.size >= PAGE_SIZE
            }.onFailure { err ->
                if (reset) {
                    errorMessage = err.message ?: "Failed to load notifications"
                }
            }
        }
    }

    LaunchedEffect(userId) {
        loadNotifications(reset = true)
        userId?.let { repository.refreshUnreadNotificationCount(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Notifications",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        if (unreadCount > 0) {
                            Text(
                                text = "$unreadCount unread",
                                style = MaterialTheme.typography.labelMedium,
                                color = NaturalPrimary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("notifications_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    if (unreadCount > 0) {
                        TextButton(
                            onClick = {
                                val currentUserId = userId ?: return@TextButton
                                coroutineScope.launch {
                                    val res = repository.markAllNotificationsAsRead(currentUserId)
                                    if (res.isSuccess) {
                                        notifications = notifications.map { it.copy(isRead = true) }
                                        repository.refreshUnreadNotificationCount(currentUserId)
                                    } else {
                                        snackbarHostState.showSnackbar("Could not mark all as read")
                                    }
                                }
                            },
                            modifier = Modifier.testTag("mark_all_read_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DoneAll,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = NaturalPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Mark all read",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = NaturalPrimary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                isLoading && notifications.isEmpty() -> {
                    // Rule 7: Skeleton list loader instead of a single spinner
                    NotificationListSkeleton(count = 5)
                }

                errorMessage != null && notifications.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ErrorCard(
                            message = errorMessage ?: "An error occurred",
                            onRetry = { loadNotifications(reset = true) }
                        )
                    }
                }

                notifications.isEmpty() -> {
                    EmptyNotificationsView()
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("notifications_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(notifications, key = { it.id }) { item ->
                            NotificationCardItem(
                                notification = item,
                                onClick = {
                                    // Mark read if unread
                                    if (!item.isRead) {
                                        val currentUserId = userId
                                        coroutineScope.launch {
                                            repository.markNotificationAsRead(item.id)
                                            currentUserId?.let { repository.refreshUnreadNotificationCount(it) }
                                        }
                                        notifications = notifications.map {
                                            if (it.id == item.id) it.copy(isRead = true) else it
                                        }
                                    }
                                    // Wallet cashback credited: open the wallet; otherwise the order if present
                                    val orderId = item.resolvedOrderId
                                    if (item.isWalletCashback) {
                                        onNavigateToWallet()
                                    } else if (!orderId.isNullOrBlank()) {
                                        onNavigateToOrder(orderId)
                                    }
                                }
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
                                            onClick = { loadNotifications(reset = false) },
                                            shape = RoundedCornerShape(20.dp),
                                            modifier = Modifier.testTag("load_more_notifications_button")
                                        ) {
                                            Text("Load More Notifications (20)")
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
private fun NotificationCardItem(
    notification: CustomerNotification,
    onClick: () -> Unit
) {
    val meta = remember(notification.displayTitle, notification.displayBody) {
        resolveNotificationMeta(notification.displayTitle, notification.displayBody)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("notification_item_${notification.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!notification.isRead) NaturalPrimaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (!notification.isRead) 1.5.dp else 0.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Icon
            Surface(
                shape = CircleShape,
                color = meta.tintColor.copy(alpha = 0.12f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = meta.icon,
                        contentDescription = null,
                        tint = meta.tintColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = notification.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (!notification.isRead) FontWeight.Bold else FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )

                    if (!notification.isRead) {
                        Surface(
                            shape = CircleShape,
                            color = NaturalPrimary,
                            modifier = Modifier.size(8.dp)
                        ) {}
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = notification.displayBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (!notification.isRead) TextPrimary else TextSecondary,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatRelativeTime(notification.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary.copy(alpha = 0.8f)
                    )

                    if (!notification.resolvedOrderId.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = "View Order",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = NaturalPrimary
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = NaturalPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class NotificationMeta(
    val icon: ImageVector,
    val tintColor: Color
)

@Composable
private fun EmptyNotificationsView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = NaturalPrimaryContainer,
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsNone,
                    contentDescription = null,
                    tint = NaturalPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "No notifications yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Order status updates, live delivery tracking, and city announcements will appear right here.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun resolveNotificationMeta(title: String, body: String): NotificationMeta {
    val combined = "$title $body".lowercase()
    return when {
        combined.contains("delivered") -> NotificationMeta(Icons.Outlined.TaskAlt, SuccessGreen)
        combined.contains("out for delivery") -> NotificationMeta(Icons.Outlined.LocalShipping, NaturalOceanBlue)
        combined.contains("picked up") -> NotificationMeta(Icons.Outlined.DirectionsBike, AmberAccent)
        combined.contains("delivery partner") || combined.contains("assigned") -> NotificationMeta(Icons.Outlined.Person, NaturalPrimary)
        combined.contains("ready") -> NotificationMeta(Icons.Outlined.ShoppingBag, NaturalPrimary)
        combined.contains("preparing") -> NotificationMeta(Icons.Outlined.Restaurant, AmberAccent)
        combined.contains("confirmed") -> NotificationMeta(Icons.Outlined.CheckCircle, SuccessGreen)
        combined.contains("cancel") -> NotificationMeta(Icons.Outlined.Cancel, ErrorRed)
        else -> NotificationMeta(Icons.Outlined.Campaign, NaturalPrimary)
    }
}

private fun formatRelativeTime(isoString: String?): String {
    if (isoString.isNullOrBlank()) return ""
    return try {
        // Handles "2026-09-10T19:00:00Z", "2026-09-10T19:00:00.123456+00:00", etc.
        val clean = isoString.substringBefore(".").substringBefore("+").substringBefore("Z")
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = format.parse(clean) ?: return ""
        val now = System.currentTimeMillis()
        val diffMs = now - date.time

        when {
            diffMs < 60_000 -> "Just now"
            diffMs < 3600_000 -> "${diffMs / 60_000}m ago"
            diffMs < 86400_000 -> "${diffMs / 3600_000}h ago"
            diffMs < 172800_000 -> "Yesterday"
            else -> {
                val outFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
                outFormat.format(date)
            }
        }
    } catch (e: Exception) {
        ""
    }
}
