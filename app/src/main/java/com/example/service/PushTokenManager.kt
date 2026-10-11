package com.example.service

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.remote.ApiException
import com.example.data.remote.SupabaseClient
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Makes sure a logged-in customer's FCM token is registered in `device_tokens`. Centralizes the
 * registration call site (login, every app start/resume, onNewToken) so it has one retry/backoff
 * policy and doesn't hammer the backend with a network call on every single app open.
 */
object PushTokenManager {
    private const val TAG = "PushTokenManager"
    private const val PREFS_NAME = "sndmart_push_token"
    private const val KEY_LAST_TOKEN = "last_token"
    private const val KEY_LAST_USER_ID = "last_user_id"
    private const val KEY_LAST_REGISTERED_AT_MS = "last_registered_at_ms"
    private const val KEY_PENDING_TOKEN = "pending_token"

    private const val DEDUPE_WINDOW_MS = 24 * 60 * 60 * 1000L
    private const val RETRY_DELAY_MS = 30_000L
    private const val MAX_ATTEMPTS = 3
    private const val GET_TOKEN_TIMEOUT_MS = 20_000L

    private val mutex = Mutex()

    /** Why the last FCM getToken call returned no token (null when it worked). */
    @Volatile
    private var lastTokenError: String? = null

    /** Stages already reported to push_registration_errors in this process (no repeats). */
    private val reportedStages = mutableSetOf<String>()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Called from CustomerFcmService.onNewToken when FCM hands out a token before the customer
     * is logged in. The token is stashed and registered the next time [ensurePushTokenRegistered]
     * runs (e.g. right after login).
     */
    fun stashPendingToken(context: Context, token: String) {
        if (token.isBlank()) return
        prefs(context).edit().putString(KEY_PENDING_TOKEN, token).apply()
    }

    /**
     * Called on logout, after the device_tokens row was marked inactive. Forgets the last
     * registration so the next login registers (re-activates) the token instead of skipping it
     * as "already registered within 24h". A pending token is kept.
     */
    fun forgetRegistration(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_TOKEN)
            .remove(KEY_LAST_USER_ID)
            .remove(KEY_LAST_REGISTERED_AT_MS)
            .apply()
    }

    /**
     * Registers the current FCM token for the logged-in customer, unless the same token was
     * already registered for this same user within the last 24 hours.
     *
     * Safe to call liberally (login, app start, every resume) - the 24h dedupe means most calls
     * are a SharedPreferences read and nothing else.
     */
    suspend fun ensurePushTokenRegistered(
        context: Context,
        sessionManager: UserSessionManager,
        repository: SndmartRepository
    ) {
        val userId = sessionManager.userId.value ?: sessionManager.getUserId()
        if (userId.isNullOrBlank()) return

        mutex.withLock {
            val p = prefs(context)
            val pending = p.getString(KEY_PENDING_TOKEN, null)
            val token = pending ?: getFcmTokenOrNull()
            if (token.isNullOrBlank()) {
                logDebug("No FCM token available, skipping registration")
                reportToBackend(repository, "get_token", lastTokenError ?: "FCM returned an empty token")
                return
            }

            val lastToken = p.getString(KEY_LAST_TOKEN, null)
            val lastUserId = p.getString(KEY_LAST_USER_ID, null)
            val lastAtMs = p.getLong(KEY_LAST_REGISTERED_AT_MS, 0L)
            val withinDedupeWindow = System.currentTimeMillis() - lastAtMs < DEDUPE_WINDOW_MS
            if (pending == null && token == lastToken && userId == lastUserId && withinDedupeWindow) {
                logDebug("Token already registered for this user within 24h, skipping")
                return
            }

            var attempt = 0
            var usedSessionRefresh = false
            while (attempt < MAX_ATTEMPTS) {
                attempt++
                val result = repository.registerDeviceToken(userId, token)
                if (result.isSuccess) {
                    p.edit()
                        .putString(KEY_LAST_TOKEN, token)
                        .putString(KEY_LAST_USER_ID, userId)
                        .putLong(KEY_LAST_REGISTERED_AT_MS, System.currentTimeMillis())
                        .remove(KEY_PENDING_TOKEN)
                        .apply()
                    logDebug("Push token registered on attempt $attempt")
                    return
                }

                val httpCode = (result.exceptionOrNull() as? ApiException)?.code
                logDebug("Push token registration failed on attempt $attempt (httpCode=$httpCode)")
                if (attempt == MAX_ATTEMPTS) {
                    reportNonFatal("device_tokens registration failed (httpCode=$httpCode)", result.exceptionOrNull())
                    reportToBackend(repository, "register", "httpCode=$httpCode ${describe(result.exceptionOrNull())}")
                }

                if (httpCode == 401 && !usedSessionRefresh) {
                    usedSessionRefresh = true
                    SupabaseClient.refreshTokensBlocking()
                    continue // retry immediately with the refreshed session, no backoff delay
                }

                if (attempt < MAX_ATTEMPTS) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    private suspend fun getFcmTokenOrNull(): String? {
        lastTokenError = null
        val token = withTimeoutOrNull(GET_TOKEN_TIMEOUT_MS) { requestFcmToken() }
        if (token == null && lastTokenError == null) {
            lastTokenError = "FCM getToken did not answer within ${GET_TOKEN_TIMEOUT_MS / 1000}s"
        }
        return token
    }

    private suspend fun requestFcmToken(): String? = suspendCancellableCoroutine { cont ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    lastTokenError = "FCM getToken failed: ${describe(task.exception)}"
                    reportNonFatal("FCM getToken failed", task.exception)
                }
                if (cont.isActive) {
                    cont.resumeWith(Result.success(if (task.isSuccessful) task.result else null))
                }
            }
        } catch (e: Throwable) {
            lastTokenError = "FirebaseMessaging unavailable: ${describe(e)}"
            reportNonFatal("FirebaseMessaging unavailable", e)
            if (cont.isActive) {
                cont.resumeWith(Result.success(null))
            }
        }
    }

    private fun describe(e: Throwable?): String {
        if (e == null) return "no exception"
        val parts = generateSequence(e) { it.cause }.take(3)
            .map { "${it.javaClass.simpleName}: ${it.message}" }
        return parts.joinToString(" <- ")
    }

    /** Writes the failure to push_registration_errors (once per stage per app run). */
    private suspend fun reportToBackend(repository: SndmartRepository, stage: String, message: String) {
        synchronized(reportedStages) {
            if (!reportedStages.add(stage)) return
        }
        repository.reportPushRegistrationError(stage, message)
    }

    /** Release builds log nothing, so push failures go to Crashlytics as non-fatals. */
    private fun reportNonFatal(message: String, cause: Throwable?) {
        Log.w(TAG, message, cause)
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .recordException(IllegalStateException(message, cause))
        } catch (_: Throwable) {
            // Crashlytics not initialized (no google-services.json)
        }
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }
}
