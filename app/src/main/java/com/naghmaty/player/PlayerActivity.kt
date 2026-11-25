package com.naghmaty.player

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class PlayerActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var elapsedView: TextView
    private lateinit var durationView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var toggleView: ImageView
    private lateinit var favoriteView: ImageView
    private lateinit var shuffleView: ImageView
    private lateinit var repeatView: ImageView

    private var currentSongId = -1L
    private var userSeeking = false
    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            currentSongId = intent.getLongExtra(PlayerService.EXTRA_SONG_ID, -1L)
            val title = intent.getStringExtra(PlayerService.EXTRA_TITLE) ?: ""
            val artist = intent.getStringExtra(PlayerService.EXTRA_ARTIST) ?: ""
            val playing = intent.getBooleanExtra(PlayerService.EXTRA_PLAYING, false)
            val position = intent.getIntExtra(PlayerService.EXTRA_POSITION_MS, 0)
            val duration = intent.getIntExtra(PlayerService.EXTRA_DURATION_MS, 0)
            val shuffle = intent.getBooleanExtra(PlayerService.EXTRA_SHUFFLE, false)
            val repeat = intent.getIntExtra(PlayerService.EXTRA_REPEAT, PlayerService.REPEAT_OFF)

            titleView.text = if (title.isEmpty()) getString(R.string.nothing_playing) else title
            artistView.text = artist
            toggleView.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
            shuffleView.alpha = if (shuffle) 1f else 0.4f
            repeatView.setImageResource(
                if (repeat == PlayerService.REPEAT_ONE) R.drawable.ic_repeat_one
                else R.drawable.ic_repeat
            )
            repeatView.alpha = if (repeat == PlayerService.REPEAT_OFF) 0.4f else 1f

            if (!userSeeking) {
                seekBar.max = if (duration > 0) duration else 1
                seekBar.progress = position.coerceIn(0, seekBar.max)
                elapsedView.text = Util.formatDuration(position.toLong())
            }
            durationView.text = Util.formatDuration(duration.toLong())
            favoriteView.setImageResource(
                if (prefs.isFavorite(currentSongId)) R.drawable.ic_heart_filled
                else R.drawable.ic_heart_outline
            )
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            sendAction(PlayerService.ACTION_REQUEST_STATE)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)

        titleView = findViewById(R.id.playerTitle)
        artistView = findViewById(R.id.playerArtist)
        elapsedView = findViewById(R.id.playerElapsed)
        durationView = findViewById(R.id.playerDuration)
        seekBar = findViewById(R.id.playerSeek)
        toggleView = findViewById(R.id.playerToggle)
        favoriteView = findViewById(R.id.playerFavorite)
        shuffleView = findViewById(R.id.playerShuffle)
        repeatView = findViewById(R.id.playerRepeat)

        findViewById<ImageView>(R.id.playerBack).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.playerPrev).setOnClickListener {
            sendAction(PlayerService.ACTION_PREV)
        }
        findViewById<ImageView>(R.id.playerNext).setOnClickListener {
            sendAction(PlayerService.ACTION_NEXT)
        }
        toggleView.setOnClickListener { sendAction(PlayerService.ACTION_TOGGLE) }
        shuffleView.setOnClickListener { sendAction(PlayerService.ACTION_TOGGLE_SHUFFLE) }
        repeatView.setOnClickListener { sendAction(PlayerService.ACTION_CYCLE_REPEAT) }
        favoriteView.setOnClickListener {
            if (currentSongId >= 0L) {
                prefs.toggleFavorite(currentSongId)
                favoriteView.setImageResource(
                    if (prefs.isFavorite(currentSongId)) R.drawable.ic_heart_filled
                    else R.drawable.ic_heart_outline
                )
            }
        }
        findViewById<TextView>(R.id.playerSleep).setOnClickListener { showSleepDialog() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsedView.text = Util.formatDuration(progress.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                userSeeking = false
                val position = bar?.progress ?: 0
                val intent = Intent(this@PlayerActivity, PlayerService::class.java)
                intent.action = PlayerService.ACTION_SEEK
                intent.putExtra(PlayerService.EXTRA_POSITION, position)
                try {
                    startService(intent)
                } catch (error: Exception) {
                    Unit
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        registerStateReceiver()
        sendAction(PlayerService.ACTION_REQUEST_STATE)
        handler.postDelayed(ticker, 1000L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver)
            } catch (error: Exception) {
                Unit
            }
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun registerStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(PlayerService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun sendAction(action: String) {
        val intent = Intent(this, PlayerService::class.java)
        intent.action = action
        try {
            startService(intent)
        } catch (error: Exception) {
            Unit
        }
    }

    private fun showSleepDialog() {
        val minutes = intArrayOf(5, 10, 15, 30, 60)
        val labels = ArrayList<String>()
        for (value in minutes) labels.add(getString(R.string.sleep_minutes, value))
        labels.add(getString(R.string.sleep_off))
        AlertDialog.Builder(this)
            .setTitle(R.string.sleep_timer)
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = if (which < minutes.size) minutes[which] else 0
                val intent = Intent(this, PlayerService::class.java)
                intent.action = PlayerService.ACTION_SET_SLEEP
                intent.putExtra(PlayerService.EXTRA_MINUTES, chosen)
                try {
                    startService(intent)
                } catch (error: Exception) {
                    Unit
                }
                val message = if (chosen > 0) {
                    getString(R.string.sleep_set, chosen)
                } else {
                    getString(R.string.sleep_off)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
