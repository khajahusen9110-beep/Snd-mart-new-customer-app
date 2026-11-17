package com.example.data.remote

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class SessionExpiredException(message: String = "Your session expired, please log in again") : Exception(message)

class ApiException(val code: Int, message: String) : Exception("API error $code: $message")

interface SessionTokenProvider {
    fun getAccessToken(): String?
    fun getRefreshToken(): String?
    fun onTokensUpdated(accessToken: String, refreshToken: String?, expiresInSeconds: Long?)
    fun onSessionExpired(message: String = "Your session expired, please log in again")
}

object SupabaseClient {
    private const val TAG = "SupabaseClient"

    @Volatile
    var customAnonKey: String? = null

    @Volatile
    var userAccessToken: String? = null

    @Volatile
    var sessionTokenProvider: SessionTokenProvider? = null

    private val refreshLock = Any()

    fun init(url: String = SupabaseConfig.BASE_URL, anonKey: String? = null) {
        if (!anonKey.isNullOrBlank()) {
            customAnonKey = anonKey
        }
    }

    fun isKeyConfigured(): Boolean {
        val key = getEffectiveAnonKey()
        return key.isNotBlank() &&
                !key.endsWith(".placeholder", ignoreCase = true) &&
                !key.contains("placeholder", ignoreCase = true) &&
                key != "DEFAULT_ANON_KEY"
    }

    fun getEffectiveAnonKey(): String {
        if (com.example.BuildConfig.DEBUG) {
            val custom = customAnonKey?.takeIf { it.isNotBlank() }
            if (custom != null) return custom
        }

        // Check if injected via BuildConfig (Secrets Gradle plugin from AI Studio secrets panel)
        val buildConfigKey = try {
            val field = com.example.BuildConfig::class.java.getField("SUPABASE_ANON_KEY")
            (field.get(null) as? String)?.takeIf {
                it.isNotBlank() &&
                        !it.contains("placeholder", ignoreCase = true) &&
                        it != "DEFAULT_ANON_KEY"
            }
        } catch (e: Exception) {
            null
        }
        if (buildConfigKey != null) return buildConfigKey

        return SupabaseConfig.DEFAULT_ANON_KEY
    }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val anonKey = getEffectiveAnonKey()
        val token = userAccessToken?.takeIf { it.isNotBlank() } ?: anonKey

        val requestBuilder = original.newBuilder()
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .method(original.method, original.body)

        val request = requestBuilder.build()
        val response = chain.proceed(request)

        if (!response.isSuccessful) {
            Log.w(TAG, "Request to ${request.url} returned HTTP ${response.code}: ${response.message}")
        }
        response
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }

    private val refreshHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    sealed interface TokenRefreshResult {
        data class Ok(val accessToken: String, val refreshToken: String?, val expiresIn: Long) : TokenRefreshResult
        data object Invalid : TokenRefreshResult
        data object NetworkError : TokenRefreshResult
    }

    @Volatile private var lastRefreshAtMs = 0L
    @Volatile private var lastRefreshOutcome: RefreshOutcome? = null

    enum class RefreshOutcome { SUCCESS, INVALID, NETWORK_ERROR }

    private fun executeSyncTokenRefreshDetailed(refreshToken: String): TokenRefreshResult {
        return try {
            val anonKey = getEffectiveAnonKey()
            val json = JSONObject().apply {
                put("refresh_token", refreshToken)
            }.toString()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toRequestBody(mediaType)

            val refreshRequest = Request.Builder()
                .url("${SupabaseConfig.BASE_URL}/auth/v1/token?grant_type=refresh_token")
                .header("apikey", anonKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            val response = refreshHttpClient.newCall(refreshRequest).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && !responseBody.isNullOrBlank()) {
                val jsonObj = JSONObject(responseBody)
                val newAccessToken = jsonObj.optString("access_token", "")
                val newRefreshToken = jsonObj.optString("refresh_token", "").takeIf { it.isNotBlank() } ?: refreshToken
                val expiresIn = jsonObj.optLong("expires_in", 3600L)
                if (newAccessToken.isNotBlank()) {
                    TokenRefreshResult.Ok(newAccessToken, newRefreshToken, expiresIn)
                } else {
                    TokenRefreshResult.Invalid
                }
            } else {
                val code = response.code
                Log.w(TAG, "Sync token refresh rejected HTTP $code")
                if (code == 400 || code == 401) {
                    TokenRefreshResult.Invalid
                } else {
                    TokenRefreshResult.NetworkError
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during sync token refresh: ${e.message}")
            TokenRefreshResult.NetworkError
        }
    }

    fun refreshTokensBlocking(): RefreshOutcome {
        val provider = sessionTokenProvider ?: return RefreshOutcome.INVALID
        synchronized(refreshLock) {
            if (System.currentTimeMillis() - lastRefreshAtMs < 30_000 && !userAccessToken.isNullOrBlank()) {
                return RefreshOutcome.SUCCESS // someone just refreshed
            }
            val refreshToken = provider.getRefreshToken() ?: return RefreshOutcome.INVALID
            // executeSyncTokenRefresh must distinguish: HTTP 400/401 -> INVALID; IOException/timeout/5xx -> NETWORK_ERROR
            val outcome = when (val r = executeSyncTokenRefreshDetailed(refreshToken)) {
                is TokenRefreshResult.Ok -> {
                    userAccessToken = r.accessToken
                    lastRefreshAtMs = System.currentTimeMillis()
                    provider.onTokensUpdated(r.accessToken, r.refreshToken ?: refreshToken, r.expiresIn)
                    RefreshOutcome.SUCCESS
                }
                TokenRefreshResult.Invalid -> RefreshOutcome.INVALID
                TokenRefreshResult.NetworkError -> RefreshOutcome.NETWORK_ERROR
            }
            lastRefreshOutcome = outcome
            return outcome
        }
    }

    private val tokenAuthenticator = Authenticator { _: Route?, response: Response ->
        val url = response.request.url.toString()
        // 1. Do not loop if the failed request itself was an auth endpoint
        if (url.contains("grant_type=refresh_token") || url.contains("/auth/v1/")) {
            return@Authenticator null
        }

        // 2. Prevent infinite retry loops (max 2 attempts)
        if (responseCount(response) >= 2) {
            Log.w(TAG, "Request to ${response.request.url} failed 401 twice; aborting retry.")
            if (lastRefreshOutcome != RefreshOutcome.NETWORK_ERROR) {
                sessionTokenProvider?.onSessionExpired("Your session expired, please log in again")
            }
            return@Authenticator null
        }

        val originalAuth = response.request.header("Authorization")

        synchronized(refreshLock) {
            val provider = sessionTokenProvider
            if (provider == null) {
                return@Authenticator null
            }

            val currentToken = userAccessToken
            // If another thread already refreshed the token since this request was initiated:
            if (!currentToken.isNullOrBlank() && "Bearer $currentToken" != originalAuth) {
                Log.d(TAG, "Reusing already-refreshed access token for retry.")
                return@Authenticator response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            Log.i(TAG, "HTTP 401 received: Refreshing Supabase auth session...")
            when (refreshTokensBlocking()) {
                RefreshOutcome.SUCCESS -> {
                    val newToken = userAccessToken
                    if (!newToken.isNullOrBlank()) {
                        Log.i(TAG, "Session refreshed successfully; retrying request.")
                        return@Authenticator response.request.newBuilder()
                            .header("Authorization", "Bearer $newToken")
                            .build()
                    }
                    return@Authenticator null
                }
                RefreshOutcome.INVALID -> {
                    Log.e(TAG, "Session refresh failed or refresh token expired.")
                    provider.onSessionExpired("Your session expired, please log in again")
                    return@Authenticator null
                }
                RefreshOutcome.NETWORK_ERROR -> {
                    Log.w(TAG, "Session refresh failed due to network error; aborting retry without logout.")
                    return@Authenticator null
                }
            }
        }
    }

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d(TAG, message)
    }.apply {
        level = if (com.example.BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
    }

    // OkHttp's default is 5 requests at a time per host. The Food and Tiffin screens start 15+ requests at once
    // to the same Supabase host, so most of them stood in a queue behind the first five. Allow more at once
    // (they share one HTTP/2 connection, so this costs almost nothing).
    private val dispatcher = okhttp3.Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 24
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    val api: SupabaseApi by lazy {
        Retrofit.Builder()
            .baseUrl("${SupabaseConfig.BASE_URL}/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SupabaseApi::class.java)
    }

    fun parseErrorMessage(response: retrofit2.Response<*>): String {
        return try {
            val errorBody = response.errorBody()?.string()
            if (!errorBody.isNullOrBlank()) {
                try {
                    val json = JSONObject(errorBody)
                    val msg = json.optString("message", json.optString("msg", json.optString("error_description", "")))
                    val hint = json.optString("hint", "")
                    val details = json.optString("details", "")
                    buildString {
                        if (msg.isNotBlank()) append(msg) else append(errorBody)
                        if (hint.isNotBlank()) append(" (Hint: $hint)")
                        if (details.isNotBlank()) append(" [$details]")
                    }
                } catch (e: Exception) {
                    errorBody
                }
            } else {
                "HTTP ${response.code()}: ${response.message()}"
            }
        } catch (e: Exception) {
            "Error HTTP ${response.code()}: ${response.message()}"
        }
    }
}
