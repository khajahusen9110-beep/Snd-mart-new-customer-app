package com.example

import com.example.data.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Stores answers from the server are read correctly, and a missing/odd answer means "Stores is off". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StoresModelsUnitTest {
    @Test fun config_readsSwitchesCategoriesAndRadius() {
        val c = StoresConfig.from(JSONObject("""{"stores_enabled":true,"replace_grocery":true,"radius_km":5,"categories":[
            {"id":"c1","key":"kirana","name":"Kirana & Grocery","name_kn":"ಕಿರಾಣಿ","image_url":null,"template":"standard","store_count":2}]}"""))
        assertTrue(c.enabled); assertTrue(c.replaceGrocery); assertEquals(5.0, c.radiusKm, 0.0)
        assertEquals(1, c.categories.size)
        assertEquals("ಕಿರಾಣಿ", c.categories[0].nameKn); assertNull(c.categories[0].imageUrl); assertEquals(2, c.categories[0].storeCount)
    }

    @Test fun config_offAnswerAndOldServerAnswerKeepAppUnchanged() {
        val off = StoresConfig.from(JSONObject("""{"stores_enabled":false,"radius_km":5,"categories":[]}"""))
        assertFalse(off.enabled); assertFalse(off.replaceGrocery); assertTrue(off.categories.isEmpty())
        // the first Stores SQL had no replace_grocery field: that must read as false
        val old = StoresConfig.from(JSONObject("""{"stores_enabled":true,"radius_km":5,"categories":[]}"""))
        assertTrue(old.enabled); assertFalse(old.replaceGrocery)
        assertFalse(StoresConfig.OFF.enabled)
    }

    @Test fun shopList_keepsOrderNearestBadgeAndDistance() {
        val l = StoreListItem.listFrom(JSONArray("""[
          {"store_id":"s1","name":"Ganesh Kirana","banner_url":null,"address":"Main Road","category_id":"c1","category_name":"Kirana","distance_km":0.8,"is_open":true,"is_nearest":true},
          {"store_id":"s2","name":"Lakshmi Stores","banner_url":"http://x/y.jpg","address":null,"category_id":"c1","category_name":"Kirana","distance_km":null,"is_open":false,"is_nearest":false}]"""))
        assertEquals(listOf("s1", "s2"), l.map { it.storeId })
        assertTrue(l[0].isNearest); assertEquals(0.8, l[0].distanceKm!!, 0.0)
        assertNull(l[1].distanceKm); assertFalse(l[1].isOpen); assertEquals("http://x/y.jpg", l[1].bannerUrl); assertNull(l[1].address)
    }

    @Test fun searchHits_readPricePerKgAndShop() {
        val h = StoreProductHit.listFrom(JSONArray("""[{"product_id":"p1","name":"Sona Masoori Rice","price":58.00,"mrp":65,"unit":"1 kg","unit_price":58.00,"unit_label":"kg",
            "image_url":null,"store_id":"s2","store_name":"Lakshmi Stores","category_id":"c1","distance_km":2.4,"total_count":1}]"""))
        assertEquals(1, h.size); assertEquals(58.0, h[0].price, 0.0); assertEquals(65.0, h[0].mrp!!, 0.0)
        assertEquals("kg", h[0].unitLabel); assertEquals("Lakshmi Stores", h[0].storeName)
    }

    @Test fun shopPage_readsCategoriesProductsAndStock() {
        val m = StoreMenu.from(JSONObject("""{"store":{"id":"s1","name":"Ganesh Kirana","banner_url":null,"address":"Main Road","category_id":"c1","category_name":"Kirana & Grocery","is_open":true,"prep_minutes":10},
          "categories":[{"id":"k1","name":"Rice"}],
          "products":[{"id":"p1","name":"Rice","description":null,"image_url":null,"price":290,"mrp":320,"unit":"5 kg","unit_price":58,"unit_label":"kg","category_id":"k1","is_available":true},
                      {"id":"p2","name":"Oil","description":null,"image_url":null,"price":140,"mrp":null,"unit":"1 litre","unit_price":140,"unit_label":"litre","category_id":null,"is_available":false}]}"""))
        assertEquals("Ganesh Kirana", m.name); assertTrue(m.isOpen); assertEquals(10, m.prepMinutes)
        assertEquals(listOf("k1" to "Rice"), m.categories)
        assertEquals(2, m.products.size); assertTrue(m.products[0].isAvailable); assertFalse(m.products[1].isAvailable); assertNull(m.products[1].categoryId)
    }
}
