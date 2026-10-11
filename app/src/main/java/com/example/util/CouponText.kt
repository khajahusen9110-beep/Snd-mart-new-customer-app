package com.example.util

import com.example.data.model.Coupon
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** "₹70" or "₹70.50" (drops .00). */
fun rupees(amount: Double): String {
    val rounded = Math.round(amount * 100) / 100.0
    return if (rounded % 1.0 == 0.0) "₹${rounded.toLong()}" else "₹${"%.2f".format(rounded)}"
}

private fun isPercent(c: Coupon) =
    c.discountType.equals("percent", ignoreCase = true) || c.discountType.equals("percentage", ignoreCase = true)

/** "50% OFF up to ₹100", "Flat ₹50 OFF", "50% cashback up to ₹500", "Flat ₹100 cashback". */
fun couponHeadline(c: Coupon): String {
    val value = c.discountValue.toInt()
    val cap = if (isPercent(c) && c.maxDiscountAmount != null) " up to ₹${c.maxDiscountAmount.toInt()}" else ""
    return if (c.isCashback) {
        if (isPercent(c)) "$value% cashback$cap" else "Flat ₹$value cashback"
    } else {
        if (isPercent(c)) "$value% OFF$cap" else "Flat ₹$value OFF"
    }
}

/** "12 Oct, 11:59 PM" in the phone's time zone, or null if the date cannot be read. */
fun formatCouponDate(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")
    for (p in patterns) {
        try {
            val parser = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val date = parser.parse(iso) ?: continue
            return SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(date)
        } catch (_: Exception) {
        }
    }
    return null
}

/**
 * Full terms shown before the customer applies a coupon, e.g. "50% cashback up to ₹500 · On orders
 * above ₹199 · Valid till 12 Oct, 11:59 PM · Once per customer · Cashback valid 45 days".
 */
fun couponTerms(c: Coupon, walletUsePercent: Double? = null): String {
    val parts = mutableListOf(couponHeadline(c))
    val min = c.minOrderAmount ?: 0.0
    if (min > 0) parts.add("On orders above ₹${min.toInt()}")
    formatCouponDate(c.expiresAt)?.let { parts.add("Valid till $it") }
    val limit = c.perUserLimit
    if (limit == 1) parts.add("Once per customer") else if (limit != null && limit > 1) parts.add("$limit times per customer")
    if (c.isCashback) {
        parts.add("Credited to your wallet after delivery")
        c.cashbackValidDays?.let { parts.add("Cashback valid $it days") }
        if (walletUsePercent != null && walletUsePercent > 0) parts.add("Use max ${walletUsePercent.toInt()}% of wallet per order")
    }
    return parts.joinToString(" · ")
}

/** Kannada note shown under an applied cashback coupon (bill is not reduced now). */
const val CASHBACK_NOTE_KANNADA =
    "ನಿಮ್ಮ ಡಿಸ್ಕೌಂಟ್ ಕ್ಯಾಶ್‌ಬ್ಯಾಕ್ ಮೊತ್ತವನ್ನು ಆರ್ಡರ್ ಡೆಲಿವರಿ ಆದ ನಂತರ ನಿಮ್ಮ ವಾಲೆಟ್‌ಗೆ ಜಮಾ ಮಾಡಲಾಗುತ್ತದೆ.\n" +
        "ಮೊದಲಿಗೆ ಆರ್ಡರ್‌ನ ಪೂರ್ಣ ಮೊತ್ತವನ್ನು ಪಾವತಿಸಿ.\n" +
        "ಮುಂದಿನ ಆರ್ಡರ್‌ಗಳಿಗೆ ನಿಮ್ಮ ವಾಲೆಟ್‌ನಲ್ಲಿರುವ ಕ್ಯಾಶ್‌ಬ್ಯಾಕ್ ಮೊತ್ತವನ್ನು ಬಳಸಬಹುದು."
