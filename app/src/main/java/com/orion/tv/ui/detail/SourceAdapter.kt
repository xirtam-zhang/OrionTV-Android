package com.orion.tv.ui.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.orion.tv.R
import com.orion.tv.data.remote.dto.SearchResult

class SourceAdapter(private val onClick: (SearchResult) -> Unit) :
    RecyclerView.Adapter<SourceAdapter.ViewHolder>() {

    private val items = mutableListOf<SearchResult>()
    private var selectedSource: String? = null

    fun submit(list: List<SearchResult>, selected: String?) {
        items.clear()
        items.addAll(list)
        selectedSource = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_source_badge, parent, false)
        return ViewHolder(view as TextView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.textView.text = "${item.source_name} (${item.episodes.size})"
        holder.textView.isSelected = item.source == selectedSource
        holder.textView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
