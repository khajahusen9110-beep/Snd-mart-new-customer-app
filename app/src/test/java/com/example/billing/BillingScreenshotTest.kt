package com.example.billing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.BillRow
import com.example.ui.components.OrderSuccessScreen
import com.example.ui.screens.AppSoundsRow
import com.example.ui.screens.TaxesChargesRow
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.OutlineSoft
import com.example.ui.theme.SndmartTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class BillingScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Composable
    private fun BillCard(open: Boolean) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, OutlineSoft)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Bill Details", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                BillRow("Chicken Masala × 1", "₹322")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                BillRow("Item total", "₹322")
                BillRow("Offer 5% OFF", "−₹16", color = Color(0xFF1A8A3F))
                BillRow("Delivery Fee", "FREE", color = NaturalPrimary)
                TaxesChargesRow(handlingFee = 5.0, platformFee = 10.0, initiallyOpen = open)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                BillRow("To Pay", "₹321", isBold = true, color = NaturalPrimary)
            }
        }
    }

    @Test
    fun taxes_collapsed() {
        rule.setContent { SndmartTheme { Box(Modifier.background(MaterialTheme.colorScheme.background)) { BillCard(open = false) } } }
        rule.onRoot().captureRoboImage("src/test/screenshots/billing_taxes_collapsed.png")
    }

    @Test
    fun taxes_expanded() {
        rule.setContent { SndmartTheme { Box(Modifier.background(MaterialTheme.colorScheme.background)) { BillCard(open = true) } } }
        rule.onRoot().captureRoboImage("src/test/screenshots/billing_taxes_expanded.png")
    }

    @Test
    fun order_success_frame() {
        rule.setContent { SndmartTheme { OrderSuccessScreen(progressOverride = 0.55f) } }
        rule.onRoot().captureRoboImage("src/test/screenshots/billing_order_success.png")
    }

    @Test
    fun profile_sound_row() {
        rule.setContent {
            SndmartTheme {
                Column(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, OutlineSoft)
                    ) {
                        Column {
                            AppSoundsRow(checked = true, onToggle = {})
                            HorizontalDivider(color = OutlineSoft)
                            AppSoundsRow(checked = false, onToggle = {})
                        }
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage("src/test/screenshots/billing_sound_rows.png")
    }
}
