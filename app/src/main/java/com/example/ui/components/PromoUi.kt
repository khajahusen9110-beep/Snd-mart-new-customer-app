package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ActivePromoRow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ---------------------------------------------------------------------------------------------
// Promotional offer UI — entirely driven by the backend (public.get_active_promo via the
// repository's foodPromo / groceryPromo flows, surfaced here through LocalActivePromo).
//
// The discount % lives ONLY in promo_campaigns. One admin change there updates the ribbon, every
// item's strikethrough price, the badges, the banners and the checkout together. When no offer is
// active both flows are null, every promo composable renders nothing, and prices are unchanged.
// ---------------------------------------------------------------------------------------------

enum class PromoKind { FOOD, GROCERY }

/** The active offer for each section, as seen by the whole UI tree. */
data class PromoState(val food: ActivePromoRow? = null, val grocery: ActivePromoRow? = null)

val LocalActivePromo = staticCompositionLocalOf { PromoState() }

@Composable
fun promoFor(kind: PromoKind): ActivePromoRow? {
    val s = LocalActivePromo.current
    return when (kind) {
        PromoKind.FOOD -> s.food
        PromoKind.GROCERY -> s.grocery
    }
}

/** Discounted price for a single item — same rounding as the server item view. */
fun offerPrice(price: Double, percent: Double): Double = Math.round(price * (100 - percent) / 100.0).toDouble()

/** Cart/checkout discount — mirrors the server promo_discount_for (percent, min order, max cap). */
fun cartPromoDiscount(subtotal: Double, promo: ActivePromoRow?): Double {
    if (promo == null || promo.percent <= 0 || subtotal < promo.minOrderAmount) return 0.0
    var d = Math.round((subtotal * promo.percent / 100.0) * 100) / 100.0
    promo.maxDiscountPerOrder?.let { if (it > 0) d = minOf(d, it) }
    return maxOf(0.0, minOf(d, subtotal))
}

// Backend timestamps look like "2026-10-20T18:30:00+00:00" / "...Z" / with fractional seconds.
private val PROMO_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss"
)

/** Epoch millis for an offer end/start time, or null if it cannot be read. */
fun parsePromoMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    for (p in PROMO_PATTERNS) {
        try {
            val parser = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return parser.parse(iso)?.time
        } catch (_: Exception) {
        }
    }
    return null
}

private data class Countdown(val d: Long, val h: Long, val m: Long, val s: Long)

private fun countdownParts(ms: Long): Countdown {
    val sec = maxOf(0L, ms / 1000)
    return Countdown(sec / 86400, (sec % 86400) / 3600, (sec % 3600) / 60, sec % 60)
}

private fun pad(n: Long) = n.toString().padStart(2, '0')

/** A ticking "time left" (ms) for the given end time, or null when there is no end / it passed. */
@Composable
private fun rememberTimeLeft(endIso: String?): Long? {
    val end = remember(endIso) { parsePromoMillis(endIso) } ?: return null
    var now by remember(endIso) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endIso) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = end - now
    return if (left > 0) left else null
}

/**
 * The big promotional banner with a live countdown, shown only when an offer is active for the
 * given section. Red for food, green for grocery — matching the Food Fest / Groceries Fest design.
 */
@Composable
fun OfferBanner(kind: PromoKind, modifier: Modifier = Modifier) {
    val promo = promoFor(kind) ?: return
    if (promo.percent <= 0) return

    val isGrocery = kind == PromoKind.GROCERY
    val gradient = if (isGrocery) {
        Brush.linearGradient(listOf(Color(0xFF15803D), Color(0xFF166534)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFE11D48), Color(0xFFB91C3C)))
    }
    val pct = Math.round(promo.percent).toInt()
    val title = promo.bannerNote?.takeIf { it.isNotBlank() } ?: "$pct% OFF"
    val timeLeft = rememberTimeLeft(promo.endsAt)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(gradient)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isGrocery) "SND MART · GROCERIES FEST" else "SND MART · FOOD FEST",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.6.sp
                )
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = "ಇಂದೇ ಆರ್ಡರ್ ಮಾಡಿ · Order now",
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = "$pct%", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    text = " OFF",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
        }
        if (timeLeft != null) {
            val p = countdownParts(timeLeft)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.22f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ಆಫರ್ ಕೊನೆಯಾಗಲು\nEnds in",
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CountdownCell(p.d.toString(), "ದಿನ")
                    CountdownCell(pad(p.h), "ಗಂ")
                    CountdownCell(pad(p.m), "ನಿ")
                    CountdownCell(pad(p.s), "ಸೆ")
                }
            }
        }
    }
}

@Composable
private fun CountdownCell(value: String, label: String) {
    Column(
        modifier = Modifier
            .widthIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = value, color = Color(0xFF0F172A), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Text(text = label, color = Color(0xFF0F172A).copy(alpha = 0.65f), fontSize = 9.sp)
    }
}

/**
 * A thin, always-present strip that tells users an offer is running — shown on EVERY page (placed
 * in the app scaffold). Driven entirely by the backend %. Renders nothing when no offer is active.
 */
@Composable
fun OfferRibbon(modifier: Modifier = Modifier) {
    val state = LocalActivePromo.current
    val food = state.food
    val grocery = state.grocery
    val promo = food ?: grocery ?: return
    if (promo.percent <= 0) return

    val pct = Math.round(maxOf(food?.percent ?: 0.0, grocery?.percent ?: 0.0)).toInt()
    val what = when {
        food != null && grocery != null -> "food & groceries"
        food != null -> "hotel food"
        else -> "groceries"
    }
    // Ribbon counts down to whichever active offer ends first.
    val endIso = listOfNotNull(food?.endsAt, grocery?.endsAt).minByOrNull { parsePromoMillis(it) ?: Long.MAX_VALUE }
    val timeLeft = rememberTimeLeft(endIso)
    val left = timeLeft?.let {
        val p = countdownParts(it)
        if (p.d > 0) "${p.d}d ${pad(p.h)}h" else "${pad(p.h)}h ${pad(p.m)}m ${pad(p.s)}s"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Color(0xFFE11D48), Color(0xFFB91C3C))))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🎉 $pct% OFF on $what · ಇಂದೇ ಆರ್ಡರ್ ಮಾಡಿ",
            color = Color.White,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        if (left != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "⏳ $left",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.22f))
                    .padding(horizontal = 9.dp, vertical = 2.dp)
            )
        }
    }
}
