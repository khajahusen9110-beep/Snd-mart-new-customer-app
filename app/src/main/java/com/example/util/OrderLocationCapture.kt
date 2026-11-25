package com.example.util

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.data.location.HighAccuracyLocationManager
import com.example.data.repository.SndmartRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The customer's exact position at the moment the order is placed, sent to the City Admin (and the delivery partner) with the
 * order, with its accuracy. Many customers cannot place the address pin correctly; this is the real place they stand.
 * It runs next to the checkout call, never delays or blocks the order, and does nothing without the location permission.
 */
object OrderLocationCapture {
    private const val TAG = "OrderLocation"
    private const val CAPTURE_TIMEOUT_MS = 15_000L

    // Outlives the checkout screen: the order may already be on the success screen when the fix arrives.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Starts the position search now (returns at once). The result is null without permission, with GPS off, or on no fix. */
    fun start(context: Context): Deferred<Location?> {
        val app = context.applicationContext
        return appScope.async {
            if (!HighAccuracyLocationManager.hasAnyLocationPermission(app)) return@async null
            try {
                withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { HighAccuracyLocationManager.getAccurateGpsLocation(app) { } }
            } catch (e: Exception) {
                Log.w(TAG, "No position: ${e.message}")
                null
            }
        }
    }

    /** Waits for the search and attaches the position to the placed order; failures are ignored (the order is already placed). */
    fun attach(repository: SndmartRepository, orderId: String, capture: Deferred<Location?>) {
        if (orderId.isBlank()) return
        appScope.launch {
            val loc = try { capture.await() } catch (e: Exception) { null } ?: return@launch
            if (loc.latitude == 0.0 && loc.longitude == 0.0) return@launch
            val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
            repository.recordOrderLocation(orderId, loc.latitude, loc.longitude, acc)
        }
    }
}
