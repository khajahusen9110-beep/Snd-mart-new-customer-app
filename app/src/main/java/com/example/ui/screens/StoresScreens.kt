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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

/** Third/fourth tab pill of the Stores screen: Stores (current) · Hotel Food · Tiffin. */
@Composable
private fun StoresModeTabs(tiffinOn: Boolean, onFood: () -> Unit, onTiffin: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = SurfaceVariantLight, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(shape = RoundedCornerShape(11.dp), color = androidx.compose.ui.graphics.Color.White, shadowElevation = 3.dp, modifier = Modifier.weight(1f)) {
                Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Text("Stores", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = NaturalPrimary) }
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).clickable(onClick = onFood).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("Hotel Food", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextSecondary)
            }
            if (tiffinOn) Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).clickable(onClick = onTiffin).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("Tiffin Pickup", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextSecondary)
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
    tiffinOn: Boolean,
    onOpenCategory: (String) -> Unit,
    onOpenStore: (String) -> Unit,
    onOpenGrocery: () -> Unit,
    onOpenFood: () -> Unit,
    onOpenTiffin: () -> Unit,
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
    LaunchedEffect(cityId, cfg?.enabled, locDone, loc, retry) {
        val id = cityId ?: return@LaunchedEffect
        if (cfg?.enabled != true || !locDone) return@LaunchedEffect
        error = null
        StoresRepository.listStores(id, loc, null).onSuccess { stores = it }.onFailure { error = it.message }
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

    val nearby = remember(stores) { stores.orEmpty().filter { it.isOpen }.sortedBy { it.distanceKm ?: 999.0 }.take(6) }
    val searching = query.length >= 2

    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("Stores", fontWeight = FontWeight.Bold); if (!cityName.isNullOrBlank()) Text(cityName, fontSize = 12.sp, color = TextMuted) } })
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("stores_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            item { StoresModeTabs(tiffinOn, onOpenFood, onOpenTiffin) }
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
                if (cfg?.replaceGrocery == true) item {
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
                val cats = cfg?.categories.orEmpty()
                if (cats.isNotEmpty()) {
                    item { Text("Shop by category", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                    items(cats.chunked(2)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { c -> CategoryCard(c, Modifier.weight(1f)) { onOpenCategory(c.id) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                item { Text("Shops near you", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                if (stores == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (stores != null && nearby.isEmpty()) item { StoresEmpty("No shops open nearby", "We show shops within ${(cfg?.radiusKm ?: 5.0).toInt()} km. Please check again soon.") }
                items(nearby, key = { it.storeId }) { s -> StoreCard(s) { onOpenStore(s.storeId) } }
                if (nearby.isNotEmpty()) item {
                    Text("Showing shops within ${(cfg?.radiusKm ?: 5.0).toInt()} km" + if (loc != null) " · nearest first" else "", fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreDetailScreen(repository: SndmartRepository, cityId: String?, storeId: String, onBack: () -> Unit, onCheckout: () -> Unit) {
    var menu by remember { mutableStateOf<StoreMenu?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var cat by remember { mutableStateOf<String?>(null) }
    val cart by repository.hotelCart.collectAsState()
    var conflict by remember { mutableStateOf<AddToCartResult.HotelConflict?>(null) }
    var customising by remember { mutableStateOf<StoreMenuProduct?>(null) }
    LaunchedEffect(storeId, retry) {
        error = null
        StoresRepository.getMenu(storeId).onSuccess { menu = it; StoresRepository.rememberStore(storeId) }.onFailure { error = it.message }
    }
    val m = menu
    val mine = cart.filter { it.vendorId == storeId }
    fun qty(id: String) = mine.firstOrNull { it.productId == id }?.quantity ?: 0
    val priceOf = { id: String -> m?.products?.firstOrNull { it.id == id }?.price ?: 0.0 }
    val cartCount = mine.sumOf { it.quantity }
    val cartTotal = mine.sumOf { priceOf(it.productId) * it.quantity }
    fun change(p: StoreMenuProduct, delta: Int) {
        if (delta > 0) repository.sessionManager.cacheCartProductSnapshot(p.id, null, p.name, p.imageUrl, p.unit)
        val res = repository.addToCart(productId = p.id, vendorId = storeId, cityId = cityId, quantityDelta = delta, isHotel = true)
        if (res is AddToCartResult.HotelConflict) conflict = res
    }
    fun addCustom(p: StoreMenuProduct, note: String?, photo: String?, q: Int) {
        if (qty(p.id) > 0) { repository.setCartCustomization(p.id, note, photo); return }
        repository.sessionManager.cacheCartProductSnapshot(p.id, null, p.name, p.imageUrl, p.unit)
        val res = repository.addToCart(productId = p.id, vendorId = storeId, cityId = cityId, quantityDelta = q, isHotel = true, customNote = note, customPhotoPath = photo)
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
                        if (m.address != null) Text(m.address, fontSize = 12.sp, color = TextMuted)
                    }
                }
                if (m.categories.isNotEmpty()) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { StoreChip("All", cat == null) { cat = null } }
                        items(m.categories) { (id, name) -> StoreChip(name, cat == id) { cat = id } }
                    }
                }
                val shown = m.products.filter { cat == null || it.categoryId == cat }
                if (shown.isEmpty()) item { StoresEmpty("No products yet", "This shop has not added products here.") }
                items(shown, key = { it.id }) { p -> MenuProductRow(p, canOrder = m.isOpen, quantity = qty(p.id), onCustomise = { customising = p }) { d -> change(p, d) } }
            }
        }
    }
    customising?.let { p ->
        val line = mine.firstOrNull { it.productId == p.id }
        CustomiseSheet(
            product = p, repository = repository, initialNote = line?.customNote, initialPhotoPath = line?.customPhotoPath,
            initialQty = line?.quantity ?: 1, editing = line != null,
            onDismiss = { customising = null },
            onDone = { note, photo, q -> addCustom(p, note, photo, q); customising = null }
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
                if (!p.isAvailable) StoreBadge("Out of stock", androidx.compose.ui.graphics.Color(0xFFFEE2E2), androidx.compose.ui.graphics.Color(0xFF991B1B))
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(rupee(p.price), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                if (p.mrp != null && p.mrp > p.price) Text(rupee(p.mrp), fontSize = 11.sp, color = TextMuted, textDecoration = TextDecoration.LineThrough)
                if (p.isAvailable && canOrder) {
                    if (p.isCustomizable) FilledTonalButton(onClick = onCustomise, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp), modifier = Modifier.height(32.dp).testTag("store_customise_button")) { Text(if (quantity > 0) "Edit · $quantity in cart" else "Customise", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
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
