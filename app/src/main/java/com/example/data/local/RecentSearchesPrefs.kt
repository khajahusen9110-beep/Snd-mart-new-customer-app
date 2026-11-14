package com.example.data.local

import android.content.Context

/**
 * Food search history is no longer kept. This only clears what older app versions saved on the
 * device (SharedPreferences), so it can be called once on the food home.
 */
class RecentSearchesPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun clearRecent() {
        prefs.edit().remove(KEY_RECENT).apply()
    }

    companion object {
        private const val PREFS_NAME = "sndmart_recent_searches"
        private const val KEY_RECENT = "recent_food_searches"
    }
}
