package com.example.data.repository

import com.example.data.model.*
import com.example.data.remote.SupabaseClient
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Tiffin Pickup calls. Everything is decided by the server; this only sends requests and reads answers. */
object TiffinRepository {
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

    /** Null when it cannot be read (e.g. the backend does not have it yet): treat Tiffin as off. */
    suspend fun getConfig(cityId: String): TiffinConfig? =
        call("Could not load Tiffin Pickup", { api.tiffinGetConfig(mapOf("p_city_id" to cityId)) }) { TiffinConfig.from(JSONObject(it)) }.getOrNull()

    suspend fun getQuote(pickupId: String, dropId: String, days: List<Int>, returnTrip: Boolean, startDate: String): Result<TiffinQuote> =
        call("Could not get the price", {
            api.tiffinQuote(mapOf("p_pickup_address_id" to pickupId, "p_drop_address_id" to dropId, "p_days" to days, "p_return" to returnTrip, "p_start_date" to startDate))
        }) { TiffinQuote.from(JSONObject(it)) }

    // The server ignores the return time unless a return trip is chosen (null params are dropped by the JSON writer).
    suspend fun createPlan(i: TiffinPlanInput): Result<String> =
        call("Could not create the plan", {
            api.tiffinSubscribe(mapOf(
                "p_pickup_address_id" to i.pickupAddressId, "p_drop_address_id" to i.dropAddressId, "p_kind" to i.kind,
                "p_recipient_name" to i.recipientName, "p_recipient_detail" to i.recipientDetail, "p_handover_note" to i.handoverNote,
                "p_days" to i.days, "p_pickup_time" to i.pickupTime, "p_return" to i.returnTrip,
                "p_return_pickup_time" to (if (i.returnTrip) i.returnPickupTime else i.pickupTime), "p_start_date" to i.startDate
            ))
        }) { JSONObject(it).getString("id") }

    suspend fun myPlans(): Result<List<TiffinPlan>> =
        call("Could not load your tiffin plans", { api.tiffinMySubscriptions(emptyMap()) }) {
            val a = JSONArray(it)
            (0 until a.length()).map { i -> TiffinPlan.from(a.getJSONObject(i)) }
        }

    suspend fun skipDay(planId: String, date: String, skip: Boolean): Result<Unit> =
        call("Could not change that day", { api.tiffinSkipDay(mapOf("p_subscription_id" to planId, "p_date" to date, "p_skip" to skip)) }) { }

    suspend fun setPaused(planId: String, paused: Boolean): Result<Unit> =
        call(if (paused) "Could not pause the plan" else "Could not resume the plan", { api.tiffinSetPaused(mapOf("p_subscription_id" to planId, "p_paused" to paused)) }) { }

    suspend fun cancelRequest(planId: String): Result<Unit> =
        call("Could not cancel the request", { api.tiffinCancelRequest(mapOf("p_subscription_id" to planId)) }) { }

    // ---- monthly auto-pay (edge function `tiffin-autopay`) ----

    suspend fun startAutopay(planId: String): Result<TiffinCheckout> =
        call("Could not start payment", { api.tiffinAutopay(mapOf("action" to "create", "subscription_id" to planId)) }) {
            val o = JSONObject(it)
            TiffinCheckout(o.getString("key_id"), o.getString("razorpay_subscription_id"), o.flag("is_trial"), o.num("pay_now"), o.num("monthly"),
                o.optString("short_url").takeIf { u -> u.startsWith("https://") })
        }

    suspend fun verifyAutopay(planId: String, paymentId: String, subscriptionId: String, signature: String): Result<Unit> =
        call("Could not confirm the payment", {
            api.tiffinAutopay(mapOf("action" to "verify", "subscription_id" to planId, "razorpay_payment_id" to paymentId,
                "razorpay_subscription_id" to subscriptionId, "razorpay_signature" to signature))
        }) { }

    /** Re-checks a payment with Razorpay and applies it (rescues "money taken but plan not started"). Never fails the screen. */
    suspend fun syncAutopay(planId: String) {
        call("Could not check the payment", { api.tiffinAutopay(mapOf("action" to "sync", "subscription_id" to planId)) }) { }
    }

    suspend fun stopAutopay(planId: String): Result<Unit> =
        call("Could not stop auto-pay", { api.tiffinAutopay(mapOf("action" to "stop", "subscription_id" to planId)) }) { }

    /** A short-lived link to a handover photo (private bucket; only the plan's owner can read it). */
    suspend fun photoUrl(path: String): String? = try {
        val res = api.tiffinSignPhoto(path, mapOf("expiresIn" to 300))
        val raw = readText(res)
        if (res.isSuccessful) JSONObject(raw).optString("signedURL").takeIf { it.isNotBlank() }?.let { s ->
            val base = com.example.data.remote.SupabaseConfig.BASE_URL.trimEnd('/')
            if (s.startsWith("http")) s else "$base/storage/v1${if (s.startsWith("/")) s else "/$s"}"
        } else null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    // ---- dates: the service runs on India time, whatever the phone clock says ----
    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }

    fun indiaToday(): String = fmt().format(Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).time)

    fun addDays(date: String, n: Int): String {
        val f = fmt()
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply { time = f.parse(date)!!; add(Calendar.DAY_OF_MONTH, n) }
        return f.format(c.time)
    }

    /** ISO weekday: 1 = Monday .. 7 = Sunday. */
    fun isoWeekday(date: String): Int {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply { time = fmt().parse(date)!! }
        return when (c.get(Calendar.DAY_OF_WEEK)) { Calendar.SUNDAY -> 7; else -> c.get(Calendar.DAY_OF_WEEK) - 1 }
    }

    fun dayLabel(date: String, lang: String = "en"): String = try {
        SimpleDateFormat("EEE, d MMM", if (lang == "kn") Locale("kn", "IN") else Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(fmt().parse(date)!!)
    } catch (e: Exception) { date }

    /** "07:30:00" or "07:30" -> "7:30 AM". */
    fun timeLabel(t: String?): String {
        if (t.isNullOrBlank()) return ""
        val p = t.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return t
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        return "%d:%02d %s".format(((h + 11) % 12) + 1, m, if (h >= 12) "PM" else "AM")
    }

    /** The plan's next delivery days (from tomorrow); skipped ones included so they can be un-skipped. */
    fun upcomingPlanDays(plan: TiffinPlan, count: Int = 7): List<String> {
        val out = ArrayList<String>()
        var d = addDays(indiaToday(), 1)
        if (d < plan.startDate) d = plan.startDate
        var guard = 0
        while (guard++ < 45 && out.size < count && d <= plan.endDate) {
            if (plan.days.contains(isoWeekday(d))) out.add(d)
            d = addDays(d, 1)
        }
        return out
    }
}
