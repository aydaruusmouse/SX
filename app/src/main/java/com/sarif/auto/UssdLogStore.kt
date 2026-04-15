package com.sarif.auto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists recent USSD steps for the “Served requests” list (similar to reference queue UIs).
 */
class UssdLogStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadRecent(max: Int = MAX_ENTRIES): List<UssdLogEntry> {
        val raw = prefs.getString(KEY_LOG, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<UssdLogEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    UssdLogEntry(
                        id = o.getLong("id"),
                        stepIndex = o.optInt("i", 0),
                        timeMs = o.getLong("t"),
                        requestOpener = o.getString("q"),
                        simLabel = o.getString("s"),
                        ok = o.getBoolean("ok"),
                        response = o.getString("r")
                    )
                )
            }
            out.takeLast(max)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun append(entry: UssdLogEntry) {
        val current = loadRecent(MAX_ENTRIES * 2).toMutableList()
        current.add(entry)
        val trimmed = if (current.size > MAX_ENTRIES) current.takeLast(MAX_ENTRIES) else current
        val arr = JSONArray()
        for (e in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("i", e.stepIndex)
                    put("t", e.timeMs)
                    put("q", e.requestOpener)
                    put("s", e.simLabel)
                    put("ok", e.ok)
                    put("r", e.response)
                }
            )
        }
        prefs.edit().putString(KEY_LOG, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_LOG).apply()
    }

    companion object {
        private const val PREFS = "sarif_ussd_log"
        private const val KEY_LOG = "entries_json"
        private const val MAX_ENTRIES = 50
    }
}

data class UssdLogEntry(
    val id: Long,
    val stepIndex: Int,
    val timeMs: Long,
    val requestOpener: String,
    val simLabel: String,
    val ok: Boolean,
    val response: String
)
