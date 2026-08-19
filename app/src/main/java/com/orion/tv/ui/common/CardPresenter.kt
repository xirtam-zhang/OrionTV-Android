package com.orion.tv.ui.common

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.orion.tv.R

/** 5-column poster card presenter used by Home rows and Search results (matches OrionTV's VideoCard.tv.tsx sizing). */
class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            setInfoAreaBackgroundColor(Color.parseColor("#1C1C1C"))
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cardView = viewHolder.view as ImageCardView
        val catalogItem = item as CatalogItem
        cardView.titleText = catalogItem.title
        cardView.contentText = catalogItem.subtitle
        cardView.mainImage = ColorDrawable(Color.parseColor("#1C1C1C"))
        Glide.with(cardView.context)
            .load(catalogItem.posterUrl)
            .placeholder(R.drawable.ic_poster_placeholder)
            .error(R.drawable.ic_poster_placeholder)
            .centerCrop()
            .into(cardView.mainImageView!!)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        Glide.with(cardView.context).clear(cardView.mainImageView)
        cardView.mainImage = null
    }

    companion object {
        private const val CARD_WIDTH = 220
        private const val CARD_HEIGHT = 310
    }
}
