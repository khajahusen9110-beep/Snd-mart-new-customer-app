package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.data.model.CityThemeRow
import com.example.data.model.HomeSectionSettings
import com.example.data.repository.SndmartRepository

/**
 * City Admin controlled look of the home banners, exactly as the website applies them
 * (src/lib/theme.tsx). Anything the admin has not set falls back to the built-in colors below,
 * which are the website's defaults.
 */
data class CityTheme(
    val foodPromoStart: Color = Color(0xFFF4975A),
    val foodPromoEnd: Color = Color(0xFFDD6B74),
    val groceryBannerStart: Color = Color(0xFF4A9D68),
    val groceryBannerEnd: Color = Color(0xFF3F7A54),
    val soldBadgeStart: Color = Color(0xFFF4728A),
    val soldBadgeEnd: Color = Color(0xFFE35F79),
    /** "Ocean" accent: the Deals banner background and blue links/tags. */
    val ocean: Color = Color(0xFF3D7EA6),
    val offerBannerGroceryImageUrl: String? = null,
    val offerBannerFoodImageUrl: String? = null,
    val dealsBannerImageUrl: String? = null,
    val foodPromoBannerImageUrl: String? = null
) {
    val foodPromoBrush: Brush get() = Brush.linearGradient(listOf(foodPromoStart, foodPromoEnd))
    val groceryBannerBrush: Brush get() = Brush.linearGradient(listOf(groceryBannerStart, groceryBannerEnd))
    val soldBadgeBrush: Brush get() = Brush.linearGradient(listOf(soldBadgeStart, soldBadgeEnd))
}

/** "#rrggbb" -> Color, or the fallback for anything that is not a valid 6-digit hex. */
fun parseHexColor(hex: String?, fallback: Color): Color {
    val h = hex?.trim() ?: return fallback
    if (!Regex("^#[0-9a-fA-F]{6}$").matches(h)) return fallback
    return Color(("FF" + h.substring(1)).toLong(16))
}

fun CityThemeRow.toCityTheme(): CityTheme {
    val d = CityTheme()
    return CityTheme(
        foodPromoStart = parseHexColor(foodPromoColorStart, d.foodPromoStart),
        foodPromoEnd = parseHexColor(foodPromoColorEnd, d.foodPromoEnd),
        groceryBannerStart = parseHexColor(groceryBannerColorStart, d.groceryBannerStart),
        groceryBannerEnd = parseHexColor(groceryBannerColorEnd, d.groceryBannerEnd),
        soldBadgeStart = parseHexColor(soldBadgeColorStart, d.soldBadgeStart),
        soldBadgeEnd = parseHexColor(soldBadgeColorEnd, d.soldBadgeEnd),
        ocean = parseHexColor(oceanColor, d.ocean),
        offerBannerGroceryImageUrl = offerBannerGroceryImageUrl?.takeIf { it.startsWith("https://") },
        offerBannerFoodImageUrl = offerBannerFoodImageUrl?.takeIf { it.startsWith("https://") },
        dealsBannerImageUrl = dealsBannerImageUrl?.takeIf { it.startsWith("https://") },
        foodPromoBannerImageUrl = foodPromoBannerImageUrl?.takeIf { it.startsWith("https://") }
    )
}

/** This city's admin theme (colors + banner photos); the defaults until it loads. */
@Composable
fun rememberCityTheme(repository: SndmartRepository, cityId: String?): CityTheme {
    var theme by remember(cityId) { mutableStateOf(CityTheme()) }
    LaunchedEffect(cityId) {
        if (!cityId.isNullOrBlank()) theme = repository.getCityTheme(cityId).toCityTheme()
    }
    return theme
}

/** City Admin's home switches (Most Selling / Recently Viewed / food banner order); all on until loaded. */
@Composable
fun rememberHomeSections(repository: SndmartRepository, cityId: String?): HomeSectionSettings {
    var settings by remember(cityId) { mutableStateOf(HomeSectionSettings()) }
    LaunchedEffect(cityId) {
        if (!cityId.isNullOrBlank()) settings = repository.getHomeSectionSettings(cityId)
    }
    return settings
}
