package com.freebuff.gallery.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A FrameLayout that always measures itself as tall as it is wide (square cells). */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
