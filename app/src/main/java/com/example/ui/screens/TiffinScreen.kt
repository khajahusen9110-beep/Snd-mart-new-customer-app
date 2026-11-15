package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.data.repository.SndmartRepository
import com.example.data.repository.TiffinRepository
import com.example.ui.components.PromoBannerCarousel
import com.example.ui.theme.*
import com.example.util.RazorpayPaymentManager
import com.example.util.RazorpayPaymentResult
import com.example.util.rupees
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Tiffin Pickup: a monthly plan where a delivery partner collects a home-made tiffin every chosen day and
 * hands it over at a school or office. ₹9 trial, then monthly UPI auto-pay. Same rules as the website page.
 */

private fun android.content.Context.findActivity(): android.app.Activity? {
    var c = this
    while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    return null
}

// Text of this screen: English or Kannada, chosen by the customer (chips at the top of the screen).
private val LocalTiffinLang = compositionLocalOf { "en" }

@Composable
private fun tx(key: String, vararg args: Any?): String {
    TiffinText.ensureLoaded(LocalContext.current)
    return TiffinText.t(LocalTiffinLang.current, key, *args)
}

@Composable
private fun dl(date: String): String = TiffinRepository.dayLabel(date, LocalTiffinLang.current)

private fun savedLang(context: android.content.Context): String {
    val saved = context.getSharedPreferences("tiffin_prefs", android.content.Context.MODE_PRIVATE).getString("lang", null)
    if (saved == "en" || saved == "kn") return saved
    return if (java.util.Locale.getDefault().language == "kn") "kn" else "en"
}

private val WEEKDAYS = (1..7).toList()
private val KINDS = listOf("school", "office", "other")

private fun statusKey(s: String) = when (s) {
    "pending_payment" -> "stPending"; "active" -> "stActive"; "paused" -> "stPaused"; "completed" -> "stCompleted"; else -> "stCancelled"
}

private fun tripKey(s: String) = when (s) {
    "scheduled" -> "trScheduled"; "picked_up" -> "trPicked"; "delivered" -> "trDelivered"; "missed" -> "trMissed"; else -> "trCancelled"
}

private fun kindWho(k: String) = when (k) { "school" -> "whoSchool"; "office" -> "whoOffice"; else -> "whoOther" }
private fun kindLabel(k: String) = when (k) { "school" -> "kindSchool"; "office" -> "kindOffice"; else -> "kindOther" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TiffinScreen(
    repository: SndmartRepository,
    cityId: String?,
    userPhone: String?,
    userEmail: String?,
    onOpenHotel: (vendorId: String) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    TiffinText.ensureLoaded(context)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var lang by remember { mutableStateOf(savedLang(context)) }
    val m = { key: String, args: Array<out Any?> -> TiffinText.t(lang, key, *args) }

    var loading by remember { mutableStateOf(true) }
    // Banners the City Admin or Super Admin set for the "Tiffin Pickup page" placement.
    var banners by remember(cityId) { mutableStateOf(repository.getCachedPromoBanners(cityId, "tiffin").orEmpty()) }
    LaunchedEffect(cityId) { if (cityId != null) banners = repository.getPromoBanners(cityId, "tiffin") }
    var error by remember { mutableStateOf<String?>(null) }
    var config by remember { mutableStateOf<TiffinConfig?>(null) }
    var plans by remember { mutableStateOf<List<TiffinPlan>>(emptyList()) }
    var addresses by remember { mutableStateOf<List<CustomerAddress>>(emptyList()) }
    var showForm by remember { mutableStateOf(false) }
    var busyKey by remember { mutableStateOf<String?>(null) }
    // The plan being paid for while the Razorpay screen is open.
    var payingPlanId by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        error = null
        val cfg = cityId?.let { TiffinRepository.getConfig(it) }
        config = cfg
        if (cfg == null || !cfg.enabled) { loading = false; return }
        var mine = TiffinRepository.myPlans()
        // A plan still "waiting for payment" with auto-pay set up may have been paid after the payment screen
        // closed (slow UPI): ask Razorpay and fix it before showing the plan.
        val unpaid = mine.getOrNull().orEmpty().filter { it.status == "pending_payment" && it.autopayStatus == "created" }
        if (unpaid.isNotEmpty()) {
            unpaid.forEach { TiffinRepository.syncAutopay(it.id) }
            mine = TiffinRepository.myPlans()
        }
        mine.onSuccess { plans = it }.onFailure { error = it.message ?: "Could not load your tiffin plans" }
        val uid = repository.sessionManager.getUserId()
        if (!uid.isNullOrBlank()) addresses = repository.getAddresses(uid).getOrNull().orEmpty().filter { !it.id.isNullOrBlank() }
        if (plans.none { it.status != "cancelled" && it.status != "completed" }) showForm = true
        loading = false
    }
    LaunchedEffect(cityId) { load() }

    // Result of the auto-pay screen: confirm with the server so the plan starts right away.
    LaunchedEffect(Unit) {
        RazorpayPaymentManager.paymentResult.collectLatest { result ->
            val planId = payingPlanId ?: return@collectLatest
            payingPlanId = null
            when (result) {
                is RazorpayPaymentResult.Success -> {
                    val data = result.paymentData?.data
                    val paymentId = result.paymentData?.paymentId ?: result.razorpayPaymentId ?: ""
                    val rzpSub = data?.optString("razorpay_subscription_id").orEmpty()
                    val signature = result.paymentData?.signature ?: data?.optString("razorpay_signature").orEmpty()
                    busyKey = "pay"
                    val r = TiffinRepository.verifyAutopay(planId, paymentId, rzpSub, signature)
                    busyKey = null
                    snackbar.showSnackbar(if (r.isSuccess) m("payDone", emptyArray()) else (r.exceptionOrNull()?.message ?: "Payment received, confirming with your bank…"))
                    load()
                }
                is RazorpayPaymentResult.Error -> {
                    snackbar.showSnackbar(m("payNotDone", emptyArray()))
                    load()
                }
            }
        }
    }

    // Starts auto-pay for a plan that is waiting for payment and opens the payment screen.
    fun pay(plan: TiffinPlan) {
        scope.launch {
            busyKey = "pay:${plan.id}"
            val r = TiffinRepository.startAutopay(plan.id)
            busyKey = null
            val co = r.getOrNull()
            val activity = context.findActivity()
            if (co == null || activity == null) {
                snackbar.showSnackbar(r.exceptionOrNull()?.message ?: "Unable to open payment screen. Please try again.")
                return@launch
            }
            payingPlanId = plan.id
            val open = RazorpayPaymentManager.startSubscriptionCheckout(
                activity = activity, keyId = co.keyId, subscriptionId = co.razorpaySubscriptionId,
                description = if (co.isTrial) "Tiffin Pickup trial, then monthly auto-pay" else "Tiffin Pickup monthly plan",
                userPhone = userPhone, userEmail = userEmail
            )
            if (open.isFailure) {
                payingPlanId = null
                snackbar.showSnackbar("Could not open the payment screen. Please try again.")
            }
        }
    }

    fun run(key: String, done: String?, block: suspend () -> Result<Unit>) {
        scope.launch {
            busyKey = key
            val r = block()
            busyKey = null
            if (r.isFailure) snackbar.showSnackbar(r.exceptionOrNull()?.message ?: "Something went wrong")
            else if (done != null) snackbar.showSnackbar(done)
            load()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(TiffinText.t(lang, "title")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag("tiffin_screen"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          CompositionLocalProvider(LocalTiffinLang provides lang) {
            if (banners.isNotEmpty()) PromoBannerCarousel(
                banners = banners,
                onOpen = { b ->
                    val v = b.linkValue.orEmpty()
                    when {
                        b.linkType == "hotel" && v.isNotBlank() -> onOpenHotel(v)
                        b.linkType == "url" && v.startsWith("https://") -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v)))
                    }
                }
            )
            LangChips(lang) { l ->
                context.getSharedPreferences("tiffin_prefs", android.content.Context.MODE_PRIVATE).edit().putString("lang", l).apply()
                lang = l
            }
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = NaturalPrimary) }
                config == null || config?.enabled != true -> Card(shape = AppCardShape, colors = appCardColors(), border = AppCardBorder) {
                    Text(tx("notAvailable", "—"), modifier = Modifier.padding(16.dp), color = TextSecondary)
                }
                else -> {
                    val cfg = config!!
                    Card(shape = AppCardShape, colors = CardDefaults.cardColors(containerColor = NaturalPrimaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tx("heroTitle"), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = NaturalOnPrimaryContainer)
                            Text(tx("heroSub"), fontSize = 13.sp, color = NaturalOnPrimaryContainer)
                            if (cfg.trialEnabled) Text(tx("heroTrial", cfg.trialDays, rupees(cfg.trialPrice)), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = NaturalPrimary)
                        }
                    }
                    error?.let { Text(it, color = NaturalBadgeRed) }
                    GuideCard(cfg)

                    plans.forEach { plan ->
                        PlanCard(
                            plan = plan, cutoffHour = cfg.skipCutoffHour, busyKey = busyKey,
                            onPay = { pay(plan) },
                            onCancelRequest = { run("cancel:${plan.id}", m("toastCancelled", emptyArray())) { TiffinRepository.cancelRequest(plan.id) } },
                            onPause = { run("pause:${plan.id}", if (plan.status == "active") m("paused", emptyArray()) else m("resumed", emptyArray())) { TiffinRepository.setPaused(plan.id, plan.status == "active") } },
                            onSkip = { d, skip -> run("skip:$d", if (skip) m("daySkipped", arrayOf(TiffinRepository.dayLabel(d, lang))) else m("dayBack", arrayOf(TiffinRepository.dayLabel(d, lang)))) { TiffinRepository.skipDay(plan.id, d, skip) } },
                            onStopAutopay = { run("stop:${plan.id}", m("toastStopped", emptyArray())) { TiffinRepository.stopAutopay(plan.id) } },
                            onOpenPhoto = { path ->
                                scope.launch {
                                    val url = TiffinRepository.photoUrl(path)
                                    if (url != null) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) else snackbar.showSnackbar(m("photoFail", emptyArray()))
                                }
                            }
                        )
                    }

                    if (!showForm) {
                        OutlinedButton(onClick = { showForm = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("+ " + tx("newPlan")) }
                    } else if (addresses.size < 2) {
                        Card(shape = AppCardShape, colors = appCardColors(), border = AppCardBorder) {
                            Text(tx("saveAddressesFirst"), modifier = Modifier.padding(16.dp), color = TextSecondary)
                        }
                    } else {
                        NewPlanForm(
                            config = cfg, addresses = addresses, canClose = plans.isNotEmpty(), onClose = { showForm = false },
                            onCreated = { id ->
                                scope.launch {
                                    load()
                                    plans.firstOrNull { it.id == id }?.let { pay(it) }
                                }
                            },
                            snackbar = snackbar
                        )
                    }
                    PriceTable(cfg)
                }
            }
          }
        }
    }
}

@Composable
private fun LangChips(lang: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = lang == "en", onClick = { onPick("en") }, label = { Text("English") })
        FilterChip(selected = lang == "kn", onClick = { onPick("kn") }, label = { Text("ಕನ್ನಡ") })
    }
}

/** "How it works": six short steps in the customer's language; the first sentence of each is its heading. */
@Composable
private fun GuideCard(cfg: TiffinConfig) {
    val steps = listOf("g1") + (if (cfg.trialEnabled) listOf("g2") else emptyList()) + listOf("g3", "g4", "g5", "g6")
    Card(shape = AppCardShape, colors = appCardColors(), border = AppCardBorder, elevation = appCardElevation()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tx("guideTitle"), fontWeight = FontWeight.Bold)
            steps.forEachIndexed { i, k ->
                val text = if (k == "g2") tx(k, cfg.trialDays, rupees(cfg.trialPrice)) else tx(k)
                val cut = text.indexOf('.')
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append("${i + 1}. ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)); append(text.substring(0, cut + 1)); pop()
                        append(text.substring(cut + 1))
                    },
                    fontSize = 13.sp
                )
            }
        }
    }
}

/** Terms & Conditions, opened on demand above the "I agree" checkbox. */
@Composable
private fun TermsCard(cfg: TiffinConfig) {
    var open by remember { mutableStateOf(false) }
    val maxKm = cfg.slabs.lastOrNull()?.upToKm?.let { if (it % 1.0 == 0.0) it.toInt() else it } ?: 10
    val cutoff = TiffinRepository.timeLabel("%02d:00".format(cfg.skipCutoffHour))
    val keys = (1..13).map { "t$it" }.filter { it != "t7" || cfg.trialEnabled }
    Surface(shape = RoundedCornerShape(12.dp), color = SurfaceVariantLight, modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((if (open) "▾ " else "▸ ") + tx("termsOpen"), fontWeight = FontWeight.Bold, color = NaturalPrimary, fontSize = 14.sp)
            if (open) {
                Text(tx("termsTitle"), fontWeight = FontWeight.Bold)
                keys.forEachIndexed { i, k ->
                    val text = when (k) { "t5" -> tx(k, cutoff); "t11" -> tx(k, maxKm); else -> tx(k) }
                    val cut = text.indexOf('.')
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append("${i + 1}. ")
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)); append(text.substring(0, cut + 1)); pop()
                            append(text.substring(cut + 1))
                        },
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

@Composable
private fun PlanCard(
    plan: TiffinPlan,
    cutoffHour: Int,
    busyKey: String?,
    onPay: () -> Unit,
    onCancelRequest: () -> Unit,
    onPause: () -> Unit,
    onSkip: (String, Boolean) -> Unit,
    onStopAutopay: () -> Unit,
    onOpenPhoto: (String) -> Unit
) {
    var confirmStop by remember { mutableStateOf(false) }
    val today = TiffinRepository.indiaToday()
    val todays = plan.trips.filter { it.tripDate == today }
    val upcoming = remember(plan) { if (plan.running) TiffinRepository.upcomingPlanDays(plan, 7) else emptyList() }
    val busy = busyKey != null

    Card(shape = AppCardShape, colors = appCardColors(), border = AppCardBorder, elevation = appCardElevation()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plan.recipientName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    plan.recipientDetail?.let { Text(it, color = TextMuted, fontSize = 12.sp) }
                }
                val ok = plan.status == "active"
                Pill(tx(statusKey(plan.status)), if (ok) PastelMint else if (plan.status == "pending_payment") Color(0xFFFFF3CD) else SurfaceVariantLight, if (ok) DarkGreenText else TextSecondary)
            }
            Text(plan.pickupAddress, fontSize = 12.sp, color = TextSecondary)
            Text("${plan.dropLabel?.let { "$it — " } ?: ""}${plan.dropAddress}", fontSize = 12.sp, color = TextSecondary)
            Text(
                tx("pickupAt", TiffinRepository.timeLabel(plan.pickupTime), WEEKDAYS.filter { plan.days.contains(it) }.map { tx("wd$it") }.joinToString(", ")),
                fontSize = 12.sp, color = TextSecondary
            )
            if (plan.returnTrip) Text(tx("returnLine", TiffinRepository.timeLabel(plan.returnPickupTime)), fontSize = 12.sp, color = TextSecondary)

            if (plan.status == "pending_payment") {
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFFF8E1)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (plan.isTrial) tx("pendingTrial", rupees(plan.trialFee), plan.trialDays, dl(plan.startDate), rupees(plan.totalAmount))
                            else tx("pendingNoTrial", rupees(plan.totalAmount), dl(plan.startDate)),
                            fontSize = 13.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onPay, enabled = !busy, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary)) {
                                if (busyKey == "pay:${plan.id}") CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                else Text(if (plan.isTrial) tx("payTrialBtn", rupees(plan.trialFee)) else tx("btnPay", rupees(plan.totalAmount)))
                            }
                            TextButton(onClick = onCancelRequest, enabled = !busy) { Text(tx("cancelRequest")) }
                        }
                    }
                }
            }

            if (plan.running) {
                AutopayInfo(plan, busy, onAskStop = { confirmStop = true })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("${plan.tripsRemaining}", tx("daysLeft"))
                    Stat("${plan.tripsTotal}", tx("daysMonth"))
                    Stat(dl(plan.endDate), tx("useBefore"))
                }
                if (plan.partnerName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tx("partnerIs", plan.partnerName), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        plan.partnerPhone?.let { phone ->
                            val ctx = LocalContext.current
                            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }) { Text(tx("call")) }
                        }
                    }
                } else Text(tx("partnerSoon"), fontSize = 12.sp, color = TextMuted)

                if (todays.isNotEmpty()) {
                    Text(tx("todayHdr"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                    todays.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (t.leg == "drop") tx("tiffinWord") else tx("boxReturnWord")) + " · " + tx(tripKey(t.status)) +
                                    (if (t.status == "scheduled") " " + tx("forTime", TiffinRepository.timeLabel(t.scheduledTime)) else ""),
                                fontSize = 13.sp, modifier = Modifier.weight(1f)
                            )
                            t.photoPath?.let { p -> TextButton(onClick = { onOpenPhoto(p) }) { Text(tx("photo")) } }
                        }
                    }
                }

                if (plan.status == "active" && upcoming.isNotEmpty()) {
                    Text(tx("comingDays"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        upcoming.forEach { d ->
                            val skipped = plan.skips.contains(d)
                            FilterChip(
                                selected = skipped, enabled = !busy, onClick = { onSkip(d, !skipped) },
                                label = { Text(dl(d) + if (skipped) " · " + tx("skipped") else "", fontSize = 12.sp) }
                            )
                        }
                    }
                    Text(
                        tx("skipRule", TiffinRepository.timeLabel("%02d:00".format(cutoffHour))),
                        fontSize = 11.sp, color = TextMuted
                    )
                }
                OutlinedButton(onClick = onPause, enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text(if (plan.status == "active") tx("pause") else tx("resume")) }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(tx("stopTitle")) },
            text = { Text(tx("stopBody", dl(plan.endDate))) },
            confirmButton = { TextButton(onClick = { confirmStop = false; onStopAutopay() }) { Text(tx("stopYes"), color = NaturalBadgeRed) } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(tx("stopKeep")) } }
        )
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        Text(label, fontSize = 11.sp, color = TextMuted)
    }
}

@Composable
private fun AutopayInfo(plan: TiffinPlan, busy: Boolean, onAskStop: () -> Unit) {
    if (plan.autopayStatus == "none" && !plan.isTrial) return
    Surface(shape = RoundedCornerShape(12.dp), color = SurfaceVariantLight) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (plan.isTrial && plan.billingCyclesPaid == 0 && plan.trialEndDate != null)
                Text(tx("trialUntil", dl(plan.trialEndDate)), fontSize = 13.sp)
            if (plan.autopayLive && plan.nextChargeDate != null)
                Text(tx("nextPay", rupees(plan.totalAmount), dl(plan.nextChargeDate)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (plan.autopayStatus == "pending") Text(tx("payFailedRetry"), fontSize = 13.sp, color = NaturalBadgeRed)
            if (plan.autopayStatus == "halted") Text(tx("halted", dl(plan.endDate)), fontSize = 13.sp, color = NaturalBadgeRed)
            if (plan.autopayStatus == "cancelled") Text(tx("stopped", dl(plan.endDate)), fontSize = 13.sp)
            if (plan.autopayLive) OutlinedButton(onClick = onAskStop, enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text(tx("stopBtn")) }
        }
    }
}

@Composable
private fun PriceTable(config: TiffinConfig) {
    Card(shape = AppCardShape, colors = appCardColors(), border = AppCardBorder, elevation = appCardElevation()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tx("monthlyPrice"), fontWeight = FontWeight.Bold)
            Text(tx("priceNote", config.fullMonthTrips), fontSize = 12.sp, color = TextMuted)
            config.slabs.forEach { s ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tx("upToKm", s.upToKm.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }), fontSize = 13.sp)
                    Text("${rupees(s.price)} · " + tx("boxReturnPlus", rupees(s.returnPrice)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Pickup-time options every 15 minutes between two "HH:MM" bounds. */
private fun timeSlots(from: String, to: String): List<String> {
    fun mins(t: String) = t.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    val out = ArrayList<String>()
    var m = mins(from)
    // Round up to the next quarter hour.
    if (m % 15 != 0) m += 15 - m % 15
    while (m <= mins(to)) { out.add("%02d:%02d".format(m / 60, m % 60)); m += 15 }
    return out
}

@Composable
private fun <T> PickerField(label: String, valueText: String, options: List<T>, optionText: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = TextSecondary)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = AppFieldShape) {
                Text(valueText.ifBlank { "Choose" }, modifier = Modifier.weight(1f), maxLines = 1, color = TextPrimary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(optionText(o), maxLines = 2) }, onClick = { open = false; onPick(o) }) }
            }
        }
    }
}

@Composable
private fun NewPlanForm(
    config: TiffinConfig,
    addresses: List<CustomerAddress>,
    canClose: Boolean,
    onClose: () -> Unit,
    onCreated: (String) -> Unit,
    snackbar: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val tomorrow = remember { TiffinRepository.addDays(TiffinRepository.indiaToday(), 1) }
    var kind by remember { mutableStateOf("school") }
    var name by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pickupId by remember { mutableStateOf(addresses.firstOrNull { it.isDefault }?.id ?: addresses.first().id!!) }
    var dropId by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(listOf(1, 2, 3, 4, 5, 6)) }
    var pickupTime by remember { mutableStateOf("07:30") }
    var returnTrip by remember { mutableStateOf(false) }
    var returnTime by remember { mutableStateOf("16:00") }
    var startDate by remember { mutableStateOf(tomorrow) }
    var quote by remember { mutableStateOf<TiffinQuote?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var accepted by remember { mutableStateOf(false) }
    val langNow = LocalTiffinLang.current
    val who = tx(kindWho(kind))
    // Messages shown from inside click handlers are prepared here (they cannot call composable functions).
    val errName = tx("errName", who.lowercase())
    val errAddr = tx("errAddr")
    val errTerms = tx("errTerms")
    val hintPrice = tx("quoteHint")

    fun addrText(id: String) = addresses.firstOrNull { it.id == id }?.let { "${it.label} — ${it.addressLine}" } ?: ""

    // Live price from the server whenever the route, days or return choice change.
    LaunchedEffect(pickupId, dropId, days, returnTrip, startDate) {
        if (pickupId.isBlank() || dropId.isBlank() || days.isEmpty()) { quote = null; return@LaunchedEffect }
        quoting = true
        quote = TiffinRepository.getQuote(pickupId, dropId, days, returnTrip, startDate)
            .getOrElse { TiffinQuote(false, it.message, 0.0, 0, 0.0, 0.0, 0.0, false, 0, 0.0, 0, null) }
        quoting = false
    }

    Card(shape = AppCardShape, colors = appCardColors(), border = AppCardBorder, elevation = appCardElevation()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tx("planFormTitle"), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (canClose) TextButton(onClick = onClose) { Text(tx("close")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KINDS.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(tx(kindLabel(k))) }) }
            }
            OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text(who) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = AppFieldShape, colors = appFieldColors())
            OutlinedTextField(
                value = detail, onValueChange = { detail = it.take(120) },
                label = { Text(tx(when (kind) { "school" -> "detSchool"; "office" -> "detOffice"; else -> "detOther" })) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = AppFieldShape, colors = appFieldColors()
            )
            PickerField(tx("pickupFrom"), addrText(pickupId), addresses, { "${it.label} — ${it.addressLine}" }) { pickupId = it.id!! }
            PickerField(tx("dropAt", tx(when (kind) { "school" -> "dropWordSchool"; "office" -> "dropWordOffice"; else -> "dropWordOther" })), addrText(dropId), addresses.filter { it.id != pickupId }, { "${it.label} — ${it.addressLine}" }) { dropId = it.id!! }

            Text(tx("days"), fontSize = 12.sp, color = TextSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WEEKDAYS.forEach { n ->
                    FilterChip(selected = days.contains(n), onClick = { days = if (days.contains(n)) days - n else (days + n).sorted() }, label = { Text(tx("wd$n")) })
                }
            }
            PickerField(tx("pickupTimeHome"), TiffinRepository.timeLabel(pickupTime), timeSlots(config.earliestPickup, config.latestPickup), { TiffinRepository.timeLabel(it) }) { pickupTime = it }
            PickerField(tx("startDate"), dl(startDate), (1..14).map { TiffinRepository.addDays(TiffinRepository.indiaToday(), it) }, { TiffinRepository.dayLabel(it, langNow) }) { startDate = it }

            Row(Modifier.fillMaxWidth().clickable { returnTrip = !returnTrip }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = returnTrip, onCheckedChange = { returnTrip = it })
                Column {
                    Text(tx("returnTrip"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(tx("extraCharge"), fontSize = 11.sp, color = TextMuted)
                }
            }
            if (returnTrip) {
                val slots = timeSlots(pickupTime, config.latestReturnPickup).filter { it > pickupTime }
                PickerField(tx("returnAt"), TiffinRepository.timeLabel(returnTime), slots, { TiffinRepository.timeLabel(it) }) { returnTime = it }
            }
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(240) }, label = { Text(tx("handoverNote")) },
                minLines = 2, modifier = Modifier.fillMaxWidth(), shape = AppFieldShape, colors = appFieldColors()
            )

            Surface(shape = RoundedCornerShape(12.dp), color = SurfaceVariantLight) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val q = quote
                    when {
                        quoting -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = NaturalPrimary)
                        q == null -> Text(hintPrice, fontSize = 12.sp, color = TextMuted)
                        !q.ok -> Text(q.message ?: "This plan is not available.", fontSize = 13.sp, color = NaturalBadgeRed)
                        else -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(tx("quoteDrop", q.trips, q.distanceKm), fontSize = 13.sp)
                                Text(rupees(q.priceOneWay), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            if (returnTrip) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(tx("quoteReturn"), fontSize = 13.sp); Text(rupees(q.priceReturn), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (q.trialAvailable) tx("everyMonthAfter") else tx("everyMonth"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(rupees(q.total), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            }
                            if (q.trialAvailable) Surface(shape = RoundedCornerShape(10.dp), color = PastelMint) {
                                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(tx("trialLine", q.trialDays, q.trialTrips), fontSize = 12.sp, color = DarkGreenText, modifier = Modifier.weight(1f))
                                    Text(tx("payToday", rupees(q.trialPrice)), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = DarkGreenText)
                                }
                            }
                        }
                    }
                }
            }
            TermsCard(config)
            Row(Modifier.fillMaxWidth().clickable { accepted = !accepted }, verticalAlignment = Alignment.Top) {
                Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                Text(tx("accept"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            }
            formError?.let { Text(it, color = NaturalBadgeRed, fontSize = 13.sp) }

            // Plain values computed once, so the button and notes below carry no chained safe calls.
            val good: TiffinQuote? = quote?.takeIf { it.ok }
            val failMessage: String = quote?.message ?: hintPrice
            val btnLabel: String = if (good == null) tx("btnStart")
                else if (good.trialAvailable) tx("btnTrial", good.trialDays, rupees(good.trialPrice))
                else tx("btnPay", rupees(good.total))
            val footNote: String = if (good != null && good.trialAvailable && good.firstChargeDate != null)
                tx("trialNote", rupees(good.trialPrice), dl(good.firstChargeDate), rupees(good.total))
                else tx("payNote")
            val canSubmit: Boolean = good != null && !saving && !quoting && accepted
            Button(
                onClick = {
                    formError = null
                    if (name.isBlank()) { formError = errName; return@Button }
                    if (dropId.isBlank()) { formError = errAddr; return@Button }
                    if (good == null) { formError = failMessage; return@Button }
                    if (!accepted) { formError = errTerms; return@Button }
                    scope.launch {
                        saving = true
                        val r = TiffinRepository.createPlan(
                            TiffinPlanInput(pickupId, dropId, kind, name.trim(), detail.trim(), note.trim(), days, pickupTime, returnTrip, returnTime, startDate)
                        )
                        saving = false
                        r.onSuccess { onCreated(it) }.onFailure { formError = it.message ?: "Could not create the plan" }
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary)
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(btnLabel, fontWeight = FontWeight.Bold)
            }
            Text(footNote, fontSize = 11.sp, color = TextMuted)
        }
    }
}
