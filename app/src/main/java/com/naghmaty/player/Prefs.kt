package com.naghmaty.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Playlist(val id: String, val name: String, val songIds: List<Long>)

class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun favoriteIds(): MutableSet<Long> {
        val result = HashSet<Long>()
        val stored = prefs.getStringSet(KEY_FAVORITES, null) ?: return result
        for (value in stored) {
            val parsed = value.toLongOrNull()
            if (parsed != null) result.add(parsed)
        }
        return result
    }

    fun isFavorite(songId: Long): Boolean = favoriteIds().contains(songId)

    fun toggleFavorite(songId: Long): Boolean {
        val ids = favoriteIds()
        val nowFavorite: Boolean
        if (ids.contains(songId)) {
            ids.remove(songId)
            nowFavorite = false
        } else {
            ids.add(songId)
            nowFavorite = true
        }
        val stored = HashSet<String>()
        for (id in ids) stored.add(id.toString())
        prefs.edit().putStringSet(KEY_FAVORITES, stored).apply()
        return nowFavorite
    }

    fun playlists(): List<Playlist> {
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        val result = ArrayList<Playlist>()
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val ids = ArrayList<Long>()
                val idArray = item.optJSONArray("songs")
                if (idArray != null) {
                    for (songIndex in 0 until idArray.length()) {
                        ids.add(idArray.getLong(songIndex))
                    }
                }
                result.add(Playlist(item.optString("id"), item.optString("name"), ids))
            }
        } catch (error: Exception) {
            return emptyList()
        }
        return result
    }

    fun savePlaylists(lists: List<Playlist>) {
        val array = JSONArray()
        for (playlist in lists) {
            val item = JSONObject()
            item.put("id", playlist.id)
            item.put("name", playlist.name)
            val idArray = JSONArray()
            for (songId in playlist.songIds) idArray.put(songId)
            item.put("songs", idArray)
            array.put(item)
        }
        prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
    }

    fun createPlaylist(name: String): Playlist {
        val lists = playlists().toMutableList()
        val playlist = Playlist(UUID.randomUUID().toString(), name, emptyList<Long>())
        lists.add(playlist)
        savePlaylists(lists)
        return playlist
    }

    fun addSongToPlaylist(playlistId: String, songId: Long) {
        val lists = playlists().toMutableList()
        for (index in lists.indices) {
            val playlist = lists[index]
            if (playlist.id == playlistId && !playlist.songIds.contains(songId)) {
                val ids = ArrayList<Long>(playlist.songIds)
                ids.add(songId)
                lists[index] = Playlist(playlist.id, playlist.name, ids)
            }
        }
        savePlaylists(lists)
    }

    fun removeSongFromPlaylist(playlistId: String, songId: Long) {
        val lists = playlists().toMutableList()
        for (index in lists.indices) {
            val playlist = lists[index]
            if (playlist.id == playlistId) {
                val ids = ArrayList<Long>(playlist.songIds)
                ids.remove(songId)
                lists[index] = Playlist(playlist.id, playlist.name, ids)
            }
        }
        savePlaylists(lists)
    }

    private companion object {
        const val NAME = "naghmaty_prefs"
        const val KEY_FAVORITES = "favorites"
        const val KEY_PLAYLISTS = "playlists"
    }
}
