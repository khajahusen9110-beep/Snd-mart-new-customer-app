package com.example.data.model

import android.content.Context
import com.example.R
import org.json.JSONObject

/**
 * Tiffin Pickup text in English and Kannada, read from res/raw/tiffin_text.json at runtime
 * ({0}, {1} ... are filled in by [t]). Kept out of Kotlin source on purpose: a few hundred string
 * pairs written as a Kotlin map made the compiler run out of memory.
 * Keep the keys identical to the website (tiffinI18n.ts).
 */
object TiffinText {
    @Volatile private var en: Map<String, String> = emptyMap()
    @Volatile private var kn: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    private fun read(o: JSONObject): Map<String, String> {
        val out = HashMap<String, String>(o.length() * 2)
        val keys = o.keys()
        while (keys.hasNext()) { val k = keys.next(); out[k] = o.getString(k) }
        return out
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val text = context.resources.openRawResource(R.raw.tiffin_text).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(text)
                en = read(root.getJSONObject("en"))
                kn = read(root.getJSONObject("kn"))
            } catch (e: Exception) {
                // Missing text falls back to the key itself; the screen still works.
            }
            loaded = true
        }
    }

    fun t(lang: String, key: String, vararg args: Any?): String {
        var s = (if (lang == "kn") kn else en)[key] ?: en[key] ?: key
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a?.toString() ?: "") }
        return s
    }
}
