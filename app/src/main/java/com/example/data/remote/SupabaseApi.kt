package com.example.data.remote

import com.example.data.model.*
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface SupabaseApi {

    // --- AUTH ENDPOINTS ---

    @POST("auth/v1/otp")
    suspend fun signInWithOtp(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<ResponseBody>

    @POST("auth/v1/signup")
    suspend fun signup(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/verify")
    suspend fun verifyOtp(
        @Body body: Map<String, String>
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token?grant_type=password")
    suspend fun loginWithPassword(
        @Body body: Map<String, String>
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token?grant_type=id_token")
    suspend fun loginWithGoogleIdToken(
        @Body body: Map<String, String>
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token?grant_type=refresh_token")
    suspend fun refreshSession(
        @Body body: Map<String, String>
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/logout")
    suspend fun signOut(
        @Query("scope") scope: String? = null
    ): Response<ResponseBody>

    // --- CITIES ---

    @GET("rest/v1/cities")
    suspend fun getCities(
        @Query("status") status: String = "eq.active",
        @Query("order") order: String = "name.asc"
    ): Response<List<City>>

    @GET("rest/v1/cities")
    suspend fun getCityById(
        @Query("id") idQuery: String,
        @Query("select") select: String = "*"
    ): Response<List<City>>

    // --- CITY DETECTION RPC ---

    @POST("rest/v1/rpc/find_city_for_location")
    suspend fun findCityForLocation(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<List<CityLocationResult>>

    // --- PROFILES ---

    @GET("rest/v1/profiles")
    suspend fun getProfile(
        @Query("id") idQuery: String
    ): Response<List<Profile>>

    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("rest/v1/profiles")
    suspend fun createProfile(
        @Body profile: Profile
    ): Response<List<Profile>>

    @Headers("Prefer: return=representation")
    @PATCH("rest/v1/profiles")
    suspend fun updateProfile(
        @Query("id") idQuery: String,
        @Body profile: Map<String, @JvmSuppressWildcards Any?>
    ): Response<List<Profile>>

    // --- CATEGORIES ---

    @GET("rest/v1/categories")
    suspend fun getCategories(
        @Query("select") select: String? = null,
        @Query("is_active") isActive: String = "eq.true",
        @Query("order") order: String = "sort_order.asc",
        @Query("vendor_type", encoded = true) vendorType: String? = null,
        @Query("vendor_id") vendorId: String? = null,
        @Query("city_id") cityId: String? = null
    ): Response<List<Category>>

    // --- VENDORS ---

    @GET("rest/v1/vendors")
    suspend fun getVendors(
        @Query("city_id") cityId: String,
        @Query("approval_status") approvalStatus: String = "eq.approved",
        @Query("vendor_type") vendorType: String? = null,
        @Query("is_active") isActive: String? = null,
        @Query("name", encoded = true) name: String? = null,
        @Query("select") select: String = "id,name,banner_url,is_active,is_featured,is_open,address,latitude,longitude,opening_time,closing_time",
        @Query("order") order: String = "is_active.desc,is_featured.desc.nullslast,name.asc",
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<List<Vendor>>

    @GET("rest/v1/vendors")
    suspend fun getVendorsByIds(
        @Query("id") idInQuery: String,
        @Query("select") select: String = "id,name,banner_url"
    ): Response<List<Vendor>>

    @GET("rest/v1/vendors")
    suspend fun getVendorById(
        @Query("id") idQuery: String,
        @Query("select") select: String = "id,name,banner_url,is_active,is_featured,is_open,address,latitude,longitude,opening_time,closing_time"
    ): Response<List<Vendor>>

    @GET("rest/v1/vendor_operating_hours")
    suspend fun getVendorOperatingHours(
        @Query("vendor_id") vendorId: String,
        @Query("is_active") isActive: String? = "eq.true",
        @Query("select") select: String = "id,vendor_id,start_time,end_time,is_active"
    ): Response<List<OperatingSlot>>

    // --- PRODUCTS ---

    @GET("rest/v1/products")
    suspend fun getProducts(
        @Query("is_active") isActive: String? = null,
        @Query("category_id") categoryId: String? = null,
        @Query("vendor_id") vendorId: String? = null,
        @Query("name", encoded = true) name: String? = null,
        @Query("select") select: String? = null,
        @Query("order") order: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<List<Product>>

    @GET("rest/v1/products")
    suspend fun getProductById(
        @Query("id") idQuery: String
    ): Response<List<Product>>

    @GET("rest/v1/products")
    suspend fun getProductsByIds(
        @Query("id") idInQuery: String,
        @Query("select") select: String? = null
    ): Response<List<Product>>

    // --- PRODUCT CITY STOCK ---

    @GET("rest/v1/product_city_stock")
    suspend fun getProductCityStock(
        @Query("city_id") cityId: String
    ): Response<List<ProductCityStock>>

    @GET("rest/v1/product_city_stock")
    suspend fun getProductCityStockBatch(
        @Query("city_id") cityId: String,
        @Query("product_id") productIdsQuery: String,
        @Query("select") select: String = "product_id,price,mrp,stock_qty,is_available"
    ): Response<List<ProductCityStock>>

    @GET("rest/v1/product_city_stock")
    suspend fun getSingleProductCityStock(
        @Query("product_id") productId: String,
        @Query("city_id") cityId: String
    ): Response<List<ProductCityStock>>

    // --- CART ITEMS ---

    @GET("rest/v1/cart_items")
    suspend fun getCartItems(
        @Query("user_id") userId: String
    ): Response<List<CartItem>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/cart_items")
    suspend fun insertCartItem(
        @Body item: CartItem
    ): Response<List<CartItem>>

    @POST("rest/v1/cart_items")
    suspend fun insertCartItemsBulk(
        @Body items: List<CartItem>
    ): Response<ResponseBody>

    @Headers("Prefer: return=representation")
    @PATCH("rest/v1/cart_items")
    suspend fun updateCartItemQuantity(
        @Query("id") idQuery: String,
        @Body body: Map<String, Int>
    ): Response<List<CartItem>>

    @DELETE("rest/v1/cart_items")
    suspend fun deleteCartItem(
        @Query("id") idQuery: String
    ): Response<ResponseBody>

    @DELETE("rest/v1/cart_items")
    suspend fun clearCartForUser(
        @Query("user_id") userIdQuery: String
    ): Response<ResponseBody>

    // --- ADDRESSES ---

    @GET("rest/v1/customer_addresses")
    suspend fun getAddresses(
        @Query("user_id") userId: String,
        @Query("order") order: String = "is_default.desc,id.desc"
    ): Response<List<CustomerAddress>>

    @GET("rest/v1/customer_addresses")
    suspend fun getCustomerAddresses(
        @Query("user_id") userId: String,
        @Query("order") order: String = "is_default.desc,id.desc"
    ): Response<List<CustomerAddress>>

    @GET("rest/v1/customer_addresses")
    suspend fun getAddressById(
        @Query("id") idQuery: String
    ): Response<List<CustomerAddress>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/customer_addresses")
    suspend fun insertAddress(
        @Body address: CustomerAddress
    ): Response<List<CustomerAddress>>

    @Headers("Prefer: return=representation")
    @PATCH("rest/v1/customer_addresses")
    suspend fun updateAddress(
        @Query("id") idQuery: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<List<CustomerAddress>>

    @DELETE("rest/v1/customer_addresses")
    suspend fun deleteAddress(
        @Query("id") idQuery: String
    ): Response<ResponseBody>

    // --- DELIVERY SETTINGS & RPC ---

    @GET("rest/v1/city_delivery_settings")
    suspend fun getCityDeliverySettings(
        @Query("city_id") cityId: String
    ): Response<List<CityDeliverySettings>>

    @GET("rest/v1/delivery_zones")
    suspend fun getDeliveryZones(
        @Query("city_id") cityId: String,
        @Query("is_active") isActive: String = "eq.true",
        @Query("order") order: String = "created_at.asc"
    ): Response<List<DeliveryZone>>

    @POST("rest/v1/rpc/calculate_delivery_fee")
    suspend fun calculateDeliveryFee(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Double>

    @POST("rest/v1/rpc/get_handling_fee")
    suspend fun getHandlingFee(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Double>

    // Whether the signed-in customer may pay Cash on Delivery (blocked after repeated cancels).
    @POST("rest/v1/rpc/my_cod_status")
    suspend fun myCodStatus(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Map<String, @JvmSuppressWildcards Any>>

    // Active promotional offer for a section ("food" / "grocery"). Returns [] when no offer runs.
    @POST("rest/v1/rpc/get_active_promo")
    suspend fun getActivePromo(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<List<ActivePromoRow>>

    // Most-selling grocery products for a city: product_id + units_sold, ranked.
    @POST("rest/v1/rpc/get_top_grocery_products")
    suspend fun getTopGroceryProducts(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<List<TopGroceryRow>>

    // Most-ordered hotel dishes for a city: product_id + vendor + units_sold, ranked.
    @POST("rest/v1/rpc/get_top_food_products")
    suspend fun getTopFoodProducts(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<List<TopFoodRow>>

    @POST("rest/v1/rpc/calculate_city_coupon_discount")
    suspend fun calculateCityCouponDiscount(
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<ResponseBody>

    // --- COUPONS ---

    @GET("rest/v1/coupons")
    suspend fun getCoupons(
        @Query("city_id") cityId: String,
        @Query("is_active") isActive: String = "eq.true",
        @Query("code") code: String? = null
    ): Response<List<Coupon>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/coupon_usages")
    suspend fun insertCouponUsage(
        @Body usage: CouponUsage
    ): Response<List<CouponUsage>>

    // --- ORDERS ---

    @GET("rest/v1/orders")
    suspend fun getOrders(
        @Query("customer_id") customerId: String,
        @Query("select") select: String? = null,
        @Query("order") order: String = "placed_at.desc",
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("status") status: String? = null
    ): Response<List<Order>>

    @GET("rest/v1/orders")
    suspend fun getOrderById(
        @Query("id") idQuery: String,
        @Query("select") select: String? = null
    ): Response<List<Order>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/orders")
    suspend fun createOrder(
        @Body order: Order
    ): Response<List<Order>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/order_items")
    suspend fun createOrderItems(
        @Body items: List<OrderItem>
    ): Response<List<OrderItem>>

    @GET("rest/v1/order_items")
    suspend fun getOrderItems(
        @Query("order_id") orderId: String
    ): Response<List<OrderItem>>

    @GET("rest/v1/order_status_history")
    suspend fun getOrderStatusHistory(
        @Query("order_id") orderId: String,
        @Query("order") order: String = "created_at.asc"
    ): Response<List<OrderStatusHistory>>

    // --- SAFE CONCURRENT CHECKOUT RPC ---
    // Do NOT manually insert into orders or order_items. These atomic RPC functions
    // calculate authoritative pricing and prevent price tampering/concurrency conflicts.

    // v2 = the same checkout plus cashback coupons and paying part of the order from the wallet
    // (extra parameter p_use_wallet).
    @POST("rest/v1/rpc/checkout_grocery_order_v2")
    suspend fun checkoutGroceryOrder(
        @Body body: RequestBody
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/checkout_food_order_v2")
    suspend fun checkoutFoodOrder(
        @Body body: RequestBody
    ): Response<ResponseBody>

    // --- SNDMART WALLET & CASHBACK ---

    @POST("rest/v1/rpc/preview_checkout_rewards")
    suspend fun previewCheckoutRewards(
        @Body body: RequestBody
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/get_my_wallet")
    suspend fun getMyWallet(
        @Body body: RequestBody
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/get_my_wallet_history")
    suspend fun getMyWalletHistory(
        @Body body: RequestBody
    ): Response<ResponseBody>

    // --- PROMO BANNERS (Super Admin) ---

    @GET("rest/v1/promo_banners")
    suspend fun getPromoBanners(
        @Query("placement") placementIn: String,
        @Query("or") cityFilter: String,
        @Query("select") select: String = "id,city_id,placement,image_url,title,subtitle,cta_text,show_text,link_type,link_value,sort_order,starts_at,ends_at",
        @Query("order") order: String = "sort_order.asc",
        @Query("limit") limit: Int = 5
    ): Response<List<PromoBanner>>

    // --- DELIVERY ASSIGNMENTS & PARTNER ---

    @GET("rest/v1/delivery_assignments")
    suspend fun getDeliveryAssignment(
        @Query("order_id") orderId: String,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 1,
        @Query("select") select: String? = null
    ): Response<List<DeliveryAssignment>>

    @GET("rest/v1/delivery_partners")
    suspend fun getDeliveryPartner(
        @Query("id") partnerId: String
    ): Response<List<DeliveryPartner>>

    // --- REVIEWS ---

    @Headers("Prefer: return=representation")
    @POST("rest/v1/vendor_reviews")
    suspend fun submitVendorReview(
        @Body review: VendorReview
    ): Response<List<VendorReview>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/delivery_partner_reviews")
    suspend fun submitDeliveryPartnerReview(
        @Body review: DeliveryPartnerReview
    ): Response<List<DeliveryPartnerReview>>

    @GET("rest/v1/vendor_reviews")
    suspend fun getMyReviews(
        @Query("customer_id") customerId: String,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<List<VendorReview>>

    @GET("rest/v1/vendor_reviews")
    suspend fun getVendorReviews(
        @Query("vendor_id") vendorId: String,
        @Query("select") select: String = "rating"
    ): Response<List<VendorReview>>

    @GET("rest/v1/delivery_partner_reviews")
    suspend fun getMyDeliveryPartnerReviews(
        @Query("customer_id") customerId: String,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<List<DeliveryPartnerReview>>

    // --- WALLET ---

    @GET("rest/v1/customer_wallet_transactions")
    suspend fun getWalletTransactions(
        @Query("customer_id") customerId: String,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<List<CustomerWalletTransaction>>

    @GET("rest/v1/customer_wallet_transactions")
    suspend fun getWalletAmountsForBalance(
        @Query("customer_id") customerId: String,
        @Query("select") select: String = "type,amount"
    ): Response<List<WalletAmountRow>>

    // --- DEVICE TOKENS ---

    @Headers("Prefer: resolution=merge-duplicates")
    @POST("rest/v1/device_tokens")
    suspend fun registerDeviceToken(
        @Body deviceToken: DeviceToken,
        // on_conflict is REQUIRED: without it the upsert merges on the row id (new each
        // time) and duplicate token rows pile up instead of updating the existing one.
        @Query("on_conflict") onConflict: String = "user_id,user_type"
    ): Response<ResponseBody>

    @Headers("Prefer: return=minimal")
    @POST("rest/v1/push_registration_errors")
    suspend fun reportPushRegistrationError(@Body body: Map<String, String>): Response<ResponseBody>

    @Headers("Prefer: return=minimal")
    @PATCH("rest/v1/device_tokens")
    suspend fun deactivateDeviceToken(
        @Query("user_id") userIdQuery: String,
        @Query("user_type") userType: String = "eq.customer",
        @Body body: Map<String, Boolean> = mapOf("active" to false)
    ): Response<ResponseBody>

    // --- NOTIFICATIONS ---

    @GET("rest/v1/notifications")
    suspend fun getCustomerNotifications(
        @Query("user_id") userIdQuery: String,
        @Query("user_type") userType: String = "eq.customer",
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int? = null
    ): Response<List<CustomerNotification>>

    @Headers("Prefer: return=minimal")
    @PATCH("rest/v1/notifications")
    suspend fun markNotificationAsRead(
        @Query("id") idQuery: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?> = mapOf("is_read" to true)
    ): Response<ResponseBody>

    @Headers("Prefer: return=minimal")
    @PATCH("rest/v1/notifications")
    suspend fun markAllNotificationsAsRead(
        @Query("user_id") userIdQuery: String,
        @Query("user_type") userType: String = "eq.customer",
        @Body body: Map<String, @JvmSuppressWildcards Any?> = mapOf("is_read" to true)
    ): Response<ResponseBody>

    @Headers("Prefer: count=exact")
    @HEAD("rest/v1/notifications")
    suspend fun countUnreadNotifications(
        @Query("user_id") userId: String,
        @Query("is_read") isRead: String = "eq.false"
    ): Response<Void>

    // --- APP VERSIONS & MAINTENANCE ---

    @GET("rest/v1/app_versions")
    suspend fun getAppVersionInfo(
        @Query("platform") platform: String = "eq.customer_app",
        @Query("select") select: String = "*",
        @Query("limit") limit: Int = 1
    ): Response<List<AppVersionInfo>>

    @GET("rest/v1/app_settings")
    suspend fun getAppSetting(
        @Query("key") key: String = "eq.maintenance_mode",
        @Query("select") select: String = "value",
        @Query("limit") limit: Int = 1
    ): Response<ResponseBody>

    // --- RAZORPAY PAYMENT (EDGE FUNCTIONS) ---

    @POST("functions/v1/create-razorpay-order")
    suspend fun createRazorpayOrder(
        @Body body: CreateRazorpayOrderRequest
    ): Response<ResponseBody>

    @POST("functions/v1/verify-razorpay-payment")
    suspend fun verifyRazorpayPayment(
        @Body body: VerifyRazorpayPaymentRequest
    ): Response<ResponseBody>

    @POST("functions/v1/sync-razorpay-payment")
    suspend fun syncRazorpayPayment(
        @Body body: SyncRazorpayPaymentRequest
    ): Response<ResponseBody>

    @GET("rest/v1/order_cancellations")
    suspend fun getOrderCancellation(
        @Query("order_id") orderId: String,
        @Query("select") select: String = "*",
        @Query("limit") limit: Int = 1
    ): Response<List<OrderCancellation>>
}

