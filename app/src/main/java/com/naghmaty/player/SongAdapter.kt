package com.naghmaty.player

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

class SongAdapter(
    private val context: Context,
    private val songs: MutableList<Song>,
    private val favorites: MutableSet<Long>,
    private val listener: Listener
) : BaseAdapter() {

    interface Listener {
        fun onFavoriteClick(song: Song)
        fun onMoreClick(song: Song)
    }

    override fun getCount(): Int = songs.size

    override fun getItem(position: Int): Song = songs[position]

    override fun getItemId(position: Int): Long = songs[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_song, parent, false)
        val song = songs[position]
        val titleView = view.findViewById<TextView>(R.id.itemTitle)
        val subtitleView = view.findViewById<TextView>(R.id.itemSubtitle)
        val favoriteView = view.findViewById<ImageView>(R.id.itemFavorite)
        val moreView = view.findViewById<ImageView>(R.id.itemMore)

        titleView.text = song.title
        subtitleView.text = song.subtitle
        favoriteView.setImageResource(
            if (favorites.contains(song.id)) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        )
        favoriteView.setOnClickListener { listener.onFavoriteClick(song) }
        moreView.setOnClickListener { listener.onMoreClick(song) }
        return view
    }
}
