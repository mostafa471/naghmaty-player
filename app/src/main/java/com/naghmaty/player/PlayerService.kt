package com.naghmaty.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class PlayerService : Service() {

    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val songMap = HashMap<Long, Song>()
    private val queue = ArrayList<Long>()
    private var queueIndex = -1
    private var shuffleEnabled = false
    private var repeatMode = REPEAT_OFF
    private var prepared = false
    private var foregroundStarted = false
    private var noisyRegistered = false
    private val handler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            pausePlayback()
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausePlayback()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        for (song in MusicLibrary.loadSongs(this)) {
            songMap[song.id] = song
        }
        createChannel()
        val mediaSession = MediaSession(this, "Naghmaty")
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                resumePlayback()
            }

            override fun onPause() {
                pausePlayback()
            }

            override fun onSkipToNext() {
                skipNext(true)
            }

            override fun onSkipToPrevious() {
                skipPrevious()
            }

            override fun onSeekTo(pos: Long) {
                seekTo(pos.toInt())
            }

            override fun onStop() {
                stopEverything()
            }
        })
        mediaSession.isActive = true
        session = mediaSession
        registerNoisy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_PLAY -> {
                val ids = intent.getLongArrayExtra(EXTRA_IDS)
                val index = intent.getIntExtra(EXTRA_INDEX, 0)
                if (ids != null && ids.isNotEmpty()) startQueue(ids, index)
            }
            ACTION_TOGGLE -> {
                if (isPlayingNow()) pausePlayback() else resumePlayback()
            }
            ACTION_NEXT -> skipNext(true)
            ACTION_PREV -> skipPrevious()
            ACTION_STOP -> stopEverything()
            ACTION_SEEK -> seekTo(intent.getIntExtra(EXTRA_POSITION, 0))
            ACTION_TOGGLE_SHUFFLE -> {
                shuffleEnabled = !shuffleEnabled
                applyShuffle()
                refreshState()
                broadcastState()
            }
            ACTION_CYCLE_REPEAT -> {
                repeatMode = (repeatMode + 1) % 3
                refreshState()
                broadcastState()
            }
            ACTION_SET_SLEEP -> setSleepTimer(intent.getIntExtra(EXTRA_MINUTES, 0))
            ACTION_REQUEST_STATE -> {
                broadcastState()
                if (player == null) stopSelf()
            }
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        if (noisyRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (error: Exception) {
                Unit
            }
            noisyRegistered = false
        }
        releasePlayer()
        session?.isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun startQueue(ids: LongArray, index: Int) {
        val known = ArrayList<Long>()
        for (id in ids) {
            if (songMap.containsKey(id)) known.add(id)
        }
        if (known.isEmpty()) {
            for (song in MusicLibrary.loadSongs(this)) {
                songMap[song.id] = song
                known.add(song.id)
            }
        }
        if (known.isEmpty()) return
        queue.clear()
        queue.addAll(known)
        queueIndex = index.coerceIn(0, queue.size - 1)
        if (shuffleEnabled) applyShuffle()
        playCurrent()
    }

    private fun playCurrent() {
        val songId = queue.getOrNull(queueIndex) ?: return
        val song = songMap[songId] ?: return
        releasePlayer()
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            media.setDataSource(this, song.uri)
            media.setOnPreparedListener { mp ->
                prepared = true
                try {
                    mp.start()
                } catch (error: Exception) {
                    Unit
                }
                ensureForeground()
                refreshState()
                broadcastState()
            }
            media.setOnCompletionListener { onTrackFinished() }
            media.setOnErrorListener { _, _, _ ->
                prepared = false
                handler.post { skipNext(true) }
                true
            }
            media.prepareAsync()
            updateSessionMetadata(song)
            ensureForeground()
            refreshState()
            broadcastState()
        } catch (error: Exception) {
            releasePlayer()
            broadcastState()
        }
    }

    private fun releasePlayer() {
        val media = player
        player = null
        prepared = false
        if (media != null) {
            try {
                media.stop()
            } catch (error: Exception) {
                Unit
            }
            try {
                media.release()
            } catch (error: Exception) {
                Unit
            }
        }
    }

    private fun pausePlayback() {
        val media = player
        if (media != null && prepared && media.isPlaying) {
            try {
                media.pause()
            } catch (error: Exception) {
                Unit
            }
        }
        refreshState()
        updateNotification()
        broadcastState()
    }

    private fun resumePlayback() {
        val media = player
        if (media != null && prepared) {
            try {
                media.start()
            } catch (error: Exception) {
                Unit
            }
            ensureForeground()
            refreshState()
            broadcastState()
        } else if (queue.isNotEmpty()) {
            playCurrent()
        }
    }

    private fun skipNext(fromUser: Boolean) {
        if (queue.isEmpty()) return
        if (queueIndex >= queue.size - 1) {
            if (repeatMode == REPEAT_ALL || fromUser) {
                queueIndex = 0
            } else {
                pausePlayback()
                return
            }
        } else {
            queueIndex += 1
        }
        playCurrent()
    }

    private fun skipPrevious() {
        if (queue.isEmpty()) return
        val media = player
        if (media != null && prepared && media.currentPosition > 3000) {
            seekTo(0)
            return
        }
        if (queueIndex > 0) {
            queueIndex -= 1
        } else if (repeatMode == REPEAT_ALL) {
            queueIndex = queue.size - 1
        }
        playCurrent()
    }

    private fun onTrackFinished() {
        if (repeatMode == REPEAT_ONE) {
            seekTo(0)
            resumePlayback()
            return
        }
        skipNext(false)
    }

    private fun seekTo(positionMs: Int) {
        val media = player
        if (media != null && prepared) {
            val target = positionMs.coerceIn(0, media.duration)
            media.seekTo(target)
            refreshState()
            broadcastState()
        }
    }

    private fun applyShuffle() {
        if (queue.isEmpty()) return
        val currentId = queue.getOrNull(queueIndex)
        if (shuffleEnabled) {
            queue.shuffle()
            if (currentId != null) {
                val position = queue.indexOf(currentId)
                queueIndex = if (position >= 0) position else 0
            }
        }
    }

    private fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        if (minutes <= 0) return
        val runnable = Runnable {
            sleepRunnable = null
            pausePlayback()
        }
        sleepRunnable = runnable
        handler.postDelayed(runnable, minutes.toLong() * 60L * 1000L)
    }

    private fun stopEverything() {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        releasePlayer()
        queue.clear()
        queueIndex = -1
        session?.isActive = false
        if (foregroundStarted) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (error: Exception) {
                Unit
            }
            foregroundStarted = false
        }
        broadcastState()
        stopSelf()
    }

    private fun isPlayingNow(): Boolean {
        val media = player
        return media != null && prepared && media.isPlaying
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        if (!foregroundStarted) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                foregroundStarted = true
            } catch (error: Exception) {
                Unit
            }
        } else {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val song = queue.getOrNull(queueIndex)?.let { songMap[it] }
        val playing = isPlayingNow()
        val contentIntent = PendingIntent.getActivity(
            this,
            CONTENT_REQUEST,
            Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder.setSmallIcon(R.drawable.ic_note)
            .setContentIntent(contentIntent)
            .setShowWhen(false)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentTitle(song?.title ?: getString(R.string.app_name))
            .setContentText(song?.artist ?: getString(R.string.nothing_playing))
            .addAction(
                R.drawable.ic_prev,
                getString(R.string.previous),
                servicePending(ACTION_PREV, PREV_REQUEST)
            )
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.pause else R.string.play),
                servicePending(ACTION_TOGGLE, TOGGLE_REQUEST)
            )
            .addAction(
                R.drawable.ic_next,
                getString(R.string.next),
                servicePending(ACTION_NEXT, NEXT_REQUEST)
            )
            .addAction(
                R.drawable.ic_close,
                getString(R.string.close),
                servicePending(ACTION_STOP, STOP_REQUEST)
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        return builder.build()
    }

    private fun servicePending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlayerService::class.java)
        intent.action = action
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun refreshState() {
        val media = player
        val position = if (media != null && prepared) media.currentPosition.toLong() else 0L
        val playing = isPlayingNow()
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        val state = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                position,
                if (playing) 1f else 0f
            )
            .build()
        session?.setPlaybackState(state)
        updateSessionMetadata(queue.getOrNull(queueIndex)?.let { songMap[it] })
    }

    private fun updateSessionMetadata(song: Song?) {
        val mediaSession = session ?: return
        val builder = MediaMetadata.Builder()
        if (song != null) {
            builder.putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, song.durationMs)
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun broadcastState() {
        val media = player
        val song = queue.getOrNull(queueIndex)?.let { songMap[it] }
        val intent = Intent(ACTION_STATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_PLAYING, isPlayingNow())
        intent.putExtra(EXTRA_SONG_ID, song?.id ?: -1L)
        intent.putExtra(EXTRA_TITLE, song?.title ?: "")
        intent.putExtra(EXTRA_ARTIST, song?.artist ?: "")
        intent.putExtra(
            EXTRA_POSITION_MS,
            if (media != null && prepared) media.currentPosition else 0
        )
        intent.putExtra(
            EXTRA_DURATION_MS,
            if (media != null && prepared) media.duration else (song?.durationMs?.toInt() ?: 0)
        )
        intent.putExtra(EXTRA_SHUFFLE, shuffleEnabled)
        intent.putExtra(EXTRA_REPEAT, repeatMode)
        intent.putExtra(EXTRA_SLEEP, sleepRunnable != null)
        sendBroadcast(intent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
        noisyRegistered = true
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    companion object {
        const val ACTION_PLAY = "com.naghmaty.player.PLAY"
        const val ACTION_TOGGLE = "com.naghmaty.player.TOGGLE"
        const val ACTION_NEXT = "com.naghmaty.player.NEXT"
        const val ACTION_PREV = "com.naghmaty.player.PREV"
        const val ACTION_STOP = "com.naghmaty.player.STOP"
        const val ACTION_SEEK = "com.naghmaty.player.SEEK"
        const val ACTION_TOGGLE_SHUFFLE = "com.naghmaty.player.TOGGLE_SHUFFLE"
        const val ACTION_CYCLE_REPEAT = "com.naghmaty.player.CYCLE_REPEAT"
        const val ACTION_SET_SLEEP = "com.naghmaty.player.SET_SLEEP"
        const val ACTION_REQUEST_STATE = "com.naghmaty.player.REQUEST_STATE"
        const val ACTION_STATE = "com.naghmaty.player.STATE"

        const val EXTRA_IDS = "ids"
        const val EXTRA_INDEX = "index"
        const val EXTRA_POSITION = "position"
        const val EXTRA_MINUTES = "minutes"

        const val EXTRA_PLAYING = "playing"
        const val EXTRA_SONG_ID = "song_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_SHUFFLE = "shuffle"
        const val EXTRA_REPEAT = "repeat"
        const val EXTRA_SLEEP = "sleep"

        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2

        private const val CHANNEL_ID = "naghmaty_playback"
        private const val NOTIFICATION_ID = 101
        private const val PREV_REQUEST = 11
        private const val TOGGLE_REQUEST = 12
        private const val NEXT_REQUEST = 13
        private const val STOP_REQUEST = 14
        private const val CONTENT_REQUEST = 15
    }
}
