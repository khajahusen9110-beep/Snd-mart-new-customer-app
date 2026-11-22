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
import com.example.data.repository.HelperRepository
import com.example.ui.theme.TextSecondary

// Kept out of HomeScreen on purpose: that function is at the compiler's size limit.

/** True when Quick Helper is switched on for this city; the third home tab only shows then. */
@Composable
fun rememberHelperEnabled(cityId: String?): Boolean {
    val enabled by produceState(initialValue = false, cityId) {
        value = cityId != null && HelperRepository.getConfig(cityId)?.enabled == true
    }
    return enabled
}

/** Third tab of the home mode toggle; opens the Quick Helper screen. */
@Composable
fun RowScope.HelperToggleTab(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = Color.Transparent,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .testTag("toggle_helper")
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 10.dp)) {
            Text(
                text = "Quick Helper",
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = TextSecondary
            )
        }
    }
}
