package com.example.data.analytics

import android.os.Build
import android.util.Log
import com.example.data.remote.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Lightweight, fire-and-forget session + event tracking for the Super Admin analytics
 * dashboard — mirrors new-web-customer/src/lib/analytics.ts so both platforms feed the same
 * analytics_sessions/analytics_events tables with the same event_name vocabulary. Every call
 * here swallows its own errors; tracking must never crash the app or delay a real user action
 * (cart, checkout, order placement). No PII beyond the authenticated user id is ever sent.
 */
object Analytics {
    private const val TAG = "Analytics"
    private const val HEARTBEAT_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionId: String? = null
    private var userId: String? = null
    private var started = false
    private var heartbeatJob: Job? = null

    private fun nowIso(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    /** Call once near app bootstrap (MainActivity), after the Supabase client is ready. */
    fun init(userId: String?) {
        this.userId = userId
        if (started) return
        started = true
        val id = UUID.randomUUID().toString()
        sessionId = id

        scope.launch {
            try {
                if (!SupabaseClient.isKeyConfigured()) return@launch
                val body = buildMap<String, Any> {
                    put("id", id)
                    this@Analytics.userId?.let { put("user_id", it) }
                    put("is_authenticated", this@Analytics.userId != null)
                    put("device_type", "mobile")
                    put("os", "Android ${Build.VERSION.RELEASE}")
                    put("browser", "AndroidApp")
                    put("entry_page", "Home")
                    put("last_seen_at", nowIso())
                }
                SupabaseClient.api.upsertAnalyticsSession(body)
            } catch (e: Exception) {
                Log.w(TAG, "session init failed: ${e.message}")
            }
        }
        resumeHeartbeat()
    }

    /** Call when the user id becomes known/changes (login/logout) after [init] already ran. */
    fun setUserId(userId: String?) {
        this.userId = userId
    }

    /** Resume the periodic heartbeat — call from ON_RESUME. Safe to call repeatedly. */
    fun resumeHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                heartbeatOnce()
                delay(HEARTBEAT_MS)
            }
        }
    }

    /** Pause the periodic heartbeat while the app is backgrounded — call from ON_PAUSE/ON_STOP. */
    fun pauseHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun heartbeatOnce() {
        val id = sessionId ?: return
        scope.launch {
            try {
                if (!SupabaseClient.isKeyConfigured()) return@launch
                SupabaseClient.api.touchAnalyticsSession("eq.$id", mapOf("last_seen_at" to nowIso()))
            } catch (e: Exception) {
                Log.w(TAG, "heartbeat failed: ${e.message}")
            }
        }
    }

    /** Call on every screen/route change. */
    fun trackPageView(page: String) {
        trackEvent("page_view", page = page)
        // A page view is also a sign of life — refresh last_seen_at without waiting for the
        // next heartbeat tick, so "Live Active Users" reacts promptly to real navigation.
        heartbeatOnce()
    }

    /**
     * Generic event tracker: product_view, add_to_cart, category_select, search,
     * checkout_started, payment_method_selected, order_placed, login_success,
     * registration_success, ... Fire-and-forget — never call this from a path that awaits
     * its result; cart/checkout/order placement must never wait on analytics.
     */
    fun trackEvent(eventName: String, metadata: Map<String, Any?>? = null, page: String? = null) {
        val id = sessionId ?: return
        scope.launch {
            try {
                if (!SupabaseClient.isKeyConfigured()) return@launch
                val body = buildMap<String, Any> {
                    put("session_id", id)
                    this@Analytics.userId?.let { put("user_id", it) }
                    put("event_name", eventName)
                    page?.let { put("page", it) }
                    val clean = metadata?.filterValues { it != null }
                    if (!clean.isNullOrEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        put("metadata", clean as Map<String, Any>)
                    }
                }
                SupabaseClient.api.insertAnalyticsEvent(body)
            } catch (e: Exception) {
                Log.w(TAG, "trackEvent($eventName) failed: ${e.message}")
            }
        }
    }
}
