package com.naghmaty.player

import android.content.Context
import android.provider.MediaStore

object MusicLibrary {

    fun loadSongs(context: Context): List<Song> {
        val songs = ArrayList<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
            MediaStore.Audio.Media.DURATION + " > 20000"
        val sortOrder = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )
        } catch (error: Exception) {
            null
        }

        if (cursor != null) {
            try {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn) ?: ""
                    val artist = cursor.getString(artistColumn) ?: ""
                    val album = cursor.getString(albumColumn) ?: ""
                    val duration = cursor.getLong(durationColumn)
                    songs.add(Song(id, title, artist, album, duration))
                }
            } catch (error: Exception) {
                cursor.close()
                return songs
            }
            cursor.close()
        }
        return songs
    }
}
