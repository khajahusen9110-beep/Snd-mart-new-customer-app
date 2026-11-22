package com.example.data.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HelperModelsUnitTest {
    private val json = """{"enabled":true,"max_photos":2,"payment_mode":"upi","min_lead_minutes":45,"max_days_ahead":5,"require_rules_ack":false,
      "support_phone":"+919000000000","terms_text":"T","banned_note":"B",
      "categories":[{"id":"c1","name":"Queue","description":"Wait in line","image_url":"https://x/y.jpg","photos_allowed":true,"photo_required":true,
        "task_placeholder":"Which bank?","rate_per_30":120,"durations":[30,60],"prices":{"30":120,"60":240},
        "dos":[{"text":"Stands in line","text_kn":null,"image_url":"https://x/a.jpg"}],"donts":[{"text":"No cash handling","image_url":null}]}]}"""

    @Test fun parsesSettingsAndCategories() {
        val c = HelperConfig.from(JSONObject(json))
        assertTrue(c.enabled); assertEquals("upi", c.paymentMode); assertEquals(45, c.minLeadMinutes); assertEquals(5, c.maxDaysAhead)
        assertFalse(c.requireRulesAck); assertEquals(1, c.categories.size)
        val k = c.categories[0]
        assertEquals("Queue", k.name); assertTrue(k.photoRequired); assertEquals(listOf(30, 60), k.durations)
        assertEquals(240.0, k.priceFor(60), 0.0); assertEquals(120.0, k.fromPrice, 0.0)
        assertEquals("https://x/a.jpg", k.dos[0].imageUrl); assertNull(k.donts[0].imageUrl); assertEquals("No cash handling", k.donts[0].text)
    }

    @Test fun missingPrice_fallsBackToRate() {
        val k = HelperConfig.from(JSONObject("""{"categories":[{"id":"c","name":"A","rate_per_30":50,"durations":[90],"prices":{}}]}""")).categories[0]
        assertEquals(150.0, k.priceFor(90), 0.0)
    }

    @Test fun offWhenNotEnabled() = assertFalse(HelperConfig.from(JSONObject("""{"enabled":false}""")).enabled)
}
