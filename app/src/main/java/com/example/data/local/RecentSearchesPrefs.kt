package com.example.data.local

import android.content.Context
import org.json.JSONArray

/**
 * Per-device "Recent searches" for food search — local only (SharedPreferences), never sent to
 * the server. "Trending searches" (what OTHER customers search) is the server-side counterpart,
 * powered by SndmartRepository.getTrendingFoodSearches().
 *
 * Follows the same convention as [com.example.data.maps.MapProviderPrefs].
 */
class RecentSearchesPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRecent(): List<String> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecent(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val list = getRecent().filterNot { it.equals(q, ignoreCase = true) }.toMutableList()
        list.add(0, q)
        val trimmed = list.take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, JSONArray(trimmed).toString()).apply()
    }

    fun clearRecent() {
        prefs.edit().remove(KEY_RECENT).apply()
    }

    companion object {
        private const val PREFS_NAME = "sndmart_recent_searches"
        private const val KEY_RECENT = "recent_food_searches"
        private const val MAX_RECENT = 8
    }
}
