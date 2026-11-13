package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.TextSecondary

/**
 * Handling Fee + Platform Fee collapsed behind one "Taxes & Charges" row (their sum shown), tap to
 * expand and see each — same as the website's FeesBreakdownRow. The Platform Fee row only shows
 * when it is above zero.
 */
@Composable
fun TaxesChargesRow(
    handlingFee: Double,
    platformFee: Double,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    decimals: Int = 0
) {
    val fmt = "%.${decimals}f"
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "taxesChevron")
    val total = handlingFee + platformFee
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = !open }
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Taxes & Charges", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (open) "Hide charges" else "Show charges",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp).rotate(rotation)
                )
            }
            Text("₹${fmt.format(total)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.padding(start = 2.dp)) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Handling Fee", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Text("₹${fmt.format(handlingFee)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                }
                if (platformFee > 0.0) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Platform Fee", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        Text("₹${fmt.format(platformFee)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
