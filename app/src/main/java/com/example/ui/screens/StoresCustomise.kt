package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StoreMenuProduct
import com.example.data.repository.SndmartRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Shrinks a picked photo (long side 1280 px, JPEG 82) so uploads stay small. */
internal suspend fun shrinkPhoto(context: android.content.Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@withContext null
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    } catch (_: Exception) {
        null
    }
}

/** Made-to-order product: the customer writes a message and/or adds a photo, then adds it to the cart. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CustomiseSheet(
    product: StoreMenuProduct,
    repository: SndmartRepository,
    initialNote: String?,
    initialPhotoPath: String?,
    initialQty: Int,
    editing: Boolean,
    onDismiss: () -> Unit,
    initialVariantId: String? = null,
    onDone: (note: String?, photoPath: String?, qty: Int, variantId: String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf(initialNote ?: "") }
    var photoPath by remember { mutableStateOf(initialPhotoPath) }
    var qty by remember { mutableIntStateOf(maxOf(initialQty, product.minOrderQty, 1)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var variantId by remember { mutableStateOf(initialVariantId ?: product.availableVariants.singleOrNull()?.id) }
    // customising (a message or a photo) costs the shop's extra charge on top, per piece
    val customised = product.isCustomizable && ((product.allowNote && note.trim().isNotEmpty()) || (product.allowPhoto && photoPath != null))
    val unitPrice = (product.availableVariants.firstOrNull { it.id == variantId }?.price ?: product.price) + (if (customised) product.customExtraCharge else 0.0)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            val bytes = shrinkPhoto(context, uri)
            if (bytes == null) { error = "Could not read this photo"; busy = false; return@launch }
            repository.uploadCustomPhoto(bytes).onSuccess { photoPath = it }.onFailure { error = it.message ?: "Photo upload failed" }
            busy = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(product.name, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            if (product.hasVariants) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Choose an option *", fontWeight = FontWeight.SemiBold)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    product.availableVariants.forEach { v ->
                        FilterChip(selected = variantId == v.id, onClick = { variantId = v.id; error = null }, label = { Text("${v.label} · ₹${if (v.price % 1.0 == 0.0) "%.0f".format(v.price) else "%.2f".format(v.price)}") })
                    }
                }
            }
            if (product.minOrderQty > 1 && !editing) Text("Minimum order: ${product.minOrderQty}", fontSize = 12.sp)
            if (product.isCustomizable && product.customExtraCharge > 0) Text("Customising costs ₹${"%.0f".format(product.customExtraCharge)} extra per piece${if (customised) " (added)" else ""}. · ಕಸ್ಟಮೈಸ್ ಮಾಡಲು ಪ್ರತಿ ಒಂದಕ್ಕೆ ₹${"%.0f".format(product.customExtraCharge)} ಹೆಚ್ಚು.", fontSize = 12.sp)
            if (product.leadHours > 0) Text(
                "Made to order · needs ${product.leadHours} hour${if (product.leadHours > 1) "s" else ""} notice, so choose a Scheduled time at checkout. · ಆರ್ಡರ್‌ಗೆ ತಯಾರಿಸಲಾಗುತ್ತದೆ, ${product.leadHours} ಗಂಟೆ ಮುಂಚಿತವಾಗಿ ತಿಳಿಸಿ.",
                fontSize = 12.sp
            )
            if (product.isCustomizable && product.allowNote) OutlinedTextField(
                value = note, onValueChange = { if (it.length <= 300) note = it; error = null },
                label = { Text((product.noteLabel?.takeIf { it.isNotBlank() } ?: "Message / name to write") + if (product.customRequired) " *" else "") },
                placeholder = { Text("e.g. Happy Birthday Rahul") },
                supportingText = { Text("${note.length}/300") },
                minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
            )
            if (product.isCustomizable && product.allowPhoto) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Photo" + if (product.customRequired) " *" else "", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text(if (photoPath == null) "Add photo" else "Change photo")
                    }
                    if (photoPath != null) {
                        Text("✓ Photo added", fontSize = 13.sp)
                        TextButton(onClick = { photoPath = null }) { Text("Remove") }
                    }
                }
                Text("Only you and the shop can see this photo.", fontSize = 11.sp)
            }
            if (!editing) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quantity", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { qty = (qty - 1).coerceAtLeast(maxOf(1, product.minOrderQty)) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("−") }
                Text("$qty", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
                OutlinedButton(onClick = { qty += 1 }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("+") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Button(
                enabled = !busy,
                onClick = {
                    val n = note.trim()
                    if (product.hasVariants && variantId == null) { error = "Please choose an option"; return@Button }
                    if (product.isCustomizable && product.customRequired && product.allowNote && n.isEmpty()) { error = "Please write the message"; return@Button }
                    if (product.isCustomizable && product.customRequired && product.allowPhoto && photoPath == null) { error = "Please add a photo"; return@Button }
                    onDone(if (product.isCustomizable && product.allowNote && n.isNotEmpty()) n else null, if (product.isCustomizable && product.allowPhoto) photoPath else null, qty, variantId)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(if (editing) "Save changes" else "Add to cart · ₹${if (unitPrice % 1.0 == 0.0) "%.0f".format(unitPrice * qty) else "%.2f".format(unitPrice * qty)}", fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
