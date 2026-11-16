package com.example.util

import android.app.Activity
import android.util.Log
import com.razorpay.Checkout
import com.razorpay.PaymentData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

sealed class RazorpayPaymentResult {
    data class Success(
        val razorpayPaymentId: String?,
        val paymentData: PaymentData?
    ) : RazorpayPaymentResult()

    data class Error(
        val code: Int,
        val response: String?,
        val paymentData: PaymentData?
    ) : RazorpayPaymentResult()
}

object RazorpayPaymentManager {
    private const val TAG = "RazorpayPaymentManager"

    private val _paymentResult = MutableSharedFlow<RazorpayPaymentResult>(extraBufferCapacity = 1)
    val paymentResult: SharedFlow<RazorpayPaymentResult> = _paymentResult.asSharedFlow()

    /**
     * Customer details for Razorpay's "prefill". Razorpay asks for mobile and e-mail on its first screen unless
     * both are given, so both are always given: the phone as +91 and 10 digits, and the real e-mail when the
     * customer has one, otherwise a placeholder built from the phone (receipts then go by SMS only).
     * Returns null when there is no usable phone number.
     */
    internal fun customerPrefill(userPhone: String?, userEmail: String?): JSONObject? {
        val digits = userPhone.orEmpty().filter { it.isDigit() }.takeLast(10)
        if (digits.length != 10) return null
        val email = userEmail?.trim()?.takeIf { it.contains("@") && it.contains(".") } ?: "$digits@sndmart.in"
        return JSONObject().put("contact", "+91$digits").put("email", email)
    }

    fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        Log.d(TAG, "Razorpay payment success: id=$razorpayPaymentId")
        _paymentResult.tryEmit(RazorpayPaymentResult.Success(razorpayPaymentId, paymentData))
    }

    fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        Log.w(TAG, "Razorpay payment error: code=$code, response=$response")
        _paymentResult.tryEmit(RazorpayPaymentResult.Error(code, response, paymentData))
    }

    /**
     * Opens the Razorpay Checkout widget restricted to UPI only, matching:
     * method: {
     *   upi: true,
     *   card: false,
     *   netbanking: false,
     *   wallet: false,
     *   paylater: false,
     * }
     */
    fun startUpiCheckout(
        activity: Activity,
        keyId: String,
        amountInPaise: Long,
        currency: String,
        razorpayOrderId: String,
        orderNumber: String,
        userPhone: String? = null,
        userEmail: String? = null
    ): Result<Unit> {
        return try {
            val checkout = Checkout()
            if (keyId.isNotBlank()) {
                checkout.setKeyID(keyId)
            }

            val options = JSONObject().apply {
                put("key", keyId)
                put("amount", amountInPaise)
                put("currency", currency.ifBlank { "INR" })
                put("order_id", razorpayOrderId)
                put("name", "Sndmart")
                put("description", "Order $orderNumber")

                // Restrict exclusively to UPI as requested
                val methodObj = JSONObject().apply {
                    put("upi", true)
                    put("card", false)
                    put("netbanking", false)
                    put("wallet", false)
                    put("paylater", false)
                }
                put("method", methodObj)

                // Notify callback on dismiss
                val modalObj = JSONObject().apply {
                    put("ondismiss", true)
                }
                put("modal", modalObj)

                customerPrefill(userPhone, userEmail)?.let {
                    put("prefill", it)
                    put("readonly", JSONObject().put("contact", true).put("email", true))
                }
            }

            checkout.open(activity, options)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Razorpay checkout", e)
            Result.failure(e)
        }
    }

    /**
     * Opens Razorpay Checkout for a monthly auto-pay subscription (UPI AutoPay mandate; the trial fee
     * is taken with it). The result arrives on [paymentResult] like any other payment.
     */
    fun startSubscriptionCheckout(
        activity: Activity,
        keyId: String,
        subscriptionId: String,
        description: String,
        userPhone: String? = null,
        userEmail: String? = null
    ): Result<Unit> {
        return try {
            val checkout = Checkout()
            if (keyId.isNotBlank()) checkout.setKeyID(keyId)
            val options = JSONObject().apply {
                put("key", keyId)
                put("subscription_id", subscriptionId)
                put("name", "Sndmart")
                put("description", description)
                put("modal", JSONObject().apply { put("ondismiss", true) })
                customerPrefill(userPhone, userEmail)?.let {
                    put("prefill", it)
                    // Both are known, so Razorpay skips its "Contact details" screen.
                    put("readonly", JSONObject().put("contact", true).put("email", true))
                }
            }
            checkout.open(activity, options)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Razorpay subscription checkout", e)
            Result.failure(e)
        }
    }
}
