package com.orion.tv.data.repo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.orion.tv.data.remote.dto.Favorite

/**
 * Favorites are kept entirely on-device (SharedPreferences JSON blob), not synced through
 * MoonTV's per-user /api/favorites — this app has no account system, so there's no server-side
 * user identity to key server-stored favorites against.
 */
class FavoriteRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Favorite>>() {}.type

    suspend fun getAll(): Map<String, Favorite> {
        val json = prefs.getString(KEY_FAVORITES, null) ?: return emptyMap()
        return runCatching { gson.fromJson<Map<String, Favorite>>(json, mapType) }.getOrDefault(emptyMap())
    }

    suspend fun isFavorite(source: String, id: String): Boolean = getAll().containsKey("$source+$id")

    suspend fun save(source: String, id: String, favorite: Favorite) {
        val all = getAll().toMutableMap()
        all["$source+$id"] = favorite
        persist(all)
    }

    suspend fun delete(source: String, id: String) {
        val all = getAll().toMutableMap()
        all.remove("$source+$id")
        persist(all)
    }

    private fun persist(all: Map<String, Favorite>) {
        prefs.edit().putString(KEY_FAVORITES, gson.toJson(all)).apply()
    }

    companion object {
        private const val PREFS_NAME = "orion_favorites"
        private const val KEY_FAVORITES = "favorites_json"
    }
}
