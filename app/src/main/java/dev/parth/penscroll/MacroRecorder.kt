package dev.parth.penscroll

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Full-screen overlay that records a macro. Each touch becomes a tap, long-press or
 * swipe step and is also performed on the app underneath (the overlay briefly lets touches
 * through), so you can move through the app's screens while recording.
 */
class MacroRecorder(
    private val service: PenScrollService,
    private val onFinish: (steps: List<MacroStep>?) -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val steps = ArrayList<MacroStep>()
    private val surface = Surface()
    private val root = FrameLayout(service)
    private val toolbar = LinearLayout(service)
    private val count = TextView(service)
    private var toolbarAtTop = false

    /** True while a recorded step is being performed on the app; its injected touch isn't a new step. */
    private var performing = false

    /** When the last recorded step finished playing on the app. */
    private var lastStepDoneAt = 0L

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    fun show() {
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        buildToolbar()
        root.addView(toolbar, toolbarParams())
        wm.addView(root, params)
        updateCount()
    }

    fun cancel() = finish(null)

    private var finished = false

    private fun finish(result: List<MacroStep>?) {
        if (finished) return
        finished = true
        if (root.parent != null) wm.removeView(root)
        onFinish(result)
    }

    private fun buildToolbar() {
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(SURFACE)
            setStroke(dp(1), OUTLINE)
        }
        toolbar.setPadding(dp(8), dp(4), dp(8), dp(4))
        count.setTextColor(REC_RED)
        count.textSize = 13f
        count.typeface = Typeface.DEFAULT_BOLD
        count.setPadding(dp(6), 0, dp(6), 0)
        toolbar.addView(count)
        toolbar.addView(button("Back") { record(MacroStep(MacroStep.BACK), SystemClock.uptimeMillis()) })
        toolbar.addView(button("Home") { record(MacroStep(MacroStep.HOME), SystemClock.uptimeMillis()) })
        toolbar.addView(button("Undo") {
            if (steps.isNotEmpty()) steps.removeAt(steps.lastIndex)
            updateCount()
        })
        toolbar.addView(button("⇅") {
            toolbarAtTop = !toolbarAtTop
            toolbar.layoutParams = toolbarParams()
        })
        toolbar.addView(button("✕") { finish(null) })
        toolbar.addView(button("Save", ACCENT) { finish(steps.toList()) })
    }

    private fun toolbarParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER_HORIZONTAL or if (toolbarAtTop) Gravity.TOP else Gravity.BOTTOM,
    ).apply { setMargins(dp(8), dp(48), dp(8), dp(48)) }

    private fun button(label: String, color: Int = TEXT, onClick: () -> Unit) = TextView(service).apply {
        text = label
        setTextColor(color)
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { onClick() }
    }

    private fun updateCount() {
        count.text = service.getString(R.string.rec_count, steps.size)
        surface.invalidate()
    }

    /** Adds [step] and performs it on the app, letting touches through while it runs. */
    private fun record(step: MacroStep, startedAt: Long) {
        val gap = if (steps.isEmpty()) 0 else (startedAt - lastStepDoneAt).coerceIn(MacroStep.MIN_GAP_MS, MacroStep.MAX_RECORDED_GAP_MS)
        steps += step.copy(gapMs = gap)
        updateCount()
        performing = true
        setPassThrough(true)
        // Let the window manager apply pass-through first, or the injected touch lands on this overlay.
        surface.postDelayed({
            service.performStep(step) {
                surface.postDelayed({
                    setPassThrough(false)
                    performing = false
                    lastStepDoneAt = SystemClock.uptimeMillis()
                }, SETTLE_MS)
            }
        }, PASS_THROUGH_DELAY_MS)
    }

    private fun setPassThrough(on: Boolean) {
        if (root.parent == null) return
        params.flags = if (on) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        wm.updateViewLayout(root, params)
    }

    private fun screen() = wm.currentWindowMetrics.bounds

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).roundToInt()

    /** Captures touches, shows a trail while touching and numbered markers for recorded steps. */
    private inner class Surface : View(service) {
        private var downAt = 0L
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var touching = false

        /** Finger samples of the current touch: raw x, raw y, uptime. */
        private val samples = ArrayList<Triple<Float, Float, Long>>()
        private val loc = IntArray(2)

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARKER }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MARKER
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MARKER
            strokeWidth = dp(4).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = dp(13).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        init {
            setBackgroundColor(SCRIM)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (performing) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    downX = e.rawX
                    downY = e.rawY
                    lastX = downX
                    lastY = downY
                    touching = true
                    samples.clear()
                    samples += Triple(downX, downY, downAt)
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    samples += Triple(lastX, lastY, SystemClock.uptimeMillis())
                }
                MotionEvent.ACTION_UP -> {
                    touching = false
                    val held = SystemClock.uptimeMillis() - downAt
                    val b = screen()
                    val w = b.width().toFloat()
                    val h = b.height().toFloat()
                    val moved = hypot(e.rawX - downX, e.rawY - downY) > dp(SWIPE_SLOP_DP)
                    record(
                        when {
                            moved -> {
                                samples += Triple(e.rawX, e.rawY, SystemClock.uptimeMillis())
                                swipe(w, h)
                            }
                            held >= LONG_PRESS_MS -> MacroStep(MacroStep.LONG_PRESS, downX / w, downY / h, durationMs = held)
                            else -> MacroStep(MacroStep.TAP, downX / w, downY / h)
                        },
                        downAt,
                    )
                }
                MotionEvent.ACTION_CANCEL -> touching = false
            }
            invalidate()
            return true
        }

        /** Splits the touch into resting / moving / resting phases and keeps the moving path. */
        private fun swipe(w: Float, h: Float): MacroStep {
            val slop = dp(STILL_SLOP_DP)
            val first = samples.first()
            val last = samples.last()
            // Last sample still at the touch-down point, and first sample already at the lift point.
            val start = samples.indexOfFirst { hypot(it.first - first.first, it.second - first.second) > slop }
                .let { (it - 1).coerceAtLeast(0) }
            val end = samples.indexOfLast { hypot(it.first - last.first, it.second - last.second) > slop }
                .let { (it + 1).coerceIn(start, samples.lastIndex) }
            val moving = samples.subList(start, end + 1)
            // Keep at most ~40 points; the gesture API spreads time evenly along the path anyway.
            val stride = (moving.size + MAX_PATH_POINTS - 1) / MAX_PATH_POINTS
            val points = moving.filterIndexed { i, _ -> i % stride.coerceAtLeast(1) == 0 || i == moving.lastIndex }
            return MacroStep(
                MacroStep.SWIPE,
                first.first / w, first.second / h, last.first / w, last.second / h,
                durationMs = moving.last().third - moving.first().third,
                path = points.flatMap { listOf(it.first / w, it.second / h) },
                holdMs = moving.first().third - first.third,
                endHoldMs = last.third - moving.last().third,
            )
        }

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(loc)
            val b = screen()
            val r = dp(14).toFloat()
            steps.forEachIndexed { i, s ->
                val x = s.x * b.width() - loc[0]
                val y = s.y * b.height() - loc[1]
                when (s.type) {
                    MacroStep.TAP -> canvas.drawCircle(x, y, r, fill)
                    MacroStep.LONG_PRESS -> {
                        canvas.drawCircle(x, y, r, fill)
                        canvas.drawCircle(x, y, r + dp(5), ring)
                    }
                    MacroStep.SWIPE -> {
                        canvas.drawLine(x, y, s.x2 * b.width() - loc[0], s.y2 * b.height() - loc[1], line)
                        canvas.drawCircle(x, y, r, fill)
                    }
                    else -> return@forEachIndexed
                }
                canvas.drawText("${i + 1}", x, y - (label.ascent() + label.descent()) / 2, label)
            }
            if (touching) {
                canvas.drawLine(downX - loc[0], downY - loc[1], lastX - loc[0], lastY - loc[1], line)
                canvas.drawCircle(downX - loc[0], downY - loc[1], r, ring)
            }
        }
    }

    private companion object {
        const val LONG_PRESS_MS = 450L
        const val SWIPE_SLOP_DP = 24
        const val SETTLE_MS = 350L
        const val PASS_THROUGH_DELAY_MS = 150L
        const val STILL_SLOP_DP = 6
        const val MAX_PATH_POINTS = 40
        const val SCRIM = 0x22FF3B30
        const val SURFACE = 0xF21C1E24.toInt()
        const val OUTLINE = 0x33FFFFFF
        const val TEXT = 0xFFFFFFFF.toInt()
        const val ACCENT = 0xFF8AB4FF.toInt()
        const val REC_RED = 0xFFFF5A52.toInt()
        const val MARKER = 0xCCFF3B30.toInt()
    }
}
