package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.HelperBooking
import com.example.data.model.HelperCategory
import com.example.data.model.HelperConfig
import com.example.data.model.HelperRuleItem
import com.example.data.repository.HelperRepository
import com.example.ui.theme.OutlineSoft
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

// Quick Helper look: calm blue accent (Grocery is green, Food is orange). Same layout language as the Grocery section.
internal val HelperBlue = Color(0xFF2563EB)
internal val HelperBlueSoft = Color(0xFFDBEAFE)
private val HelperBlueDark = Color(0xFF1D4ED8)

@Composable
internal fun HelperHeader(cityName: String?, onBack: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Box(Modifier.size(40.dp).clip(CircleShape).background(HelperBlueSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Person, null, tint = HelperBlueDark, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Quick Helper", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 22.sp)
                if (!cityName.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocationOn, null, tint = TextMuted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(cityName, fontSize = 13.sp, color = TextMuted, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Status colours: requested amber, accepted blue, working indigo, completed green, cancelled grey. */
@Composable
internal fun HelperStatusChip(b: HelperBooking) {
    val (bg, fg) = when (b.status) {
        "pending_payment", "requested" -> Color(0xFFFEF3C7) to Color(0xFF92400E)
        "accepted", "on_the_way" -> Color(0xFFDBEAFE) to Color(0xFF1D4ED8)
        "started" -> Color(0xFFE0E7FF) to Color(0xFF4338CA)
        "completed" -> Color(0xFFDCFCE7) to Color(0xFF166534)
        else -> Color(0xFFE5E7EB) to Color(0xFF4B5563)
    }
    val label = when (b.status) {
        "pending_payment" -> "Payment pending"; "requested" -> "Upcoming"; "accepted", "on_the_way" -> "Active"; "started" -> "Active"
        "completed" -> "Completed"; "cancelled" -> if (b.paymentStatus == "refunded" || b.paymentStatus == "refund_due") "Refunded" else "Cancelled"
        "expired" -> "No helper"; else -> b.statusLabel
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg) { Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
}

@Composable
internal fun HelperBookingCard(b: HelperBooking, cfg: HelperConfig, onOpen: () -> Unit) {
    val cat = cfg.categories.firstOrNull { it.id == b.categoryId }
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, OutlineSoft), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(HelperBlueSoft), contentAlignment = Alignment.Center) {
                if (cat?.imageUrl != null) AsyncImage(cat.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(b.taskType.take(1), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = HelperBlueDark)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                HelperStatusChip(b)
                Text(b.taskType, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                Text("${b.durationMinutes} min · ₹${"%.0f".format(b.priceTotal)} · ${b.bookingNumber}", fontSize = 12.sp, color = TextSecondary, maxLines = 1)
            }
            Surface(shape = RoundedCornerShape(10.dp), color = HelperBlueSoft) { Text("View", color = HelperBlueDark, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) }
        }
    }
}

/** Step 1 of the flow: hero line, round service icons, how it works and the latest booking. */
@Composable
internal fun HelperHomeContent(cfg: HelperConfig, onPick: (HelperCategory) -> Unit, onOpenBooking: (String) -> Unit, onSeeAll: () -> Unit) {
    var latest by remember { mutableStateOf<HelperBooking?>(null) }
    LaunchedEffect(Unit) { HelperRepository.mine().onSuccess { l -> latest = l.firstOrNull() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), color = HelperBlueSoft, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("A helper for any small task", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = HelperBlueDark)
                Text("Pay per 30 minutes. A verified person near you.", fontSize = 13.sp, color = TextSecondary)
            }
        }
        if (cfg.categories.isEmpty()) Text("No services yet. Please check again soon.", color = TextSecondary)
        else {
            Text("Choose a service", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            cfg.categories.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onPick(k) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(68.dp).clip(CircleShape).background(HelperBlueSoft), contentAlignment = Alignment.Center) {
                                if (k.imageUrl != null) AsyncImage(k.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else if (k.isCustom) Icon(Icons.Filled.Edit, null, tint = HelperBlueDark, modifier = Modifier.size(28.dp))
                                else Text(k.name.take(1), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = HelperBlueDark)
                            }
                            Text(k.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 15.sp)
                            Text(if (k.isCustom) "Any task · ₹${"%.0f".format(k.fromPrice)}+" else "from ₹${"%.0f".format(k.fromPrice)}", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Text("How it works", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Choose task", "Pay advance", "Helper arrives").forEachIndexed { i, t ->
                Surface(shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, OutlineSoft), modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(HelperBlue), contentAlignment = Alignment.Center) { Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        Text(t, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        latest?.let { b ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("My bookings", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("View all", color = HelperBlueDark, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onSeeAll))
            }
            HelperBookingCard(b, cfg) { onOpenBooking(b.id) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Step 2: the service with its price blocks and what the helper does / does not do. */
@Composable
internal fun HelperCategoryDetail(c: HelperCategory, minutes: Int, onMinutes: (Int) -> Unit, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (c.imageUrl != null) AsyncImage(c.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(190.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(c.name, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                if (c.description.isNotBlank()) Text(c.description, fontSize = 14.sp, color = TextSecondary)
                if (c.isCustom) Surface(shape = RoundedCornerShape(12.dp), color = HelperBlueSoft) {
                    Text("Not sure which service? Describe your own task in the next step. Tell us what, where and for how long, and a helper will take it up.", fontSize = 13.sp, color = HelperBlueDark, modifier = Modifier.padding(12.dp))
                }
                Text("Choose time", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    c.durations.forEach { d ->
                        val sel = d == minutes
                        Surface(
                            shape = RoundedCornerShape(14.dp), color = if (sel) HelperBlueSoft else Color.White,
                            border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) HelperBlue else OutlineSoft),
                            modifier = Modifier.weight(1f).clickable { onMinutes(d) }
                        ) {
                            Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("₹${"%.0f".format(c.priceFor(d))}", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = if (sel) HelperBlueDark else Color.Unspecified)
                                Text("$d min", fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                    }
                }
                if (c.dos.isNotEmpty() || c.donts.isNotEmpty()) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RuleCard("Do", c.dos, true, Modifier.weight(1f).fillMaxHeight())
                    RuleCard("Don't", c.donts, false, Modifier.weight(1f).fillMaxHeight())
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Surface(shadowElevation = 8.dp, color = Color.White) {
            Button(onClick = onContinue, colors = ButtonDefaults.buttonColors(containerColor = HelperBlue), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp)) {
                Text("Continue", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun RuleCard(title: String, items: List<HelperRuleItem>, good: Boolean, modifier: Modifier) {
    val bg = if (good) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
    val fg = if (good) Color(0xFF047857) else Color(0xFFBE123C)
    Surface(shape = RoundedCornerShape(16.dp), color = bg, modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = fg)
                Icon(if (good) Icons.Filled.Check else Icons.Filled.Close, null, tint = fg, modifier = Modifier.size(18.dp))
            }
            if (items.isEmpty()) Text("-", color = TextMuted)
            items.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Icon(if (good) Icons.Filled.Check else Icons.Filled.Close, null, tint = fg, modifier = Modifier.size(14.dp).padding(top = 3.dp))
                    Text(r.text, fontSize = 13.sp)
                }
                if (r.imageUrl != null) AsyncImage(r.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(10.dp)))
            }
        }
    }
}

/** Requested > Assigned > Arrived > Working > Done progress row. */
@Composable
internal fun HelperSteps(status: String) {
    val labels = listOf("Requested", "Assigned", "Arrived", "Working", "Done")
    val idx = when (status) { "pending_payment", "requested" -> 0; "accepted" -> 1; "on_the_way" -> 2; "started" -> 3; "completed" -> 4; else -> -1 }
    if (idx < 0) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { i, l ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (i <= idx) HelperBlue else Color(0xFFE5E7EB)), contentAlignment = Alignment.Center) {
                    if (i < idx || (i == idx && status == "completed")) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    else Text("${i + 1}", color = if (i <= idx) Color.White else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(l, fontSize = 10.sp, color = if (i <= idx) HelperBlueDark else TextMuted, fontWeight = if (i == idx) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
