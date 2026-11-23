package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.*
import com.example.data.repository.AddToCartResult
import com.example.data.repository.SndmartRepository
import com.example.data.repository.StoresRepository
import com.example.ui.theme.*
import kotlinx.coroutines.delay

// Stores screens: home (search, categories, nearby shops), the shops of one category, and one shop.
// Everything is decided by the server; these only show it. Same flow as the website.

private val SORTS = listOf("cheapest" to "Cheapest", "nearest" to "Nearest", "relevance" to "Best match")

/** Third/fourth tab pill of the Stores screen: Stores (current) · Hotel Food · Quick Helper. */
@Composable
private fun StoresModeTabs(helperOn: Boolean, onFood: () -> Unit, onHelper: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = SurfaceVariantLight, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(shape = RoundedCornerShape(11.dp), color = androidx.compose.ui.graphics.Color(0xFFDCFCE7), shadowElevation = 2.dp, modifier = Modifier.weight(1f)) {
                Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Text("Grocery", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFF15803D)) }
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).clickable(onClick = onFood).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("Hotel Food", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextSecondary)
            }
            if (helperOn) Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).clickable(onClick = onHelper).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("Quick Helper", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextSecondary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoresScreen(
    repository: SndmartRepository,
    cityId: String?,
    cityName: String?,
    helperOn: Boolean,
    onOpenCategory: (String) -> Unit,
    onOpenStore: (String) -> Unit,
    onOpenGrocery: () -> Unit,
    onOpenFood: () -> Unit,
    onOpenHelper: () -> Unit,
    onOpenAddresses: () -> Unit,
    onNotAvailable: () -> Unit
) {
    val cfg by produceState<StoresConfig?>(null, cityId) { value = StoresRepository.getConfig(cityId, force = true) }
    var loc by remember { mutableStateOf<StoreLoc?>(null) }
    var locDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { loc = StoresRepository.customerLocation(repository); locDone = true }
    LaunchedEffect(cfg) { if (cfg != null && cfg?.enabled != true) onNotAvailable() }

    var stores by remember { mutableStateOf<List<StoreListItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var catId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(cityId, cfg?.enabled, locDone, loc, retry, catId) {
        val id = cityId ?: return@LaunchedEffect
        if (cfg?.enabled != true || !locDone) return@LaunchedEffect
        error = null
        stores = null
        StoresRepository.listStores(id, loc, catId).onSuccess { stores = it }.onFailure { error = it.message }
    }

    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("cheapest") }
    var hits by remember { mutableStateOf<List<StoreProductHit>?>(null) }
    LaunchedEffect(text) { delay(350); query = text.trim() }
    LaunchedEffect(cityId, cfg?.enabled, locDone, loc, query, sort) {
        val id = cityId ?: return@LaunchedEffect
        if (cfg?.enabled != true || !locDone || query.length < 2) { hits = null; return@LaunchedEffect }
        StoresRepository.search(id, loc, query, sort).onSuccess { hits = it }.onFailure { error = it.message }
    }

    val shops = remember(stores) { stores.orEmpty().sortedWith(compareBy<StoreListItem>({ !it.isOpen }, { it.distanceKm ?: 999.0 }, { it.name })) }
    val searching = query.length >= 2

    Scaffold(topBar = {
        Surface(color = androidx.compose.ui.graphics.Color.White, shadowElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xFFDCFCE7)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Storefront, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFF15803D), modifier = Modifier.size(22.dp))
                }
                Column {
                    Text("Grocery", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 22.sp)
                    if (!cityName.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = TextMuted, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(cityName, fontSize = 13.sp, color = TextMuted, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("stores_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            item { StoresModeTabs(helperOn, onOpenFood, onOpenHelper) }
            item {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, shape = RoundedCornerShape(24.dp),
                    placeholder = { Text("Search products in all shops") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } },
                    modifier = Modifier.fillMaxWidth().testTag("stores_search")
                )
            }
            if (locDone && loc == null) item {
                Surface(shape = RoundedCornerShape(14.dp), color = SurfaceLight, border = BorderStroke(1.dp, OutlineSoft), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAddresses)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = NaturalPrimary)
                        Column(Modifier.weight(1f)) {
                            Text("Add your address", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Then we show the shops nearest to you first.", fontSize = 12.sp, color = TextMuted)
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted)
                    }
                }
            }
            error?.let { msg ->
                item {
                    Surface(shape = RoundedCornerShape(12.dp), color = androidx.compose.ui.graphics.Color(0xFFFEE2E2), modifier = Modifier.fillMaxWidth().clickable { retry++ }) {
                        Text("$msg  (tap to retry)", color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            if (!searching && cfg?.categories.orEmpty().isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.testTag("store_category_row")) {
                    item { RoundCat("Recommended", null, catId == null) { catId = null } }
                    items(cfg?.categories.orEmpty(), key = { it.id }) { c -> RoundCat(c.name, c.imageUrl, catId == c.id) { catId = c.id } }
                }
            }
            if (searching) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SORTS) { (k, label) -> StoreChip(label, sort == k) { sort = k } } }
                }
                if (hits == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                hits?.let { list ->
                    if (list.isEmpty()) item { StoresEmpty("Nothing found", "No open shop near you has “$query” right now.") }
                    items(list, key = { it.storeId + ":" + it.productId }) { h -> StoreProductRow(h) { onOpenStore(h.storeId) } }
                }
            } else {
                if (catId == null && cfg?.replaceGrocery == true) item {
                    Surface(shape = RoundedCornerShape(16.dp), color = NaturalPrimaryContainer, border = BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.3f)), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenGrocery).testTag("snd_mart_store_card")) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StorePhoto(null, 56)
                            Column(Modifier.weight(1f)) {
                                Text("SND Mart Store", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("Fresh groceries & fruits from SND Mart", fontSize = 12.sp, color = TextMuted)
                            }
                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted)
                        }
                    }
                }
                val title = cfg?.categories?.firstOrNull { it.id == catId }?.name ?: "Recommended shops"
                item { Text(title + if (shops.isNotEmpty()) " · ${shops.size}" else "", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                if (stores == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (stores != null && shops.isEmpty()) item { StoresEmpty("No shops here yet", "We show shops within ${(cfg?.radiusKm ?: 5.0).toInt()} km of you. Please check again soon.") }
                items(shops, key = { it.storeId }) { s -> StoreCard(s) { onOpenStore(s.storeId) } }
                if (shops.isNotEmpty()) item {
                    Text("Open shops first" + (if (loc != null) " · nearest first" else "") + " · within ${(cfg?.radiusKm ?: 5.0).toInt()} km", fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Round shop-category icon (image from the City Admin, first letter when there is none). */
@Composable
private fun RoundCat(name: String, imageUrl: String?, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(74.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).testTag("store_round_category"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape).background(NaturalPrimaryContainer)
                .border(if (selected) 2.dp else 0.dp, if (selected) NaturalPrimary else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) AsyncImage(imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else if (name == "Recommended") Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(28.dp))
            else Text(name.take(1), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = NaturalPrimary)
        }
        Text(name, fontSize = 12.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium, color = if (selected) NaturalPrimary else TextSecondary, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 14.sp)
    }
}

@Composable
private fun CategoryCard(c: StoreCategory, modifier: Modifier, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceLight, border = BorderStroke(1.dp, OutlineSoft), modifier = modifier.clickable(onClick = onClick).testTag("store_category_card")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(NaturalPrimaryContainer), contentAlignment = Alignment.Center) {
                if (c.imageUrl != null) AsyncImage(c.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(c.name.take(1), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = NaturalPrimary)
            }
            Spacer(Modifier.height(4.dp))
            Text(c.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2)
            if (c.nameKn != null) Text(c.nameKn, fontSize = 12.sp, color = TextMuted, maxLines = 1)
            Text("${c.storeCount} shop${if (c.storeCount == 1) "" else "s"}", fontSize = 12.sp, color = TextMuted)
        }
    }
}

@Composable
private fun StoresEmpty(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Storefront, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(text, fontSize = 13.sp, color = TextMuted, textAlign = TextAlign.Center)
    }
}

/** The shops of one category: open first, the nearest on top (marked "Nearest"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreCategoryScreen(
    repository: SndmartRepository, cityId: String?, categoryId: String,
    onBack: () -> Unit, onOpenStore: (String) -> Unit
) {
    val cfg by produceState<StoresConfig?>(null, cityId) { value = StoresRepository.getConfig(cityId) }
    var stores by remember { mutableStateOf<List<StoreListItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(cityId, categoryId, retry) {
        val id = cityId ?: return@LaunchedEffect
        error = null
        val loc = StoresRepository.customerLocation(repository)
        StoresRepository.listStores(id, loc, categoryId).onSuccess { stores = it }.onFailure { error = it.message }
    }
    val cat = cfg?.categories?.firstOrNull { it.id == categoryId }
    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text(cat?.name ?: "Shops", fontWeight = FontWeight.Bold); if (cat?.nameKn != null) Text(cat.nameKn, fontSize = 12.sp, color = TextMuted) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("store_category_screen"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            error?.let { msg -> item { Text("$msg  (tap to retry)", color = ErrorRed, modifier = Modifier.clickable { retry++ }) } }
            if (stores == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (stores != null && stores!!.isEmpty()) item { StoresEmpty("No shops here yet", "We show shops within ${(cfg?.radiusKm ?: 5.0).toInt()} km of you.") }
            items(stores.orEmpty(), key = { it.storeId }) { s -> StoreCard(s) { onOpenStore(s.storeId) } }
        }
    }
}

/** One shop: its details, its products by category, and the cart (one shop at a time, like a hotel). */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StoreDetailScreen(repository: SndmartRepository, cityId: String?, storeId: String, onBack: () -> Unit, onCheckout: () -> Unit) {
    var menu by remember { mutableStateOf<StoreMenu?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var cat by remember { mutableStateOf<String?>(null) } // null = Recommended
    var find by remember { mutableStateOf("") }
    val cart by repository.hotelCart.collectAsState()
    var conflict by remember { mutableStateOf<AddToCartResult.HotelConflict?>(null) }
    var customising by remember { mutableStateOf<StoreMenuProduct?>(null) }
    LaunchedEffect(storeId, retry) {
        error = null
        StoresRepository.getMenu(storeId).onSuccess { menu = it; StoresRepository.rememberStore(storeId) }.onFailure { error = it.message }
    }
    val m = menu
    val mine = cart.filter { it.vendorId == storeId }
    fun qty(id: String) = mine.filter { it.productId == id }.sumOf { it.quantity }
    val priceOf = { id: String -> m?.products?.firstOrNull { it.id == id }?.price ?: 0.0 }
    fun lineUnit(ci: com.example.data.model.CartItem): Double {
        val p = m?.products?.firstOrNull { it.id == ci.productId }
        val extra = if (!ci.customNote.isNullOrBlank() || !ci.customPhotoPath.isNullOrBlank()) (p?.customExtraCharge ?: 0.0) else 0.0
        return (p?.variants?.firstOrNull { it.id == ci.variantId }?.price ?: p?.price ?: 0.0) + extra
    }
    val cartCount = mine.sumOf { it.quantity }
    val cartTotal = mine.sumOf { lineUnit(it) * it.quantity }
    fun change(p: StoreMenuProduct, delta: Int) {
        if (delta > 0) repository.sessionManager.cacheCartProductSnapshot(p.id, null, p.name, p.imageUrl, p.unit)
        // a shop's minimum order: the first add brings the minimum, and going below it removes the line
        val cur = qty(p.id)
        var d = delta
        if (delta > 0 && cur == 0) d = p.minOrderQty
        if (delta < 0 && cur > 0 && cur - 1 < p.minOrderQty) d = -cur
        val res = repository.addToCart(productId = p.id, vendorId = storeId, cityId = cityId, quantityDelta = d, isHotel = true)
        if (res is AddToCartResult.HotelConflict) conflict = res
    }
    fun addCustom(p: StoreMenuProduct, note: String?, photo: String?, q: Int, variantId: String?) {
        // a plain customisable product already in the cart: only its message / photo change
        if (!p.hasVariants && qty(p.id) > 0) { repository.setCartCustomization(p.id, null, note, photo); return }
        val label = p.availableVariants.firstOrNull { it.id == variantId }?.label
        repository.sessionManager.cacheCartProductSnapshot(p.id, variantId, if (label != null) "${p.name} - $label" else p.name, p.imageUrl, p.unit)
        val res = repository.addToCart(productId = p.id, vendorId = storeId, cityId = cityId, variantId = variantId, quantityDelta = q, isHotel = true, customNote = note, customPhotoPath = photo)
        if (res is AddToCartResult.HotelConflict) conflict = res
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(m?.name ?: "Shop", fontWeight = FontWeight.Bold); if (m?.categoryName != null) Text(m.categoryName, fontSize = 12.sp, color = TextMuted) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        bottomBar = {
            if (cartCount > 0) Surface(color = NaturalPrimary, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(12.dp).clickable(onClick = onCheckout).testTag("store_checkout_bar")) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$cartCount item${if (cartCount > 1) "s" else ""} • ${rupee(cartTotal)}", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Checkout", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.ExtraBold)
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("store_detail_screen"), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            error?.let { msg -> item { Text("$msg  (tap to retry)", color = ErrorRed, modifier = Modifier.clickable { retry++ }) } }
            if (m == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (m != null) {
                if (m.bannerUrl != null) item { AsyncImage(m.bannerUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp))) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (m.isOpen) StoreBadge("Open now", androidx.compose.ui.graphics.Color(0xFFDCFCE7), androidx.compose.ui.graphics.Color(0xFF166534))
                            else StoreBadge("Closed now", androidx.compose.ui.graphics.Color(0xFFFEE2E2), androidx.compose.ui.graphics.Color(0xFF991B1B))
                            if (m.prepMinutes != null) Text("packs in about ${m.prepMinutes} min", fontSize = 12.sp, color = TextMuted)
                        }
                        if (m.address != null) Text("📍 " + m.address, fontSize = 12.sp, color = TextMuted)
                    }
                }
                item {
                    OutlinedTextField(
                        value = find, onValueChange = { find = it }, singleLine = true, shape = RoundedCornerShape(24.dp),
                        placeholder = { Text("Search in ${m.name}") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = { if (find.isNotEmpty()) IconButton(onClick = { find = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } },
                        modifier = Modifier.fillMaxWidth().testTag("store_find")
                    )
                }
                if (find.isBlank()) stickyHeader {
                    Surface(color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.fillMaxWidth()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp).testTag("store_product_chips")) {
                            item { StoreChip("Recommended", cat == null) { cat = null } }
                            items(m.categories) { (id, name) -> StoreChip(name, cat == id) { cat = id } }
                        }
                    }
                }
                // Recommended = the shop's best picks: products on offer first, then the rest in the shop's own order
                val shown = when {
                    find.isNotBlank() -> m.products.filter { it.name.contains(find.trim(), ignoreCase = true) }
                    cat == null -> m.products.filter { it.isAvailable }.withIndex().sortedWith(compareBy({ if (it.value.mrp != null && it.value.mrp!! > it.value.price) 0 else 1 }, { it.index })).map { it.value }.take(12)
                    else -> m.products.filter { it.categoryId == cat }
                }
                if (shown.isEmpty()) item { StoresEmpty("No products yet", "This shop has not added products here.") }
                items(shown, key = { it.id }) { p -> MenuProductRow(p, canOrder = m.isOpen, quantity = qty(p.id), onCustomise = { customising = p }) { d -> change(p, d) } }
            }
        }
    }
    customising?.let { p ->
        val line = if (p.hasVariants) null else mine.firstOrNull { it.productId == p.id }
        CustomiseSheet(
            product = p, repository = repository, initialNote = line?.customNote, initialPhotoPath = line?.customPhotoPath,
            initialQty = line?.quantity ?: 1, editing = line != null && !p.hasVariants,
            onDismiss = { customising = null },
            onDone = { note, photo, q, vid -> addCustom(p, note, photo, q, vid); customising = null }
        )
    }
    conflict?.let { c ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Replace your cart?") },
            text = { Text("Your cart already has items from another shop or hotel. Clear it and add from ${m?.name ?: "this shop"}?") },
            confirmButton = { TextButton(onClick = { repository.forceClearHotelCartAndAdd(c.pendingItem); conflict = null }) { Text("Clear & Add") } },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MenuProductRow(p: StoreMenuProduct, canOrder: Boolean, quantity: Int, onCustomise: () -> Unit, onChange: (Int) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = SurfaceLight, border = BorderStroke(1.dp, OutlineSoft), modifier = Modifier.fillMaxWidth().testTag("store_menu_product")) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StorePhoto(p.imageUrl, 56)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(p.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2)
                val unitLine = listOfNotNull(p.unit, p.unitPrice?.let { "${rupee(it)}/${p.unitLabel}" }).joinToString(" · ")
                if (unitLine.isNotBlank()) Text(unitLine, fontSize = 12.sp, color = TextMuted)
                if (p.minOrderQty > 1) Text("Minimum order ${p.minOrderQty}", fontSize = 11.sp, color = TextMuted)
                if (!p.isAvailable) StoreBadge("Out of stock", androidx.compose.ui.graphics.Color(0xFFFEE2E2), androidx.compose.ui.graphics.Color(0xFF991B1B))
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (p.hasVariants) "from ${rupee(p.fromPrice)}" else rupee(p.price), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                if (!p.hasVariants && p.mrp != null && p.mrp > p.price) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(rupee(p.mrp), fontSize = 11.sp, color = TextMuted, textDecoration = TextDecoration.LineThrough)
                    StoreBadge("${p.discountPercent}% OFF", androidx.compose.ui.graphics.Color(0xFFDCFCE7), androidx.compose.ui.graphics.Color(0xFF166534))
                }
                if (p.isAvailable && canOrder) {
                    if (p.isCustomizable || p.hasVariants) FilledTonalButton(onClick = onCustomise, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp), modifier = Modifier.height(32.dp).testTag("store_customise_button")) { Text(if (quantity > 0) "$quantity in cart · ${if (p.hasVariants) "Add" else "Edit"}" else if (p.hasVariants) "Choose" else "Customise", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
                    else if (quantity > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onChange(-1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Remove, contentDescription = "Remove one") }
                        Text("$quantity", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                        IconButton(onClick = { onChange(1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Add, contentDescription = "Add one") }
                    } else FilledTonalButton(onClick = { onChange(1) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp), modifier = Modifier.height(32.dp).testTag("store_add_button")) { Text("Add", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
                }
            }
        }
    }
}
