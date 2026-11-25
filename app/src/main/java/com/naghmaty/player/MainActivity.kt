package com.naghmaty.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SongAdapter.Listener {

    private var mode = MODE_SONGS
    private var selectedGroup: String? = null
    private var selectedPlaylistId: String? = null
    private var query = ""
    private var allSongs: List<Song> = emptyList()
    private var visibleSongs: MutableList<Song> = ArrayList()
    private val playlistIdsByName = HashMap<String, String>()

    private lateinit var prefs: Prefs
    private lateinit var searchInput: EditText
    private lateinit var songList: ListView
    private lateinit var emptyText: TextView
    private lateinit var permissionBlock: LinearLayout
    private lateinit var actionButton: Button
    private lateinit var nowBar: LinearLayout
    private lateinit var nowBarTitle: TextView
    private lateinit var nowBarSubtitle: TextView
    private lateinit var nowBarToggle: ImageView
    private lateinit var tabs: MutableList<TextView>

    private var songAdapter: SongAdapter? = null
    private var groupAdapter: GroupAdapter? = null
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val songId = intent.getLongExtra(PlayerService.EXTRA_SONG_ID, -1L)
            if (songId < 0L) {
                nowBar.visibility = View.GONE
                return
            }
            val playing = intent.getBooleanExtra(PlayerService.EXTRA_PLAYING, false)
            nowBar.visibility = View.VISIBLE
            nowBarTitle.text = intent.getStringExtra(PlayerService.EXTRA_TITLE) ?: ""
            nowBarSubtitle.text = intent.getStringExtra(PlayerService.EXTRA_ARTIST) ?: ""
            nowBarToggle.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            sendAction(PlayerService.ACTION_REQUEST_STATE)
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        searchInput = findViewById(R.id.searchInput)
        songList = findViewById(R.id.songList)
        emptyText = findViewById(R.id.emptyText)
        permissionBlock = findViewById(R.id.permissionBlock)
        actionButton = findViewById(R.id.actionButton)
        nowBar = findViewById(R.id.nowBar)
        nowBarTitle = findViewById(R.id.nowBarTitle)
        nowBarSubtitle = findViewById(R.id.nowBarSubtitle)
        nowBarToggle = findViewById(R.id.nowBarToggle)

        tabs = ArrayList()
        tabs.add(findViewById(R.id.tabSongs))
        tabs.add(findViewById(R.id.tabAlbums))
        tabs.add(findViewById(R.id.tabArtists))
        tabs.add(findViewById(R.id.tabFavorites))
        tabs.add(findViewById(R.id.tabPlaylists))
        for (index in tabs.indices) {
            tabs[index].setOnClickListener { selectTab(index) }
        }

        findViewById<Button>(R.id.permissionButton).setOnClickListener {
            requestAudioPermission()
        }
        actionButton.setOnClickListener { createPlaylistDialog() }
        nowBar.setOnClickListener { openPlayer() }
        nowBarToggle.setOnClickListener {
            sendAction(PlayerService.ACTION_TOGGLE)
        }

        songList.setOnItemClickListener { _, _, position, _ ->
            if (songList.adapter === songAdapter) {
                if (position in visibleSongs.indices) playFromList(position)
            } else {
                val group = groupAdapter?.getItem(position)
                if (group != null) openGroup(group)
            }
        }
        songList.setOnItemLongClickListener { _, _, position, _ ->
            if (songList.adapter === songAdapter && position in visibleSongs.indices) {
                showSongOptions(visibleSongs[position])
                true
            } else {
                false
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                query = text?.toString()?.trim()?.lowercase() ?: ""
                render()
            }

            override fun afterTextChanged(text: Editable?) = Unit
        })

        if (hasAudioPermission()) {
            loadLibrary()
        } else {
            permissionBlock.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        registerStateReceiver()
        sendAction(PlayerService.ACTION_REQUEST_STATE)
        handler.postDelayed(ticker, 2000L)
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

    override fun onFavoriteClick(song: Song) {
        prefs.toggleFavorite(song.id)
        if (mode == MODE_FAVORITES) {
            render()
        } else {
            songAdapter?.notifyDataSetChanged()
        }
    }

    override fun onMoreClick(song: Song) {
        showSongOptions(song)
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

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        val permissions = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(permissions.toTypedArray(), REQUEST_AUDIO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            if (hasAudioPermission()) {
                permissionBlock.visibility = View.GONE
                loadLibrary()
            } else {
               алло(0)
            }
        }
    }

    private fun loadLibrary() {
        permissionBlock.visibility = View.GONE
        emptyText.visibility = View.VISIBLE
        emptyText.text = getString(R.string.loading)
        Thread {
            val songs = MusicLibrary.loadSongs(this)
            runOnUiThread {
                allSongs = songs
                render()
            }
        }.start()
    }

    private fun selectTab(index: Int) {
        mode = index
        selectedGroup = null
        selectedPlaylistId = null
        query = ""
        searchInput.setText("")
        actionButton.visibility = if (index == MODE_PLAYLISTS) View.VISIBLE else View.GONE
        for (position in tabs.indices) {
            tabs[position].setTextColor(
                if (position == index) getColor(R.color.accent) else getColor(R.color.text_secondary)
            )
        }
        render()
    }

    private fun render() {
        actionButton.visibility = if (mode == MODE_PLAYLISTS) View.VISIBLE else View.GONE
        when (mode) {
            MODE_SONGS -> showSongs(filterSongs(allSongs))
            MODE_ALBUMS -> showGroups(buildGroups(true))
            MODE_ARTISTS -> showGroups(buildGroups(false))
            MODE_FAVORITES -> {
                val favorites = prefs.favoriteIds()
                showSongs(filterSongs(allSongs.filter { favorites.contains(it.id) }))
            }
            MODE_PLAYLISTS -> showPlaylists()
            MODE_ALBUM_SONGS -> {
                val group = selectedGroup ?: ""
                showSongs(filterSongs(allSongs.filter { albumName(it) == group }))
            }
            MODE_ARTIST_SONGS -> {
                val group = selectedGroup ?: ""
                showSongs(filterSongs(allSongs.filter { artistName(it) == group }))
            }
            MODE_PLAYLIST_SONGS -> {
                val ids = prefs.playlists().firstOrNull { it.id == selectedPlaylistId }?.songIds
                    ?: emptyList()
                showSongs(filterSongs(allSongs.filter { ids.contains(it.id) }))
            }
            else -> showSongs(filterSongs(allSongs))
        }
    }

    private fun filterSongs(source: List<Song>): List<Song> {
        if (query.isEmpty()) return source
        return source.filter {
            it.title.lowercase().contains(query) ||
                it.artist.lowercase().contains(query) ||
                it.album.lowercase().contains(query)
        }
    }

    private fun albumName(song: Song): String =
        if (song.album.isEmpty()) getString(R.string.unknown_album) else song.album

    private fun artistName(song: Song): String =
        if (song.artist.isEmpty()) getString(R.string.unknown_artist) else song.artist

    private fun buildGroups(byAlbum: Boolean): List<GroupItem> {
        val counts = HashMap<String, Int>()
        for (song in allSongs) {
            val key = if (byAlbum) albumName(song) else artistName(song)
            counts[key] = (counts[key] ?: 0) + 1
        }
        val names = counts.keys.sortedBy { it.lowercase() }
        val items = ArrayList<GroupItem>()
        for (name in names) {
            items.add(GroupItem(name, getString(R.string.song_count, counts[name] ?: 0)))
        }
        return items
    }

    private fun showSongs(songs: List<Song>) {
        visibleSongs = ArrayList(songs)
        songAdapter = SongAdapter(this, visibleSongs, prefs.favoriteIds(), this)
        songList.adapter = songAdapter
        if (visibleSongs.isEmpty()) {
            showEmpty(
                if (query.isNotEmpty()) getString(R.string.empty_search)
                else getString(R.string.empty_library)
            )
        } else {
            emptyText.visibility = View.GONE
        }
    }

    private fun showGroups(groups: List<GroupItem>) {
        val filtered = if (query.isEmpty()) groups else groups.filter { it.title.lowercase().contains(query) }
        groupAdapter = GroupAdapter(this, filtered)
        songList.adapter = groupAdapter
        if (filtered.isEmpty()) {
            showEmpty(
                if (query.isNotEmpty()) getString(R.string.empty_search)
                else getString(R.string.empty_list)
            )
        } else {
            emptyText.visibility = View.GONE
        }
    }

    private fun showPlaylists() {
        val lists = prefs.playlists()
        playlistIdsByName.clear()
        val items = ArrayList<GroupItem>()
        for (playlist in lists) {
            playlistIdsByName[playlist.name] = playlist.id
            items.add(GroupItem(playlist.name, getString(R.string.song_count, playlist.songIds.size)))
        }
        if (items.isEmpty()) {
            groupAdapter = GroupAdapter(this, items)
            songList.adapter = groupAdapter
            showEmpty(getString(R.string.no_playlists))
        } else {
            showGroups(items)
        }
    }

    private fun showEmpty(message: String) {
        emptyText.text = message
        emptyText.visibility = View.VISIBLE
    }

    private fun openGroup(group: GroupItem) {
        when (mode) {
            MODE_ALBUMS -> {
                mode = MODE_ALBUM_SONGS
                selectedGroup = group.title
            }
            MODE_ARTISTS -> {
                mode = MODE_ARTIST_SONGS
                selectedGroup = group.title
            }
            MODE_PLAYLISTS -> {
                mode = MODE_PLAYLIST_SONGS
                selectedPlaylistId = playlistIdsByName[group.title]
                selectedGroup = group.title
            }
        }
        query = ""
        searchInput.setText("")
        render()
    }

    private fun playFromList(position: Int) {
        if (visibleSongs.isEmpty()) return
        val ids = LongArray(visibleSongs.size)
        for (index in visibleSongs.indices) {
            ids[index] = visibleSongs[index].id
        }
        val intent = Intent(this, PlayerService::class.java)
        intent.action = PlayerService.ACTION_PLAY
        intent.putExtra(PlayerService.EXTRA_IDS, ids)
        intent.putExtra(PlayerService.EXTRA_INDEX, position)
        try {
            startService(intent)
        } catch (error: Exception) {
            Toast.makeText(this, getString(R.string.playback_failed), Toast.LENGTH_SHORT).show()
        }
        openPlayer()
    }

    private fun openPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
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

    private fun showSongOptions(song: Song) {
        val options = ArrayList<String>()
        options.add(
            if (prefs.isFavorite(song.id)) getString(R.string.unfavorite)
            else getString(R.string.favorite)
        )
        options.add(getString(R.string.add_to_playlist))
        if (mode == MODE_PLAYLIST_SONGS) options.add(getString(R.string.remove_from_playlist))
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> onFavoriteClick(song)
                    1 -> addToPlaylistDialog(song)
                    else -> removeFromCurrentPlaylist(song)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addToPlaylistDialog(song: Song) {
        val lists = prefs.playlists()
        if (lists.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_playlists), Toast.LENGTH_SHORT).show()
            createPlaylistDialog()
            return
        }
        val names = ArrayList<String>()
        for (playlist in lists) names.add(playlist.name)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(names.toTypedArray()) { _, which ->
                val playlist = lists[which]
                prefs.addSongToPlaylist(playlist.id, song.id)
                Toast.makeText(
                    this,
                    getString(R.string.added_to_playlist, playlist.name),
                    Toast.LENGTH_SHORT
                ).show()
                if (mode == MODE_PLAYLIST_SONGS) render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeFromCurrentPlaylist(song: Song) {
        val playlistId = selectedPlaylistId ?: return
        prefs.removeSongFromPlaylist(playlistId, song.id)
        render()
    }

    private fun createPlaylistDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.playlist_name_hint)
        input.setTextColor(getColor(R.color.text_primary))
        input.setHintTextColor(getColor(R.color.text_secondary))
        AlertDialog.Builder(this)
            .setTitle(R.string.new_playlist)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                prefs.createPlaylist(name)
                Toast.makeText(
                    this,
                    getString(R.string.playlist_created, name),
                    Toast.LENGTH_SHORT
                ).show()
                mode = MODE_PLAYLISTS
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private companion object {
        const val MODE_SONGS = 0
        const val MODE_ALBUMS = 1
        const val MODE_ARTISTS = 2
        const val MODE_FAVORITES = 3
        const val MODE_PLAYLISTS = 4
        const val MODE_ALBUM_SONGS = 5
        const val MODE_ARTIST_SONGS = 6
        const val MODE_PLAYLIST_SONGS = 7
        const val REQUEST_AUDIO = 200
    }
}
