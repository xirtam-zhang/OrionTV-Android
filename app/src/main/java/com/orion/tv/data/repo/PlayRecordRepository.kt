package com.orion.tv.data.repo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.orion.tv.data.remote.dto.PlayRecord

/**
 * Play progress (最近播放) is kept entirely on-device (SharedPreferences JSON blob), not synced
 * through MoonTV's per-user /api/playrecords — this app has no account system, so there's no
 * server-side user identity to key server-stored history against.
 */
class PlayRecordRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, PlayRecord>>() {}.type

    suspend fun getAll(): Map<String, PlayRecord> {
        val json = prefs.getString(KEY_RECORDS, null) ?: return emptyMap()
        return runCatching { gson.fromJson<Map<String, PlayRecord>>(json, mapType) }.getOrDefault(emptyMap())
    }

    suspend fun save(source: String, id: String, record: PlayRecord) {
        val all = getAll().toMutableMap()
        all["$source+$id"] = record
        persist(all)
    }

    suspend fun delete(source: String, id: String) {
        val all = getAll().toMutableMap()
        all.remove("$source+$id")
        persist(all)
    }

    private fun persist(all: Map<String, PlayRecord>) {
        prefs.edit().putString(KEY_RECORDS, gson.toJson(all)).apply()
    }

    companion object {
        private const val PREFS_NAME = "orion_play_records"
        private const val KEY_RECORDS = "records_json"
    }
}
