package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.data.model.StoreCardInfo
import com.example.data.model.StoreListItem
import com.example.ui.screens.StoreCard
import com.example.ui.screens.StoresFootnote
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
class StoreCardScreenshotTest {
  @get:Rule val composeTestRule = createComposeRule()

  private fun item(id: String, name: String, km: Double?, open: Boolean, nearest: Boolean) =
    StoreListItem(id, name, null, null, "c", "Bakery & Cakes", km, open, nearest)

  @Test fun store_cards() {
    composeTestRule.setContent {
      SndmartTheme {
        Column(Modifier.fillMaxWidth().background(Color(0xFFF5F5F8)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          StoreCard(item("1", "new bakery", 0.0, true, true)) {}
          StoreCard(item("2", "HKGN Fruit marchant", 1.4, true, false), StoreCardInfo("2", 4.3, 18, true, 99.0)) {}
          StoreCard(item("3", "Sri Venkatesh Veg", 4.4, false, false), StoreCardInfo("3", null, 0, false, null)) {}
          StoresFootnote("Open shops first · nearest first · within 5 km")
        }
      }
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/store_cards.png")
  }
}
