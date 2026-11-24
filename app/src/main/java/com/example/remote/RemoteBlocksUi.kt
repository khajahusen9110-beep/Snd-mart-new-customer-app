package com.example.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** The app tells this how to open a screen by name (a banner button / card tap); set once by the app's main screen. */
object RemoteActions {
    @Volatile var handler: ((String) -> Unit)? = null
    fun open(name: String) { handler?.invoke(name) }
}

private fun tone(color: String): Pair<Color, Color> = when (color) {
    "green" -> Color(0xFFDCFCE7) to Color(0xFF166534)
    "orange" -> Color(0xFFFFEDD5) to Color(0xFF9A3412)
    "red" -> Color(0xFFFEE2E2) to Color(0xFF991B1B)
    "purple" -> Color(0xFFEDE9FE) to Color(0xFF5B21B6)
    "grey" -> Color(0xFFF1F5F9) to Color(0xFF334155)
    else -> Color(0xFFDBEAFE) to Color(0xFF1E40AF)
}

/**
 * Banners, notices and cards the City Admin sent for this slot of this app. Nothing shows when there are none.
 * [onScreen] opens an app screen by name (the app decides what each name means); a link opens in the browser.
 */
@Composable
fun RemoteBlocksStrip(slot: String, onScreen: (String) -> Unit, modifier: Modifier = Modifier) {
    val cfg by RemoteConfigStore.config.collectAsState()
    val blocks = cfg.blocks.filter { it.slot == slot }
    if (blocks.isEmpty()) return
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            val (bg, fg) = tone(b.color)
            // a notice can be closed for this session; banners and cards stay
            var hidden by rememberSaveable(b.id) { mutableStateOf(false) }
            if (hidden) return@forEach
            val act = { if (b.actionType == "screen" && !b.actionValue.isNullOrBlank()) onScreen(b.actionValue) else if (b.actionType == "url" && !b.actionValue.isNullOrBlank()) runCatching { uri.openUri(b.actionValue) } }
            Surface(
                shape = RoundedCornerShape(16.dp), color = bg, border = BorderStroke(1.dp, fg.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth().then(if (b.actionType != "none" && b.buttonText == null) Modifier.clickable { act() } else Modifier)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (b.imageUrl != null && b.kind != "notice") AsyncImage(model = b.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(if (b.kind == "banner") 64.dp else 52.dp).then(Modifier))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(b.title, fontWeight = FontWeight.ExtraBold, fontSize = if (b.kind == "notice") 14.sp else 16.sp, color = fg)
                        b.body?.let { Text(it, fontSize = 13.sp, color = fg.copy(alpha = 0.85f)) }
                    }
                    if (b.buttonText != null && b.actionType != "none") Button(onClick = { act() }, colors = ButtonDefaults.buttonColors(containerColor = fg, contentColor = Color.White), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        Text(b.buttonText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    if (b.kind == "notice") Text("✕", color = fg, modifier = Modifier.clickable { hidden = true }.padding(4.dp))
                }
            }
        }
    }
}
