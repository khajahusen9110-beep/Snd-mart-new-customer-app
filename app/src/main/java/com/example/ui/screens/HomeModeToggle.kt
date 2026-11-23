package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.SurfaceVariantLight
import com.example.ui.theme.TextSecondary

// The Grocery / Hotel Food / Quick Helper tab pill. Lives in its own file because HomeScreen is at the
// Kotlin compiler's memory limit (the build ran out of memory when it grew).
@Composable
fun HomeModeToggle(
    browsingMode: BrowsingMode,
    serviceMode: String,
    helperOn: Boolean,
    onSelect: (BrowsingMode) -> Unit,
    onHelper: () -> Unit,
    storesOn: Boolean = false,
    storesReplace: Boolean = false,
    onStores: () -> Unit = {}
) {
    // Natural Tones Segmented Mode Pill
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SurfaceVariantLight,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("home_mode_toggle")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (storesReplace) {
                StoresToggleTab(onClick = onStores)
            } else {
            val isGrocery = browsingMode == BrowsingMode.GROCERY
            val groceryAvailable = serviceMode != "food_only"
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = if (isGrocery) Color(0xFFDCFCE7) else Color.Transparent,
                shadowElevation = if (isGrocery) 3.dp else 0.dp,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .clickable(enabled = groceryAvailable) { onSelect(BrowsingMode.GROCERY) }
                    .testTag("toggle_grocery")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (helperOn || storesOn) "Groceries" else "Fresh Groceries & Fruits",
                            fontWeight = if (isGrocery) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (!groceryAvailable) TextSecondary.copy(alpha = 0.5f) else if (isGrocery) Color(0xFF15803D) else TextSecondary
                        )
                        if (!groceryAvailable) {
                            Text("Coming Soon", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = NaturalPrimary)
                        }
                    }
                }
            }
            }

            val isHotel = browsingMode == BrowsingMode.HOTELS
            val hotelsAvailable = serviceMode != "grocery_only"
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = if (isHotel) Color(0xFFFFEDD5) else Color.Transparent,
                shadowElevation = if (isHotel) 3.dp else 0.dp,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .clickable(enabled = hotelsAvailable) { onSelect(BrowsingMode.HOTELS) }
                    .testTag("toggle_hotels")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (helperOn || storesOn) "Hotel Food" else "Hotel Food & Dining",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (!hotelsAvailable) TextSecondary.copy(alpha = 0.5f) else if (isHotel) Color(0xFFC2410C) else TextSecondary
                        )
                        if (!hotelsAvailable) {
                            Text("Coming Soon", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = NaturalPrimary)
                        }
                    }
                }
            }
            if (storesOn && !storesReplace) StoresToggleTab(onClick = onStores)
            if (helperOn) HelperToggleTab(onClick = { onHelper() })
        }
    }
}
