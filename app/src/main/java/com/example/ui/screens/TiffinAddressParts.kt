package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomerAddress
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.OutlineSoft
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

/**
 * Pickup / drop address chooser for the Tiffin plan form: every saved address as a tappable card (label and
 * address, the chosen one highlighted), Edit on each, and "Add new address" at the end, so the customer never
 * has to leave the plan form to save or fix an address.
 */
@Composable
internal fun TiffinAddressChoice(
    title: String,
    addresses: List<CustomerAddress>,
    selectedId: String,
    hideId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (CustomerAddress) -> Unit
) {
    val shown = addresses.filter { it.id != null && it.id != hideId }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 12.sp, color = TextSecondary)
        if (shown.isEmpty()) Text(tx("noAddrHint"), fontSize = 12.sp, color = TextMuted)
        shown.forEach { a ->
            val id = a.id.orEmpty()
            val picked = id == selectedId
            Row(
                Modifier.fillMaxWidth()
                    .clip(AppFieldShape)
                    .border(if (picked) 2.dp else 1.dp, if (picked) NaturalPrimary else OutlineSoft, AppFieldShape)
                    .clickable { onSelect(id) }
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = picked, onClick = { onSelect(id) })
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(a.label.ifBlank { "Address" }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(a.addressLine, fontSize = 12.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onEdit(a) }) { Text(tx("editAddr"), fontSize = 13.sp) }
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Text(tx("addNewAddr"), fontWeight = FontWeight.SemiBold)
        }
    }
}
