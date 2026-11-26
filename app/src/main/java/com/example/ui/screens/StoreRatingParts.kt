package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MIN_REVIEWS_FOR_STARS
import com.example.data.model.PendingReview
import com.example.data.model.StoreRatingSummary
import com.example.data.repository.StoresRepository

private val Star = Color(0xFF7BC043)

/** Average, bars per star and the latest written reviews. Nothing is shown for a shop nobody rated yet. */
@Composable
fun StoreRatingSection(storeId: String, modifier: Modifier = Modifier) {
    val sum by produceState<StoreRatingSummary?>(null, storeId) { value = StoresRepository.ratingSummary(storeId) }
    sum?.let { StoreRatingContent(it, modifier) }
}

@Composable
fun StoreRatingContent(s: StoreRatingSummary, modifier: Modifier = Modifier) {
    if (s.count == 0) return
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE5E7EB)), modifier = modifier.fillMaxWidth().testTag("store_rating_section")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ratings & reviews", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (s.count >= MIN_REVIEWS_FOR_STARS && s.avg != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, null, tint = Star, modifier = Modifier.size(22.dp))
                            Text(" %.1f".format(s.avg), fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
                        }
                    } else Text("✨ New", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = Color(0xFF5B21B6))
                    Text("${s.count} rating${if (s.count > 1) "s" else ""}", fontSize = 12.sp, color = Color(0xFF6B7280))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val most = (s.distribution.values.maxOrNull() ?: 1).coerceAtLeast(1)
                    for (star in 5 downTo 1) {
                        val n = s.distribution[star] ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$star", fontSize = 11.sp, modifier = Modifier.width(12.dp))
                            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFF1F5F9))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(n.toFloat() / most).background(Star))
                            }
                            Text("$n", fontSize = 11.sp, color = Color(0xFF6B7280), modifier = Modifier.width(26.dp).padding(start = 6.dp))
                        }
                    }
                }
            }
            s.recent.forEach { c ->
                HorizontalDivider(color = Color(0xFFF1F5F9))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.Star, null, tint = Star, modifier = Modifier.size(14.dp))
                        Text(" ${c.rating}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(c.comment, fontSize = 13.sp, color = Color(0xFF374151))
                }
            }
        }
    }
}

/** "How was your order?" nudge on the home screen; tapping opens that order, where the rating form already is. */
@Composable
fun ReviewPromptCard(onOpenOrder: (String) -> Unit, modifier: Modifier = Modifier) {
    var dismissed by remember { mutableStateOf(false) }
    val p by produceState<PendingReview?>(null) { value = StoresRepository.pendingReview() }
    val r = p
    if (r == null || dismissed) return
    Surface(
        shape = RoundedCornerShape(16.dp), color = Color(0xFFFFF8E1), border = BorderStroke(1.dp, Color(0xFFFFE082)),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onOpenOrder(r.orderId) }.testTag("review_prompt")
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("How was ${r.vendorName}?", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Text("★★★★★  Tap to rate your order", fontSize = 13.sp, color = Color(0xFF9A5B00))
            }
            Icon(Icons.Filled.ChevronRight, null, tint = Color(0xFF9A5B00))
            TextButton(onClick = { dismissed = true }) { Text("Later", fontSize = 12.sp) }
        }
    }
}
