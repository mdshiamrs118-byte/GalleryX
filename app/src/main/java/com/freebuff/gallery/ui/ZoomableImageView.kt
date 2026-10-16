package com.freebuff.gallery.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import android.animation.ValueAnimator

/**
 * ImageView with pinch-to-zoom, drag-panning, double-tap zoom and single tap
 * callbacks. At base scale it never intercepts horizontal drags so the parent
 * ViewPager2 can still swipe between pages.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** Invoked on a confirmed single tap (used to show/hide the chrome). */
    var onSingleTap: (() -> Unit)? = null

    private val displayMatrix = Matrix()
    private val baseMatrix = Matrix()
    private val workRect = RectF()

    private var baseScale = 1f
    private var minScale = 1f
    private var maxScale = 4f
    private var layoutReady = false

    private val animatorInterpolator = AccelerateDecelerateInterpolator()

    private val scaleDetector =
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (drawable == null) return false
                val factor = detector.scaleFactor
                if (factor.isNaN() || factor.isInfinite() || factor <= 0f) return false
                displayMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clamp()
                apply()
                return true
            }

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = drawable != null
        })

    private val gestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (drawable == null) return false
                animateTo(if (isZoomedIn()) 1f else 2.5f, e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!isZoomedIn()) return false
                displayMatrix.postTranslate(-distanceX, -distanceY)
                clamp()
                apply()
                return true
            }
        })

    init {
        scaleType = ImageView.ScaleType.MATRIX
    }

    /** Called by the adapter before binding a new page. */
    fun resetZoom() {
        if (layoutReady && drawable != null) rebuildBase(reset = true)
    }

    fun isZoomedIn(): Boolean = totalScale() > minScale * 1.05f

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (layoutReady) rebuildBase(reset = true)
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        if (layoutReady) rebuildBase(reset = true)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (width > 0 && height > 0) {
            val first = !layoutReady
            layoutReady = true
            if (changed || first) rebuildBase(reset = true)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_MOVE -> {
                // While zoomed in we own the gesture; otherwise let ViewPager2 swipe.
                if (scaleDetector.isInProgress || isZoomedIn()) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isZoomedIn()) parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- helpers

    private fun rebuildBase(reset: Boolean) {
        val d = drawable ?: return
        val dw = d.intrinsicWidth
        val dh = d.intrinsicHeight
        if (dw <= 0 || dh <= 0 || width <= 0 || height <= 0) return

        val scale = minOf(width.toFloat() / dw, height.toFloat() / dh)
        baseScale = scale
        minScale = scale
        maxScale = scale * 4f

        val dx = (width - dw * scale) / 2f
        val dy = (height - dh * scale) / 2f
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)

        if (reset) {
            displayMatrix.set(baseMatrix)
            apply()
        }
    }

    private fun apply() {
        imageMatrix = displayMatrix
    }

    private fun totalScale(): Float {
        if (baseScale <= 0f) return 1f
        val values = FloatArray(9)
        displayMatrix.getValues(values)
        val scaleX = values[Matrix.MSCALE_X]
        return scaleX / baseScale
    }

    private fun clamp() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f || width <= 0 || height <= 0) return

        // Respect the allowed zoom range.
        val values = FloatArray(9)
        displayMatrix.getValues(values)
        var s = values[Matrix.MSCALE_X]
        if (s < minScale || s > maxScale) {
            val target = s.coerceIn(minScale, maxScale)
            val factor = target / s
            displayMatrix.postScale(factor, factor, width / 2f, height / 2f)
        }

        // Keep the image covering the viewport correctly.
        workRect.set(0f, 0f, dw, dh)
        displayMatrix.mapRect(workRect)

        var dx = 0f
        var dy = 0f
        if (workRect.width() <= width) {
            dx = width / 2f - workRect.centerX()
        } else {
            if (workRect.left > 0f) dx = -workRect.left
            else if (workRect.right < width) dx = width - workRect.right
        }
        if (workRect.height() <= height) {
            dy = height / 2f - workRect.centerY()
        } else {
            if (workRect.top > 0f) dy = -workRect.top
            else if (workRect.bottom < height) dy = height - workRect.bottom
        }
        if (dx != 0f || dy != 0f) displayMatrix.postTranslate(dx, dy)
    }

    private fun animateTo(targetRelativeScale: Float, focusX: Float, focusY: Float) {
        if (baseScale <= 0f) return
        val startMatrix = Matrix(displayMatrix)
        val values = FloatArray(9)
        displayMatrix.getValues(values)
        val startScale = values[Matrix.MSCALE_X]
        if (startScale <= 0f) return
        val endFactor = (targetRelativeScale * baseScale) / startScale

        val animator = ValueAnimator.ofFloat(1f, endFactor)
        animator.duration = 220L
        animator.interpolator = animatorInterpolator
        animator.addUpdateListener { valueAnimator ->
            displayMatrix.set(startMatrix)
            val factor = valueAnimator.animatedValue as Float
            displayMatrix.postScale(factor, factor, focusX, focusY)
            clamp()
            apply()
        }
        animator.start()
    }
}
