package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.ui.screens.HomeBrandHeader
import com.example.ui.screens.HomeModeTabs
import com.example.ui.screens.HomePillSearch
import com.example.ui.screens.HomeSectionHeader
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
class HomeTopBlocksScreenshotTest {
  @get:Rule val composeTestRule = createComposeRule()

  @Composable private fun Top(foodSelected: Boolean, helper: Boolean) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
      HomeBrandHeader(cityName = "Sindhanur", unreadCount = 2, onMenu = {}, onCity = {}, onBell = {}, onWhatsApp = {})
      HomeModeTabs(foodSelected = foodSelected, helperOn = helper, onGrocery = {}, onFood = {}, onHelper = {})
      HomePillSearch(value = "", onChange = {}, hint = "Search products, stores or food", modifier = Modifier.padding(horizontal = 16.dp), onFilter = {})
      Spacer(Modifier.height(12.dp))
      Box(Modifier.padding(horizontal = 16.dp)) { HomeSectionHeader("Shop Categories", "See All") }
    }
  }

  @Test fun grocery_top() {
    composeTestRule.setContent { SndmartTheme { Top(false, false) } }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/home_top_grocery.png")
  }

  @Test fun food_top_with_helper() {
    composeTestRule.setContent { SndmartTheme { Top(true, true) } }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/home_top_food_helper.png")
  }
}
