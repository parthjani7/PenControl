package dev.parth.penscroll

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Small draggable pill shown while scrolling or in pen remote mode:
 * drag handle / slower / status / faster | reverse / top / stop.
 * Uses an accessibility overlay window, so no "draw over other apps" permission is needed.
 */
class FloatingControls(private val service: PenScrollService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var penBadge: ImageView? = null
    private var statusIcon: ImageView? = null
    private var value: TextView? = null
    private var unit: TextView? = null
    private var status: LinearLayout? = null
    private var speedViews = emptyList<View>()
    private var scrollButtons = emptyList<View>()
    private var recordButton: View? = null
    private var lastTouchAt = 0L

    // Top of the screen, clear of the 25–75% band the scroll gestures use.
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(56)
    }

    val isShowing get() = root != null

    fun touchedRecently() = SystemClock.uptimeMillis() - lastTouchAt < TOUCH_WINDOW_MS

    fun show() {
        if (root != null) return
        val row = object : LinearLayout(service) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                lastTouchAt = SystemClock.uptimeMillis()
                return super.dispatchTouchEvent(ev)
            }
        }
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(SURFACE)
            setStroke(dp(1), OUTLINE)
        }
        row.setPadding(dp(2), dp(4), dp(6), dp(4))

        row.addView(ImageView(service).apply {
            setImageResource(R.drawable.ic_drag)
            imageTintList = ColorStateList.valueOf(MUTED)
            setPadding(dp(6), dp(10), dp(2), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(44))
            setOnTouchListener(DragListener())
        })
        val slower = iconButton(R.drawable.ic_remove, "Slower") { service.adjust(faster = false) }
        val faster = iconButton(R.drawable.ic_add, "Faster") { service.adjust(faster = true) }
        row.addView(slower)
        row.addView(statusBlock())
        row.addView(faster)
        speedViews = listOfNotNull(slower, faster, value?.parent as? View)
        row.addView(View(service).apply {
            setBackgroundColor(OUTLINE)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(24)).apply { marginStart = dp(4); marginEnd = dp(4) }
        })
        recordButton = iconButton(R.drawable.ic_record, "Record macro for this app") { service.startRecording() }
            .also { it.imageTintList = null }
        row.addView(recordButton)
        val reverse = iconButton(R.drawable.ic_swap_vert, "Reverse") { service.reverse() }
        val top = iconButton(R.drawable.ic_jump_top, "Jump to top") { service.jump(PenScrollService.Direction.UP) }
        row.addView(reverse)
        row.addView(top)
        scrollButtons = listOf(reverse, top)
        // Stops scrolling; when already idle, turns pen remote off.
        row.addView(iconButton(R.drawable.ic_stop, "Stop", fill = STOP_RED) {
            if (service.direction != null) service.stop() else service.setRemoteMode(false)
        })

        wm.addView(row, params)
        root = row
        update()
    }

    fun update() {
        val value = value ?: return
        val scrolling = service.direction != null
        val macro = service.macroProgress()
        // Swipe-per-click doesn't use a speed, so hide it until something scrolls continuously.
        val showSpeed = scrolling ||
            (macro == null && (!service.remoteMode || Prefs.clickAction(service) != Prefs.ACT_PAGE))
        speedViews.forEach { it.visibility = if (showSpeed) View.VISIBLE else View.GONE }
        status?.minimumWidth = dp(if (showSpeed) 76 else 0)
        scrollButtons.forEach { it.visibility = if (macro == null) View.VISIBLE else View.GONE }
        recordButton?.visibility = if (service.remoteMode && !scrolling) View.VISIBLE else View.GONE
        if (macro != null) {
            // Macro mode: the step the next click runs.
            penBadge?.visibility = View.VISIBLE
            (value.parent as View).visibility = View.VISIBLE
            statusIcon?.setImageResource(R.drawable.ic_touch)
            statusIcon?.imageTintList = ColorStateList.valueOf(ACCENT)
            value.text = service.getString(R.string.bar_progress, macro.first, macro.second)
            unit?.setText(
                when {
                    service.macroPlaying -> R.string.bar_playing
                    Prefs.macroAutoPlay(service) && macro.first > 1 -> R.string.bar_paused
                    else -> R.string.bar_step
                },
            )
            return
        }
        penBadge?.visibility = if (service.remoteMode) View.VISIBLE else View.GONE
        statusIcon?.apply {
            setImageResource(
                // Pen remote shows the direction the next click goes in, dimmed while idle.
                when (service.direction ?: service.lastDirection.takeIf { service.remoteMode }) {
                    PenScrollService.Direction.DOWN -> R.drawable.ic_arrow_down
                    PenScrollService.Direction.UP -> R.drawable.ic_arrow_up
                    null -> R.drawable.ic_pause
                },
            )
            imageTintList = ColorStateList.valueOf(if (scrolling) ACCENT else MUTED)
        }
        // Shown so that a bigger number always means faster, matching + and −.
        if (service.currentStyle() == Prefs.CONTINUOUS) {
            value.text = String.format(Locale.getDefault(), "%d", Prefs.speed(service))
            unit?.setText(R.string.bar_px_s)
        } else {
            value.text = String.format(Locale.getDefault(), "%.1f", 60f / Prefs.pageSeconds(service))
            unit?.setText(R.string.bar_pages_min)
        }
    }

    fun hide() {
        root?.let { wm.removeView(it) }
        root = null
        penBadge = null
        statusIcon = null
        value = null
        unit = null
        status = null
        speedViews = emptyList()
        scrollButtons = emptyList()
        recordButton = null
    }

    private fun statusBlock() = LinearLayout(service).apply {
        status = this
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumWidth = dp(76)
        setPadding(dp(4), 0, dp(4), 0)

        penBadge = ImageView(service).apply {
            setImageResource(R.drawable.ic_pen)
            imageTintList = ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(3) }
        }
        addView(penBadge)
        statusIcon = ImageView(service).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(4) }
        }
        addView(statusIcon)
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            value = TextView(service).apply {
                setTextColor(TEXT)
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false
            }
            addView(value)
            unit = TextView(service).apply {
                setTextColor(MUTED)
                textSize = 10f
                includeFontPadding = false
            }
            addView(unit)
        })
    }

    private fun iconButton(icon: Int, label: String, fill: Int? = null, onClick: () -> Unit) =
        ImageView(service).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(TEXT)
            contentDescription = label
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val size = if (fill != null) 36 else 40
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                if (fill != null) marginStart = dp(4)
            }
            val content = fill?.let { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(it) } }
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), content, ShapeDrawable(OvalShape()))
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).roundToInt()

    /** Drags the pill by the finger that grabbed the handle, ignoring any other pointer. */
    private inner class DragListener : View.OnTouchListener {
        private var pointerId = MotionEvent.INVALID_POINTER_ID
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerId = e.getPointerId(0)
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = e.findPointerIndex(pointerId)
                    if (index < 0) return true
                    params.x = startX + (e.getRawX(index) - downX).roundToInt()
                    params.y = startY + (e.getRawY(index) - downY).roundToInt()
                    root?.let { wm.updateViewLayout(it, params) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pointerId = MotionEvent.INVALID_POINTER_ID
                    snapOutOfGestureBand()
                }
            }
            return true
        }
    }

    /** Keeps the pill out of the middle band where scroll gestures are injected. */
    private fun snapOutOfGestureBand() {
        val view = root ?: return
        val screen = service.resources.displayMetrics.heightPixels
        val bandTop = (screen * GESTURE_BAND_TOP).toInt()
        val bandBottom = (screen * GESTURE_BAND_BOTTOM).toInt()
        val top = params.y
        val bottom = params.y + view.height
        if (bottom <= bandTop || top >= bandBottom) return
        val centre = (top + bottom) / 2
        params.y = if (centre < screen / 2) (bandTop - view.height).coerceAtLeast(0) else bandBottom
        wm.updateViewLayout(view, params)
    }

    private companion object {
        const val TOUCH_WINDOW_MS = 600L
        // Matches the 20–80% band the page swipes use (continuous scrolling uses 25–75%).
        const val GESTURE_BAND_TOP = 0.2f
        const val GESTURE_BAND_BOTTOM = 0.8f
        const val SURFACE = 0xF21C1E24.toInt()
        const val OUTLINE = 0x33FFFFFF
        const val TEXT = 0xFFFFFFFF.toInt()
        const val MUTED = 0xB3FFFFFF.toInt()
        const val ACCENT = 0xFF8AB4FF.toInt()
        const val RIPPLE = 0x40FFFFFF
        const val STOP_RED = 0xFFE5484D.toInt()
    }
}
