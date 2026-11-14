package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.RamenDining
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.City
import com.example.data.model.FoodHomeMeta
import com.example.data.model.Vendor
import com.example.data.model.isEffectivelyOpenNow
import com.example.data.repository.SndmartRepository
import com.example.ui.components.ProductImage
import com.example.ui.theme.OutlineSoft
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Food home "restaurants by budget" pieces. Kept out of HomeScreen.kt on purpose: that file's main
 * function is already so large that the Kotlin compiler runs out of memory when more is added to it.
 */

enum class PriceTier(
    val key: String,
    val label: String,
    val shortLabel: String,
    val heading: String,
    val icon: ImageVector,
    val start: Color,
    val end: Color,
    val accent: Color
) {
    LUXURY("luxury", "Luxury", "Luxury", "Luxury Restaurants", Icons.Outlined.WorkspacePremium, Color(0xFF4A3508), Color(0xFF1C1404), Color(0xFFFFD84D)),
    MEDIUM("medium", "Medium-range", "Medium", "Medium-range Restaurants", Icons.Outlined.RamenDining, Color(0xFFF08A24), Color(0xFFC2410C), Color(0xFFFFFFFF)),
    LOW_BUDGET("low_budget", "Low-budget", "Low", "Low-budget Restaurants", Icons.Outlined.Restaurant, Color(0xFF34A853), Color(0xFF17713A), Color(0xFFFFFFFF))
}

private fun FoodHomeMeta?.countFor(tier: PriceTier): Int? = this?.let {
    when (tier) {
        PriceTier.LUXURY -> it.tierCounts.luxury
        PriceTier.MEDIUM -> it.tierCounts.medium
        PriceTier.LOW_BUDGET -> it.tierCounts.lowBudget
    }
}

/** The tier's real price band, from the city's thresholds. */
private fun PriceTier.band(meta: FoodHomeMeta?): String {
    val low = (meta?.lowThreshold ?: 100.0).toInt()
    val high = (meta?.highThreshold ?: 200.0).toInt()
    return when (this) {
        PriceTier.LUXURY -> "Dishes mostly above ₹$high"
        PriceTier.MEDIUM -> "Dishes mostly ₹$low–₹$high"
        PriceTier.LOW_BUDGET -> "Dishes mostly up to ₹$low"
    }
}

class FoodHomeExtras(val meta: FoodHomeMeta?, val topHotels: List<Pair<Vendor, Int>>)

/** Price-tier bands + counts and the Top Hotels row for the food home of this city. */
@Composable
fun rememberFoodHomeExtras(repository: SndmartRepository, cityId: String?): FoodHomeExtras {
    var meta by remember(cityId) { mutableStateOf<FoodHomeMeta?>(null) }
    var top by remember(cityId) { mutableStateOf<List<Pair<Vendor, Int>>>(emptyList()) }
    LaunchedEffect(cityId) {
        if (cityId == null) return@LaunchedEffect
        val m = repository.getFoodHomeMeta(cityId) ?: return@LaunchedEffect
        meta = m
        top = repository.getTopHotels(m)
    }
    return FoodHomeExtras(meta, top)
}

/**
 * Everything the food home needs for "restaurants by budget" in one object, so HomeScreen reads plain
 * properties instead of repeating nullable checks (the compiler runs out of memory on that function).
 */
class FoodTierUi(
    val tier: PriceTier?,
    val hotels: List<Vendor>,
    val loading: Boolean,
    val extras: FoodHomeExtras
) {
    val open: Boolean get() = tier != null
    val heading: String get() = tier?.heading ?: ""
}

/** A typed search wins over a chosen tier. */
@Composable
fun rememberFoodTierUi(repository: SndmartRepository, cityId: String?, selectedKey: String?, searchBlank: Boolean): FoodTierUi {
    val extras = rememberFoodHomeExtras(repository, cityId)
    val tier = if (searchBlank) PriceTier.values().firstOrNull { it.key == selectedKey } else null
    val loaded = rememberTierHotels(repository, cityId, tier)
    return FoodTierUi(tier, loaded.hotels, loaded.loading, extras)
}

/** Switcher while a tier is open; otherwise the budget cards and the Top Hotels row. */
fun LazyListScope.foodTierSection(
    ui: FoodTierUi,
    searchBlank: Boolean,
    city: City?,
    repository: SndmartRepository,
    onSelect: (PriceTier?) -> Unit,
    onOpenHotel: (Vendor) -> Unit
) {
    val active = ui.tier
    if (active != null) {
        item(key = "food_tier_tabs") { TierTabs(active = active, meta = ui.extras.meta, onSelect = onSelect) }
    } else if (searchBlank) {
        item(key = "food_tier_cards") { PriceTierCards(meta = ui.extras.meta, onSelect = { t -> onSelect(t) }) }
        if (ui.extras.topHotels.size >= 3) {
            item(key = "food_top_hotels") {
                TopHotelsRow(hotels = ui.extras.topHotels, city = city, repository = repository, onOpen = onOpenHotel)
            }
        }
    }
}

class TierHotels(val hotels: List<Vendor>, val loading: Boolean)

/** Every hotel of the chosen tier (null tier = nothing to load). */
@Composable
fun rememberTierHotels(repository: SndmartRepository, cityId: String?, tier: PriceTier?): TierHotels {
    var loaded by remember(cityId, tier) { mutableStateOf<List<Vendor>?>(null) }
    LaunchedEffect(cityId, tier) {
        if (cityId == null || tier == null) return@LaunchedEffect
        loaded = repository.getHotelsByTier(cityId, tier.key).getOrNull() ?: emptyList()
    }
    return TierHotels(loaded.orEmpty(), tier != null && loaded == null)
}

@Composable
fun PriceTierCards(meta: FoodHomeMeta?, onSelect: (PriceTier) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
        Text(
            text = "Restaurants for every budget",
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
            PriceTier.values().forEach { tier ->
                val count = meta.countFor(tier)
                val empty = count == 0
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(142.dp)
                        .alpha(if (empty) 0.5f else 1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(tier.start, tier.end)))
                        .clickable(enabled = !empty) { onSelect(tier) }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(start = 11.dp, end = 8.dp, top = 12.dp, bottom = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color.White.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(tier.icon, contentDescription = null, tint = tier.accent, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            tier.label,
                            color = tier.accent,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            tier.band(meta),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (tier == PriceTier.LUXURY) Color(0xFFFFD84D) else Color.White.copy(alpha = 0.92f))
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when {
                                    count == null -> "Explore"
                                    empty -> "None yet"
                                    else -> "$count ${if (count == 1) "place" else "places"}"
                                },
                                color = if (tier == PriceTier.LUXURY) Color(0xFF241A04) else Color(0xFF1F2937),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1
                            )
                            if (!empty) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = if (tier == PriceTier.LUXURY) Color(0xFF241A04) else Color(0xFF1F2937),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** While one tier is showing: a compact switcher between the three (tap the active one to clear). */
@Composable
fun TierTabs(active: PriceTier, meta: FoodHomeMeta?, onSelect: (PriceTier?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(BorderStroke(1.dp, OutlineSoft), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PriceTier.values().forEach { tier ->
            val on = tier == active
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (on) Modifier.background(Brush.linearGradient(listOf(tier.start, tier.end))) else Modifier
                    )
                    .clickable { onSelect(if (on) null else tier) }
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    tier.icon,
                    contentDescription = null,
                    tint = if (on) tier.accent else TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    tier.shortLabel,
                    color = if (on) tier.accent else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                meta.countFor(tier)?.let { c ->
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "$c",
                        color = if (on) Color.White else TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.07f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/** "Top Hotels": the hotels this city orders from most (real order counts), best first. */
@Composable
fun TopHotelsRow(
    hotels: List<Pair<Vendor, Int>>,
    city: City?,
    repository: SndmartRepository,
    onOpen: (Vendor) -> Unit
) {
    if (hotels.size < 3) return
    val grayscale = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Top Hotels", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            Text(
                "Most ordered" + (city?.name?.let { " in $it" } ?: ""),
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
        ) {
            items(hotels.size, key = { hotels[it].first.id }) { index ->
                val (vendor, orders) = hotels[index]
                val open = vendor.isEffectivelyOpenNow(city)
                var rating by remember(vendor.id) { mutableStateOf(0.0) }
                LaunchedEffect(vendor.id) {
                    rating = repository.getVendorAverageRating(vendor.id).getOrNull() ?: 0.0
                }
                Card(
                    modifier = Modifier
                        .width(172.dp)
                        .alpha(if (open) 1f else 0.8f)
                        .clickable { onOpen(vendor) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, OutlineSoft),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
                ) {
                    Column {
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                            ProductImage(
                                url = vendor.bannerUrl,
                                contentDescription = vendor.name,
                                modifier = Modifier.fillMaxSize(),
                                colorFilter = if (open) null else grayscale
                            )
                            Text(
                                "#${index + 1}",
                                color = Color(0xFFFFD84D),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xD1111827))
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                            if (!open) {
                                Text(
                                    "CLOSED",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF6B7280))
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                vendor.name,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (rating > 0) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF16A34A))
                                            .padding(horizontal = 6.dp, vertical = 1.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("%.1f".format(rating), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                                Text("$orders orders", color = TextSecondary, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
