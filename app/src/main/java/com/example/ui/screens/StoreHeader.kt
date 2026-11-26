package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MIN_REVIEWS_FOR_STARS
import com.example.data.model.StoreMenu
import com.example.data.model.StoreRatingSummary
import com.example.ui.theme.*

private val Brand = Color(0xFF6D3AD9)
private val Green = Color(0xFF1A9A49)

/** Wide photo of the shop with the open / closed chip on it (a soft purple block when the shop has no photo). */
@Composable
fun StoreHero(m: StoreMenu) {
    Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp)).background(NaturalPrimaryContainer).testTag("store_hero")) {
        if (m.bannerUrl != null) AsyncImage(m.bannerUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.Storefront, null, tint = NaturalPrimary, modifier = Modifier.align(Alignment.Center).size(64.dp))
        Box(Modifier.fillMaxWidth().height(80.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
        Text(
            if (m.isOpen) "● Open now" else "Closed now", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
            color = if (m.isOpen) Color(0xFF166534) else Color(0xFF991B1B),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(999.dp))
                .background(if (m.isOpen) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)).padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** Name, tick, category, stars and address, as a card that overlaps the photo. */
@Composable
fun StoreInfoCard(m: StoreMenu, rating: StoreRatingSummary?) {
    Surface(
        shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, OutlineSoft),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).offset(y = (-26).dp).shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = Brand, spotColor = Brand)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Verified, "Verified shop", tint = Green, modifier = Modifier.size(22.dp))
            }
            if (m.categoryName != null) Text(m.categoryName, fontSize = 14.sp, color = TextSecondary)
            val stars = rating?.takeIf { it.count >= MIN_REVIEWS_FOR_STARS && it.avg != null }
            if (stars != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, tint = Color(0xFF7BC043), modifier = Modifier.size(17.dp))
                Text(" %.1f".format(stars.avg), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                Text(" (${stars.count} ratings)", fontSize = 13.sp, color = TextSecondary)
            } else if (rating != null) Text("✨ New on SND Mart", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5B21B6))
            if (m.address != null) Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.LocationOn, null, tint = Brand, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                Text(" ${m.address}", fontSize = 13.sp, color = TextSecondary)
            }
        }
    }
}

/** Three facts in one purple strip; a cell with no data is left out. */
@Composable
fun StoreInfoStrip(m: StoreMenu) {
    val cells = buildList {
        add(Triple(Icons.Filled.AccessTime, if (m.isOpen) "Open now" else "Closed now", if (m.isOpen) "Taking orders" else "Back soon"))
        if (m.prepMinutes != null) add(Triple(Icons.Filled.Inventory2, "Packs in", "~${m.prepMinutes} min"))
        add(Triple(Icons.Filled.TwoWheeler, "Delivery", "Available"))
    }
    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFFF1ECFF), border = BorderStroke(1.dp, Color(0xFFE0D5FB)), modifier = Modifier.fillMaxWidth().offset(y = (-14).dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            cells.forEachIndexed { i, (icon, top, bottom) ->
                if (i > 0) Box(Modifier.width(1.dp).height(34.dp).background(Color(0xFFD9CCF7)))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, null, tint = Brand, modifier = Modifier.size(22.dp))
                    Text(top, fontSize = 12.sp, color = TextSecondary)
                    Text(bottom, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                }
            }
        }
    }
}

/** Category tiles with the photo of one of their products; "Recommended" first. */
@Composable
fun StoreCategoryTiles(m: StoreMenu, selected: String?, onSelect: (String?) -> Unit) {
    fun photo(catId: String) = m.products.firstOrNull { it.categoryId == catId && it.imageUrl != null }?.imageUrl
    @Composable fun Tile(label: String, url: String?, on: Boolean, fallback: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
        Column(Modifier.width(76.dp).clickable(onClick = click), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(68.dp).clip(RoundedCornerShape(18.dp)).background(NaturalPrimaryContainer)
                    .then(if (on) Modifier.border(2.dp, Brand, RoundedCornerShape(18.dp)) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(fallback, null, tint = Brand, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 12.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, color = if (on) Brand else TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (on) Box(Modifier.padding(top = 3.dp).width(26.dp).height(3.dp).clip(CircleShape).background(Brand))
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp).testTag("store_product_chips")) {
        item { Tile("Top Picks", m.products.firstOrNull { it.imageUrl != null }?.imageUrl, selected == null, Icons.Filled.Star) { onSelect(null) } }
        items(m.categories) { (id, name) -> Tile(name, photo(id), selected == id, Icons.Filled.Category) { onSelect(id) } }
    }
}

/** Sticky bottom bar: Call (when the shop has a number) and, once the cart has this shop's items, Checkout. */
@Composable
fun StoreBottomBar(phone: String?, cartCount: Int, cartTotal: Double, onCheckout: () -> Unit) {
    val ctx = LocalContext.current
    if (phone.isNullOrBlank() && cartCount == 0) return
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White, shadowElevation = 10.dp, modifier = Modifier.fillMaxWidth().padding(10.dp)) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!phone.isNullOrBlank()) Surface(
                shape = RoundedCornerShape(16.dp), color = Color(0xFFF1ECFF),
                modifier = Modifier.then(if (cartCount > 0) Modifier else Modifier.weight(1f)).clip(RoundedCornerShape(16.dp)).clickable {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
                }.testTag("store_call")
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 13.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Call, null, tint = Brand, modifier = Modifier.size(20.dp))
                    Text("  Call", fontWeight = FontWeight.ExtraBold, color = Brand)
                }
            }
            if (cartCount > 0) Surface(shape = RoundedCornerShape(16.dp), color = Brand, modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onCheckout).testTag("store_checkout_bar")) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$cartCount item${if (cartCount > 1) "s" else ""} • ${rupee(cartTotal)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("Checkout", color = Color.White, fontWeight = FontWeight.ExtraBold)
                    Icon(Icons.Filled.ChevronRight, null, tint = Color.White)
                }
            }
        }
    }
}
