package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import com.example.data.model.StoreMenu
import com.example.data.model.StoreMenuProduct
import com.example.data.model.StoreRatingSummary
import com.example.ui.screens.*
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
class StoreHeaderScreenshotTest {
  @get:Rule val composeTestRule = createComposeRule()

  @Test fun store_header() {
    val m = StoreMenu("1", "new bakery", null, "Main Road, Sindhanur", "Bakery & Cakes", true, 20,
      listOf("c1" to "Cakes", "c2" to "Cookies"), emptyList<StoreMenuProduct>(), "9999999999")
    val rating = StoreRatingSummary(4.6, 10, mapOf(1 to 0, 2 to 1, 3 to 0, 4 to 1, 5 to 8), emptyList())
    composeTestRule.setContent {
      SndmartTheme {
        Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
          StoreHero(m)
          StoreInfoCard(m, rating)
          StoreInfoStrip(m)
          StoreCategoryTiles(m, null) {}
          StoreBottomBar("9999999999", 2, 640.0) {}
        }
      }
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/store_header.png")
  }
}
