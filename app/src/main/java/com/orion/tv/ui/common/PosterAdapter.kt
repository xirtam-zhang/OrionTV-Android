package com.orion.tv.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.orion.tv.R

/**
 * Plain RecyclerView adapter for poster grids (Home/Favorites) — deliberately not a Leanback
 * Presenter/ObjectAdapter: a lighter-weight GridLayoutManager RecyclerView is easier to keep fast
 * and predictable on old/low-memory devices, and avoids the rendering quirks that showed up with
 * Leanback's higher-level grid/browse widgets on some phones. Posters are decoded at the card's
 * actual display size (not full source resolution) to keep memory use down over long scroll
 * sessions.
 */
class PosterAdapter(private val onClick: (CatalogItem) -> Unit) : RecyclerView.Adapter<PosterAdapter.ViewHolder>() {

    private val items = mutableListOf<CatalogItem>()

    fun submit(newItems: List<CatalogItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Appends a further page of results (infinite scroll) without re-binding what's already on screen. */
    fun appendItems(newItems: List<CatalogItem>) {
        if (newItems.isEmpty()) return
        val startIndex = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(startIndex, newItems.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.poster_image)
        private val ratingBadge: TextView = itemView.findViewById(R.id.rating_badge)
        private val titleText: TextView = itemView.findViewById(R.id.title_text)

        fun bind(item: CatalogItem) {
            titleText.text = item.title
            if (item.subtitle.isNullOrBlank()) {
                ratingBadge.visibility = View.GONE
            } else {
                ratingBadge.text = item.subtitle
                ratingBadge.visibility = View.VISIBLE
            }
            Glide.with(poster.context)
                .load(item.posterUrl)
                .placeholder(R.drawable.ic_poster_placeholder)
                .error(R.drawable.ic_poster_placeholder)
                .override(POSTER_WIDTH_PX, POSTER_HEIGHT_PX)
                .centerCrop()
                .into(poster)
            itemView.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        // Matches item_poster_card.xml's 130dp x 180dp poster frame, in px @ ~3x density —
        // decoding Glide's request at this size (not the source image's full resolution) is what
        // actually keeps memory bounded on low-RAM devices across long scroll sessions.
        private const val POSTER_WIDTH_PX = 390
        private const val POSTER_HEIGHT_PX = 540
    }
}
