package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.example.data.model.City
import com.example.service.InAppNotification
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SndmartTopBar(
    currentCity: City?,
    cartCount: Int,
    deliveryAddressLabel: String? = null,
    unreadNotificationCount: Int = 0,
    navigationIcon: (@Composable () -> Unit)? = null,
    onCityClick: () -> Unit,
    onCartClick: () -> Unit,
    onNotificationsClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    // Sndmart Wallet chip (shown only when there is a balance)
    walletBalance: Double? = null,
    onWalletClick: (() -> Unit)? = null
) {
    val displayDeliverTo = remember(currentCity, deliveryAddressLabel) {
        val cityName = currentCity?.name ?: "Select City"
        if (!deliveryAddressLabel.isNullOrBlank()) {
            "$deliveryAddressLabel • $cityName"
        } else {
            cityName
        }
    }

    TopAppBar(
        navigationIcon = {
            navigationIcon?.invoke()
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onCityClick() }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .testTag("city_selector_topbar")
            ) {
                Surface(
                    shape = CircleShape,
                    color = NaturalPrimaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = NaturalPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "DELIVER TO",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = TextSecondary.copy(alpha = 0.8f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayDeliverTo,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Select City",
                            modifier = Modifier.size(16.dp),
                            tint = NaturalPrimary
                        )
                    }
                }
            }
        },
        actions = {
            if (walletBalance != null && walletBalance > 0.0 && onWalletClick != null) {
                Surface(
                    shape = RoundedCornerShape(17.dp),
                    color = PastelMint,
                    modifier = Modifier
                        .padding(end = 2.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .clickable { onWalletClick() }
                        .testTag("topbar_wallet_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AccountBalanceWallet,
                            contentDescription = "Sndmart Wallet",
                            tint = DarkGreenText,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = com.example.util.rupees(walletBalance),
                            color = DarkGreenText,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            if (onRefresh != null) {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.testTag("topbar_refresh_button")
                ) {
                    Surface(
                        shape = CircleShape,
                        color = NaturalPrimaryContainer,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = NaturalPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            if (onNotificationsClick != null) {
                IconButton(
                    onClick = onNotificationsClick,
                    modifier = Modifier.testTag("topbar_notifications_button")
                ) {
                    BadgedBox(
                        badge = {
                            if (unreadNotificationCount > 0) {
                                Badge(
                                    containerColor = NaturalBadgeRed,
                                    contentColor = Color.White
                                ) {
                                    Text(
                                        text = if (unreadNotificationCount > 99) "99+" else "$unreadNotificationCount",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = NaturalPrimaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Notifications,
                                    contentDescription = "Notifications",
                                    tint = NaturalPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (onSettingsClick != null) {
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.testTag("topbar_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Connection Settings",
                        tint = TextSecondary
                    )
                }
            }
            IconButton(
                onClick = onCartClick,
                modifier = Modifier.testTag("topbar_cart_button")
            ) {
                BadgedBox(
                    badge = {
                        if (cartCount > 0) {
                            Badge(
                                containerColor = NaturalBadgeRed,
                                contentColor = Color.White
                            ) {
                                Text("$cartCount", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                ) {
                    Surface(
                        shape = CircleShape,
                        color = NaturalPrimaryContainer,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.ShoppingCart,
                                contentDescription = "Cart",
                                tint = NaturalPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
fun QuantityStepper(
    quantity: Int,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "qty"
) {
    if (quantity == 0) {
        Button(
            onClick = onIncrease,
            modifier = modifier
                .height(36.dp)
                .testTag("${testTagPrefix}_add"),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NaturalPrimary,
                contentColor = Color.White
            )
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add",
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("ADD", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    } else {
        Surface(
            modifier = modifier.height(36.dp),
            shape = RoundedCornerShape(20.dp),
            color = NaturalPrimary,
            contentColor = Color.White,
            tonalElevation = 2.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                IconButton(
                    onClick = onDecrease,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("${testTagPrefix}_minus")
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Decrease",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = "$quantity",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .testTag("${testTagPrefix}_count")
                )
                IconButton(
                    onClick = onIncrease,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("${testTagPrefix}_plus")
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Increase",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PriceDisplay(
    price: Double,
    mrp: Double?,
    unit: String? = null,
    modifier: Modifier = Modifier,
    kind: PromoKind? = null
) {
    // When a promotional offer is running for this section, the customer sees the discounted price
    // with the normal price struck through and a "X% OFF" badge. When no offer is active (kind null
    // or no promo), this is identical to the original price display — existing rates are unchanged.
    val promo = if (kind != null) promoFor(kind) else null
    if (promo != null && promo.percent > 0) {
        val discounted = offerPrice(price, promo.percent)
        val pct = Math.round(promo.percent).toInt()
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "₹${"%.0f".format(discounted)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OfferRed
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "₹${"%.0f".format(price)}",
                style = MaterialTheme.typography.bodySmall,
                textDecoration = TextDecoration.LineThrough,
                color = TextMuted
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$pct% OFF",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Color(0xFFF43F5E), Color(0xFFE11D48))
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            if (!unit.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "/ $unit",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        return
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "₹${"%.0f".format(price)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PriceGreen
        )
        if (mrp != null && mrp > price) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "₹${"%.0f".format(mrp)}",
                style = MaterialTheme.typography.bodySmall,
                textDecoration = TextDecoration.LineThrough,
                color = TextMuted
            )
            val discountPercent = (((mrp - price) / mrp) * 100).toInt()
            if (discountPercent > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = TangerineOrange.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "$discountPercent% OFF",
                        style = MaterialTheme.typography.labelSmall,
                        color = TangerineOrange,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
        if (!unit.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "/ $unit",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun ProductImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null
) {
    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(300)
                .scale(Scale.FILL)
                .build(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Fastfood,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
fun InAppNotificationBanner(
    notification: InAppNotification?,
    onDismiss: () -> Unit,
    onNavigateToOrder: (String) -> Unit,
    onNavigateToInbox: (() -> Unit)? = null
) {
    AnimatedVisibility(
        visible = notification != null,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        if (notification != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable {
                        if (!notification.orderId.isNullOrBlank()) {
                            onNavigateToOrder(notification.orderId)
                        } else if (onNavigateToInbox != null) {
                            onNavigateToInbox()
                        }
                        onDismiss()
                    }
                    .testTag("in_app_notification_banner"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = EmeraldGreenDark,
                    contentColor = Color.White
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = TangerineOrange,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = notification.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = notification.body,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onConfigureKey: (() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("error_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = PastelCoral.copy(alpha = 0.6f),
            contentColor = TextPrimary
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = "Error",
                tint = NaturalBadgeRed,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Live Supabase Error",
                fontWeight = FontWeight.Bold,
                color = NaturalBadgeRed
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.testTag("error_retry_button")
                ) {
                    Text("Retry Live Query")
                }
                if (onConfigureKey != null) {
                    OutlinedButton(
                        onClick = onConfigureKey,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("⚙️ Supabase Key")
                    }
                }
            }
        }
    }
}

@Composable
fun BillRow(
    label: String,
    value: String,
    isBold: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    color: Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            color = if (isBold) TextPrimary else TextSecondary
        )
        Text(
            text = value,
            fontSize = fontSize,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.SemiBold,
            color = color
        )
    }
}

/**
 * Pinned floating cart bar displayed near the bottom of browsing screens
 * (Home screen grocery grid, hotel menu) when items exist in the active cart.
 * Shows item count and total price on the left, and a direct "Checkout →" action on the right.
 */
@Composable
fun FloatingCartButton(
    itemCount: Int,
    totalPrice: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "floating_cart_button"
) {
    if (itemCount <= 0) return

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = NaturalPrimary,
        contentColor = Color.White,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(54.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$itemCount item${if (itemCount > 1) "s" else ""} • ₹${"%.0f".format(totalPrice)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Checkout",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Proceed to Checkout",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

