package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.data.repository.AddToCartResult
import com.example.data.repository.SndmartRepository
import com.example.data.repository.StoresRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/** Ask a shop for a price: how many, what you need, an optional photo. No payment now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteRequestSheet(repository: SndmartRepository, storeId: String, product: StoreMenuProduct, onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var variantId by remember { mutableStateOf(product.availableVariants.firstOrNull()?.id) }
    var qty by remember { mutableStateOf(product.minOrderQty.toString()) }
    var note by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            val bytes = shrinkPhoto(context, uri)
            if (bytes == null) error = "Could not read this photo"
            else repository.uploadCustomPhoto(bytes).onSuccess { photo = it }.onFailure { error = it.message ?: "Photo upload failed" }
            busy = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Get a quote: ${product.name}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            if (product.hasVariants) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                product.availableVariants.forEach { v -> FilterChip(selected = variantId == v.id, onClick = { variantId = v.id }, label = { Text(v.label) }) }
            }
            OutlinedTextField(value = qty, onValueChange = { qty = it.filter(Char::isDigit).take(4) }, label = { Text("How many") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = note, onValueChange = { if (it.length <= 600) note = it }, label = { Text("What do you need?") }, placeholder = { Text("Size, colour, message, date... anything the shop should know") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) { Text(if (photo != null) "Photo added ✓ (tap to change)" else if (busy) "Uploading…" else "Add a photo (optional)") }
            Text("No payment now. The shop sends you a price; you accept it and order.", fontSize = 12.sp, color = TextSecondary)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Button(
                onClick = {
                    val q = qty.toIntOrNull() ?: 0
                    when {
                        q < product.minOrderQty -> error = "Minimum order is ${product.minOrderQty}"
                        note.isBlank() && photo == null -> error = "Describe what you need, or add a photo"
                        else -> { busy = true; error = null
                            scope.launch {
                                StoresRepository.requestQuote(storeId, product.id, variantId, q, note.trim().ifBlank { null }, photo).onSuccess { onSent() }.onFailure { error = it.message ?: "Could not send" }
                                busy = false
                            }
                        }
                    }
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text(if (busy) "Please wait…" else "Send request", fontWeight = FontWeight.ExtraBold) }
        }
    }
}

/** My price quotes: waiting, price received (accept / decline), accepted (add to the cart), and the past ones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyQuotesScreen(repository: SndmartRepository, cityId: String?, onBack: () -> Unit, onOpenCart: () -> Unit, onOpenOrder: (String) -> Unit) {
    var list by remember { mutableStateOf<List<StoreQuote>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<AddToCartResult.HotelConflict?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tick) { StoresRepository.myQuotes().onSuccess { list = it; error = null }.onFailure { error = it.message } }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); tick++ } }

    fun toCart(q: StoreQuote): Boolean {
        repository.sessionManager.cacheCartProductSnapshot(q.productId, q.variantId, if (q.variantLabel != null) "${q.productName} - ${q.variantLabel}" else q.productName, null, null)
        val r = repository.addToCart(productId = q.productId, vendorId = q.vendorId, cityId = cityId, variantId = q.variantId, quantityDelta = q.quantity, isHotel = true,
            customNote = q.note, customPhotoPath = q.photoPath, quoteId = q.id, quotePrice = q.quotedUnitPrice)
        if (r is AddToCartResult.HotelConflict) { conflict = r; return false }
        return true
    }
    fun run(q: StoreQuote, call: suspend () -> Result<Unit>, after: (() -> Unit)? = null) {
        busyId = q.id
        scope.launch { call().onSuccess { after?.invoke() }.onFailure { error = it.message }; busyId = null; tick++ }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("My price quotes", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            error?.let { msg -> item { Text("$msg (tap to retry)", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { tick++ }) } }
            if (list == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (list != null && list!!.isEmpty()) item { Text("No quotes yet. On a custom product, tap “Get a quote” and the shop will send you a price.", color = TextSecondary) }
            items(list.orEmpty(), key = { it.id }) { q ->
                val (bg, fg) = when (q.status) {
                    "requested" -> Color(0xFFFEF3C7) to Color(0xFF92400E); "quoted" -> Color(0xFFDBEAFE) to Color(0xFF1D4ED8)
                    "accepted" -> Color(0xFFDCFCE7) to Color(0xFF166534); "used" -> Color(0xFFE0E7FF) to Color(0xFF4338CA); else -> Color(0xFFE5E7EB) to Color(0xFF4B5563)
                }
                Surface(shape = RoundedCornerShape(16.dp), color = SurfaceLight, border = BorderStroke(1.dp, OutlineSoft), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${q.productName}${q.variantLabel?.let { " - $it" } ?: ""} × ${q.quantity}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Surface(shape = RoundedCornerShape(8.dp), color = bg) { Text(q.statusLabel, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
                        }
                        Text(q.vendorName, fontSize = 12.sp, color = TextSecondary)
                        q.note?.let { Text("✏️ $it", fontSize = 13.sp) }
                        if (q.photoPath != null) Text("📷 Photo sent", fontSize = 12.sp, color = TextSecondary)
                        if (q.status == "quoted" && q.quotedUnitPrice != null) Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFEFF6FF), border = BorderStroke(1.dp, Color(0xFF93C5FD))) {
                            Column(Modifier.padding(10.dp).fillMaxWidth()) {
                                Text("Price: ₹${"%.0f".format(q.quotedUnitPrice)} per piece · total ₹${"%.0f".format(q.total)}", fontWeight = FontWeight.Bold, color = Color(0xFF1D4ED8), fontSize = 14.sp)
                                q.vendorNote?.let { Text("“$it”", fontSize = 12.sp) }
                                Text("Accept before it expires", fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                        if (q.status == "accepted" && q.total != null) Text("Accepted: ₹${"%.0f".format(q.total)} total. Add it to your cart and order before it expires.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (q.status == "declined" && q.vendorNote != null) Text("“${q.vendorNote}”", fontSize = 12.sp, color = TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (q.status == "quoted") {
                                Button(onClick = { run(q, { StoresRepository.answerQuote(q.id, true) }) { if (toCart(q)) onOpenCart() } }, enabled = busyId != q.id) { Text("Accept & add to cart") }
                                OutlinedButton(onClick = { run(q, { StoresRepository.answerQuote(q.id, false) }) }, enabled = busyId != q.id) { Text("Decline") }
                            }
                            if (q.status == "accepted") Button(onClick = { if (toCart(q)) onOpenCart() }) { Text("Add to cart") }
                            if (q.status in setOf("requested", "quoted", "accepted")) OutlinedButton(onClick = { run(q, { StoresRepository.cancelQuote(q.id) }) }, enabled = busyId != q.id) { Text("Cancel") }
                            if (q.status == "used" && q.orderId != null) OutlinedButton(onClick = { onOpenOrder(q.orderId) }) { Text("View order") }
                        }
                    }
                }
            }
        }
    }
    conflict?.let { c ->
        AlertDialog(
            onDismissRequest = { conflict = null }, title = { Text("Replace your cart?") },
            text = { Text("Your cart already has the most shops you can order from at once (3). Clear it and add this quoted item?") },
            confirmButton = { TextButton(onClick = { repository.forceClearHotelCartAndAdd(c.pendingItem); conflict = null; onOpenCart() }) { Text("Clear & Add") } },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text("Cancel") } }
        )
    }
}
