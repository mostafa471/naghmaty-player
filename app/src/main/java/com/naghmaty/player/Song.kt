package com.naghmaty.player

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    val subtitle: String
        get() {
            val parts = ArrayList<String>()
            if (artist.isNotEmpty() && artist != "<unknown>") parts.add(artist)
            if (album.isNotEmpty() && album != "<unknown>") parts.add(album)
            val length = Util.formatDuration(durationMs)
            return if (parts.isEmpty()) length else parts.joinToString(" \u2022 ") + "   " + length
        }
}
