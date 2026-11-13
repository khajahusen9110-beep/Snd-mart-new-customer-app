package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Tactile feedback, same behaviour as the website: a short vibration and tiny synthesized sounds
 * (no audio files). Sounds can be switched off from Profile; the choice is kept on this device.
 */
object AppFeedback {
    private const val PREFS = "sndmart_feedback"
    private const val KEY_SOUNDS = "sounds_on"

    private var appContext: Context? = null
    private var soundsOn = true

    /** Set by a control that plays its own feedback (cart stepper) so the global tap tick skips that touch. */
    @Volatile var suppressNextTap = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        soundsOn = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SOUNDS, true)
    }

    fun soundsEnabled(): Boolean = soundsOn

    fun setSoundsEnabled(on: Boolean) {
        soundsOn = on
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_SOUNDS, on)?.apply()
    }

    // ---- public effects (mirror the website's feedback.* helpers) ----

    /** Any button tap: barely-there tick. */
    fun tap() {
        vibrate(8)
        play(Note(1500.0, 0.0, 0.035, 0.05))
    }

    /** Item added to cart: a soft rising pop. */
    fun add() {
        vibrate(14)
        play(Note(520.0, 0.0, 0.09, 0.16, slideTo = 880.0))
    }

    /** Item removed / quantity reduced: a soft falling note. */
    fun remove() {
        vibrate(10)
        play(Note(620.0, 0.0, 0.08, 0.12, slideTo = 380.0))
    }

    /** Order placed: a three-note chime. */
    fun success() {
        vibrate(longArrayOf(0, 40, 60, 40, 60, 90))
        play(
            Note(659.25, 0.0, 0.16, 0.2),
            Note(783.99, 0.12, 0.16, 0.2),
            Note(1046.5, 0.24, 0.34, 0.22)
        )
    }

    fun error() {
        vibrate(longArrayOf(0, 30, 40, 30))
        play(Note(240.0, 0.0, 0.14, 0.14, slideTo = 180.0))
    }

    // ---- internals ----

    private class Note(val freq: Double, val startSec: Double, val lengthSec: Double, val volume: Double, val slideTo: Double? = null)

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

    private const val SAMPLE_RATE = 22050

    /** Renders the notes into one PCM buffer and plays it on a short-lived AudioTrack (UI-sound usage). */
    private fun play(vararg notes: Note) {
        if (!soundsOn || appContext == null) return
        Thread {
            try {
                val totalSec = notes.maxOf { it.startSec + it.lengthSec } + 0.03
                val samples = ShortArray((totalSec * SAMPLE_RATE).toInt() + 1)
                val mix = DoubleArray(samples.size)
                for (n in notes) {
                    val start = (n.startSec * SAMPLE_RATE).toInt()
                    val len = (n.lengthSec * SAMPLE_RATE).toInt()
                    var phase = 0.0
                    for (i in 0 until len) {
                        val t = i.toDouble() / len
                        val f = if (n.slideTo != null) n.freq * (n.slideTo / n.freq).pow(t) else n.freq
                        phase += 2 * PI * f / SAMPLE_RATE
                        // 8 ms attack, exponential decay: no click at either end
                        val attack = if (i < SAMPLE_RATE * 0.008) i / (SAMPLE_RATE * 0.008) else 1.0
                        val env = attack * exp(-4.0 * t)
                        val idx = start + i
                        if (idx < mix.size) mix[idx] += sin(phase) * env * n.volume
                    }
                }
                for (i in mix.indices) samples[i] = (mix[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.setNotificationMarkerPosition(samples.size - 1)
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) {
                        try { t?.release() } catch (_: Exception) {}
                    }
                    override fun onPeriodicNotification(t: AudioTrack?) {}
                })
                track.play()
            } catch (_: Exception) {
                // no audio device / sandbox: feedback is optional
            }
        }.start()
    }
}

/** Drives the full-screen "Order placed" moment; shown by OrderSuccessHost mounted at the app root. */
object OrderCelebration {
    var shownAt by mutableStateOf<Long?>(null)
        private set

    /** Call right after the server confirms the order (or the payment): chime + full-screen tick. */
    fun show() {
        AppFeedback.success()
        shownAt = System.currentTimeMillis()
    }

    fun hide() {
        shownAt = null
    }
}
