package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.screens.FoodFilterToggleButton
import com.example.ui.screens.FoodPromoBanner
import com.example.ui.screens.FoodSearchFiltersBar
import com.example.ui.screens.MostSellingRow
import com.example.ui.screens.SearchDishCard
import com.example.ui.screens.SearchSuggestRow
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
class HomeRemainingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun product(id: String, name: String, price: Double, mrp: Double?, featured: Boolean = false) =
    ResolvedProduct(
      baseProduct = Product(id = id, name = name, price = price, mrp = mrp, unit = "kg", isFeatured = featured, stockQty = 50),
      effectivePrice = price, effectiveMrp = mrp, effectiveStock = 50, effectiveIsAvailable = true
    )

  @Test
  fun nav_bar_with_indicator() {
    composeTestRule.mainClock.autoAdvance = true
    composeTestRule.setContent {
      SndmartTheme {
        Column(Modifier.fillMaxWidth()) {
          HorizontalDivider(color = OutlineSoft)
          NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
            val c = NavigationBarItemDefaults.colors(selectedIconColor = NaturalPrimary, selectedTextColor = NaturalPrimary, indicatorColor = NaturalPrimaryContainer, unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary)
            NavigationBarItem(selected = true, onClick = {}, icon = { Box(Modifier.navIconScale(true)) { Icon(Icons.Filled.Home, null) } }, label = { Text("Home") }, colors = c, modifier = Modifier.navTopIndicator(true))
            NavigationBarItem(selected = false, onClick = {}, icon = { Box(Modifier.navIconScale(false)) { Icon(Icons.Outlined.ShoppingCart, null) } }, label = { Text("Cart") }, colors = c, modifier = Modifier.navTopIndicator(false))
            NavigationBarItem(selected = false, onClick = {}, icon = { Box(Modifier.navIconScale(false)) { Icon(Icons.Outlined.Receipt, null) } }, label = { Text("Orders") }, colors = c, modifier = Modifier.navTopIndicator(false))
            NavigationBarItem(selected = false, onClick = {}, icon = { Box(Modifier.navIconScale(false)) { Icon(Icons.Outlined.Person, null) } }, label = { Text("Profile") }, colors = c, modifier = Modifier.navTopIndicator(false))
          }
        }
      }
    }
    composeTestRule.mainClock.advanceTimeBy(1500)
    composeTestRule.waitForIdle()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/home_nav_indicator.png")
  }

  @Test
  fun header_badges_settled() {
    composeTestRule.setContent {
      SndmartTheme {
        SndmartTopBar(
          currentCity = City(id = "1", name = "Sindhanur", state = "Karnataka", status = "active"),
          cartCount = 3, deliveryAddressLabel = "Home", unreadNotificationCount = 27,
          onCityClick = {}, onCartClick = {}, onNotificationsClick = {}, onRefresh = {},
          walletBalance = 248.0, onWalletClick = {}
        )
      }
    }
    composeTestRule.mainClock.advanceTimeBy(1500)
    composeTestRule.waitForIdle()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/home_header_badges.png")
  }

  @Test
  fun coupon_banner_and_featured_row() {
    val coupon = Coupon(id = "c1", code = "WELCOME50", discountType = "percentage", discountValue = 50.0, maxDiscountAmount = 100.0, minOrderAmount = 199.0, rewardType = "cashback")
    val flat = Coupon(id = "c2", code = "SAVE30", discountType = "flat", discountValue = 30.0, minOrderAmount = 0.0)
    composeTestRule.setContent {
      SndmartTheme {
        CompositionLocalProvider(LocalCityTheme provides CityTheme()) {
          Column(Modifier.fillMaxWidth().background(BackgroundLight).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FoodPromoBanner(threshold = 99.0, dishImageUrl = null, onOrder = {}, coupon = coupon, onCopyCode = {})
            FoodPromoBanner(threshold = 99.0, dishImageUrl = null, onOrder = {}, coupon = flat, onCopyCode = {})
            MostSellingRow(
              products = listOf(product("a", "Beetroot", 23.0, 25.0, true), product("b", "Brinjal", 54.0, 60.0, true), product("c", "Onion", 27.0, 30.0, true)),
              groceryCart = emptyList(), onIncrease = {}, onDecrease = {}, onSelectSize = {}, title = "Featured Products"
            )
          }
        }
      }
    }
    composeTestRule.mainClock.advanceTimeBy(1000)
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/home_coupon_featured.png")
  }

  @Test
  fun search_cards_and_chips() {
    val dish = product("d1", "Chicken Dum Biryani", 249.0, 299.0).copy(
      soldVendorName = "Hotel Bawarchi", vendorRating = 4.3, guaranteedAvailable = true, discountPercent = 17.0,
      baseProduct = Product(id = "d1", name = "Chicken Dum Biryani", price = 249.0, mrp = 299.0, unit = "plate", isVeg = false, stockQty = 50)
    )
    composeTestRule.setContent {
      SndmartTheme {
        Column(Modifier.fillMaxWidth().background(BackgroundLight).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          SearchSuggestRow(recentSearches = listOf("biryani", "dosa"), trendingSearches = listOf("chicken biryani", "veg thali", "rolls"), onPick = {})
          Row { FoodFilterToggleButton(active = false, onClick = {}); Spacer(Modifier.width(8.dp)); FoodFilterToggleButton(active = true, onClick = {}) }
          FoodSearchFiltersBar(sort = "relevance", onSortChange = {}, veg = "nonveg", onVegChange = {}, budgetOnly = true, onBudgetToggle = {}, ratingOnly = false, onRatingToggle = {}, offersOnly = false, onOffersToggle = {})
          SearchDishCard(product = dish, quantity = 0, onOpenHotel = {}, onAdd = {})
          SearchDishCard(product = dish, quantity = 2, onOpenHotel = {}, onAdd = {})
        }
      }
    }
    composeTestRule.mainClock.advanceTimeBy(1000)
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/home_search_cards.png")
  }
}
