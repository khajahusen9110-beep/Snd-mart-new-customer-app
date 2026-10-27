package com.example.ui.screens

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.analytics.Analytics
import com.example.data.model.Profile
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.service.PushTokenManager
import com.example.ui.components.ErrorCard
import com.example.ui.theme.*
import com.example.util.PhoneUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Passwordless Mobile Number + SMS OTP Flow (Swiggy-Style):
 * - Screen 1: Enter 10-digit mobile number with fixed +91 prefix
 * - Screen 2: Enter 6-digit SMS OTP (with auto-advancing boxes and 30s resend timer)
 * - Screen 3: Enter Full Name (first-time users only)
 *
 * Screen 4 (City detection + Address) and Screen 5 (Home) follow upon authentication.
 * Returning users with existing profiles skip name collection and go straight to city/address or Home.
 */
enum class AuthStep {
    PHONE_INPUT,    // Screen 1: Mobile Number
    OTP_VERIFY,     // Screen 2: OTP Verification
    ENTER_NAME      // Screen 3: Full Name (first-time users only)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onNavigateToHome: () -> Unit,
    onNavigateToCityOnboarding: () -> Unit,
    onBack: () -> Unit,
    canGoBack: Boolean = false,
    onAuthSuccess: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Screen navigation state
    var authStep by remember { mutableStateOf(AuthStep.PHONE_INPUT) }

    // Form inputs
    var mobileNumber by remember { mutableStateOf("") } // 10 digits
    var otpCode by remember { mutableStateOf("") }       // 6 digits
    var fullName by remember { mutableStateOf("") }      // First-time user name

    // UI state
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Resend OTP countdown timer
    var resendCooldownSeconds by remember { mutableIntStateOf(30) }
    var resendTrigger by remember { mutableIntStateOf(0) }

    // Verified session cache for name-collection step
    var verifiedUserId by remember { mutableStateOf("") }
    var verifiedPhone by remember { mutableStateOf("") }
    var verifiedToken by remember { mutableStateOf<String?>(null) }
    var verifiedRefreshToken by remember { mutableStateOf<String?>(null) }
    var verifiedExpiresIn by remember { mutableStateOf<Long?>(null) }

    // Timer effect for Resend OTP
    LaunchedEffect(authStep, resendTrigger) {
        if (authStep == AuthStep.OTP_VERIFY) {
            resendCooldownSeconds = 30
            while (resendCooldownSeconds > 0) {
                delay(1000L)
                resendCooldownSeconds--
            }
        }
    }

    // Display session expired message (e.g. forced logout due to another device login)
    val sessionExpiredMsg by sessionManager.sessionExpiredMessage.collectAsState()
    LaunchedEffect(sessionExpiredMsg) {
        sessionExpiredMsg?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            sessionManager.clearSessionExpiredMessage()
        }
    }

    // Helper: Register FCM Token upon successful authentication
    fun registerFcm(userId: String) {
        coroutineScope.launch {
            try {
                PushTokenManager.ensurePushTokenRegistered(context, sessionManager, repository)
            } catch (e: Exception) {
                Log.w("AuthScreen", "Failed to register FCM token: ${e.message}")
            }
        }
    }

    // STEP 1: Mobile Number Screen - Send OTP to Mobile Number
    fun handleSendOtp() {
        if (!PhoneUtils.isValidPhoneNumber(mobileNumber)) {
            errorMessage = "Please enter a valid 10-digit mobile number."
            return
        }
        val e164Phone = PhoneUtils.toE164(mobileNumber)

        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val result = repository.signInWithPhoneOtp(e164Phone)
            isLoading = false
            if (result.isSuccess) {
                authStep = AuthStep.OTP_VERIFY
                otpCode = ""
                resendCooldownSeconds = 30
                resendTrigger++
                snackbarHostState.showSnackbar("OTP sent to $e164Phone")
            } else {
                val err = result.exceptionOrNull()?.message ?: "Failed to send OTP. Please check your number."
                Log.e("AuthScreen", "signInWithOtp error: $err")
                errorMessage = err
            }
        }
    }

    // STEP 2: Resend OTP
    fun handleResendOtp() {
        if (resendCooldownSeconds > 0 || isLoading) return
        val e164Phone = PhoneUtils.toE164(mobileNumber)

        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val result = repository.signInWithPhoneOtp(e164Phone)
            isLoading = false
            if (result.isSuccess) {
                resendCooldownSeconds = 30
                resendTrigger++
                snackbarHostState.showSnackbar("OTP resent to $e164Phone")
            } else {
                val err = result.exceptionOrNull()?.message ?: "Failed to resend OTP."
                Log.e("AuthScreen", "resendOtp error: $err")
                errorMessage = err
            }
        }
    }

    // STEP 2: Verify 6-digit OTP
    fun handleVerifyOtp() {
        val code = otpCode.trim()
        if (code.length != 6) {
            errorMessage = "Please enter the complete 6-digit OTP code."
            return
        }
        val e164Phone = PhoneUtils.toE164(mobileNumber)

        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val result = repository.verifyPhoneOtp(e164Phone, code)
            if (result.isSuccess && result.getOrNull() != null) {
                val authResponse = result.getOrNull()!!
                val uid = authResponse.user?.id ?: ""
                val token = authResponse.accessToken
                val returnedPhone = authResponse.user?.phone?.takeIf { it.isNotBlank() }?.let { PhoneUtils.toE164(it) } ?: e164Phone

                verifiedUserId = uid
                verifiedPhone = returnedPhone
                verifiedToken = token
                verifiedRefreshToken = authResponse.refreshToken
                verifiedExpiresIn = authResponse.expiresIn

                // Persist session tokens immediately
                sessionManager.saveSession(
                    token = token,
                    refreshToken = authResponse.refreshToken,
                    userId = uid,
                    email = null,
                    phone = returnedPhone,
                    expiresInSeconds = authResponse.expiresIn
                )

                Analytics.trackEvent("login_success")

                // Single device login: register this device as active session and sign out others
                repository.registerDeviceSession(uid)

                // Call exact API to check if this person already has a profile:
                // supabase.from("profiles").select("id, full_name, city_id").eq("id", data.user.id).maybeSingle()
                val profileRes = repository.getProfile(uid)
                if (profileRes.isFailure) {
                    val err = profileRes.exceptionOrNull()?.message ?: "Failed to load profile."
                    Log.e("AuthScreen", "profiles query error: $err")
                    errorMessage = err
                    isLoading = false
                    return@launch
                }

                val existingProfile = profileRes.getOrNull()

                if (existingProfile == null || existingProfile.fullName.isNullOrBlank()) {
                    // First time user: go to STEP 3 (Name collection)
                    isLoading = false
                    authStep = AuthStep.ENTER_NAME
                } else if (!existingProfile.cityId.isNullOrBlank()) {
                    // existingProfile exists AND existingProfile.city_id is already filled in.
                    // But verify the customer has a confirmed delivery address on file before routing to Home!
                    val cityRes = repository.resolveUserCity(uid)
                    val city = cityRes.getOrNull()
                    if (city != null) {
                        sessionManager.setSelectedCity(city)
                    }
                    sessionManager.updateProfileInfo(existingProfile)
                    registerFcm(uid)

                    // Right after successful login/registration, clear memory cart and re-sync from backend
                    repository.resetInMemoryCart()
                    repository.syncCartFromBackend()

                    val addressesRes = repository.getAddresses(uid)
                    val addresses = addressesRes.getOrNull().orEmpty()
                    if (addresses.isNotEmpty()) {
                        val def = addresses.firstOrNull { it.isDefault } ?: addresses.first()
                        sessionManager.setHasSavedAddress(true, def.label)
                        isLoading = false
                        onNavigateToHome()
                    } else {
                        sessionManager.setHasSavedAddress(false)
                        isLoading = false
                        onNavigateToCityOnboarding()
                    }
                } else {
                    // existingProfile exists BUT existingProfile.city_id is empty/null:
                    sessionManager.setHasSavedAddress(false)
                    sessionManager.updateProfileInfo(existingProfile)
                    registerFcm(uid)
                    isLoading = false
                    onNavigateToCityOnboarding()
                }
            } else {
                isLoading = false
                val err = result.exceptionOrNull()?.message ?: "OTP verification failed."
                Log.e("AuthScreen", "verifyOtp error: $err")
                errorMessage = err
            }
        }
    }

    // STEP 3: "What's Your Name?" Screen (first-time users only)
    fun handleSaveName() {
        val name = fullName.trim()
        if (name.isBlank()) {
            errorMessage = "Please enter your full name."
            return
        }

        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val uid = verifiedUserId.ifBlank {
                sessionManager.getUserId() ?: sessionManager.userId.value ?: ""
            }
            val rawPhone = verifiedPhone.ifBlank { mobileNumber }
            val phone = PhoneUtils.toE164(rawPhone)

            val deviceId = sessionManager.getOrCreateDeviceId()
            val profileToInsert = Profile(
                id = uid,
                role = "customer",
                fullName = name,
                phone = phone,
                currentDeviceSession = deviceId
            )

            // supabase.from("profiles").insert({ id, role: "customer", full_name, phone })
            val insertRes = repository.createProfile(profileToInsert)
            if (insertRes.isSuccess) {
                Analytics.trackEvent("registration_success")
                repository.registerDeviceSession(uid)
                sessionManager.saveSession(
                    token = verifiedToken ?: sessionManager.getAccessToken(),
                    refreshToken = verifiedRefreshToken ?: sessionManager.getRefreshToken(),
                    userId = uid,
                    email = null,
                    name = name,
                    phone = phone,
                    expiresInSeconds = verifiedExpiresIn
                )
                // Right after successful registration, clear memory cart and re-sync from backend
                repository.resetInMemoryCart()
                repository.syncCartFromBackend()

                sessionManager.updateProfileInfo(insertRes.getOrNull() ?: profileToInsert)
                registerFcm(uid)
                isLoading = false
                // If error is null, go to STEP 4 (City Detection)
                onNavigateToCityOnboarding()
            } else {
                val err = insertRes.exceptionOrNull()?.message ?: "Could not save profile. Please try again."
                Log.e("AuthScreen", "profiles insert error: $err")
                errorMessage = err
                isLoading = false
                // Do NOT move forward
            }
        }
    }

    // Handle system Back button gracefully between screens
    BackHandler(enabled = true) {
        when (authStep) {
            AuthStep.PHONE_INPUT -> {
                if (canGoBack) onBack()
            }
            AuthStep.OTP_VERIFY -> {
                authStep = AuthStep.PHONE_INPUT
                errorMessage = null
                otpCode = ""
            }
            AuthStep.ENTER_NAME -> {
                authStep = AuthStep.PHONE_INPUT
                errorMessage = null
                otpCode = ""
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (authStep) {
                            AuthStep.PHONE_INPUT -> "Welcome to Sndmart"
                            AuthStep.OTP_VERIFY -> "Verify Mobile"
                            AuthStep.ENTER_NAME -> "Your Profile"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    when (authStep) {
                        AuthStep.PHONE_INPUT -> {
                            if (canGoBack) {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        }
                        AuthStep.OTP_VERIFY -> {
                            IconButton(onClick = {
                                authStep = AuthStep.PHONE_INPUT
                                errorMessage = null
                                otpCode = ""
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Edit phone number")
                            }
                        }
                        AuthStep.ENTER_NAME -> {
                            IconButton(onClick = {
                                authStep = AuthStep.PHONE_INPUT
                                errorMessage = null
                                otpCode = ""
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to phone input")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Error banner
            if (errorMessage != null) {
                ErrorCard(
                    message = errorMessage!!,
                    onRetry = { errorMessage = null }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            when (authStep) {
                AuthStep.PHONE_INPUT -> {
                    // ==========================================
                    // SCREEN 1 — ENTER MOBILE NUMBER
                    // ==========================================
                    Spacer(modifier = Modifier.height(12.dp))

                    // Brand Illustration / Icon
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = PastelSage.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.25f)),
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.ShoppingBag,
                                contentDescription = null,
                                tint = NaturalPrimary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "Login or Sign Up",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter your 10-digit mobile number to proceed. We'll send a 6-digit OTP.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // 10-digit mobile number input with fixed +91 prefix
                    OutlinedTextField(
                        value = mobileNumber,
                        onValueChange = { input ->
                            val digits = PhoneUtils.to10Digits(input)
                            mobileNumber = digits
                            if (errorMessage != null) errorMessage = null
                        },
                        label = { Text("Mobile Number") },
                        placeholder = { Text("Enter 10 digits") },
                        leadingIcon = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, end = 6.dp)
                            ) {
                                Text(
                                    text = "🇮🇳",
                                    fontSize = 18.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "+91",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                VerticalDivider(
                                    modifier = Modifier.height(20.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        },
                        trailingIcon = {
                            if (mobileNumber.isNotEmpty()) {
                                IconButton(onClick = { mobileNumber = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear phone number"
                                    )
                                }
                            }
                        },
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (mobileNumber.length == 10) "Valid 10-digit number" else "10 digits required",
                                    color = if (mobileNumber.length == 10) NaturalPrimary else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (mobileNumber.length == 10) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    text = "${mobileNumber.length}/10",
                                    color = if (mobileNumber.length == 10) NaturalPrimary else TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (mobileNumber.length == 10 && !isLoading) {
                                    handleSendOtp()
                                }
                            }
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_phone_input")
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Single Continue Button (handles both Login and Signup transparently)
                    Button(
                        onClick = { handleSendOtp() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("auth_submit_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                        enabled = mobileNumber.length == 10 && !isLoading,
                        shape = RoundedCornerShape(26.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Text(
                                text = "Continue",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Terms disclaimer
                    Text(
                        text = "By continuing, you agree to Sndmart's Terms of Service and Privacy Policy.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                AuthStep.OTP_VERIFY -> {
                    // ==========================================
                    // SCREEN 2 — OTP VERIFICATION
                    // ==========================================
                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = PastelSage.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.25f)),
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Sms,
                                contentDescription = null,
                                tint = NaturalPrimary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "Verify with OTP",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    // Target phone display with "Edit" option
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Sent to ${PhoneUtils.toE164(mobileNumber)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(
                            onClick = {
                                authStep = AuthStep.PHONE_INPUT
                                errorMessage = null
                                otpCode = ""
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.testTag("edit_phone_number_button")
                        ) {
                            Text(
                                text = "Edit",
                                color = NaturalPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // 6-digit styled OTP box input
                    OtpCodeInput(
                        value = otpCode,
                        onValueChange = { code ->
                            otpCode = code
                            errorMessage = null
                            if (code.length == 6 && !isLoading) {
                                handleVerifyOtp()
                            }
                        },
                        length = 6,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Resend OTP countdown / CTA
                    if (resendCooldownSeconds > 0) {
                        Text(
                            text = "Resend OTP in ${resendCooldownSeconds}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Didn't receive the OTP? ",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            TextButton(
                                onClick = { handleResendOtp() },
                                enabled = !isLoading,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.testTag("resend_otp_button")
                            ) {
                                Text(
                                    text = "Resend OTP",
                                    color = NaturalPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Verify button
                    Button(
                        onClick = { handleVerifyOtp() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("verify_otp_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                        enabled = otpCode.length == 6 && !isLoading,
                        shape = RoundedCornerShape(26.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Text(
                                text = "Verify & Continue",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }

                AuthStep.ENTER_NAME -> {
                    // ==========================================
                    // SCREEN 3 — NAME (First-time users only)
                    // ==========================================
                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = PastelSage.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.25f)),
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = null,
                                tint = NaturalPrimary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "What's your name?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Please enter your full name so delivery partners know who to look for.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    OutlinedTextField(
                        value = fullName,
                        onValueChange = {
                            fullName = it
                            if (errorMessage != null) errorMessage = null
                        },
                        label = { Text("Full Name") },
                        placeholder = { Text("e.g. Rahul Sharma") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Badge,
                                contentDescription = null,
                                tint = NaturalPrimary
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (fullName.trim().isNotBlank() && !isLoading) {
                                    handleSaveName()
                                }
                            }
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_name_input")
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { handleSaveName() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("save_name_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                        enabled = fullName.trim().isNotBlank() && !isLoading,
                        shape = RoundedCornerShape(26.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Text(
                                text = "Continue",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 6-digit Auto-Advancing OTP Input Composable with individual digit cells.
 * Backed by a standard BasicTextField for accessibility, hardware keyboard support,
 * and clipboard paste support.
 */
@Composable
fun OtpCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 6,
    enabled: Boolean = true
) {
    BasicTextField(
        value = value,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }.take(length)
            onValueChange(digits)
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done
        ),
        enabled = enabled,
        modifier = modifier.testTag("otp_code_input"),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                for (i in 0 until length) {
                    val char = value.getOrNull(i)?.toString() ?: ""
                    val isCurrent = enabled && (value.length == i || (i == length - 1 && value.length == length))

                    Surface(
                        modifier = Modifier
                            .width(46.dp)
                            .height(54.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isCurrent) {
                            PastelSage.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                        border = BorderStroke(
                            width = if (isCurrent) 2.dp else 1.dp,
                            color = if (isCurrent) NaturalPrimary else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = char,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    )
}
