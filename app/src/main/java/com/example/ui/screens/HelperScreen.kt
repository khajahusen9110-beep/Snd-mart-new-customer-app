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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomerAddress
import com.example.data.model.HelperBooking
import com.example.data.model.HelperConfig
import com.example.data.repository.HelperRepository
import com.example.data.repository.SndmartRepository
import com.example.ui.components.AddressPickerDialog
import com.example.ui.components.map.GeoLatLng
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.OutlineSoft
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
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
    LaunchedEffect(cityId) { if (cityId != null) cfg = HelperRepository.getConfig(cityId) }
    LaunchedEffect(cfg) { if (cfg != null && cfg?.enabled != true) onBack() }

    Scaffold(
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
                openId != null -> HelperDetail(openId!!)
                else -> {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Book a helper") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("My bookings") })
                    }
                    if (tab == 0) HelperBookForm(repository, c, cityId.orEmpty(), userPhone) { openId = it }
                    else HelperList { openId = it }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HelperBookForm(repository: SndmartRepository, cfg: HelperConfig, cityId: String, userPhone: String?, onBooked: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sm = repository.sessionManager
    val userId = sm.getUserId().orEmpty()
    var minutes by remember { mutableIntStateOf(cfg.durations.firstOrNull() ?: 30) }
    var taskType by remember { mutableStateOf(cfg.taskTypes.firstOrNull().orEmpty()) }
    var desc by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf(listOf<String>()) }
    var addresses by remember { mutableStateOf(listOf<CustomerAddress>()) }
    var addressId by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var later by remember { mutableStateOf(false) }
    var whenMillis by remember { mutableStateOf<Long?>(null) }
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

    val price = cfg.priceFor(minutes)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("How long do you need a helper?", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cfg.durations.forEach { d ->
                FilterChip(selected = minutes == d, onClick = { minutes = d }, label = { Text("$d min · ₹${"%.0f".format(cfg.priceFor(d))}") })
            }
        }

        Text("What do you need help with?", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cfg.taskTypes.forEach { t -> FilterChip(selected = taskType == t, onClick = { taskType = t }, label = { Text(t) }) }
        }
        OutlinedTextField(
            value = desc, onValueChange = { if (it.length <= 600) desc = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("Describe the task") }, placeholder = { Text("e.g. Stand in the queue at the bank and keep my token") }
        )
        if (cfg.maxPhotos > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                photos.forEachIndexed { i, _ ->
                    AssistChip(onClick = { photos = photos.filterIndexed { j, _ -> j != i } }, label = { Text("Photo ${i + 1}  ✕") })
                }
                if (photos.size < cfg.maxPhotos) OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) { Text(if (busy) "Uploading…" else "Add photo") }
            }
            Text("Optional. Only you, your helper and Sndmart can see these.", fontSize = 12.sp, color = TextSecondary)
        }
        if (cfg.bannedNote.isNotBlank()) Text(cfg.bannedNote, fontSize = 12.sp, color = TextSecondary)

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
            OutlinedButton(onClick = { pickDateTime(ctx) { whenMillis = it } }, modifier = Modifier.fillMaxWidth()) {
                Text(whenMillis?.let { SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(it)) } ?: "Pick date and time")
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Text("Pay your helper in cash or UPI after the work is done.", fontSize = 12.sp, color = TextSecondary)
        Button(
            onClick = {
                val aid = addressId
                when {
                    aid == null -> error = "Choose your address"
                    desc.trim().length < 5 -> error = "Please describe the task"
                    later && whenMillis == null -> error = "Choose the time"
                    else -> {
                        busy = true; error = null
                        scope.launch {
                            val iso = if (later) whenMillis?.let { iso(it) } else null
                            HelperRepository.create(cityId, aid, taskType, desc.trim(), photos, minutes, iso)
                                .onSuccess { onBooked(it.id) }.onFailure { error = it.message ?: "Could not book" }
                            busy = false
                        }
                    }
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)
        ) { Text(if (busy) "Please wait…" else "Book helper · ₹${"%.0f".format(price)}", fontWeight = FontWeight.ExtraBold) }
    }
}

private fun iso(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

private fun pickDateTime(context: android.content.Context, onPicked: (Long) -> Unit) {
    val now = java.util.Calendar.getInstance()
    android.app.DatePickerDialog(context, { _, y, m, d ->
        android.app.TimePickerDialog(context, { _, h, min ->
            val c = java.util.Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(java.util.Calendar.MILLISECOND, 0) }
            onPicked(c.timeInMillis)
        }, now.get(java.util.Calendar.HOUR_OF_DAY) + 1, 0, false).show()
    }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH)).apply {
        datePicker.minDate = System.currentTimeMillis()
        datePicker.maxDate = System.currentTimeMillis() + 7L * 24 * 3600 * 1000
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
private fun HelperDetail(id: String) {
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
    LaunchedEffect(id, tick) {
        while (true) {
            HelperRepository.get(id).onSuccess { b = it; error = null }.onFailure { error = it.message }
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
                Text("${booking.taskType} · ${booking.durationMinutes} min · ₹${"%.0f".format(booking.priceTotal)}")
                Text(booking.taskDescription, fontSize = 14.sp)
                Text(booking.addressLine, fontSize = 12.sp, color = TextSecondary)
                if (booking.photoPaths.isNotEmpty()) Text("${booking.photoPaths.size} photo(s) shared with your helper", fontSize = 12.sp, color = TextSecondary)
            }
        }
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
            Text("Work completed. Please pay ₹${"%.0f".format(booking.priceTotal)} to your helper (cash or UPI).", fontWeight = FontWeight.SemiBold)
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
        if (booking.status in setOf("requested", "accepted", "on_the_way")) OutlinedButton(onClick = { confirmCancel = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel booking") }
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
