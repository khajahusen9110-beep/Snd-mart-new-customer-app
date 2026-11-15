package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

// Tiffin Pickup: a monthly plan where a delivery partner collects a home-made tiffin every chosen day and
// hands it over at a school/office (optionally bringing the empty box back). The server computes every
// price and enforces every rule; these are plain read models parsed from its answers.

internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
internal fun JSONObject.num(key: String, default: Double = 0.0): Double = if (isNull(key)) default else optDouble(key, default)
internal fun JSONObject.int(key: String, default: Int = 0): Int = if (isNull(key)) default else optInt(key, default)
internal fun JSONObject.flag(key: String, default: Boolean = false): Boolean = if (isNull(key)) default else optBoolean(key, default)

data class TiffinSlab(val upToKm: Double, val price: Double, val returnPrice: Double)

data class TiffinConfig(
    val enabled: Boolean,
    val slabs: List<TiffinSlab>,
    val fullMonthTrips: Int,
    val earliestPickup: String,
    val latestPickup: String,
    val latestReturnPickup: String,
    val skipCutoffHour: Int,
    val trialEnabled: Boolean,
    val trialDays: Int,
    val trialPrice: Double
) {
    companion object {
        fun from(o: JSONObject): TiffinConfig {
            val slabs = o.optJSONArray("slabs")?.let { a ->
                (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    TiffinSlab(s.num("up_to_km"), s.num("price"), s.num("return_price"))
                }
            } ?: emptyList()
            return TiffinConfig(
                enabled = o.flag("enabled"),
                slabs = slabs,
                fullMonthTrips = o.int("full_month_trips", 26),
                earliestPickup = o.str("earliest_pickup") ?: "06:30",
                latestPickup = o.str("latest_pickup") ?: "13:30",
                latestReturnPickup = o.str("latest_return_pickup") ?: "18:30",
                skipCutoffHour = o.int("skip_cutoff_hour", 21),
                trialEnabled = o.flag("trial_enabled"),
                trialDays = o.int("trial_days", 7),
                trialPrice = o.num("trial_price", 9.0)
            )
        }
    }
}

data class TiffinQuote(
    val ok: Boolean,
    val message: String?,
    val distanceKm: Double,
    val trips: Int,
    val priceOneWay: Double,
    val priceReturn: Double,
    val total: Double,
    val trialAvailable: Boolean,
    val trialDays: Int,
    val trialPrice: Double,
    val trialTrips: Int,
    val firstChargeDate: String?
) {
    companion object {
        fun from(o: JSONObject) = TiffinQuote(
            ok = o.flag("ok"), message = o.str("message"), distanceKm = o.num("distance_km"), trips = o.int("trips"),
            priceOneWay = o.num("price_one_way"), priceReturn = o.num("price_return"), total = o.num("total"),
            trialAvailable = o.flag("trial_available"), trialDays = o.int("trial_days"), trialPrice = o.num("trial_price"),
            trialTrips = o.int("trial_trips"), firstChargeDate = o.str("first_charge_date")
        )
    }
}

data class TiffinTripInfo(
    val id: String,
    val tripDate: String,
    val leg: String,
    val status: String,
    val scheduledTime: String,
    val photoPath: String?
)

data class TiffinPlan(
    val id: String,
    val kind: String,
    val recipientName: String,
    val recipientDetail: String?,
    val pickupAddress: String,
    val dropLabel: String?,
    val dropAddress: String,
    val days: List<Int>,
    val pickupTime: String,
    val returnTrip: Boolean,
    val returnPickupTime: String?,
    val totalAmount: Double,
    val tripsTotal: Int,
    val tripsRemaining: Int,
    val startDate: String,
    val endDate: String,
    val status: String,
    val isTrial: Boolean,
    val trialDays: Int,
    val trialFee: Double,
    val trialEndDate: String?,
    val autopayStatus: String,
    val billingCyclesPaid: Int,
    val nextChargeDate: String?,
    val partnerName: String?,
    val partnerPhone: String?,
    val skips: List<String>,
    val trips: List<TiffinTripInfo>
) {
    val autopayLive: Boolean get() = autopayStatus == "authenticated" || autopayStatus == "active" || autopayStatus == "pending"
    val running: Boolean get() = status == "active" || status == "paused"

    companion object {
        private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList()

        fun from(o: JSONObject): TiffinPlan {
            val partner = o.optJSONObject("partner")
            val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
            val trips = o.optJSONArray("trips")?.let { a ->
                (0 until a.length()).map { i ->
                    val t = a.getJSONObject(i)
                    TiffinTripInfo(
                        id = t.optString("id"), tripDate = t.optString("trip_date"), leg = t.optString("leg"),
                        status = t.optString("status"), scheduledTime = t.optString("scheduled_time"),
                        photoPath = t.str("delivered_photo_url")
                    )
                }
            } ?: emptyList()
            return TiffinPlan(
                id = o.optString("id"), kind = o.optString("kind", "school"), recipientName = o.optString("recipient_name"),
                recipientDetail = o.str("recipient_detail"), pickupAddress = o.optString("pickup_address"),
                dropLabel = o.str("drop_label"), dropAddress = o.optString("drop_address"), days = days,
                pickupTime = o.optString("pickup_time"), returnTrip = o.flag("return_trip"), returnPickupTime = o.str("return_pickup_time"),
                totalAmount = o.num("total_amount"), tripsTotal = o.int("trips_total"), tripsRemaining = o.int("trips_remaining"),
                startDate = o.optString("start_date"), endDate = o.optString("end_date"), status = o.optString("status"),
                isTrial = o.flag("is_trial"), trialDays = o.int("trial_days"), trialFee = o.num("trial_fee"),
                trialEndDate = o.str("trial_end_date"), autopayStatus = o.optString("autopay_status", "none"),
                billingCyclesPaid = o.int("billing_cycles_paid"), nextChargeDate = o.str("next_charge_date"),
                partnerName = partner?.str("name"), partnerPhone = partner?.str("phone"),
                skips = strings(o.optJSONArray("skips")), trips = trips
            )
        }
    }
}

/** What the payment screen needs after the server has created the Razorpay auto-pay subscription. */
data class TiffinCheckout(
    val keyId: String,
    val razorpaySubscriptionId: String,
    val isTrial: Boolean,
    val payNow: Double,
    val monthly: Double
)

data class TiffinPlanInput(
    val pickupAddressId: String,
    val dropAddressId: String,
    val kind: String,
    val recipientName: String,
    val recipientDetail: String,
    val handoverNote: String,
    val days: List<Int>,
    val pickupTime: String,
    val returnTrip: Boolean,
    val returnPickupTime: String,
    val startDate: String
)
