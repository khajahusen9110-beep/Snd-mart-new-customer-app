package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.screens.FoodPromoBanner
import com.example.ui.screens.FreeDeliveryBanner
import com.example.ui.screens.GroceryProductCard
import com.example.ui.screens.NaturalFeaturedDealsBanner
import com.example.ui.theme.*
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
class HomeRedesignScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun product(id: String, name: String, price: Double, mrp: Double?, variants: Boolean = false, featured: Boolean = false) =
    ResolvedProduct(
      baseProduct = Product(id = id, name = name, price = price, mrp = mrp, unit = "kg", isFeatured = featured, stockQty = 50),
      effectivePrice = price, effectiveMrp = mrp, effectiveStock = 50, effectiveIsAvailable = true,
      variants = if (variants) listOf(ResolvedVariant(id = "v$id", label = "500 g", price = price, stockQty = 20, isAvailable = true)) else emptyList(),
      soldCount = if (featured) 42 else 0
    )

  @Test
  fun home_components() {
    val cats = listOf(Category(id = "1", name = "Vegitables"), Category(id = "2", name = "Fruits"), Category(id = "3", name = "Dairy & Eggs"))
    composeTestRule.setContent {
      SndmartTheme {
        CompositionLocalProvider(LocalCityTheme provides CityTheme()) {
          Column(Modifier.fillMaxWidth().background(MaterialTheme3Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SndmartTopBar(
              currentCity = City(id = "1", name = "Sindhanur", state = "Karnataka", status = "active"),
              cartCount = 3, deliveryAddressLabel = "Home", unreadNotificationCount = 27,
              onCityClick = {}, onCartClick = {}, onNotificationsClick = {}, onRefresh = {},
              walletBalance = 248.0, onWalletClick = {}
            )
            FreeDeliveryBanner(threshold = 99.0)
            NaturalFeaturedDealsBanner()
            GroceryCategoryList(categories = cats, selectedCategoryId = "1", onCategorySelected = {})
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Box(Modifier.weight(1f)) { GroceryProductCard(product("a", "Beetroot", 23.0, 25.0, variants = true, featured = true), 0, {}, {}, {}) }
              Box(Modifier.weight(1f)) { GroceryProductCard(product("b", "Brinjal long purple", 54.0, 60.0), 2, {}, {}, {}) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Box(Modifier.weight(1f)) { GroceryProductCard(product("c", "Onion", 27.0, 30.0, variants = true), 3, {}, {}, {}) }
              Box(Modifier.weight(1f)) { GroceryProductCard(product("d", "Potato", 14.0, 15.0), 0, {}, {}, {}) }
            }
          }
        }
      }
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/home_components.png")
  }

  @Test
  fun banners_and_ads() {
    val ad = LocalAd(id = "1", cityId = "c", advertiserName = "Sindhanur Mobile Store", imageUrl = "https://x/y.jpg", adType = "clickable", linkType = "website", linkValue = "https://x")
    val wa = ad.copy(id = "2", linkType = "whatsapp", advertiserName = "Fresh Dairy")
    composeTestRule.setContent {
      SndmartTheme {
        CompositionLocalProvider(LocalCityTheme provides CityTheme()) {
          Column(Modifier.fillMaxWidth().background(MaterialTheme3Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LocalAdStrip(ads = listOf(ad, wa), onOpen = {})
            FoodPromoBanner(threshold = 99.0, dishImageUrl = null, onOrder = {})
            FloatingCartButton(itemCount = 2, totalPrice = 60.0, onClick = {}, freeDeliveryAt = 99.0)
          }
        }
      }
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/banners_and_ads.png")
  }

  @Test
  fun bottom_bar() {
    composeTestRule.setContent {
      SndmartTheme {
        Column(Modifier.fillMaxWidth()) {
          androidx.compose.material3.HorizontalDivider(color = OutlineSoft)
          NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
            val c = NavigationBarItemDefaults.colors(selectedIconColor = NaturalPrimary, selectedTextColor = NaturalPrimary, indicatorColor = NaturalPrimaryContainer, unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary)
            NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Home") }, colors = c)
            NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Outlined.ShoppingCart, null) }, label = { Text("Cart") }, colors = c)
            NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Outlined.Receipt, null) }, label = { Text("Orders") }, colors = c)
            NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Outlined.Person, null) }, label = { Text("Profile") }, colors = c)
          }
        }
      }
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/bottom_bar.png")
  }

  private val MaterialTheme3Bg = BackgroundLight
}
