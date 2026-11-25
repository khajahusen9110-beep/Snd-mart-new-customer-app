package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.AppBadgeCount
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

// The top of the home screens in the new look: purple header (menu, city, logo, bell, WhatsApp), Grocery / Food tabs with a
// pointer, a white pill search box and a "Shop Categories" heading. Kept out of HomeScreen on purpose: that function is at
// the compiler's size limit. Used by the Grocery (Stores) screen and by the Food / old Grocery home.

private val HeaderPurple = Color(0xFF5B2FD0)
private val HeaderPurpleDark = Color(0xFF4A22B8)
private val TabIdle = Color(0xFFF1ECFD)
private val BrandOrange = Color(0xFFFFB020)

/** Purple bar: menu · city picker · SND Mart logo · bell with count · WhatsApp. */
@Composable
fun HomeBrandHeader(
    cityName: String?,
    unreadCount: Int,
    onMenu: () -> Unit,
    onCity: () -> Unit,
    onBell: () -> Unit,
    onWhatsApp: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    Surface(color = HeaderPurple, contentColor = Color.White) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp).testTag("home_brand_header"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) leading() else IconButton(onClick = onMenu, modifier = Modifier.size(42.dp).testTag("header_menu")) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onCity).padding(horizontal = 6.dp, vertical = 6.dp).testTag("header_city"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(2.dp))
                Text(cityName ?: "Select city", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Change city", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            BrandLogo()
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onBell, modifier = Modifier.size(42.dp).testTag("header_bell")) {
                Box {
                    Icon(Icons.Outlined.Notifications, contentDescription = "Notifications", tint = Color.White, modifier = Modifier.size(26.dp))
                    AppBadgeCount(unreadCount, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
                }
            }
            IconButton(onClick = onWhatsApp, modifier = Modifier.size(42.dp).testTag("header_whatsapp")) {
                Icon(Icons.Filled.Chat, contentDescription = "Chat with us on WhatsApp", tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** App icon + "SND" (white) "Mart" (orange), heavy italic. */
@Composable
private fun BrandLogo() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("brand_logo")) {
        Image(painter = painterResource(R.drawable.sndmart_icon), contentDescription = null, modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(6.dp))
        Column {
            Text("SND", fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 18.sp, lineHeight = 17.sp, color = Color.White)
            Text("Mart", fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 18.sp, lineHeight = 17.sp, color = BrandOrange)
        }
    }
}

/** One big tab: icon, title, small line; the chosen one is purple with a pointer under it. */
@Composable
private fun RowScope.ModeTab(icon: ImageVector, title: String, sub: String, selected: Boolean, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (selected) HeaderPurple else TabIdle,
            contentColor = if (selected) Color.White else TextPrimary,
            shadowElevation = if (selected) 4.dp else 0.dp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled, onClick = onClick).testTag(tag)
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
                    Text(if (enabled) sub else "Coming soon", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) Color.White.copy(alpha = 0.85f) else TextMuted)
                }
            }
        }
        if (selected) Box(Modifier.size(width = 18.dp, height = 8.dp).background(HeaderPurple, androidx.compose.foundation.shape.GenericShape { size, _ ->
            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
        }))
    }
}

/**
 * Grocery / Food tabs (and Quick Helper when the city has it). [groceryIsStores] true: the first tab is the Grocery (Stores) screen.
 * [first] is selected when [foodSelected] is false.
 */
@Composable
fun HomeModeTabs(
    foodSelected: Boolean,
    groceryTitle: String = "Grocery",
    grocerySub: String = "Daily Needs",
    groceryEnabled: Boolean = true,
    foodEnabled: Boolean = true,
    helperOn: Boolean = false,
    onGrocery: () -> Unit,
    onFood: () -> Unit,
    onHelper: () -> Unit = {}
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).testTag("home_mode_toggle"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeTab(Icons.Filled.ShoppingCart, groceryTitle, grocerySub, selected = !foodSelected, enabled = groceryEnabled, tag = "toggle_grocery", onClick = onGrocery)
        ModeTab(Icons.Filled.Restaurant, "Food", if (helperOn) "Hot meals" else "Delicious Food", selected = foodSelected, enabled = foodEnabled, tag = "toggle_hotels", onClick = onFood)
        if (helperOn) ModeTab(Icons.Filled.Handyman, "Helper", "Any request", selected = false, tag = "toggle_helper", onClick = onHelper)
    }
}

/** White pill search box with a filter button on the right (shown only when [onFilter] is given and nothing is typed). */
@Composable
fun HomePillSearch(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    filterActive: Boolean = false,
    onFilter: (() -> Unit)? = null,
    tag: String = "home_search_input"
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        placeholder = { Text(hint, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search", tint = TextPrimary) },
        trailingIcon = {
            when {
                value.isNotEmpty() -> IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search", tint = TextMuted) }
                onFilter != null -> IconButton(onClick = onFilter) { Icon(Icons.Filled.Tune, contentDescription = "Filters", tint = if (filterActive) NaturalPrimary else TextPrimary) }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
            focusedBorderColor = NaturalPrimary, unfocusedBorderColor = Color(0xFFE5E1F2)
        ),
        modifier = modifier.fillMaxWidth().testTag(tag)
    )
}

/** "Shop Categories" style heading with a "See All →" link. */
@Composable
fun HomeSectionHeader(title: String, action: String? = "See All", onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        if (action != null) Text("$action →", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = NaturalPrimary, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(4.dp).testTag("section_see_all"))
    }
}

/** The menu that opens from the header: a list of rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeMenuSheet(items: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).testTag("home_menu_sheet")) {
            items.forEach { (label, action) ->
                Text(
                    label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary,
                    modifier = Modifier.fillMaxWidth().clickable { onDismiss(); action() }.padding(horizontal = 24.dp, vertical = 16.dp)
                )
                HorizontalDivider(color = Color(0xFFEEEAF7))
            }
        }
    }
}

/** Opens the SND Mart WhatsApp chat. */
fun openSupportWhatsApp(context: android.content.Context) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/${com.example.util.SupportConstants.BOT_WHATSAPP}?text=${android.net.Uri.encode("Hi SND Mart")}")))
    }
}
