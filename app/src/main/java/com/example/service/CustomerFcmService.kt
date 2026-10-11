package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.OrderDetailActivity
import com.example.R
import com.example.SndmartApp
import com.example.data.repository.SndmartRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class InAppNotification(
    val title: String,
    val body: String,
    val orderId: String?
)

open class CustomerFcmService : FirebaseMessagingService() {

    private fun extractOrderId(message: RemoteMessage): String? {
        val d = message.data
        d["orderId"]?.takeIf { it.isNotBlank() }?.let { return it }
        d["order_id"]?.takeIf { it.isNotBlank() }?.let { return it }
        // Nested JSON string, e.g. data = {"data": "{\"order_id\":\"...\"}"}
        d["data"]?.let { raw ->
            try {
                val obj = org.json.JSONObject(raw)
                obj.optString("order_id").takeIf { it.isNotBlank() }?.let { return it }
                obj.optString("orderId").takeIf { it.isNotBlank() }?.let { return it }
            } catch (_: Exception) { }
        }
        for (key in listOf("order", "payload", "notification")) {
            d[key]?.let { raw ->
                try {
                    val obj = org.json.JSONObject(raw)
                    obj.optString("order_id").takeIf { it.isNotBlank() }?.let { return it }
                    obj.optString("orderId").takeIf { it.isNotBlank() }?.let { return it }
                } catch (_: Exception) { }
            }
        }
        // Only treat "id" as an order id if the notification type is order-related
        val type = d["type"]?.lowercase()
        if (type != null && type.contains("order")) {
            d["id"]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return d["id"]?.takeIf { it.isNotBlank() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (com.example.BuildConfig.DEBUG) {
            Log.d(TAG, "Customer FCM raw message data keys: ${message.data.keys}, map=${message.data}")
        }
        val orderId = extractOrderId(message)
        val title = message.data["title"]
            ?: message.notification?.title
            ?: "Sndmart"
        val body = message.data["message"]
            ?: message.data["body"]
            ?: message.notification?.body
            ?: "Your order has an update"

        Log.d(TAG, "Customer FCM message received: orderId=$orderId, title=$title, body=$body")

        // Update in-memory unread count
        val app = application as? SndmartApp
        app?.sessionManager?.let { sm ->
            sm.setUnreadNotificationCount(sm.unreadNotificationCount.value + 1)
        }

        // Broadcast in-app event for foreground banners
        _inAppEvents.tryEmit(InAppNotification(title, body, orderId))

        // Create intent opening OrderDetailActivity
        val intent = Intent(this, OrderDetailActivity::class.java).apply {
            putExtra("from_notification", true)
            if (!orderId.isNullOrBlank()) {
                putExtra("order_id", orderId)
                putExtra("orderId", orderId)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val notificationId = orderId?.hashCode() ?: System.currentTimeMillis().toInt()
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "order_updates"
        val channel = NotificationChannel(
            channelId,
            "Order Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Order status updates, dispatch and delivery progress"
            enableVibration(true)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)

        // Zomato-style: Sndmart logo as the large icon, brand colour, and the photo the city
        // admin chose (data.image_url, e.g. the ordered product) as a big picture.
        val logo = loadLogo()
        val picture = message.data["image_url"]?.takeIf { it.startsWith("https://") }?.let { downloadImage(it) }
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        if (logo != null) builder.setLargeIcon(logo)
        if (picture != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as Bitmap?) // hide the logo when expanded so the photo has room
                    .setSummaryText(body)
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        val notification = builder.build()

        notificationManager?.notify(notificationId, notification)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Customer FCM onNewToken received")
        val app = SndmartApp.instance
        // Always stash it: even if we're logged in and register it below right away, this also
        // covers the case where registration fails and a later ensurePushTokenRegistered() call
        // (app resume, etc.) needs to know the token changed and must re-register, not dedupe.
        PushTokenManager.stashPendingToken(app, token)
        val userId = app.sessionManager.userId.value ?: app.sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            Log.d(TAG, "No active session yet; new token stashed, will register after login")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = SndmartRepository(sessionManager = app.sessionManager)
                PushTokenManager.ensurePushTokenRegistered(app, app.sessionManager, repo)
            } catch (e: Exception) {
                Log.e(TAG, "Error registering FCM token onNewToken", e)
            }
        }
    }

    /** Sndmart logo, scaled down from the 1024 px app icon. */
    private fun loadLogo(): Bitmap? = try {
        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
        BitmapFactory.decodeResource(resources, R.drawable.sndmart_icon, options)
    } catch (e: Throwable) {
        Log.w(TAG, "Could not load notification logo: ${e.message}")
        null
    }

    /** Downloads the notification photo (onMessageReceived already runs off the main thread). */
    private fun downloadImage(url: String): Bitmap? = try {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { stream ->
                val bytes = stream.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1024) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not download notification image: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "CustomerFcmService"
        private const val BRAND_COLOR = 0xFF0B7A62.toInt()
        private val _inAppEvents = MutableSharedFlow<InAppNotification>(extraBufferCapacity = 5)
        val inAppEvents: SharedFlow<InAppNotification> = _inAppEvents.asSharedFlow()

        fun postInAppEvent(event: InAppNotification): Boolean = _inAppEvents.tryEmit(event)
    }
}

/**
 * Backward compatibility alias for any existing references.
 */
class SndmartMessagingService : CustomerFcmService() {
    companion object {
        val inAppEvents: SharedFlow<InAppNotification> get() = CustomerFcmService.inAppEvents
        fun postInAppEvent(event: InAppNotification): Boolean = CustomerFcmService.postInAppEvent(event)
    }
}
