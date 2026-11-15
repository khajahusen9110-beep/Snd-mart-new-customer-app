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

                val prefillObj = JSONObject()
                if (!userPhone.isNullOrBlank()) {
                    prefillObj.put("contact", userPhone)
                }
                if (!userEmail.isNullOrBlank()) {
                    prefillObj.put("email", userEmail)
                }
                if (prefillObj.length() > 0) {
                    put("prefill", prefillObj)
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
                val prefill = JSONObject()
                if (!userPhone.isNullOrBlank()) prefill.put("contact", userPhone)
                if (!userEmail.isNullOrBlank()) prefill.put("email", userEmail)
                if (prefill.length() > 0) put("prefill", prefill)
            }
            checkout.open(activity, options)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Razorpay subscription checkout", e)
            Result.failure(e)
        }
    }
}
