package com.example.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Order
import com.example.data.model.Product
import com.example.data.model.ResolvedProduct
import com.example.ui.screens.MenuGridCard
import com.example.ui.screens.OrderCardItem
import com.example.ui.screens.OrderStatusBadge
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.NaturalPrimaryDark
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
class ScreensScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun dish(id: String, name: String, price: Double, featured: Boolean) = ResolvedProduct(
        baseProduct = Product(id = id, name = name, price = price, isFeatured = featured, vendorId = "v1"),
        effectivePrice = price,
        effectiveMrp = null,
        effectiveStock = 50,
        effectiveIsAvailable = true
    )

    @Test
    fun featured_row_and_orders() {
        rule.setContent {
            SndmartTheme {
                Column(Modifier.fillMaxWidth().background(Color(0xFFF5F5F8)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Featured", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MenuGridCard(dish("1", "Chicken Dum Biryani", 220.0, true), 0, modifier = Modifier.width(158.dp), tagPrefix = "featured_", onIncrease = {}, onDecrease = {})
                        MenuGridCard(dish("2", "Half Mutton Biryani", 311.0, true), 2, modifier = Modifier.width(158.dp), tagPrefix = "featured_", onIncrease = {}, onDecrease = {})
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("pending", "preparing", "out_for_delivery", "delivered", "cancelled").forEach { OrderStatusBadge(it) }
                    }
                    OrderCardItem(order = Order(id = "o1", orderNumber = "SND-1042", status = "out_for_delivery", totalAmount = 321.0, platformFee = 10.0, handlingFee = 5.0), hotelName = "Hotel Bawarchi", onClick = {})
                    // wallet balance card gradient (same recipe as WalletScreen)
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = Color.White), elevation = CardDefaults.cardElevation(6.dp)) {
                        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(NaturalPrimary, NaturalPrimaryDark))).padding(22.dp)) {
                            Text("WALLET BALANCE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
                            Text("₹248", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        }
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/screens_featured_orders.png")
    }
}
