package io.github.jqssun.airplay.playback

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class PlaybackRecord(
    val uri: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Manages the playback progress history for the last [MAX_RECORDS] videos.
 */
class PlaybackHistoryManager(context: Context) {
    companion object {
        private const val TAG = "PlaybackHistory"
        private const val PREFS_NAME = "playback_history"
        private const val KEY_HISTORY = "history_records"
        const val MAX_RECORDS = 10

        // Reset threshold: within last 15 seconds or reached >= 95% of duration
        private const val NEAR_END_RATIO = 0.95f
        private const val NEAR_END_MS = 15_000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val records = mutableListOf<PlaybackRecord>()
    private val lock = Any()

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        synchronized(lock) {
            records.clear()
            val jsonStr = prefs.getString(KEY_HISTORY, null) ?: return
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val uri = obj.optString("uri", "")
                    if (uri.isNotEmpty()) {
                        records.add(
                            PlaybackRecord(
                                uri = uri,
                                positionMs = obj.optLong("positionMs", 0L),
                                durationMs = obj.optLong("durationMs", 0L),
                                updatedAt = obj.optLong("updatedAt", 0L)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse playback history", e)
            }
        }
    }

    private fun saveToPrefsLocked() {
        try {
            val array = JSONArray()
            for (rec in records) {
                val obj = JSONObject().apply {
                    put("uri", rec.uri)
                    put("positionMs", rec.positionMs)
                    put("durationMs", rec.durationMs)
                    put("updatedAt", rec.updatedAt)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save playback history to prefs", e)
        }
    }

    /**
     * Determines whether the playback progress has reached the end or near-end of the media.
     */
    fun isNearEnd(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        if (positionMs.toFloat() / durationMs.toFloat() >= NEAR_END_RATIO) return true
        if (durationMs >= 60_000L && (durationMs - positionMs) <= NEAR_END_MS) return true
        return false
    }

    /**
     * Returns the saved progress in milliseconds for [uri], or null if not recorded or near end.
     */
    fun getProgressMs(uri: String): Long? {
        val key = uri.trim()
        if (key.isEmpty()) return null
        synchronized(lock) {
            val record = records.find { it.uri == key } ?: return null
            if (isNearEnd(record.positionMs, record.durationMs)) {
                return null
            }
            return record.positionMs
        }
    }

    /**
     * Saves or updates progress for [uri].
     * If progress is at or near end, clears the record.
     * Otherwise updates or adds to front, keeping at most [MAX_RECORDS].
     */
    fun saveProgress(uri: String, positionMs: Long, durationMs: Long) {
        val key = uri.trim()
        if (key.isEmpty() || positionMs < 0L) return
        synchronized(lock) {
            if (isNearEnd(positionMs, durationMs)) {
                clearProgressLocked(key)
                return
            }

            records.removeAll { it.uri == key }
            records.add(0, PlaybackRecord(key, positionMs, durationMs, System.currentTimeMillis()))
            while (records.size > MAX_RECORDS) {
                records.removeAt(records.lastIndex)
            }
            saveToPrefsLocked()
            Log.d(TAG, "Saved progress for $key: ${positionMs}ms / ${durationMs}ms (total ${records.size} records)")
        }
    }

    /**
     * Clears saved progress for [uri].
     */
    fun clearProgress(uri: String) {
        val key = uri.trim()
        if (key.isEmpty()) return
        synchronized(lock) {
            clearProgressLocked(key)
        }
    }

    private fun clearProgressLocked(key: String) {
        val removed = records.removeAll { it.uri == key }
        if (removed) {
            saveToPrefsLocked()
            Log.d(TAG, "Cleared progress for $key")
        }
    }

    fun getAllRecords(): List<PlaybackRecord> {
        synchronized(lock) {
            return ArrayList(records)
        }
    }
}
