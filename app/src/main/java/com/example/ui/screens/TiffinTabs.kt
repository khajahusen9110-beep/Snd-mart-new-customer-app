package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.TiffinConfig
import com.example.ui.theme.*
import com.example.util.rupees

// The Home / My Plans / Help tabs of Tiffin Pickup. Small functions on purpose (Kotlin compiler size limit).

internal const val TAB_HOME = 0
internal const val TAB_PLANS = 1
internal const val TAB_HELP = 2

@Composable
internal fun TiffinTabRow(tab: Int, onPick: (Int) -> Unit) {
    val items = listOf(TAB_HOME to tx("tabHome"), TAB_PLANS to tx("tabPlans"), TAB_HELP to tx("tabHelp"))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF1EFFA)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (id, label) ->
            val on = id == tab
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (on) NaturalPrimary else Color.Transparent)
                    .clickable { onPick(id) }.padding(vertical = 10.dp).testTag("tiffin_tab_$id"),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else TextSecondary)
            }
        }
    }
}

/** Picture hero: the picture the City Admin uploaded, or the bundled default. */
@Composable
internal fun TiffinPictureHero(cfg: TiffinConfig) {
    val fallback = painterResource(R.drawable.tiffin_hero)
    AsyncImage(
        model = cfg.heroImageUrl ?: R.drawable.tiffin_hero,
        contentDescription = tx("title"),
        placeholder = fallback, error = fallback,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().aspectRatio(1210f / 748f).clip(RoundedCornerShape(20.dp)).testTag("tiffin_hero")
    )
}

@Composable
internal fun TiffinTrialBanner(cfg: TiffinConfig, onClick: () -> Unit) {
    val custom = cfg.trialBannerImageUrl
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (custom != null) {
            AsyncImage(
                model = custom,
                contentDescription = tx("trialAlt", cfg.trialDays, rupees(cfg.trialPrice)),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1212f / 333f).clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onClick).testTag("tiffin_trial_banner")
            )
        } else {
            TiffinOfferCard(cfg, onClick)
        }
        val monthly = cfg.slabs.firstOrNull()?.price ?: 0.0
        Text(tx("offerThen", rupees(monthly)), fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** Big highlighted trial offer: days, the regular price struck through, the trial price, cancel any time. */
@Composable
private fun TiffinOfferCard(cfg: TiffinConfig, onClick: () -> Unit) {
    val slab = cfg.slabs.firstOrNull()
    val regular = if (slab != null && cfg.fullMonthTrips > 0) Math.round(slab.price * cfg.trialDays / cfg.fullMonthTrips) else 0L
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF6236D9), Color(0xFF8B4DFF), Color(0xFFFF7A45))))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 20.dp).testTag("tiffin_trial_banner"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            tx("offerKicker"), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF3B1D00),
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFD23F)).padding(horizontal = 10.dp, vertical = 4.dp)
        )
        Text(tx("offerDays", cfg.trialDays), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.padding(top = 8.dp))
        Text(tx("offerFor"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (regular > 0) Text(
                rupees(regular.toDouble()), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.75f),
                style = androidx.compose.ui.text.TextStyle(textDecoration = TextDecoration.LineThrough),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(rupees(cfg.trialPrice), fontSize = 64.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
        Text(
            "✓ " + tx("offerCancel"), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 14.dp, vertical = 6.dp)
        )
        Text(
            tx("offerBtn", cfg.trialDays) + " →", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color(0xFF5B2FD0),
            modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(horizontal = 22.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, sub: String, bg: Color, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NaturalOnPrimaryContainer)
            Text(sub, fontSize = 12.sp, color = TextSecondary)
        }
    }
}

@Composable
internal fun TiffinFeatures() {
    Card(shape = AppCardShape, colors = appCardColors(), border = AppCardBorder) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tx("ourFeatures"), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = NaturalOnPrimaryContainer)
            FeatureRow(Icons.Filled.Home, tx("stepPickup"), tx("featPickupSub"), Color(0xFFFFF3E6), Color(0xFFE8590C))
            FeatureRow(Icons.Filled.CalendarMonth, tx("tileDays"), tx("featDaysSub"), Color(0xFFECE9FF), NaturalPrimary)
            FeatureRow(Icons.Filled.TwoWheeler, tx("tileDeliver"), tx("featDeliverSub"), Color(0xFFFBE9F6), Color(0xFFC2255C))
            FeatureRow(Icons.Filled.Repeat, tx("featBox"), tx("featBoxSub"), Color(0xFFE6F7EE), Color(0xFF1B9E5A))
        }
    }
}

@Composable
private fun HowStep(modifier: Modifier, n: Int, text: String) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(NaturalPrimary), contentAlignment = Alignment.Center) {
            Text("$n", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        }
        Text(text, fontSize = 11.sp, textAlign = TextAlign.Center, color = NaturalOnPrimaryContainer, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun TiffinHowItWorks(onFullGuide: () -> Unit) {
    Card(shape = AppCardShape, colors = appCardColors(), border = AppCardBorder) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tx("guideTitle"), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = NaturalOnPrimaryContainer)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HowStep(Modifier.weight(1f), 1, tx("hw1"))
                HowStep(Modifier.weight(1f), 2, tx("hw2"))
                HowStep(Modifier.weight(1f), 3, tx("hw3"))
                HowStep(Modifier.weight(1f), 4, tx("hw4"))
            }
            Text(
                tx("fullGuide") + "  →", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NaturalPrimary,
                modifier = Modifier.clickable(onClick = onFullGuide)
            )
        }
    }
}
