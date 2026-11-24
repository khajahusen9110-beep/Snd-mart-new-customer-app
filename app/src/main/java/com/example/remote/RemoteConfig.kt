package com.example.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** One announcement / banner / card sent from the City Admin (no app update needed). */
data class RemoteBlock(
    val id: String, val slot: String, val kind: String, val title: String, val body: String?, val imageUrl: String?,
    val buttonText: String?, val actionType: String, val actionValue: String?, val color: String
)

data class RemoteConfigData(val flags: Map<String, Any?> = emptyMap(), val blocks: List<RemoteBlock> = emptyList())

/**
 * Remote control: what the app shows and which options are on come from the server every time the app opens
 * (and about hourly while it is open). The last answer is kept on the phone, so the app works the same offline.
 * The code of the app does NOT change, this is data only (banners, notices, cards, on/off flags, small values).
 */
object RemoteConfigStore {
    private const val PREFS = "sndmart_remote_config"
    private const val KEY_JSON = "json"
    private val _config = MutableStateFlow(RemoteConfigData())
    val config: StateFlow<RemoteConfigData> = _config.asStateFlow()
    @Volatile private var lastFetchAt = 0L
    @Volatile private var loadedFromDisk = false

    /** Restores the last answer from the phone (call once, early). */
    fun load(context: Context) {
        if (loadedFromDisk) return
        loadedFromDisk = true
        runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null) ?: return
            _config.value = parse(raw)
        }
    }

    /**
     * Asks the server (via [fetch], which returns the raw JSON of get_remote_config or null) at most every [minGapMs].
     * Any problem keeps what is already shown.
     */
    suspend fun refresh(context: Context, force: Boolean = false, minGapMs: Long = 10 * 60_000L, fetch: suspend () -> String?) {
        load(context)
        if (!force && System.currentTimeMillis() - lastFetchAt < minGapMs) return
        val raw = runCatching { fetch() }.getOrNull() ?: return
        val data = runCatching { parse(raw) }.getOrNull() ?: return
        lastFetchAt = System.currentTimeMillis()
        _config.value = data
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_JSON, raw).apply() }
    }

    fun clear(context: Context) {
        _config.value = RemoteConfigData()
        lastFetchAt = 0L
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_JSON).apply() }
    }

    fun parse(raw: String): RemoteConfigData {
        val o = JSONObject(raw)
        val f = HashMap<String, Any?>()
        o.optJSONObject("flags")?.let { fo -> fo.keys().forEach { k -> f[k] = if (fo.isNull(k)) null else fo.get(k) } }
        val ba = o.optJSONArray("blocks")
        val blocks = if (ba == null) emptyList() else (0 until ba.length()).map { i ->
            val b = ba.getJSONObject(i)
            fun s(k: String): String? = if (b.isNull(k)) null else b.optString(k).takeIf { it.isNotBlank() }
            RemoteBlock(b.optString("id"), b.optString("slot", "home"), b.optString("kind", "banner"), b.optString("title"), s("body"), s("image_url"),
                s("button_text"), b.optString("action_type", "none"), s("action_value"), b.optString("color", "blue"))
        }
        return RemoteConfigData(f, blocks)
    }
}

/** Read a remote flag or value. A missing flag gives the default, so nothing breaks when nothing is set. */
object RemoteFlags {
    fun bool(key: String, default: Boolean): Boolean = when (val v = RemoteConfigStore.config.value.flags[key]) {
        is Boolean -> v
        is String -> v.equals("true", true) || v == "1"
        is Number -> v.toInt() != 0
        else -> default
    }
    fun text(key: String, default: String): String = (RemoteConfigStore.config.value.flags[key] as? String)?.takeIf { it.isNotBlank() } ?: default
    fun number(key: String, default: Double): Double = (RemoteConfigStore.config.value.flags[key] as? Number)?.toDouble() ?: default
}
