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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val fallback = painterResource(R.drawable.tiffin_trial_banner)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AsyncImage(
            model = cfg.trialBannerImageUrl ?: R.drawable.tiffin_trial_banner,
            contentDescription = tx("trialAlt", cfg.trialDays, rupees(cfg.trialPrice)),
            placeholder = fallback, error = fallback,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1212f / 333f).clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick).testTag("tiffin_trial_banner")
        )
        Text(tx("trialCaption"), fontSize = 12.sp, color = TextSecondary)
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
