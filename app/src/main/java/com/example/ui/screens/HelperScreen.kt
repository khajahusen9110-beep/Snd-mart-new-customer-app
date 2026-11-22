package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomerAddress
import com.example.data.model.HelperBooking
import com.example.data.model.HelperCategory
import com.example.data.model.HelperConfig
import com.example.data.model.HelperRuleItem
import com.example.data.repository.HelperRepository
import com.example.data.repository.SndmartRepository
import com.example.ui.components.AddressPickerDialog
import com.example.ui.components.map.GeoLatLng
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.OutlineSoft
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import com.example.util.RazorpayPaymentManager
import com.example.util.RazorpayPaymentResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Quick Helper: book a nearby helper for 30 / 60 / 90 minutes, follow the booking and rate the helper. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelperScreen(repository: SndmartRepository, cityId: String?, userPhone: String?, onBack: () -> Unit) {
    var cfg by remember { mutableStateOf<HelperConfig?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var openId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val sm = repository.sessionManager
    val payScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // the booking whose advance payment is open in Razorpay right now (id, number, Razorpay order id)
    var paying by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var payBusy by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        RazorpayPaymentManager.paymentResult.collectLatest { result ->
            val cur = paying ?: return@collectLatest
            paying = null
            when (result) {
                is RazorpayPaymentResult.Error -> {
                    // the payment may still have gone through in the UPI app: the server checks with Razorpay
                    HelperRepository.syncPayment(cur.first)
                    snackbar.showSnackbar("Payment not completed. You can pay from My bookings.")
                }
                is RazorpayPaymentResult.Success -> {
                    payBusy = true
                    val pid = result.paymentData?.paymentId ?: result.razorpayPaymentId ?: ""
                    val oid = result.paymentData?.orderId?.takeIf { it.isNotBlank() } ?: cur.third
                    val sig = result.paymentData?.signature ?: result.paymentData?.data?.optString("razorpay_signature").orEmpty()
                    val ok = pid.isNotBlank() && HelperRepository.verifyPayment(cur.first, oid, pid, sig).isSuccess
                    if (!ok) HelperRepository.syncPayment(cur.first)
                    payBusy = false
                    snackbar.showSnackbar(if (ok) "Payment received. We are finding a helper." else "We are confirming your payment. Please check My bookings in a minute.")
                }
            }
            refreshTick++
        }
    }

    // Opens the in-app payment for a booking waiting for its advance
    val startPay: (String, String) -> Unit = { bookingId, number ->
        payBusy = true
        payScope.launch {
            HelperRepository.createPayment(bookingId).onSuccess { o ->
                paying = Triple(bookingId, number, o.razorpayOrderId)
                val activity = context as? android.app.Activity
                val res = if (activity == null) Result.failure(Exception("Cannot open the payment screen"))
                else RazorpayPaymentManager.startUpiCheckout(
                    activity = activity, keyId = o.keyId, amountInPaise = o.amountPaise, currency = o.currency,
                    razorpayOrderId = o.razorpayOrderId, orderNumber = number, userPhone = userPhone, userEmail = sm.userEmail.value
                )
                if (res.isFailure) { paying = null; snackbar.showSnackbar(res.exceptionOrNull()?.message ?: "Could not open the payment screen") }
            }.onFailure { snackbar.showSnackbar(it.message ?: "Could not start payment. You can pay from My bookings.") }
            payBusy = false
        }
    }
    LaunchedEffect(cityId) { if (cityId != null) cfg = HelperRepository.getConfig(cityId) }
    LaunchedEffect(cfg) { if (cfg != null && cfg?.enabled != true) onBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Column { Text("Quick Helper", fontWeight = FontWeight.Bold); Text("Pay per 30 minutes", fontSize = 12.sp, color = TextSecondary) } },
                navigationIcon = { IconButton(onClick = { if (openId != null) openId = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val c = cfg
            when {
                c == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                openId != null -> HelperDetail(openId!!, refreshTick, payBusy, startPay)
                else -> {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Book a helper") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("My bookings") })
                    }
                    if (tab == 0) HelperBookForm(repository, c, cityId.orEmpty(), userPhone) { b ->
                        openId = b.id
                        if (b.status == "pending_payment") startPay(b.id, b.bookingNumber)
                    }
                    else HelperList { openId = it }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HelperBookForm(repository: SndmartRepository, cfg: HelperConfig, cityId: String, userPhone: String?, onBooked: (HelperBooking) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sm = repository.sessionManager
    val userId = sm.getUserId().orEmpty()
    var cat by remember { mutableStateOf<HelperCategory?>(null) }
    var minutes by remember { mutableIntStateOf(30) }
    var desc by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf(listOf<String>()) }
    var addresses by remember { mutableStateOf(listOf<CustomerAddress>()) }
    var addressId by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var later by remember { mutableStateOf(false) }
    var whenMillis by remember { mutableStateOf<Long?>(null) }
    var pay by remember { mutableStateOf(if (cfg.paymentMode == "cash") "cash" else "upi") }
    var ack by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun loadAddresses(select: String? = null) {
        if (userId.isBlank()) return
        val list = repository.getAddresses(userId).getOrNull().orEmpty().filter { !it.id.isNullOrBlank() }
        addresses = list
        addressId = select ?: addressId ?: (list.firstOrNull { it.isDefault } ?: list.firstOrNull())?.id
    }
    LaunchedEffect(Unit) { loadAddresses() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            val bytes = shrinkPhoto(context, uri)
            if (bytes == null) error = "Could not read this photo"
            else HelperRepository.uploadPhoto(userId, bytes).onSuccess { photos = photos + it }.onFailure { error = it.message ?: "Photo upload failed" }
            busy = false
        }
    }

    if (showAdd) {
        val city = sm.selectedCity.collectAsState().value
        AddressPickerDialog(
            repository = repository, userId = userId, defaultPhone = userPhone.orEmpty(),
            cityCenter = city?.centerLat?.let { lat -> city.centerLng?.let { lng -> GeoLatLng(lat, lng) } },
            onDismiss = { showAdd = false },
            onSaved = { saved -> showAdd = false; scope.launch { loadAddresses(saved.id) } }
        )
    }

    val c = cat
    // Step 1: browse the services
    if (c == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cfg.categories.isEmpty()) Text("No services yet. Please check again soon.", color = TextSecondary)
            else Text("Choose a service", fontWeight = FontWeight.Bold)
            cfg.categories.forEach { k ->
                Card(Modifier.fillMaxWidth().clickable { cat = k; minutes = k.durations.firstOrNull() ?: 30; photos = emptyList(); ack = false }, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (k.imageUrl != null) AsyncImage(model = k.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(k.name, fontWeight = FontWeight.Bold)
                            if (k.description.isNotBlank()) Text(k.description, fontSize = 12.sp, color = TextSecondary, maxLines = 2)
                            Text("From ₹${"%.0f".format(k.fromPrice)} · ${k.durations.joinToString(" / ")} min", fontSize = 12.sp)
                        }
                    }
                }
            }
            if (cfg.bannedNote.isNotBlank()) Text(cfg.bannedNote, fontSize = 12.sp, color = TextSecondary)
        }
        return
    }

    val price = c.priceFor(minutes)
    val maxPhotos = if (c.photosAllowed) cfg.maxPhotos else 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = { cat = null }) { Text("← All services") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c.imageUrl != null) AsyncImage(model = c.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
            Column { Text(c.name, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp); if (c.description.isNotBlank()) Text(c.description, fontSize = 13.sp, color = TextSecondary) }
        }
        RuleBlock("The helper does", c.dos, true)
        RuleBlock("The helper does not", c.donts, false)

        Text("How long do you need a helper?", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            c.durations.forEach { d -> FilterChip(selected = minutes == d, onClick = { minutes = d }, label = { Text("$d min · ₹${"%.0f".format(c.priceFor(d))}") }) }
        }

        Text("Describe the task", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = desc, onValueChange = { if (it.length <= 600) desc = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            placeholder = { Text(c.taskPlaceholder.ifBlank { "Describe what you need, e.g. place, what to do, anything the helper should know" }) }
        )
        if (maxPhotos > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                photos.forEachIndexed { i, _ -> AssistChip(onClick = { photos = photos.filterIndexed { j, _ -> j != i } }, label = { Text("Photo ${i + 1}  ✕") }) }
                if (photos.size < maxPhotos) OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) { Text(if (busy) "Uploading…" else "Add photo") }
            }
            Text((if (c.photoRequired) "A photo is required. " else "Optional. ") + "Only you, your helper and Sndmart can see these.", fontSize = 12.sp, color = TextSecondary)
        }

        Text("Where?", fontWeight = FontWeight.Bold)
        addresses.forEach { a ->
            val id = a.id.orEmpty(); val picked = id == addressId
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(if (picked) 2.dp else 1.dp, if (picked) NaturalPrimary else OutlineSoft, RoundedCornerShape(12.dp))
                    .clickable { addressId = id }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = picked, onClick = { addressId = id })
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(a.label.ifBlank { "Address" }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(a.addressLine, fontSize = 12.sp, color = TextSecondary, maxLines = 2)
                }
            }
        }
        OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Add new address") }

        Text("When?", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !later, onClick = { later = false }, label = { Text("As soon as possible") })
            FilterChip(selected = later, onClick = { later = true }, label = { Text("Choose time") })
        }
        if (later) {
            val ctx = LocalContext.current
            OutlinedButton(onClick = { pickDateTime(ctx, cfg.minLeadMinutes, cfg.maxDaysAhead) { whenMillis = it } }, modifier = Modifier.fillMaxWidth()) {
                Text(whenMillis?.let { SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(it)) } ?: "Pick date and time")
            }
        }

        Text("How will you pay?", fontWeight = FontWeight.Bold)
        if (cfg.paymentMode == "both") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = pay == "upi", onClick = { pay = "upi" }, label = { Text("UPI") })
                FilterChip(selected = pay == "cash", onClick = { pay = "cash" }, label = { Text("Cash") })
            }
        } else Text(if (cfg.paymentMode == "upi") "UPI only" else "Cash only")
        val online = cfg.paymentMode == "upi" || (cfg.paymentMode == "both" && pay == "upi")
        Text(
            if (online) "You pay now, inside the app. If no helper accepts, or you cancel before the work starts, you get your money back" +
                (if (cfg.refundPercentAfterAccept < 100) " (after a helper accepts, ${cfg.refundPercentAfterAccept}% is refunded)." else ".")
            else "Pay your helper in cash after the work is done.",
            fontSize = 12.sp, color = TextSecondary
        )

        if (cfg.termsText.isNotBlank()) Text(cfg.termsText, fontSize = 12.sp, color = TextSecondary)
        if (cfg.bannedNote.isNotBlank()) Text(cfg.bannedNote, fontSize = 12.sp, color = TextSecondary)
        if (cfg.requireRulesAck) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ack, onCheckedChange = { ack = it })
            Text("I have read what the helper does and does not do.", fontSize = 13.sp)
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Button(
            onClick = {
                val aid = addressId
                when {
                    aid == null -> error = "Choose your address"
                    desc.trim().length < 5 -> error = "Please describe the task"
                    later && whenMillis == null -> error = "Choose the time"
                    c.photoRequired && photos.isEmpty() -> error = "Please add a photo"
                    cfg.requireRulesAck && !ack -> error = "Please accept what the helper does and does not do"
                    else -> {
                        busy = true; error = null
                        scope.launch {
                            val isoTime = if (later) whenMillis?.let { iso(it) } else null
                            val method = if (cfg.paymentMode == "both") pay else cfg.paymentMode
                            HelperRepository.create(cityId, aid, c.id, desc.trim(), photos, minutes, isoTime, method, ack)
                                .onSuccess { onBooked(it) }.onFailure { error = it.message ?: "Could not book" }
                            busy = false
                        }
                    }
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)
        ) { Text(if (busy) "Please wait…" else (if (cfg.paymentMode == "upi" || (cfg.paymentMode == "both" && pay == "upi")) "Pay & book" else "Book helper") + " · ₹${"%.0f".format(price)}", fontWeight = FontWeight.ExtraBold) }
        if (cfg.supportPhone.isNotBlank()) Text("Need help? ${cfg.supportPhone}", fontSize = 12.sp, color = TextSecondary)
    }
}

@Composable
private fun RuleBlock(title: String, items: List<HelperRuleItem>, good: Boolean) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text((if (good) "✓ " else "✕ ") + title, fontWeight = FontWeight.Bold, color = if (good) Color(0xFF047857) else Color(0xFFBE123C))
        items.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r.imageUrl != null) AsyncImage(model = r.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                Text(r.text, fontSize = 14.sp)
            }
        }
    }
}

private fun iso(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

private fun pickDateTime(context: android.content.Context, minLeadMinutes: Int, maxDays: Int, onPicked: (Long) -> Unit) {
    val now = java.util.Calendar.getInstance()
    android.app.DatePickerDialog(context, { _, y, m, d ->
        android.app.TimePickerDialog(context, { _, h, min ->
            val c = java.util.Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(java.util.Calendar.MILLISECOND, 0) }
            onPicked(c.timeInMillis)
        }, now.get(java.util.Calendar.HOUR_OF_DAY) + 1, 0, false).show()
    }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH)).apply {
        datePicker.minDate = System.currentTimeMillis()
        datePicker.maxDate = System.currentTimeMillis() + maxDays * 24L * 3600 * 1000
    }.show()
}

@Composable
private fun HelperList(onOpen: (String) -> Unit) {
    var list by remember { mutableStateOf<List<HelperBooking>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { HelperRepository.mine().onSuccess { list = it }.onFailure { error = it.message } }
    val l = list
    when {
        error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        l == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        l.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No bookings yet", color = TextSecondary) }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            l.forEach { b ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(b.id) }, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row { Text(b.taskType, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(b.statusLabel, fontSize = 12.sp, color = TextSecondary) }
                        Text("${b.durationMinutes} min · ₹${"%.0f".format(b.priceTotal)}", fontSize = 13.sp, color = TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun HelperDetail(id: String, refreshTick: Int, payBusy: Boolean, onPay: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var b by remember { mutableStateOf<HelperBooking?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var stars by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmCancel by remember { mutableStateOf(false) }

    // live status while the booking is open
    LaunchedEffect(id, tick, refreshTick) {
        while (true) {
            HelperRepository.get(id).onSuccess { b = it; error = null }.onFailure { error = it.message }
            // a payment the app never reported, or a refund still to be sent: the server checks, then we read again
            val cur = b
            if (cur != null && ((cur.status == "pending_payment" && cur.paymentStatus == "unpaid") || cur.paymentStatus == "refund_due")) {
                HelperRepository.syncPayment(id)
                HelperRepository.get(id).onSuccess { b = it }
            }
            if (b?.isActive == false) break
            delay(8000)
        }
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val booking = b
    if (booking == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error) else CircularProgressIndicator() }; return }

    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false }, title = { Text("Cancel this booking?") },
        confirmButton = { TextButton(onClick = { confirmCancel = false; busy = true; scope.launch { HelperRepository.cancel(id).onFailure { error = it.message }; busy = false; tick++ } }) { Text("Yes, cancel") } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep it") } }
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text(booking.bookingNumber, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(booking.statusLabel, fontWeight = FontWeight.Bold) }
                Text("${booking.taskType} · ${booking.durationMinutes} min · ₹${"%.0f".format(booking.priceTotal)} · ${if (booking.paymentStatus == "paid") "paid online" else if (booking.paymentMethod == "upi") "UPI" else "pay cash after the work"}")
                Text(booking.taskDescription, fontSize = 14.sp)
                Text(booking.addressLine, fontSize = 12.sp, color = TextSecondary)
                if (booking.photoPaths.isNotEmpty()) Text("${booking.photoPaths.size} photo(s) shared with your helper", fontSize = 12.sp, color = TextSecondary)
            }
        }
        if (booking.status == "pending_payment") Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pay ₹${"%.0f".format(booking.priceTotal)} to confirm your booking", fontWeight = FontWeight.Bold)
                Text("Your request goes to helpers only after the payment. Pay within the time, or the booking lapses.", fontSize = 12.sp, color = TextSecondary)
                Button(onClick = { onPay(booking.id, booking.bookingNumber) }, enabled = !payBusy, modifier = Modifier.fillMaxWidth()) { Text(if (payBusy) "Please wait…" else "Pay ₹${"%.0f".format(booking.priceTotal)}") }
            }
        }
        if (booking.paymentStatus == "refund_due" || booking.paymentStatus == "refunded") Text(
            if (booking.paymentStatus == "refunded") "Refund of ₹${"%.0f".format(booking.refundAmount)} sent to your account." else "Your refund of ₹${"%.0f".format(booking.refundAmount)} is on its way.",
            fontWeight = FontWeight.SemiBold
        )
        if (booking.status == "requested") Text("We are finding a helper near you. This can take a few minutes.", color = TextSecondary)
        booking.helper?.let { h ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Your helper", fontSize = 12.sp, color = TextSecondary)
                    Text(h.name + (if (h.totalRatings > 0) "  ★ ${"%.1f".format(h.avgRating ?: 0.0)} (${h.totalRatings})" else ""), fontWeight = FontWeight.Bold)
                    val ctx = LocalContext.current
                    if (booking.isActive && !h.phone.isNullOrBlank()) OutlinedButton(onClick = {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, Uri.parse("tel:${h.phone}")))
                    }) { Text("Call helper") }
                }
            }
        }
        booking.startOtp?.let { CodeCard("Start code", it, "Tell this code to your helper when they arrive, to start the work.") }
        if (booking.status == "started") {
            val left = booking.endsAt?.let { com.example.util.parseIsoMillis(it) }?.let { ((it - now) / 1000).coerceAtLeast(0) }
            Text("Work in progress" + (left?.let { " · %d:%02d left".format(it / 60, it % 60) } ?: ""), fontWeight = FontWeight.Bold)
            booking.endOtp?.let { CodeCard("End code", it, "Tell this code to your helper when the work is finished.") }
        }
        if (booking.status == "completed") {
            Text(if (booking.paymentStatus == "paid") "Work completed. Paid online, nothing more to pay." else "Work completed. Please pay ₹${"%.0f".format(booking.priceTotal)} to your helper (cash).", fontWeight = FontWeight.SemiBold)
            if (booking.rating != null) Text("You rated ${booking.rating}/5" + (booking.review?.let { " · $it" } ?: ""), color = TextSecondary)
            else {
                Row { (1..5).forEach { n -> IconButton(onClick = { stars = n }) { Icon(Icons.Filled.Star, "$n star", tint = if (n <= stars) Color(0xFFFFB300) else Color(0xFFCFCFCF)) } } }
                OutlinedTextField(value = review, onValueChange = { if (it.length <= 300) review = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Say something about your helper (optional)") })
                Button(onClick = {
                    if (stars == 0) error = "Choose a star rating" else { busy = true; scope.launch { HelperRepository.rate(id, stars, review).onFailure { error = it.message }; busy = false; tick++ } }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Submit rating") }
            }
        }
        if (booking.status == "cancelled" || booking.status == "expired") Text(booking.cancelReason ?: booking.statusLabel, color = TextSecondary)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (booking.status in setOf("pending_payment", "requested", "accepted", "on_the_way")) OutlinedButton(onClick = { confirmCancel = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel booking") }
    }
}

@Composable
private fun CodeCard(title: String, code: String, hint: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF1EFFA))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(code, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            Text(hint, fontSize = 12.sp, color = TextSecondary)
        }
    }
}
