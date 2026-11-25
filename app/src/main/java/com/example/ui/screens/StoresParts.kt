package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MIN_REVIEWS_FOR_STARS
import com.example.data.model.StoreCardInfo
import com.example.data.model.StoreListItem
import com.example.data.model.StoreProductHit
import com.example.ui.theme.*

// Small pieces shared by the Stores screens (same look as the website's Stores).

internal fun rupee(n: Double): String = "₹" + if (n % 1.0 == 0.0) n.toLong().toString() else "%.2f".format(n)
internal fun kmLabel(km: Double?): String = when {
    km == null -> ""
    km < 1.0 -> "${(km * 1000).toInt()} m"
    else -> "%.1f km".format(km)
}

@Composable
internal fun StoreBadge(text: String, bg: Color, fg: Color) {
    Text(
        text, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
internal fun StorePhoto(url: String?, size: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(NaturalPrimaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.Storefront, contentDescription = null, tint = NaturalPrimary)
    }
}

/**
 * A shop in the new look: big photo, name with a verified tick, category and distance, rating and free-delivery note
 * (only when there is real data), Nearest / Nearby / Open chips and a "View Store" button.
 */
@Composable
fun StoreCard(s: StoreListItem, info: StoreCardInfo? = null, onClick: () -> Unit) {
    val green = Color(0xFF1A9A49)
    val hasRating = info?.avgRating != null && info.reviewCount >= MIN_REVIEWS_FOR_STARS
    val isNew = info != null && !hasRating
    val free = info?.freeDelivery == true
    val nearby = !s.isNearest && s.distanceKm != null && s.distanceKm <= 2.0
    Surface(
        shape = RoundedCornerShape(20.dp), color = SurfaceLight, border = BorderStroke(1.dp, OutlineSoft), shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).testTag("store_card")
    ) {
        Row(Modifier.alpha(if (s.isOpen) 1f else 0.7f).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(92.dp).clip(RoundedCornerShape(16.dp)).background(NaturalPrimaryContainer), contentAlignment = Alignment.Center) {
                if (s.bannerUrl != null) AsyncImage(model = s.bannerUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.Storefront, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(34.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Filled.Verified, contentDescription = "Verified shop", tint = green, modifier = Modifier.size(20.dp))
                }
                Text(s.categoryName + if (s.distanceKm != null) " · ${kmLabel(s.distanceKm)}" else "", fontSize = 13.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hasRating || isNew || free) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasRating) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFF7BC043), modifier = Modifier.size(15.dp))
                        Text(" %.1f".format(info!!.avgRating!!), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                        Text(" (${info.reviewCount})", fontSize = 13.sp, color = TextSecondary)
                    }
                    if (isNew) StoreBadge("✨ New", Color(0xFFEDE9FE), Color(0xFF5B21B6))
                    if ((hasRating || isNew) && free) Box(Modifier.size(width = 1.dp, height = 14.dp).background(OutlineSoft))
                    if (free) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.TwoWheeler, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        Text(" Free Delivery" + (info!!.freeOver?.let { " ₹${it.toInt()}+" } ?: ""), fontSize = 13.sp, color = TextSecondary, maxLines = 1)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                    if (s.isNearest) StoreBadge("Nearest", Color(0xFFFFF3D6), Color(0xFF9A5B00))
                    if (nearby) StoreBadge("Nearby", Color(0xFFE0ECFF), Color(0xFF1D4ED8))
                    if (s.isOpen) StoreBadge("● Open", Color(0xFFDCFCE7), Color(0xFF166534)) else StoreBadge("Closed", Color(0xFFFEE2E2), Color(0xFF991B1B))
                }
                Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFFF2FBF5), border = BorderStroke(2.dp, green), modifier = Modifier.padding(top = 2.dp)) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("View Store", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color(0xFF0E7A37))
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color(0xFF0E7A37), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** The grey pill under the shop list: "Open shops first · nearest first · within 5 km". */
@Composable
fun StoresFootnote(text: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceVariantLight, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccessTime, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
        }
    }
}

/** One search hit: product, unit price (per kg / litre), shop and distance, and the price. */
@Composable
fun StoreProductRow(hit: StoreProductHit, onAdd: (() -> Unit)? = null, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = SurfaceLight, border = BorderStroke(1.dp, OutlineSoft),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("store_product_hit")
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StorePhoto(hit.imageUrl, 56)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(hit.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val unitLine = listOfNotNull(hit.unit, hit.unitPrice?.let { "${rupee(it)}/${hit.unitLabel}" }).joinToString(" · ")
                if (unitLine.isNotBlank()) Text(unitLine, fontSize = 12.sp, color = TextMuted)
                Text(hit.storeName + if (hit.distanceKm != null) " · ${kmLabel(hit.distanceKm)}" else "", fontSize = 12.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(rupee(hit.price), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = TextPrimary)
                if (hit.mrp != null && hit.mrp > hit.price) Text(rupee(hit.mrp), fontSize = 11.sp, color = TextMuted, textDecoration = TextDecoration.LineThrough)
                if (onAdd != null) FilledTonalButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp), modifier = Modifier.padding(top = 4.dp).height(32.dp).testTag("store_search_add")) { Text("Add", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
            }
        }
    }
}

/** Pill chips for the sort choice and the shop's product categories. */
@Composable
fun StoreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp), color = if (selected) NaturalPrimary else SurfaceVariantLight,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onClick)
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else TextSecondary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}
