package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StoresConfig
import com.example.data.repository.StoresRepository
import com.example.ui.theme.TextSecondary

// Glue between the home screen and Stores. Kept out of HomeScreen on purpose: that function is at the compiler's size limit.

/** Which home section to open when going back to Home from Stores (Food or the old Grocery); read once. */
object HomeRequest {
    @Volatile var mode: BrowsingMode? = null
    fun take(): BrowsingMode? { val m = mode; mode = null; return m }
}

/** The city's Stores setting; OFF until the server says otherwise, so nothing changes where Stores is not switched on. */
@Composable
fun rememberStoresConfig(cityId: String?): StoresConfig {
    val cfg by produceState(StoresConfig.OFF, cityId) { value = StoresRepository.getConfig(cityId) }
    return cfg
}

/** Tab of the home mode toggle that opens the Stores screen. */
@Composable
fun RowScope.StoresToggleTab(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = Color.Transparent,
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).clickable(onClick = onClick).testTag("toggle_stores")
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 10.dp)) {
            Text("Stores", fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary)
        }
    }
}
