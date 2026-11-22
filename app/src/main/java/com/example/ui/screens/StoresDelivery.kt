package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.model.StoreDeliveryCfg
import com.example.data.model.StoreDeliveryChoice
import com.example.data.model.buildStoreSlots

/** Express (as soon as possible) or Scheduled (a day + time window) for an order from a local shop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreDeliveryCard(
    cfg: StoreDeliveryCfg,
    shopOpen: Boolean,
    choice: StoreDeliveryChoice,
    onChange: (StoreDeliveryChoice) -> Unit
) {
    val days = remember(cfg) { buildStoreSlots(cfg) }
    var dayKey by remember(days) { mutableStateOf(days.firstOrNull()?.key ?: "") }
    val canExpress = cfg.expressEnabled && shopOpen
    val canSchedule = cfg.scheduledEnabled && days.isNotEmpty()
    // keep the choice valid
    LaunchedEffect(canExpress, canSchedule) {
        if (!choice.scheduled && !canExpress && canSchedule) onChange(StoreDeliveryChoice(true, null))
        if (choice.scheduled && !canSchedule && canExpress) onChange(StoreDeliveryChoice(false, null))
    }
    val day = days.firstOrNull { it.key == dayKey } ?: days.firstOrNull()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Delivery", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cfg.expressEnabled) FilterChip(
                    selected = !choice.scheduled, enabled = canExpress,
                    onClick = { onChange(StoreDeliveryChoice(false, null)) },
                    label = { Text("Express" + if (cfg.expressExtraFee > 0) " (+₹${"%.0f".format(cfg.expressExtraFee)})" else "") }
                )
                if (cfg.scheduledEnabled) FilterChip(
                    selected = choice.scheduled, enabled = canSchedule,
                    onClick = { onChange(StoreDeliveryChoice(true, choice.scheduledFor)) },
                    label = { Text("Scheduled" + if (cfg.scheduledDiscount > 0) " (₹${"%.0f".format(cfg.scheduledDiscount)} off)" else "") }
                )
            }
            if (cfg.expressEnabled && !shopOpen) Text("The shop is closed right now. Choose a scheduled time. · ಅಂಗಡಿ ಈಗ ಮುಚ್ಚಿದೆ, ಸಮಯ ಆಯ್ಕೆಮಾಡಿ.", style = MaterialTheme.typography.bodySmall)
            if (!choice.scheduled && canExpress) Text("Delivered as soon as the shop packs it. · ಅಂಗಡಿ ಪ್ಯಾಕ್ ಮಾಡಿದ ತಕ್ಷಣ ಡೆಲಿವರಿ.", style = MaterialTheme.typography.bodySmall)
            if (choice.scheduled && day != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    days.forEach { d ->
                        FilterChip(selected = d.key == day.key, onClick = { dayKey = d.key; onChange(StoreDeliveryChoice(true, null)) }, label = { Text(d.label) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    day.slots.forEach { sl ->
                        FilterChip(selected = choice.scheduledFor == sl.startIso, onClick = { onChange(StoreDeliveryChoice(true, sl.startIso)) }, label = { Text(sl.label) })
                    }
                }
                if (choice.scheduledFor == null) Text("Pick a time window. · ಸಮಯ ಆಯ್ಕೆಮಾಡಿ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
