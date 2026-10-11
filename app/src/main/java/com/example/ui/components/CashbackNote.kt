package com.example.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Order
import com.example.ui.theme.TextMuted
import com.example.util.rupees

/** Cashback / wallet status line for an order (order list and order details). */
@Composable
fun CashbackNote(order: Order, modifier: Modifier = Modifier) {
    val cashback = order.cashbackAmount ?: 0.0
    val walletUsed = order.walletUsedAmount ?: 0.0
    val status = order.status.lowercase()
    val cancelled = status == "cancelled" || status == "rejected"
    if (cancelled) {
        if (cashback <= 0.0 && walletUsed <= 0.0) return
        val text = buildString {
            if (cashback > 0.0) append("Cashback cancelled. ")
            if (walletUsed > 0.0) append("${rupees(walletUsed)} returned to your wallet.")
        }.trim()
        Text(text, color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = modifier.padding(top = 6.dp))
        return
    }
    if (cashback <= 0.0) return
    Text(
        text = if (status == "delivered") "✅ ${rupees(cashback)} cashback added to wallet"
        else "🎁 ${rupees(cashback)} cashback after delivery",
        color = Color(0xFF00796B),
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(top = 6.dp)
    )
}
