package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.location.HighAccuracyLocationManager
import com.example.ui.theme.*

/**
 * Shown on checkout while the location permission is off: the order then carries no exact position. One tap asks for it.
 * Disappears by itself once it is allowed. Never blocks the order.
 */
@Composable
fun CheckoutLocationHint(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(HighAccuracyLocationManager.hasAnyLocationPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = HighAccuracyLocationManager.hasAnyLocationPermission(context)
    }
    if (allowed) return
    Surface(
        shape = RoundedCornerShape(14.dp), color = NaturalPrimaryContainer, border = BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.3f)),
        modifier = modifier.fillMaxWidth().testTag("checkout_location_hint")
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.MyLocation, contentDescription = null, tint = NaturalPrimary)
            Column(Modifier.weight(1f)) {
                Text("Share your exact location", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("So the delivery partner finds your door, even if the address pin is a little off.", fontSize = 12.sp, color = TextMuted)
            }
            FilledTonalButton(
                onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) { Text("Allow", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
        }
    }
}
