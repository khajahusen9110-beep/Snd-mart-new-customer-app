package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DiskJsonCache
import com.example.data.model.*
import com.example.data.remote.SupabaseApi
import com.example.data.remote.SupabaseClient
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Food home rows ("Most Ordered", "99 Store") now ask for their pieces at the same time and the hotel list is
 * saved on the phone. These tests make sure the answers are still exactly right.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FoodHomeLoadUnitTest {
    private val city = "city-1"
    private val calls = ConcurrentHashMap<String, AtomicInteger>()
    private fun count(name: String) = calls.getOrPut(name) { AtomicInteger() }.incrementAndGet()
    private fun times(name: String) = calls[name]?.get() ?: 0

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SupabaseClient.init("https://example.supabase.co", "test-anon-key")
        DiskJsonCache.init(context)
        DiskJsonCache.clear()
        calls.clear()
    }

    private fun product(id: String, vendor: String, price: Double) =
        Product(id = id, vendorId = vendor, name = "Dish $id", price = price, isAvailable = true, isActive = true)

    private fun repo(): SndmartRepository {
        val api = Proxy.newProxyInstance(SupabaseApi::class.java.classLoader, arrayOf(SupabaseApi::class.java)) { _, m, args ->
            count(m.name)
            when (m.name) {
                "getTopFoodProducts" -> Response.success(listOf(
                    TopFoodRow("p1", "v1", "Hotel One", 5), TopFoodRow("p2", "v1", "Hotel One", 40)))
                "getBudgetFoodProducts" -> Response.success(listOf(
                    BudgetFoodRow("p1", "v1", "Hotel One", "A", null, 80.0, null),
                    BudgetFoodRow("p3", "v2", "Hotel Two (closed)", "C", null, 60.0, null)))
                "getProductsByIds" -> Response.success(listOf(product("p1", "v1", 80.0), product("p2", "v1", 120.0), product("p3", "v2", 60.0)))
                "getProductCityStockBatch" -> Response.success(emptyList<ProductCityStock>())
                "getVendorsByIds" -> Response.success(listOf(
                    Vendor(id = "v1", name = "Hotel One", isOpen = true, isActive = true),
                    Vendor(id = "v2", name = "Hotel Two", isOpen = false, isActive = true)))
                "getVendorOperatingHours" -> Response.success(emptyList<OperatingSlot>())
                else -> throw UnsupportedOperationException("Not mocked: ${m.name}")
            }
        } as SupabaseApi
        return SndmartRepository(api = api, sessionManager = UserSessionManager(context))
    }

    @Test fun mostOrdered_isRankedBySales_andAskedOnlyOnce() = runBlocking {
        val r = repo()
        val first = r.getTopSellingFoodProducts(city).getOrThrow()
        assertEquals(listOf("p2", "p1"), first.map { it.baseProduct.id })
        assertEquals(40, first.first().soldCount)
        // the second open of the screen is answered from memory: no new request
        r.getTopSellingFoodProducts(city)
        assertEquals(1, times("getTopFoodProducts"))
        assertEquals(1, times("getProductsByIds"))
        assertEquals(1, times("getProductCityStockBatch"))
    }

    @Test fun budgetRow_keepsOnlyOpenHotelsAndCheapDishes_withOneRequestPerKind() = runBlocking {
        val r = repo()
        val row = r.getBudgetFoodProducts(city).getOrThrow()
        assertEquals(listOf("p1"), row.map { it.baseProduct.id })   // p3 belongs to a closed hotel
        assertTrue(row.all { it.effectivePrice in 0.01..99.0 })
        assertEquals(1, times("getVendorsByIds"))
        assertEquals(1, times("getVendorOperatingHours"))
        assertEquals(1, times("getProductsByIds"))
    }

    @Test fun savedHotelList_roundTripsAndIgnoresBrokenData() {
        val hotels = listOf(Vendor(id = "v1", name = "Hotel One", isOpen = true), Vendor(id = "v2", name = "Hotel Two", isOpen = false))
        DiskJsonCache.putList("hotels.$city", hotels, Vendor::class.java)
        val back = repo().getDiskHotels(city)!!
        assertEquals(hotels.map { it.id }, back.map { it.id })
        assertEquals("Hotel Two", back[1].name)
        assertFalse(back[1].isOpen)
        // another city has nothing saved
        assertNull(repo().getDiskHotels("other-city"))
        // damaged data is treated as "nothing saved", never a crash
        context.getSharedPreferences("sndmart_disk_cache", Context.MODE_PRIVATE).edit().putString("hotels.$city", "{not json").apply()
        assertNull(repo().getDiskHotels(city))
        assertNull(repo().getDiskHotels(null))
    }
}
