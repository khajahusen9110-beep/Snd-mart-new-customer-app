package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.City
import com.example.util.cityClosure

/**
 * Shown while the city is closed: outside the daily timings, or an Emergency Close (the reason is written
 * by the City Admin). Nothing is drawn while the city is open. Kept out of HomeScreen on purpose.
 */
@Composable
fun CityClosedBanner(city: City?, modifier: Modifier = Modifier) {
    val closure = cityClosure(city) ?: return
    val emergency = closure.kind == "emergency"
    val tint = if (emergency) Color(0xFF8A1111) else Color(0xFF7A4A00)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (emergency) Color(0xFFFFF0F0) else Color(0xFFFFF7E6),
        border = BorderStroke(1.dp, if (emergency) Color(0xFFF6B8B8) else Color(0xFFFDE0A6)),
        modifier = modifier.fillMaxWidth().testTag("city_closed_banner")
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (emergency) Icons.Filled.Warning else Icons.Filled.Schedule,
                contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(closure.title, fontWeight = FontWeight.ExtraBold, color = tint, style = MaterialTheme.typography.titleSmall)
                Text(closure.message, color = tint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
