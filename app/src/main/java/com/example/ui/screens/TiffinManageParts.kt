package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TiffinPlan
import com.example.data.repository.TiffinRepository
import com.example.ui.theme.*
import com.example.util.rupees

// "My Plans" rows, the Manage screen's payment details and the "your plan is ready" screen.
// Small functions on purpose (Kotlin compiler size limit).

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "school" -> Icons.Filled.School
    "office" -> Icons.Filled.Business
    else -> Icons.Filled.Restaurant
}

@Composable
private fun planFact(plan: TiffinPlan): String {
    val lang = LocalTiffinLang.current
    val today = TiffinRepository.indiaToday()
    val next = if (plan.status == "active") {
        (listOf(today) + TiffinRepository.upcomingPlanDays(plan, 3)).firstOrNull {
            it >= plan.startDate && it <= plan.endDate && TiffinRepository.isoWeekday(it) in plan.days && it !in plan.skips
        }
    } else null
    return when {
        plan.status == "pending_payment" ->
            if (plan.isTrial) tx("payTrialBtn", rupees(plan.trialFee)) else tx("btnPay", rupees(plan.totalAmount))
        plan.status == "active" && plan.startDate > today -> tx("startsOnDate", TiffinRepository.dayLabel(plan.startDate, lang))
        plan.status == "active" && next != null ->
            tx("nextPickup") + ": " + TiffinRepository.dayLabel(next, lang) + ", " + TiffinRepository.timeLabel(plan.pickupTime)
        plan.status == "completed" || plan.status == "cancelled" -> tx("endedOn", TiffinRepository.dayLabel(plan.endDate, lang))
        else -> tx(statusKey(plan.status))
    }
}

/** One plan in the "My Plans" list: who, where, and the one fact that matters for its state. */
@Composable
internal fun TiffinPlanRow(plan: TiffinPlan, onOpen: () -> Unit) {
    Card(
        shape = AppCardShape, colors = appCardColors(), border = AppCardBorder,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("tiffin_plan_row")
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFECE9FF)), contentAlignment = Alignment.Center) {
                Icon(kindIcon(plan.kind), null, tint = NaturalPrimary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(plan.recipientName, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Text(plan.dropLabel ?: plan.dropAddress, fontSize = 12.sp, color = TextSecondary, maxLines = 1)
                Text(planFact(plan), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                tx(statusKey(plan.status)), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NaturalPrimary,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFECE9FF)).padding(horizontal = 10.dp, vertical = 4.dp)
            )
            Icon(Icons.Filled.ChevronRight, null, tint = TextSecondary)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Money facts of one plan: distance, monthly price, trial, auto-pay, months paid, next charge. */
@Composable
internal fun TiffinPaymentDetails(plan: TiffinPlan, titleKey: String = "paymentDetails") {
    val lang = LocalTiffinLang.current
    Card(shape = AppCardShape, colors = appCardColors(), border = AppCardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tx(titleKey), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            if (plan.distanceKm > 0.0) DetailLine(tx("distanceLbl"), tx("kmValue", "%.1f".format(plan.distanceKm)))
            DetailLine(tx("monthlyPriceLbl"), rupees(plan.totalAmount))
            if (plan.isTrial) DetailLine(tx("trialLbl"), tx("trialValue", plan.trialDays, rupees(plan.trialFee)))
            if (plan.autopayStatus != "none") DetailLine(tx("autopayLbl"), if (plan.autopayLive) tx("autopayOn") else tx("autopayOff"))
            if (plan.billingCyclesPaid > 0) DetailLine(tx("monthsPaid"), plan.billingCyclesPaid.toString())
            if (plan.autopayLive && plan.nextChargeDate != null)
                Text(tx("nextPay", rupees(plan.totalAmount), TiffinRepository.dayLabel(plan.nextChargeDate, lang)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** After the payment screen: green tick when paid, amber "saved, pay to start" when not. */
@Composable
internal fun TiffinReadyView(paid: Boolean, plan: TiffinPlan?, onView: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(78.dp).clip(CircleShape).background(if (paid) Color(0xFF22C55E) else Color(0xFFF59E0B)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
        Text(tx(if (paid) "readyTitle" else "savedTitle"), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Text(tx(if (paid) "readySub" else "savedSub"), color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (plan != null) TiffinPaymentDetails(plan, "planDetails")
        Button(
            onClick = onView,
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag("tiffin_view_plan"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary)
        ) { Text(tx("viewMyPlan") + "  →", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
    }
}
