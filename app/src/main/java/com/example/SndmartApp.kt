package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.session.UserSessionManager
import java.io.File

class SndmartApp : Application(), ImageLoaderFactory {

    lateinit var sessionManager: UserSessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        cleanupCorruptedWebViewCache()
        sessionManager = UserSessionManager(this)
        com.example.util.AppFeedback.init(this)
        createNotificationChannel()
        initFirebaseSafety()
        com.example.data.remote.GoogleMapsConfig.initializePlaces(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 25% of available app memory
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02) // 2% of device storage
                    .build()
            }
            .crossfade(true)
            .build()
    }

    /**
     * Cleans up any incomplete or corrupted WebView cache directories left by previous
     * runs or crashes. If an HTTP Cache or Code Cache directory exists without Chromium's
     * index file ("the-real-index"), Chromium's simple_file_enumerator attempts and fails
     * to reconstruct the index from disk, logging errors. Purging incomplete cache directories
     * allows Chromium to initialize cleanly with a fresh index structure.
     */
    private fun cleanupCorruptedWebViewCache() {
        try {
            val webViewDir = File(cacheDir, "WebView")
            if (webViewDir.exists()) {
                val httpCacheDir = File(webViewDir, "Default/HTTP Cache")
                if (httpCacheDir.exists()) {
                    val indexFile = File(httpCacheDir, "index-dir/the-real-index")
                    val codeCacheDir = File(httpCacheDir, "Code Cache")
                    if (!indexFile.exists() || (codeCacheDir.exists() && !File(codeCacheDir, "js/index-dir/the-real-index").exists())) {
                        httpCacheDir.deleteRecursively()
                    }
                }
            }
        } catch (e: Throwable) {
            // Safe fallback if directory operations fail
        }
    }

    private fun initFirebaseSafety() {
        try {
            // Auto-init must stay enabled - this is what makes FCM actually generate/refresh a
            // token and deliver it to CustomerFcmService.onNewToken. Disabling it was silently
            // starving device_tokens of almost every new install/login (see PushTokenManager).
            com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = true
        } catch (e: Throwable) {
            // FirebaseApp or Play Services not present/initialized
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_ORDERS,
                "Order Status Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for active Sndmart order progress, dispatch, and delivery"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID_ORDERS = "sndmart_order_notifications"
        lateinit var instance: SndmartApp
            private set
    }
}
