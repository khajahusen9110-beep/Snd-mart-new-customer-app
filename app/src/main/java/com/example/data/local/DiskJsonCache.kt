package com.example.data.local

import android.content.Context
import com.example.data.remote.SupabaseClient
import com.squareup.moshi.Types

/**
 * A tiny on-device copy of the lists the Food and Tiffin screens open with (hotel list, Tiffin settings),
 * so the screen can paint instantly on the next app start while fresh data loads in the background.
 * The copy is only ever shown first; the real data from the server always replaces it. Every call is
 * wrapped so a missing or damaged copy simply means "nothing saved".
 */
object DiskJsonCache {
    private const val PREFS = "sndmart_disk_cache"
    private const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000   // a copy older than 3 days is ignored
    @Volatile private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun <T> putList(key: String, items: List<T>, type: Class<T>) {
        try {
            val adapter = SupabaseClient.moshi.adapter<List<T>>(Types.newParameterizedType(List::class.java, type))
            prefs()?.edit()?.putString(key, adapter.toJson(items))?.putLong("$key.at", System.currentTimeMillis())?.apply()
        } catch (_: Throwable) { }
    }

    fun <T> getList(key: String, type: Class<T>): List<T>? = try {
        val p = prefs()
        val at = p?.getLong("$key.at", 0L) ?: 0L
        val json = p?.getString(key, null)
        if (json.isNullOrBlank() || System.currentTimeMillis() - at > MAX_AGE_MS) null
        else SupabaseClient.moshi.adapter<List<T>>(Types.newParameterizedType(List::class.java, type)).fromJson(json)
    } catch (_: Throwable) { null }

    fun <T> putOne(key: String, item: T, type: Class<T>) {
        try {
            prefs()?.edit()?.putString(key, SupabaseClient.moshi.adapter(type).toJson(item))?.putLong("$key.at", System.currentTimeMillis())?.apply()
        } catch (_: Throwable) { }
    }

    fun <T> getOne(key: String, type: Class<T>): T? = try {
        val p = prefs()
        val at = p?.getLong("$key.at", 0L) ?: 0L
        val json = p?.getString(key, null)
        if (json.isNullOrBlank() || System.currentTimeMillis() - at > MAX_AGE_MS) null
        else SupabaseClient.moshi.adapter(type).fromJson(json)
    } catch (_: Throwable) { null }

    fun clear() { try { prefs()?.edit()?.clear()?.apply() } catch (_: Throwable) { } }
}
