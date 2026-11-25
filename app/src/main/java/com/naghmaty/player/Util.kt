package com.naghmaty.player

import java.util.Locale

object Util {
    fun formatDuration(milliseconds: Long): String {
        val safe = if (milliseconds < 0L) 0L else milliseconds
        val totalSeconds = safe / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
