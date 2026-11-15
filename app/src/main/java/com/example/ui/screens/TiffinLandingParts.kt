package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TiffinConfig
import com.example.data.repository.TiffinRepository
import com.example.ui.theme.*
import com.example.util.rupees

// Landing screen of Tiffin Pickup: hero, three feature tiles and the "How it works" timeline.
// Kept in its own file, in small functions, so no single function gets big for the Kotlin compiler.

@Composable
internal fun TiffinHero(cfg: TiffinConfig) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(NaturalPrimary, NaturalPrimaryDark)))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tx("title"), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(tx("heroTitle"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(tx("heroSub"), fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
            if (cfg.trialEnabled) {
                Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.18f)) {
                    Text(
                        tx("heroTrial", cfg.trialDays, rupees(cfg.trialPrice)),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun TiffinTile(modifier: Modifier, icon: ImageVector, label: String, bg: Color, tint: Color) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    }
}

@Composable
internal fun TiffinTiles() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TiffinTile(Modifier.weight(1f), Icons.Filled.Home, tx("stepPickup"), Color(0xFFFFF3E6), Color(0xFFE8590C))
        TiffinTile(Modifier.weight(1f), Icons.Filled.CalendarMonth, tx("tileDays"), Color(0xFFECE9FF), NaturalPrimary)
        TiffinTile(Modifier.weight(1f), Icons.Filled.TwoWheeler, tx("tileDeliver"), Color(0xFFFBE9F6), Color(0xFFC2255C))
    }
}

@Composable
private fun NumberDot(n: Int) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(NaturalPrimary), contentAlignment = Alignment.Center) {
        Text("$n", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

@Composable
private fun GuideStep(n: Int, icon: ImageVector, text: String) {
    val cut = text.indexOf('.')
    val head = if (cut >= 0) text.substring(0, cut) else text
    val rest = if (cut >= 0) text.substring(cut + 1).trim() else ""
    Surface(shape = RoundedCornerShape(14.dp), color = Color.White, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberDot(n)
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(NaturalPrimaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(head, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                if (rest.isNotEmpty()) Text(rest, fontSize = 12.sp, color = TextSecondary)
            }
        }
    }
}

/** "How it works": numbered steps with icons, in the customer's language. */
@Composable
internal fun TiffinGuide(cfg: TiffinConfig) {
    val trial = cfg.trialEnabled
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(NaturalPrimaryContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = AmberAccent)
            Text(tx("guideTitle"), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = NaturalOnPrimaryContainer)
        }
        var n = 1
        GuideStep(n++, Icons.Filled.Home, tx("g1"))
        if (trial) GuideStep(n++, Icons.Filled.CurrencyRupee, tx("g2", cfg.trialDays, rupees(cfg.trialPrice)))
        GuideStep(n++, Icons.Filled.Schedule, tx("g3"))
        GuideStep(n++, Icons.Filled.TwoWheeler, tx("g4"))
        GuideStep(n++, Icons.Filled.Repeat, tx("g5"))
        GuideStep(n, Icons.Filled.CheckCircle, tx("g6"))
    }
}

/** Three-dot progress: Details, Review, Confirm. */
@Composable
internal fun TiffinStepper(step: Int) {
    val labels = listOf(tx("stepDetails"), tx("stepReview"), tx("stepConfirm"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        labels.forEachIndexed { i, label ->
            val done = i + 1 < step
            val current = i + 1 == step
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(if (done || current) NaturalPrimary else OutlineBorder),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    else Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (current) TextPrimary else TextSecondary)
            }
        }
    }
}

/** A numbered section heading inside the form. */
@Composable
internal fun TiffinSection(n: Int, title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberDot(n)
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
    }
}

/** One row of the review summary: icon, small heading, value. */
@Composable
internal fun TiffinSummaryRow(icon: ImageVector, heading: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
        Column {
            Text(heading, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
            Text(value, fontSize = 14.sp, color = TextPrimary)
        }
    }
}

/** Step 2 of the plan form: what the customer chose, row by row. */
@Composable
internal fun TiffinReview(
    kind: String,
    name: String,
    detail: String,
    pickup: String,
    drop: String,
    days: List<Int>,
    pickupTime: String,
    startDate: String,
    returnTrip: Boolean,
    returnTime: String,
    note: String
) {
    val kindIcon = when (kind) { "school" -> Icons.Filled.School; "office" -> Icons.Filled.Work; else -> Icons.Filled.Inventory2 }
    val whoKey = when (kind) { "school" -> "whoSchool"; "office" -> "whoOffice"; else -> "whoOther" }
    val dayNames = days.map { tx("wd$it") }.joinToString(", ")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(18.dp))
            Text(tx("yourPlan"), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = NaturalOnPrimaryContainer)
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(NaturalPrimaryContainer).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(kindIcon, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(28.dp))
            }
            Column {
                Text(tx(whoKey), fontSize = 12.sp, color = TextSecondary)
                Text(name, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = TextSecondary)
            }
        }
        TiffinSummaryRow(Icons.Filled.Home, tx("lblPickup"), pickup)
        TiffinSummaryRow(kindIcon, tx("lblDrop"), drop)
        TiffinSummaryRow(Icons.Filled.CalendarMonth, tx("lblDays"), dayNames)
        TiffinSummaryRow(Icons.Filled.Schedule, tx("lblTime"), TiffinRepository.timeLabel(pickupTime) + " · " + tx("lblStart") + " " + TiffinRepository.dayLabel(startDate, LocalTiffinLang.current))
        if (returnTrip) TiffinSummaryRow(Icons.Filled.Repeat, tx("lblReturn"), TiffinRepository.timeLabel(returnTime))
        if (note.isNotBlank()) TiffinSummaryRow(Icons.Filled.VerifiedUser, tx("lblNote"), note)
    }
}
