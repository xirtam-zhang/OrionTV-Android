package com.orion.tv.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Lays out children left-to-right, wrapping to a new row when a child would overflow the
 * available width — for variable-width items (source badges) where a fixed-column GridLayout
 * doesn't fit. Uses each child's real on-screen position, so Android's default focus search
 * already resolves D-pad up/down to "the closest focusable view in the row above/below" with no
 * custom key handling needed.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var horizontalSpacing: Int = 0
    var verticalSpacing: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var rowWidth = 0
        var rowHeight = 0
        var totalHeight = paddingTop + paddingBottom
        var rowCount = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (rowWidth > 0 && rowWidth + horizontalSpacing + childWidth > availableWidth) {
                totalHeight += rowHeight + if (rowCount > 0) verticalSpacing else 0
                rowCount++
                rowWidth = childWidth
                rowHeight = childHeight
            } else {
                rowWidth += (if (rowWidth > 0) horizontalSpacing else 0) + childWidth
                rowHeight = maxOf(rowHeight, childHeight)
            }
        }
        if (rowWidth > 0) {
            totalHeight += rowHeight + if (rowCount > 0) verticalSpacing else 0
        }

        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(totalHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availableWidth = width - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (x > paddingLeft && x - paddingLeft + childWidth > availableWidth) {
                x = paddingLeft
                y += rowHeight + verticalSpacing
                rowHeight = 0
            }
            child.layout(x, y, x + childWidth, y + childHeight)
            x += childWidth + horizontalSpacing
            rowHeight = maxOf(rowHeight, childHeight)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)
}
