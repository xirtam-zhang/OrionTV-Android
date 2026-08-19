package com.orion.tv.ui.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.orion.tv.R

class EpisodeAdapter(private val onClick: (Int) -> Unit) :
    RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    private var episodeCount = 0
    private var playingIndex: Int = -1

    fun submit(count: Int, currentlyPlayingIndex: Int = -1) {
        episodeCount = count
        playingIndex = currentlyPlayingIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view as TextView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.text = "第${position + 1}集"
        holder.textView.isSelected = position == playingIndex
        holder.textView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = episodeCount

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
