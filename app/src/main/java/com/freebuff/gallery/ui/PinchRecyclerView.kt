package com.freebuff.gallery.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView that observes every touch in [dispatchTouchEvent], so pinch
 * gestures can be detected even while child items are handling the touch.
 */
class PinchRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** Total accumulated scale factor, delivered once per completed pinch. */
    var onPinchScale: ((Float) -> Unit)? = null

    private var accumulated = 1f

    private val detector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                accumulated *= d.scaleFactor
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                val factor = accumulated
                accumulated = 1f
                if (factor != 1f && factor.isFinite()) onPinchScale?.invoke(factor)
            }
        }
    )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) accumulated = 1f
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
