package com.example.data.repository

import com.example.data.model.HelperBooking
import com.example.data.model.HelperConfig
import com.example.data.remote.SupabaseClient
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import retrofit2.Response

/** Quick Helper calls. Everything (price, rules, codes) is decided by the server; this only sends requests and reads answers. */
object HelperRepository {
    private val api get() = SupabaseClient.api

    private fun readText(res: Response<okhttp3.ResponseBody>): String =
        try { (if (res.isSuccessful) res.body()?.string() else res.errorBody()?.string()).orEmpty() } catch (e: Exception) { "" }

    private fun failure(res: Response<*>, raw: String, fallback: String): Exception {
        val msg = try {
            JSONObject(raw).let { o -> listOf("message", "error", "msg").map { o.optString(it, "") }.firstOrNull { it.isNotBlank() } }
        } catch (e: Exception) { null }
        return Exception(msg ?: "$fallback (HTTP ${res.code()})")
    }

    private suspend fun <T> call(fallback: String, block: suspend () -> Response<okhttp3.ResponseBody>, parse: (String) -> T): Result<T> =
        try {
            val res = block()
            val raw = readText(res)
            if (res.isSuccessful) Result.success(parse(raw)) else Result.failure(failure(res, raw, fallback))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Null when it cannot be read: treat Quick Helper as off. */
    suspend fun getConfig(cityId: String): HelperConfig? =
        call("Could not load Quick Helper", { api.helperGetConfig(mapOf("p_city_id" to cityId)) }) { HelperConfig.from(JSONObject(it)) }.getOrNull()

    suspend fun create(
        cityId: String, addressId: String, categoryId: String, description: String, photoPaths: List<String>, minutes: Int,
        requestedForIso: String?, paymentMethod: String, rulesAccepted: Boolean
    ): Result<HelperBooking> =
        call("Could not book", {
            api.helperCreate(mapOf(
                "p_city_id" to cityId, "p_address_id" to addressId, "p_category_id" to categoryId, "p_description" to description,
                "p_photo_paths" to photoPaths, "p_minutes" to minutes, "p_requested_for" to requestedForIso,
                "p_payment_method" to paymentMethod, "p_rules_accepted" to rulesAccepted
            ))
        }) { raw -> JSONObject(raw).let { HelperBooking.from(it) } }

    suspend fun mine(): Result<List<HelperBooking>> =
        call("Could not load your bookings", { api.helperMyBookings(emptyMap()) }) { HelperBooking.list(it) }

    suspend fun get(id: String): Result<HelperBooking> =
        call("Could not load the booking", { api.helperGetBooking(mapOf("p_id" to id)) }) { HelperBooking.from(JSONObject(it)) }

    suspend fun cancel(id: String): Result<Unit> =
        call("Could not cancel", { api.helperCancel(mapOf("p_id" to id)) }) { }

    suspend fun rate(id: String, stars: Int, review: String): Result<Unit> =
        call("Could not save the rating", { api.helperRate(mapOf("p_id" to id, "p_rating" to stars, "p_review" to review.ifBlank { null })) }) { }

    /** Stores a (already shrunk) photo in the customer's own folder of the private bucket. Returns the path. */
    suspend fun uploadPhoto(userId: String, jpegBytes: ByteArray): Result<String> {
        val path = "$userId/${java.util.UUID.randomUUID()}.jpg"
        return try {
            val res = api.uploadHelperPhoto(path, okhttp3.RequestBody.create("image/jpeg".toMediaTypeOrNull(), jpegBytes))
            if (res.isSuccessful) Result.success(path) else Result.failure(Exception("Photo upload failed (HTTP ${res.code()})"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    class PayOrder(val keyId: String, val amountPaise: Long, val currency: String, val razorpayOrderId: String)

    /** The Razorpay order for a booking that waits for its advance payment. */
    suspend fun createPayment(bookingId: String): Result<PayOrder> =
        call("Could not start payment", { api.helperPayment(mapOf("action" to "create", "booking_id" to bookingId)) }) {
            val o = JSONObject(it)
            PayOrder(o.getString("key_id"), o.getLong("amount"), o.optString("currency", "INR"), o.getString("razorpay_order_id"))
        }

    suspend fun verifyPayment(bookingId: String, orderId: String, paymentId: String, signature: String): Result<Unit> =
        call("Could not confirm the payment", {
            api.helperPayment(mapOf("action" to "verify", "booking_id" to bookingId, "razorpay_order_id" to orderId, "razorpay_payment_id" to paymentId, "razorpay_signature" to signature))
        }) { }

    /** Asks the server to check Razorpay (a payment the app never reported) and to send a refund that is due. Never fails. */
    suspend fun syncPayment(bookingId: String) {
        call("sync", { api.helperPayment(mapOf("action" to "sync", "booking_id" to bookingId)) }) { }
    }
}
