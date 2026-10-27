package me.zhanghai.android.files.viewer.media

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import kotlin.math.abs
import me.zhanghai.android.files.R

/** Only take downward gestures that start outside controls and scrolled content. */
class MusicPlayerLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var onDismiss: (() -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var candidate = false
    private var dragging = false
    private var closing = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (closing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetSwipe()
                downX = event.rawX
                downY = event.rawY
                downTime = event.eventTime
                candidate = canStartSwipe(this, event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> detectDrag(event)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> candidate = false
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (closing) return true
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val handled = candidate || dragging
            candidate = false
            dragging = false
            settleBack()
            return handled || super.onTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) detectDrag(event)
        if (!dragging) {
            if (event.actionMasked == MotionEvent.ACTION_UP && candidate) {
                candidate = false
                performClick()
                return true
            }
            return candidate || super.onTouchEvent(event)
        }
        val distance = (event.rawY - downY).coerceAtLeast(0f)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                translationY = distance
                alpha = 1f - (distance / height.coerceAtLeast(1)).coerceIn(0f, 0.5f)
            }
            MotionEvent.ACTION_UP -> {
                val density = resources.displayMetrics.density
                val elapsed = (event.eventTime - downTime).coerceAtLeast(1)
                if (distance >= 96 * density || (distance >= 48 * density && distance / elapsed >= 0.8f * density)) {
                    closing = true
                    animate().translationY(height.toFloat()).alpha(0f).setDuration(160)
                        .withEndAction { onDismiss?.invoke() }.start()
                } else settleBack()
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun detectDrag(event: MotionEvent) {
        if (!candidate || dragging) return
        val dx = abs(event.rawX - downX)
        val dy = event.rawY - downY
        if (dy < -slop || (dx > slop && dx > abs(dy))) candidate = false
        else if (dy > slop && dy > dx * 1.5f) dragging = true
    }

    private fun canStartSwipe(view: View, x: Float, y: Float): Boolean {
        if (view is Slider || view is RecyclerView || view.canScrollVertically(-1)
            || (view.isClickable && view.id != R.id.media_artwork)) return false
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(index)
                val childX = x + view.scrollX - child.left
                val childY = y + view.scrollY - child.top
                if (child.visibility == View.VISIBLE && childX >= 0 && childX < child.width && childY >= 0 && childY < child.height) {
                    return canStartSwipe(child, childX, childY)
                }
            }
        }
        return true
    }

    private fun settleBack() {
        animate().translationY(0f).alpha(1f).setDuration(160).start()
    }

    fun resetSwipe() {
        animate().cancel()
        translationY = 0f
        alpha = 1f
        candidate = false
        dragging = false
        closing = false
    }

    override fun onDetachedFromWindow() {
        resetSwipe()
        super.onDetachedFromWindow()
    }
}
