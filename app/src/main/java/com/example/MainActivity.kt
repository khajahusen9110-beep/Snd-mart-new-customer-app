package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.data.analytics.Analytics
import com.example.data.location.LocationDetector
import com.example.data.model.*
import com.example.data.repository.SndmartRepository
import com.example.service.CustomerFcmService
import com.example.service.InAppNotification
import com.example.service.PushTokenManager
import com.example.service.SndmartMessagingService
import com.example.ui.components.InAppNotificationBanner
import com.example.ui.components.LocalActivePromo
import com.example.ui.components.LocationDetectionState
import com.example.ui.components.LocationRequirementDialog
import com.example.ui.components.OfferRibbon
import com.example.ui.components.PromoState
import com.example.ui.screens.*
import com.example.ui.theme.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.example.util.RazorpayPaymentManager

sealed class Screen(val route: String, val title: String) {
    object Home : Screen("home", "Home")
    object Cart : Screen("cart", "Cart")
    object Orders : Screen("orders", "Orders")
    object Profile : Screen("profile", "Profile")
    object HotelMenu : Screen("hotel_menu/{vendorId}/{vendorName}", "Menu") {
        fun createRoute(vendorId: String, vendorName: String): String {
            val encodedName = java.net.URLEncoder.encode(vendorName.ifBlank { "Hotel" }, "UTF-8").replace("+", "%20")
            return "hotel_menu/$vendorId/$encodedName"
        }
    }
    object Checkout : Screen("checkout/{isHotel}/{couponCode}", "Checkout") {
        fun createRoute(isHotel: Boolean, couponCode: String?) =
            "checkout/$isHotel/${couponCode ?: "none"}"
    }
    object OrderDetail : Screen("order_detail/{orderId}", "Order Details") {
        fun createRoute(orderId: String) = "order_detail/$orderId"
    }
    object Wallet : Screen("wallet", "Wallet")
    object ReferEarn : Screen("refer_earn", "Refer & Earn")
    object AgentCode : Screen("agent_code", "Agent Code")
    object MyReviews : Screen("my_reviews", "My Reviews")
    object AddressBook : Screen("address_book", "Saved Addresses")
    object EditProfile : Screen("edit_profile", "Edit Profile")
    object HelpSupport : Screen("help_support/{orderNumber}", "Help & Support") {
        fun createRoute(orderNumber: String? = null) = "help_support/${orderNumber ?: "none"}"
    }
    object Auth : Screen("auth", "Account")
    object LocationOnboarding : Screen("location_onboarding", "Delivery Location")
    object Notifications : Screen("notifications", "Notifications")
}

class MainActivity : ComponentActivity(), PaymentResultWithDataListener {

    private val pendingOrderId = mutableStateOf<String?>(null)
    private val pendingOpenOrders = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle deep link / notification extra
        handleIntent(intent)

        val app = application as SndmartApp
        val sessionManager = app.sessionManager
        val repository = SndmartRepository(sessionManager = sessionManager)

        setContent {
            // Some OEM skins (Samsung, Xiaomi, budget phones) allow system font scale up to
            // 1.5-2x vs stock Android's ~1.3x cap, and compound it with a separate "display
            // size" density setting — together these break fixed-width layouts. Clamp the
            // effective font scale app-wide so every screen stays within a safe range
            // regardless of what the device allows.
            val baseDensity = LocalDensity.current
            val clampedDensity = Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale.coerceIn(0.85f, 1.3f)
            )
            CompositionLocalProvider(LocalDensity provides clampedDensity) {
            SndmartTheme {
                val navController = rememberNavController()
                val currentBackStack by navController.currentBackStackEntryAsState()
                val currentDestination = currentBackStack?.destination?.route

                val selectedCity by sessionManager.selectedCity.collectAsState()
                val userId by sessionManager.userId.collectAsState()

                // Analytics: one session per process lifetime, tracked regardless of login state
                // (mirrors the website's initAnalyticsSession()); keep the user id in sync as it
                // changes (login/logout) and track every screen change as a page view.
                LaunchedEffect(Unit) {
                    Analytics.init(sessionManager.userId.value)
                }
                LaunchedEffect(userId) {
                    Analytics.setUserId(userId)
                }
                LaunchedEffect(currentDestination) {
                    currentDestination?.let { Analytics.trackPageView(it) }
                }

                // Promotional offer state (backend-driven). Refreshed on city change + every minute so
                // an admin change / expiry reflects within ~60s. Empty when no offer is active.
                val foodPromo by repository.foodPromo.collectAsState()
                val groceryPromo by repository.groceryPromo.collectAsState()
                val promoState = remember(foodPromo, groceryPromo) { PromoState(food = foodPromo, grocery = groceryPromo) }
                LaunchedEffect(selectedCity?.id) {
                    while (true) {
                        repository.refreshPromos(selectedCity?.id)
                        delay(60_000)
                    }
                }
                var showCityPicker by remember { mutableStateOf(false) }
                var showSupabaseSettings by remember { mutableStateOf(false) }

                val context = LocalContext.current
                val locationDetector = remember { LocationDetector(context) }
                val coroutineScope = rememberCoroutineScope()

                var showLocationDialog by remember { mutableStateOf(false) }
                var locationDetectionState by remember { mutableStateOf(LocationDetectionState.PERMISSION_REQUIRED) }
                var detectedCityName by remember { mutableStateOf<String?>(null) }
                var assignedCityName by remember { mutableStateOf<String?>(null) }
                var activeCitiesSummary by remember { mutableStateOf("") }
                var hasDeniedLocationThisSession by remember { mutableStateOf(false) }
                var lastDetectionTimeMs by remember { mutableStateOf(0L) }
                var pendingAutoCityChange by remember { mutableStateOf<City?>(null) }
                val snackbarHostState = remember { SnackbarHostState() }
                var snackbarMessage by remember { mutableStateOf<String?>(null) }
                var isNavGraphReady by remember { mutableStateOf(false) }

                // App Startup Checks (App version & Maintenance mode)
                var startupCheckResult by remember { mutableStateOf<StartupCheckResult?>(null) }
                var isCheckingStartup by remember { mutableStateOf(false) }

                fun executeStartupChecks() {
                    if (isCheckingStartup) return
                    coroutineScope.launch {
                        isCheckingStartup = true
                        val result = repository.runStartupChecks()
                        startupCheckResult = result
                        isCheckingStartup = false
                    }
                }

                LaunchedEffect(Unit) {
                    executeStartupChecks()
                }

                fun applyDetectedCity(city: City) {
                    val currentCity = sessionManager.selectedCity.value
                    val cartCount = repository.getCartCount()

                    if (currentCity != null && currentCity.id != city.id && cartCount > 0) {
                        // Cart has items and city is changing — warn before clearing
                        showLocationDialog = false
                        pendingAutoCityChange = city
                    } else {
                        // Apply directly (same city, empty cart, or first launch)
                        if (currentCity == null || currentCity.id != city.id) {
                            sessionManager.setSelectedCity(city)
                            repository.clearAllCarts()
                            // Silently update profile city_id if logged in
                            val userId = sessionManager.userId.value
                            if (!userId.isNullOrBlank()) {
                                coroutineScope.launch {
                                    try { repository.updateProfileCityId(userId, city.id) } catch (e: Exception) {}
                                }
                            }
                        }
                        assignedCityName = city.name
                        locationDetectionState = LocationDetectionState.SUCCESS
                        showCityPicker = false
                    }
                }

                fun recheckLocationSilently() {
                    coroutineScope.launch {
                        lastDetectionTimeMs = System.currentTimeMillis()
                        if (!locationDetector.hasLocationPermission() || hasDeniedLocationThisSession) return@launch

                        val coordinates = locationDetector.getCurrentCoordinates()
                        val lat = coordinates?.latitude
                        val lng = coordinates?.longitude

                        if (lat != null && lng != null) {
                            val rpcResult = repository.findCityForLocation(lat, lng)
                            val rpcCity = rpcResult.getOrNull()

                            if (rpcResult.isSuccess && rpcCity != null) {
                                val currentCity = sessionManager.selectedCity.value
                                if (currentCity == null || currentCity.id != rpcCity.id) {
                                    if (repository.getCartCount() > 0) {
                                        pendingAutoCityChange = rpcCity
                                    } else {
                                        sessionManager.setSelectedCity(rpcCity)
                                        val userId = sessionManager.userId.value
                                        if (!userId.isNullOrBlank()) {
                                            try { repository.updateProfileCityId(userId, rpcCity.id) } catch (e: Exception) {}
                                        }
                                        snackbarMessage = "Your delivery city has been updated to ${rpcCity.name}"
                                    }
                                }
                            }
                        }
                    }
                }

                fun performLocationDetectionAndCityAssignment() {
                    coroutineScope.launch {
                        showLocationDialog = true
                        locationDetectionState = LocationDetectionState.DETECTING
                        lastDetectionTimeMs = System.currentTimeMillis()

                        val coordinates = locationDetector.getCurrentCoordinates()
                        val lat = coordinates?.latitude
                        val lng = coordinates?.longitude

                        if (lat != null && lng != null) {
                            // Primary: backend RPC for authoritative city detection
                            val rpcResult = repository.findCityForLocation(lat, lng)
                            val rpcCity = rpcResult.getOrNull()

                            if (rpcResult.isSuccess && rpcCity != null) {
                                // City found via backend RPC
                                applyDetectedCity(rpcCity)
                            } else if (rpcResult.isSuccess && rpcCity == null) {
                                // Location not serviceable — no city covers this area
                                detectedCityName = null
                                locationDetectionState = LocationDetectionState.UNSUPPORTED_AREA
                            } else {
                                // RPC failed — fall back to geocoding + client-side matching
                                val geoCity = locationDetector.getCityNameFromCoordinates(lat, lng)
                                detectedCityName = geoCity
                                val citiesResult = repository.getActiveCities()
                                val backendCities = citiesResult.getOrNull()?.filter { it.status == "active" } ?: emptyList()
                                if (backendCities.isNotEmpty()) {
                                    activeCitiesSummary = backendCities.joinToString(", ") { it.name }
                                    val matchedCity = locationDetector.matchWithBackendCities(geoCity, lat, lng, backendCities)
                                    if (matchedCity != null) {
                                        applyDetectedCity(matchedCity)
                                    } else {
                                        locationDetectionState = LocationDetectionState.UNSUPPORTED_AREA
                                    }
                                } else {
                                    if (selectedCity != null) {
                                        showLocationDialog = false
                                    } else {
                                        locationDetectionState = LocationDetectionState.UNSUPPORTED_AREA
                                    }
                                }
                            }
                        } else {
                            // Location unavailable
                            if (selectedCity != null) {
                                showLocationDialog = false
                            } else {
                                locationDetectionState = LocationDetectionState.UNSUPPORTED_AREA
                                activeCitiesSummary = "Unable to determine your location."
                            }
                        }
                    }
                }

                // Kick off the first-time city detection flow (permission prompt -> GPS ->
                // find_city_for_location RPC). Sets the dialog visible synchronously so the
                // city-picker fallback doesn't race in while detection is starting.
                fun startFirstTimeCityDetection() {
                    showLocationDialog = true
                    if (!locationDetector.hasLocationPermission()) {
                        locationDetectionState = LocationDetectionState.PERMISSION_REQUIRED
                    } else {
                        performLocationDetectionAndCityAssignment()
                    }
                }

                val locationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                    val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    if (fineGranted || coarseGranted) {
                        performLocationDetectionAndCityAssignment()
                    } else {
                        locationDetectionState = LocationDetectionState.PERMISSION_DENIED
                        hasDeniedLocationThisSession = true
                    }
                }

                // City detection runs AFTER login (or a restored session), not on every app
                // open. If the user already has a city (locally or on profiles.city_id), skip
                // straight to Home. Only when profiles.city_id is null do we run the first-time
                // GPS detection flow. Re-detection otherwise happens only on a >30min resume
                // (see lifecycle observer below) or an explicit "Update my location" tap.
                LaunchedEffect(userId) {
                    val currentUserId = userId ?: return@LaunchedEffect
                    val cityAtStart = selectedCity
                    if (cityAtStart == null) {
                        val profileCity = repository.resolveUserCity(currentUserId).getOrNull()
                        if (profileCity != null) {
                            sessionManager.setSelectedCity(profileCity)
                        }
                    } else {
                        // Only id/name/state are persisted across app restarts (see
                        // UserSessionManager.loadSelectedCity), so serviceMode/openTime/
                        // closeTime/emergencyClosed would otherwise silently sit at their
                        // model defaults forever instead of reflecting City Admin changes.
                        val freshCity = repository.getCity(cityAtStart.id, forceRefresh = true).getOrNull()
                        if (freshCity != null) {
                            sessionManager.setSelectedCity(freshCity)
                        }
                    }
                    val addresses = repository.getAddresses(currentUserId).getOrNull()
                    if (addresses != null) {
                        if (addresses.isNotEmpty()) {
                            val def = addresses.firstOrNull { it.isDefault } ?: addresses.first()
                            sessionManager.setHasSavedAddress(true, def.label)
                        } else {
                            sessionManager.setHasSavedAddress(false)
                        }
                    }
                }

                // Rehydrate the user's cart from the cart_items table at session start.
                // Clear any leftover memory state before syncing for the current user.
                LaunchedEffect(userId) {
                    repository.resetInMemoryCart()
                    if (userId != null) {
                        repository.syncCartFromBackend()
                    }
                }

                // Android 13+ Notification Permission Prompt - asked only after login (not on
                // the very first screen), since it means nothing to a customer who hasn't signed
                // in yet and a denial here shouldn't block anything: the token still gets
                // registered either way (data pushes still work, and a later permission grant
                // just starts showing them).
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { /* Permission response handled by system; registration doesn't depend on it */ }

                LaunchedEffect(userId) {
                    val currentUserId = userId ?: return@LaunchedEffect
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    PushTokenManager.ensurePushTokenRegistered(this@MainActivity, sessionManager, repository)
                    // Fetch latest unread notifications count
                    repository.refreshUnreadNotificationCount(currentUserId)
                }

                // Re-check location and ensure session validity when app is resumed
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            Analytics.resumeHeartbeat()
                            val currentUserId = sessionManager.userId.value
                            if (!currentUserId.isNullOrBlank()) {
                                coroutineScope.launch {
                                    repository.refreshUnreadNotificationCount(currentUserId)
                                }
                                coroutineScope.launch {
                                    val pendingOrdersRes = repository.getPendingUpiOrders(currentUserId)
                                    pendingOrdersRes.getOrNull()?.forEach { pendingOrder ->
                                        val pOrderId = pendingOrder.id
                                        if (!pOrderId.isNullOrBlank()) {
                                            repository.syncRazorpayPayment(pOrderId)
                                        }
                                    }
                                }
                                coroutineScope.launch {
                                    PushTokenManager.ensurePushTokenRegistered(this@MainActivity, sessionManager, repository)
                                }
                            }
                            coroutineScope.launch {
                                try {
                                    repository.ensureValidSession()
                                } catch (e: Exception) {
                                    // Handled in repository
                                }
                            }
                            val elapsed = System.currentTimeMillis() - lastDetectionTimeMs
                            if (lastDetectionTimeMs > 0 && elapsed > 30 * 60 * 1000L) {
                                recheckLocationSilently()
                            }
                        } else if (event == Lifecycle.Event.ON_PAUSE) {
                            Analytics.pauseHeartbeat()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                // Periodic Single Device Session Check (every 2 minutes)
                // Ensures that if the account is logged in on another device, this device
                // is promptly logged out even if the user stays continuously on the same screen.
                LaunchedEffect(userId) {
                    val currentUserId = userId ?: return@LaunchedEffect
                    while (isActive) {
                        delay(2 * 60 * 1000L) // 2 minutes
                        try {
                            repository.checkStillActiveDevice()
                        } catch (e: Exception) {
                            android.util.Log.w("MainActivity", "Periodic session check failed: ${e.message}")
                        }
                    }
                }

                var pendingSessionExpired by remember { mutableStateOf<String?>(null) }

                // Redirect to login when session expires
                LaunchedEffect(Unit) {
                    sessionManager.sessionExpiredEvent.collectLatest { message ->
                        if (isNavGraphReady) {
                            snackbarMessage = message
                            if (currentDestination != Screen.Auth.route) {
                                try {
                                    navController.navigate(Screen.Auth.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("MainActivity", "Failed to navigate to Auth: ${e.message}")
                                }
                            }
                        } else {
                            pendingSessionExpired = message
                        }
                    }
                }

                // Handle session expired that occurred while startup checks were running
                LaunchedEffect(isNavGraphReady, pendingSessionExpired) {
                    val pendingMsg = pendingSessionExpired
                    if (isNavGraphReady && pendingMsg != null) {
                        pendingSessionExpired = null
                        snackbarMessage = pendingMsg
                        if (currentDestination != Screen.Auth.route) {
                            try {
                                navController.navigate(Screen.Auth.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("MainActivity", "Failed to navigate to Auth on pending session: ${e.message}")
                            }
                        }
                    }
                }

                // Show snackbar for city updates / notifications
                LaunchedEffect(snackbarMessage) {
                    snackbarMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        snackbarMessage = null
                    }
                }

                // In-app notification from FCM
                var activeNotification by remember { mutableStateOf<InAppNotification?>(null) }

                // Collect in-app push messages
                LaunchedEffect(Unit) {
                    CustomerFcmService.inAppEvents.collectLatest { notification ->
                        activeNotification = notification
                    }
                }

                // Navigation if opened with deep link or notification extra
                val targetOrderId by pendingOrderId
                val shouldOpenOrders by pendingOpenOrders
                LaunchedEffect(targetOrderId, shouldOpenOrders, currentDestination, isNavGraphReady) {
                    if (!isNavGraphReady || currentDestination == null) return@LaunchedEffect
                    val orderId = targetOrderId
                    if (!orderId.isNullOrBlank() && currentDestination != Screen.Auth.route && currentDestination != Screen.LocationOnboarding.route) {
                        try {
                            navController.navigate(Screen.OrderDetail.createRoute(orderId))
                            pendingOrderId.value = null
                        } catch (e: Exception) {
                            android.util.Log.w("MainActivity", "Failed to navigate to OrderDetail: ${e.message}")
                        }
                    } else if (shouldOpenOrders && currentDestination != Screen.Auth.route && currentDestination != Screen.LocationOnboarding.route) {
                        try {
                            navController.navigate(Screen.Orders.route)
                            pendingOpenOrders.value = false
                        } catch (e: Exception) {
                            android.util.Log.w("MainActivity", "Failed to navigate to Orders: ${e.message}")
                        }
                    }
                }

                // City picker at MainActivity level should NEVER be shown when on Auth screen, when user is not logged in, or on LocationOnboarding screen
                val isAuthenticated = !userId.isNullOrBlank() && sessionManager.hasSavedSession()
                val canShowGlobalCityPicker = isAuthenticated &&
                        currentDestination != null &&
                        currentDestination != Screen.Auth.route &&
                        currentDestination != Screen.LocationOnboarding.route

                LaunchedEffect(selectedCity, showLocationDialog, currentDestination, isAuthenticated) {
                    if (!canShowGlobalCityPicker) {
                        showCityPicker = false
                        showLocationDialog = false
                    } else if (selectedCity == null && !showLocationDialog && currentDestination == Screen.Home.route) {
                        showCityPicker = true
                    }
                }

                // Bottom bar visible only on top-level tabs
                val isTopLevelDestination = currentDestination in listOf(
                    Screen.Home.route,
                    Screen.Cart.route,
                    Screen.Orders.route,
                    Screen.Profile.route
                )

                when (val startupState = startupCheckResult) {
                    is StartupCheckResult.BlockingUpdate -> {
                        BlockingUpdateScreen(
                            updateMessage = startupState.updateMessage,
                            updateUrl = startupState.updateUrl
                        )
                    }
                    is StartupCheckResult.Maintenance -> {
                        MaintenanceScreen(
                            message = startupState.message,
                            isChecking = isCheckingStartup,
                            onRetry = { executeStartupChecks() }
                        )
                    }
                    null -> {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = NaturalPrimary,
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp
                                )
                            }
                        }
                    }
                    is StartupCheckResult.Passed -> {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            contentWindowInsets = WindowInsets.safeDrawing,
                            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                            bottomBar = {
                                if (isTopLevelDestination) {
                                    NavigationBar(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        tonalElevation = 0.dp
                                    ) {
                                        val groceryCart by repository.groceryCart.collectAsState()
                                        val hotelCart by repository.hotelCart.collectAsState()
                                        val cartCount = groceryCart.sumOf { it.quantity } + hotelCart.sumOf { it.quantity }

                                        val navItemColors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = NaturalOnPrimaryContainer,
                                            selectedTextColor = NaturalOnPrimaryContainer,
                                            indicatorColor = NaturalPrimaryContainer,
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary
                                        )

                                NavigationBarItem(
                                    selected = currentDestination == Screen.Home.route,
                                    onClick = {
                                        navController.navigate(Screen.Home.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (currentDestination == Screen.Home.route) Icons.Filled.Storefront else Icons.Outlined.Storefront,
                                            contentDescription = "Home"
                                        )
                                    },
                                    label = { Text("Home", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                                    colors = navItemColors,
                                    modifier = Modifier.testTag("nav_item_home")
                                )

                                NavigationBarItem(
                                    selected = currentDestination == Screen.Cart.route,
                                    onClick = {
                                        navController.navigate(Screen.Cart.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (cartCount > 0) {
                                                    Badge(containerColor = NaturalBadgeRed, contentColor = Color.White) {
                                                        Text("$cartCount", fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                if (currentDestination == Screen.Cart.route) Icons.Filled.ShoppingCart else Icons.Outlined.ShoppingCart,
                                                contentDescription = "Cart"
                                            )
                                        }
                                    },
                                    label = { Text("Cart", fontWeight = FontWeight.Medium, fontSize = 11.sp) },
                                    colors = navItemColors,
                                    modifier = Modifier.testTag("nav_item_cart")
                                )

                                NavigationBarItem(
                                    selected = currentDestination == Screen.Orders.route,
                                    onClick = {
                                        navController.navigate(Screen.Orders.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (currentDestination == Screen.Orders.route) Icons.Filled.ReceiptLong else Icons.Outlined.ReceiptLong,
                                            contentDescription = "Orders"
                                        )
                                    },
                                    label = { Text("Orders", fontWeight = FontWeight.Medium, fontSize = 11.sp) },
                                    colors = navItemColors,
                                    modifier = Modifier.testTag("nav_item_orders")
                                )

                                NavigationBarItem(
                                    selected = currentDestination == Screen.Profile.route,
                                    onClick = {
                                        navController.navigate(Screen.Profile.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (currentDestination == Screen.Profile.route) Icons.Filled.Person else Icons.Outlined.Person,
                                            contentDescription = "Profile"
                                        )
                                    },
                                    label = { Text("Profile", fontWeight = FontWeight.Medium, fontSize = 11.sp) },
                                    colors = navItemColors,
                                    modifier = Modifier.testTag("nav_item_profile")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        var verifiedHasAddress by remember { mutableStateOf<Boolean?>(null) }

                        LaunchedEffect(Unit) {
                            if (sessionManager.hasSavedSession()) {
                                val currentUserId = sessionManager.getUserId()
                                if (currentUserId != null) {
                                    val result = repository.getCustomerAddressCount(currentUserId)
                                    val actuallyHasAddress = (result.getOrNull() ?: 0) > 0
                                    if (actuallyHasAddress != sessionManager.hasSavedAddress()) {
                                        sessionManager.setHasSavedAddress(actuallyHasAddress)
                                    }
                                    verifiedHasAddress = actuallyHasAddress
                                } else {
                                    verifiedHasAddress = sessionManager.hasSavedAddress()
                                }
                            } else {
                                verifiedHasAddress = false
                            }
                        }

                        if (sessionManager.hasSavedSession() && verifiedHasAddress == null) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = NaturalPrimary,
                                        modifier = Modifier.size(36.dp),
                                        strokeWidth = 3.dp
                                    )
                                }
                            }
                        } else {
                            val startDestination = remember(verifiedHasAddress) {
                                if (!sessionManager.hasSavedSession()) {
                                    Screen.Auth.route
                                } else if (sessionManager.selectedCity.value != null && (verifiedHasAddress ?: sessionManager.hasSavedAddress())) {
                                    Screen.Home.route
                                } else {
                                    Screen.LocationOnboarding.route
                                }
                            }
                            CompositionLocalProvider(LocalActivePromo provides promoState) {
                            Column(modifier = Modifier.fillMaxSize()) {
                            OfferRibbon()
                            NavHost(
                                navController = navController,
                                startDestination = startDestination,
                                modifier = Modifier.weight(1f)
                            ) {
                            composable(Screen.Home.route) {
                                val defaultAddressLabel by sessionManager.defaultAddressLabel.collectAsState()
                                HomeScreen(
                                    repository = repository,
                                    selectedCity = selectedCity,
                                    deliveryAddressLabel = defaultAddressLabel,
                                    onCityChangeRequested = { showCityPicker = true },
                                    onNavigateToCart = { navController.navigate(Screen.Cart.route) },
                                    onNavigateToHotelMenu = { vendorId, vendorName ->
                                        navController.navigate(Screen.HotelMenu.createRoute(vendorId, vendorName))
                                    },
                                    onOpenSettings = if (com.example.BuildConfig.DEBUG) { { showSupabaseSettings = true } } else null,
                                    onNavigateToNotifications = { navController.navigate(Screen.Notifications.route) },
                                    onProceedToCheckout = { isHotel ->
                                        navController.navigate(Screen.Checkout.createRoute(isHotel, null))
                                    },
                                    onNavigateToWallet = { navController.navigate(Screen.Wallet.route) }
                                )
                            }

                            composable(Screen.LocationOnboarding.route) {
                                LocationOnboardingScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onComplete = {
                                        navController.navigate(Screen.Home.route) {
                                            popUpTo(Screen.LocationOnboarding.route) { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable(
                                route = Screen.HotelMenu.route,
                                arguments = listOf(
                                    navArgument("vendorId") { type = NavType.StringType },
                                    navArgument("vendorName") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val vendorId = backStackEntry.arguments?.getString("vendorId") ?: ""
                                val rawVendorName = backStackEntry.arguments?.getString("vendorName") ?: "Hotel"
                                val vendorName = try {
                                    java.net.URLDecoder.decode(rawVendorName, "UTF-8")
                                } catch (e: Exception) {
                                    rawVendorName
                                }
                                HotelMenuScreen(
                                    vendorId = vendorId,
                                    vendorName = vendorName,
                                    cityId = selectedCity?.id ?: "",
                                    repository = repository,
                                    onBack = { navController.popBackStack() },
                                    onNavigateToCart = { navController.navigate(Screen.Cart.route) },
                                    onProceedToCheckout = { isHotel ->
                                        navController.navigate(Screen.Checkout.createRoute(isHotel, null))
                                    }
                                )
                            }

                            composable(Screen.Cart.route) {
                                CartScreen(
                                    cityId = selectedCity?.id,
                                    repository = repository,
                                    onBack = { navController.popBackStack() },
                                    onProceedToCheckout = { isHotel, coupon ->
                                        navController.navigate(Screen.Checkout.createRoute(isHotel, coupon))
                                    }
                                )
                            }

                            composable(
                                route = Screen.Checkout.route,
                                arguments = listOf(
                                    navArgument("isHotel") { type = NavType.BoolType },
                                    navArgument("couponCode") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val isHotel = backStackEntry.arguments?.getBoolean("isHotel") ?: false
                                val coupon = backStackEntry.arguments?.getString("couponCode")?.takeIf { it != "none" }
                                CheckoutScreen(
                                    isHotel = isHotel,
                                    couponCode = coupon,
                                    cityId = selectedCity?.id,
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onBack = { navController.popBackStack() },
                                    onOrderPlacedSuccess = { orderId ->
                                        // Fires only here — the single real order-success callback
                                        // every placement path (COD, UPI, pending-sync) routes
                                        // through — never on a payment-method click or form submit.
                                        Analytics.trackEvent("order_placed", mapOf("order_id" to orderId))
                                        navController.navigate(Screen.OrderDetail.createRoute(orderId)) {
                                            popUpTo(Screen.Home.route)
                                        }
                                    },
                                    onRequireLogin = { navController.navigate(Screen.Auth.route) }
                                )
                            }

                            composable(Screen.Orders.route) {
                                OrdersScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onNavigateToDetail = { orderId ->
                                        navController.navigate(Screen.OrderDetail.createRoute(orderId))
                                    },
                                    onRequireLogin = { navController.navigate(Screen.Auth.route) }
                                )
                            }

                            composable(
                                route = Screen.OrderDetail.route,
                                arguments = listOf(navArgument("orderId") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                                OrderDetailScreen(
                                    orderId = orderId,
                                    cityId = selectedCity?.id,
                                    repository = repository,
                                    onBack = { navController.popBackStack() },
                                    onNavigateToCart = { navController.navigate(Screen.Cart.route) }
                                )
                            }

                            composable(Screen.Profile.route) {
                                ProfileScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onNavigateToCityPicker = { showCityPicker = true },
                                    onNavigateToWallet = { navController.navigate(Screen.Wallet.route) },
                                    onNavigateToReferEarn = { navController.navigate(Screen.ReferEarn.route) },
                                    onNavigateToAgentCode = { navController.navigate(Screen.AgentCode.route) },
                                    onNavigateToEditProfile = { navController.navigate(Screen.EditProfile.route) },
                                    onNavigateToAddresses = { navController.navigate(Screen.AddressBook.route) },
                                    onNavigateToMyReviews = { navController.navigate(Screen.MyReviews.route) },
                                    onNavigateToHelp = { navController.navigate(Screen.HelpSupport.createRoute()) },
                                    onRequireLogin = { navController.navigate(Screen.Auth.route) },
                                    onOpenSettings = if (com.example.BuildConfig.DEBUG) { { showSupabaseSettings = true } } else null,
                                    onNavigateToNotifications = { navController.navigate(Screen.Notifications.route) },
                                    onLogoutSuccess = {
                                        navController.navigate(Screen.Auth.route) {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable(Screen.Notifications.route) {
                                NotificationsScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onNavigateToOrder = { orderId ->
                                        navController.navigate(Screen.OrderDetail.createRoute(orderId))
                                    },
                                    onBack = { navController.popBackStack() },
                                    onNavigateToWallet = { navController.navigate(Screen.Wallet.route) }
                                )
                            }

                            composable(Screen.Wallet.route) {
                                WalletScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onBack = { navController.popBackStack() },
                                    onOpenOrder = { orderId ->
                                        navController.navigate(Screen.OrderDetail.createRoute(orderId))
                                    }
                                )
                            }

                            composable(Screen.ReferEarn.route) {
                                ReferEarnScreen(
                                    repository = repository,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(Screen.AgentCode.route) {
                                AgentCodeScreen(
                                    repository = repository,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(Screen.MyReviews.route) {
                                MyReviewsScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(Screen.AddressBook.route) {
                                AddressBookScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(Screen.EditProfile.route) {
                                EditProfileScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(
                                route = Screen.HelpSupport.route,
                                arguments = listOf(navArgument("orderNumber") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val orderNumber = backStackEntry.arguments?.getString("orderNumber")?.takeIf { it != "none" }
                                HelpSupportScreen(
                                    orderNumber = orderNumber,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(Screen.Auth.route) {
                                AuthScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onNavigateToHome = {
                                        navController.navigate(Screen.Home.route) {
                                            popUpTo(Screen.Auth.route) { inclusive = true }
                                        }
                                    },
                                    onNavigateToCityOnboarding = {
                                        navController.navigate(Screen.LocationOnboarding.route) {
                                            popUpTo(Screen.Auth.route) { inclusive = true }
                                        }
                                    },
                                    onBack = {
                                        if (navController.previousBackStackEntry != null) {
                                            navController.popBackStack()
                                        }
                                    },
                                    canGoBack = false
                                )
                            }
                        }

                            }
                            }

                        LaunchedEffect(Unit) { isNavGraphReady = true }
                        DisposableEffect(Unit) { onDispose { isNavGraphReady = false } }

                        // Top-floating in-app push notification banner
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                        ) {
                            InAppNotificationBanner(
                                notification = activeNotification,
                                onDismiss = { activeNotification = null },
                                onNavigateToOrder = { orderId ->
                                    try {
                                        navController.navigate(Screen.OrderDetail.createRoute(orderId))
                                    } catch (e: Exception) {
                                        android.util.Log.w("MainActivity", "Failed to navigate to OrderDetail from banner: ${e.message}")
                                    }
                                },
                                onNavigateToInbox = {
                                    try {
                                        navController.navigate(Screen.Notifications.route)
                                    } catch (e: Exception) {
                                        android.util.Log.w("MainActivity", "Failed to navigate to Notifications from banner: ${e.message}")
                                    }
                                }
                            )
                        }

                        // Location Requirement & Auto-Detection Dialog
                        if (showLocationDialog && canShowGlobalCityPicker) {
                            LocationRequirementDialog(
                                state = locationDetectionState,
                                detectedCityName = detectedCityName,
                                assignedCityName = assignedCityName,
                                activeCitiesSummary = activeCitiesSummary,
                                onRequestPermission = {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                onManualSelect = {
                                    showLocationDialog = false
                                    showCityPicker = true
                                },
                                onDismiss = {
                                    showLocationDialog = false
                                },
                                onNotifyMe = {
                                    showLocationDialog = false
                                    snackbarMessage = "We'll notify you when Sndmart launches in your area!"
                                    showCityPicker = true
                                }
                            )
                        }

                        // Cart-clear warning when auto-detected city differs from current
                        if (pendingAutoCityChange != null) {
                            val newCity = pendingAutoCityChange!!
                            AlertDialog(
                                onDismissRequest = { pendingAutoCityChange = null },
                                title = { Text("Switch to ${newCity.name}?", fontWeight = FontWeight.Bold) },
                                text = {
                                    Text("Your location suggests you're in ${newCity.name}. Switching your delivery city will clear your current cart items, as prices and stock are city-specific. Do you wish to continue?")
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            val c = newCity
                                            pendingAutoCityChange = null
                                            coroutineScope.launch {
                                                sessionManager.setSelectedCity(c)
                                                repository.clearAllCarts()
                                                val userId = sessionManager.userId.value
                                                if (!userId.isNullOrBlank()) {
                                                    try { repository.updateProfileCityId(userId, c.id) } catch (e: Exception) {}
                                                }
                                                snackbarMessage = "Delivery city updated to ${c.name}"
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Text("Switch & Clear Cart")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingAutoCityChange = null }) {
                                        Text("Stay in Current City")
                                    }
                                }
                            )
                        }

                        // City Picker Bottom Sheet
                        if (showCityPicker && canShowGlobalCityPicker) {
                            CityPickerSheet(
                                repository = repository,
                                sessionManager = sessionManager,
                                onDismiss = { showCityPicker = false },
                                onCitySelected = { showCityPicker = false },
                                onTriggerLocationDetect = {
                                    showCityPicker = false
                                    if (!locationDetector.hasLocationPermission()) {
                                        locationDetectionState = LocationDetectionState.PERMISSION_REQUIRED
                                        showLocationDialog = true
                                    } else {
                                        performLocationDetectionAndCityAssignment()
                                    }
                                }
                            )
                        }

                        // Supabase Settings Dialog (debug only)
                        if (com.example.BuildConfig.DEBUG && showSupabaseSettings) {
                            SupabaseSettingsDialog(
                                sessionManager = sessionManager,
                                onDismiss = { showSupabaseSettings = false }
                            )
                        }
                    }
                }
            }
        }
    }
        }
            }
    }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val orderIdFromExtra = intent.getStringExtra("order_id")
            ?: intent.getStringExtra("orderId")
            ?: intent.getStringExtra("id")
            ?: intent.extras?.getString("order_id")
            ?: intent.extras?.getString("orderId")
            ?: intent.extras?.getString("id")
        if (!orderIdFromExtra.isNullOrBlank()) {
            pendingOrderId.value = orderIdFromExtra
            return
        }
        val data: Uri? = intent.data
        if (data != null && data.scheme == "sndmart" && data.host == "order") {
            val orderId = data.lastPathSegment
            if (!orderId.isNullOrBlank()) {
                pendingOrderId.value = orderId
                return
            }
        }
        val fromNotification = intent.getBooleanExtra("from_notification", false) ||
            intent.hasExtra("google.message_id")
        if (fromNotification) {
            pendingOpenOrders.value = true
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        RazorpayPaymentManager.onPaymentSuccess(razorpayPaymentId, paymentData)
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        RazorpayPaymentManager.onPaymentError(code, response, paymentData)
    }
}

