package com.naghmaty.player

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

data class GroupItem(val title: String, val subtitle: String)

class GroupAdapter(
    private val context: Context,
    private val groups: List<GroupItem>
) : BaseAdapter() {

    override fun getCount(): Int = groups.size

    override fun getItem(position: Int): GroupItem = groups[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_group, parent, false)
        val group = groups[position]
        view.findViewById<TextView>(R.id.itemTitle).text = group.title
        view.findViewById<TextView>(R.id.itemSubtitle).text = group.subtitle
        return view
    }
}
