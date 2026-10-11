package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomerWalletTransaction
import com.example.data.model.MyWallet
import com.example.data.model.WalletLedgerEntry
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.ErrorCard
import com.example.ui.components.OrderListSkeleton
import com.example.ui.theme.*
import com.example.util.formatCouponDate
import com.example.util.rupees
import kotlinx.coroutines.launch

private val CashbackTealWallet = Color(0xFF00796B)
private val ExpiryOrange = Color(0xFFEF6C00)

/** How each wallet history entry is shown. */
private data class LedgerStyle(val label: String, val sign: String, val color: Color, val icon: ImageVector)

private fun ledgerStyle(e: WalletLedgerEntry): LedgerStyle = when (e.entryType) {
    "cashback_credit" -> LedgerStyle("Cashback received", "+", SuccessGreen, Icons.Outlined.CardGiftcard)
    "wallet_used" -> LedgerStyle(
        if (!e.orderNumber.isNullOrBlank()) "Used on order #${e.orderNumber}" else "Used on order",
        "−", NaturalBadgeRed, Icons.Outlined.ShoppingBag
    )
    "wallet_refund" -> LedgerStyle("Refund (order cancelled)", "+", SuccessGreen, Icons.Outlined.Replay)
    "cashback_reversed" -> LedgerStyle("Cashback reversed (order cancelled)", "−", NaturalBadgeRed, Icons.Outlined.Undo)
    "expired" -> LedgerStyle("Cashback expired", "−", TextMuted, Icons.Outlined.Schedule)
    else -> LedgerStyle(e.note ?: "Wallet update", if (e.amount < 0) "−" else "+", TextSecondary, Icons.Outlined.AccountBalanceWallet)
}

private fun shortDate(iso: String?): String = formatCouponDate(iso) ?: (iso?.take(16)?.replace("T", " ") ?: "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onBack: () -> Unit,
    onOpenOrder: (String) -> Unit = {}
) {
    val PAGE_SIZE = 30
    val coroutineScope = rememberCoroutineScope()
    val userId = sessionManager.userId.collectAsState().value

    var wallet by remember { mutableStateOf<MyWallet?>(null) }
    var history by remember { mutableStateOf<List<WalletLedgerEntry>>(emptyList()) }
    var olderRefunds by remember { mutableStateOf<List<CustomerWalletTransaction>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showHowItWorks by remember { mutableStateOf(false) }

    fun load() {
        if (userId.isNullOrBlank()) {
            isLoading = false
            return
        }
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val w = repository.getMyWallet(force = true)
            val h = repository.getMyWalletHistory(PAGE_SIZE, null)
            // Refund records from before the cashback wallet stay visible (read-only).
            olderRefunds = repository.getWalletTransactions(userId, limit = 20, offset = 0).getOrNull() ?: emptyList()
            if (w.isSuccess) {
                wallet = w.getOrNull()
                history = h.getOrNull() ?: emptyList()
                hasMore = history.size >= PAGE_SIZE
            } else {
                errorMessage = w.exceptionOrNull()?.message ?: "Failed to load wallet"
            }
            isLoading = false
        }
    }

    fun loadMore() {
        val last = history.lastOrNull() ?: return
        coroutineScope.launch {
            isLoadingMore = true
            val more = repository.getMyWalletHistory(PAGE_SIZE, last.createdAt).getOrNull() ?: emptyList()
            val known = history.map { it.id }.toSet()
            history = history + more.filter { it.id !in known }
            hasMore = more.size >= PAGE_SIZE
            isLoadingMore = false
        }
    }

    LaunchedEffect(userId) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sndmart Wallet", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { load() }, modifier = Modifier.testTag("refresh_wallet_button")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val w = wallet
            if (isLoading && w == null) {
                OrderListSkeleton(count = 3)
            } else if (w == null) {
                ErrorCard(
                    message = errorMessage ?: "Failed to load wallet",
                    onRetry = { load() },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (!w.enabled) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Wallet is paused right now",
                                    color = TextSecondary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }

                    // Balance card
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wallet_balance_card"),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = NaturalPrimary, contentColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(22.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = null, tint = PastelMint, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "WALLET BALANCE",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White.copy(alpha = 0.85f),
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(rupees(w.balance), fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Cashback earned ${rupees(w.totalCashbackEarned)} · Used ${rupees(w.totalUsed)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }

                    if (w.expiringSoonAmount > 0.0) {
                        item {
                            Surface(
                                color = Color(0xFFFFF3E0),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = ExpiryOrange, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "${rupees(w.expiringSoonAmount)} expires on ${shortDate(w.expiringSoonFirstExpiry)}. Use it on your next order!",
                                        color = ExpiryOrange,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    if (w.pending.isNotEmpty()) {
                        item {
                            WalletSectionCard(title = "Pending cashback") {
                                w.pending.forEach { p ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !p.orderId.isNullOrBlank()) { p.orderId?.let(onOpenOrder) }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.CardGiftcard, contentDescription = null, tint = CashbackTealWallet, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                rupees(p.amount) + (p.orderNumber?.let { " · Order #$it" } ?: ""),
                                                fontWeight = FontWeight.SemiBold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text("Credited after delivery", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (w.lots.isNotEmpty()) {
                        item {
                            WalletSectionCard(title = "Available cashback") {
                                w.lots.forEach { lot ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(rupees(lot.amount), style = MaterialTheme.typography.bodyMedium)
                                        Text("Expires ${shortDate(lot.expiresAt)}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        WalletSectionCard(
                            title = "How it works",
                            trailing = {
                                Icon(
                                    if (showHowItWorks) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null
                                )
                            },
                            onHeaderClick = { showHowItWorks = !showHowItWorks }
                        ) {
                            if (showHowItWorks) {
                                val lines = buildList {
                                    add("Cashback coupons add money to your wallet when the order is delivered.")
                                    add("Use up to ${w.maxUsePercent.toInt()}% of item total per order.")
                                    if (w.minOrder > 0) add("Minimum order ${rupees(w.minOrder)}.")
                                    add("Cashback expires as shown above.")
                                    add("Wallet money can't be withdrawn or topped up.")
                                }
                                lines.forEach { line ->
                                    Text("• $line", style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }

                    item {
                        Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    if (history.isEmpty()) {
                        item {
                            WalletSectionCard(title = null) {
                                Text(
                                    "No wallet balance yet. Use a cashback coupon to earn money back on your order.",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    } else {
                        items(history, key = { it.id }) { e ->
                            val st = ledgerStyle(e)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !e.orderId.isNullOrBlank()) { e.orderId?.let(onOpenOrder) },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(st.icon, contentDescription = null, tint = st.color, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(st.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        val sub = shortDate(e.createdAt) +
                                            if (!e.orderNumber.isNullOrBlank() && e.entryType != "wallet_used") " · Order #${e.orderNumber}" else ""
                                        Text(sub, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    }
                                    Text(
                                        "${st.sign}${rupees(kotlin.math.abs(e.amount))}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = st.color,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        if (hasMore) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp, color = NaturalPrimary)
                                    } else {
                                        OutlinedButton(
                                            onClick = { loadMore() },
                                            shape = RoundedCornerShape(20.dp),
                                            modifier = Modifier.testTag("load_more_wallet_button")
                                        ) {
                                            Text("Load more")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (olderRefunds.isNotEmpty()) {
                        item {
                            Text("Earlier refunds", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        items(olderRefunds, key = { "old_" + (it.id ?: it.hashCode().toString()) }) { txn ->
                            val isCredit = txn.type.lowercase() == "credit"
                            WalletSectionCard(title = null) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(txn.reason ?: (if (isCredit) "Wallet credit" else "Wallet debit"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text(shortDate(txn.createdAt), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    }
                                    Text(
                                        "${if (isCredit) "+" else "−"}${rupees(txn.amount)}",
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCredit) SuccessGreen else NaturalBadgeRed
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
private fun WalletSectionCard(
    title: String?,
    trailing: (@Composable () -> Unit)? = null,
    onHeaderClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (onHeaderClick != null) Modifier.clickable { onHeaderClick() } else Modifier),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}
