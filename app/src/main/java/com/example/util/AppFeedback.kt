package com.example.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tactile feedback, same behaviour as the website: a short vibration only. No sounds.
 */
object AppFeedback {
    private var appContext: Context? = null

    /** Set by a control that plays its own feedback (cart stepper) so the global tap tick skips that touch. */
    @Volatile var suppressNextTap = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    // ---- public effects (mirror the website's feedback.* helpers) ----

    /** Any button tap: barely-there tick. */
    fun tap() = vibrate(8)

    /** Item added to cart. */
    fun add() = vibrate(14)

    /** Item removed / quantity reduced. */
    fun remove() = vibrate(10)

    /** Order placed. */
    fun success() = vibrate(longArrayOf(0, 40, 60, 40, 60, 90))

    fun error() = vibrate(longArrayOf(0, 30, 40, 30))

    // ---- internals ----

    private fun vibrator(): Vibrator? {
        val ctx = appContext ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun vibrate(ms: Long) {
        try {
            val v = vibrator() ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        } catch (_: Exception) {
        }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val v = vibrator() ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
        } catch (_: Exception) {
        }
    }
}

/** Drives the full-screen "Order placed" moment; shown by OrderSuccessHost mounted at the app root. */
object OrderCelebration {
    var shownAt by mutableStateOf<Long?>(null)
        private set

    /** Call right after the server confirms the order (or the payment): vibration + full-screen tick. */
    fun show() {
        AppFeedback.success()
        shownAt = System.currentTimeMillis()
    }

    fun hide() {
        shownAt = null
    }
}
