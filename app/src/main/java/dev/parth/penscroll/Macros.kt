package dev.parth.penscroll

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * One macro step. Positions are fractions of the full screen (0..1), so a macro survives
 * small resolution changes; [durationMs] is how long the touch lasted when recorded (for a
 * swipe, how long the finger was moving).
 *
 * Swipes also keep the finger's [path] (x, y pairs) and how long it rested before moving
 * ([holdMs]) and before lifting ([endHoldMs]): sliders and drag handles often ignore a
 * swipe that starts moving the instant it touches down.
 *
 * [gapMs] is how long after the previous step finished this one started while recording;
 * auto-play waits that long so the app (or a camera it opened) has time to catch up.
 */
data class MacroStep(
    val type: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val durationMs: Long = 0,
    val path: List<Float> = emptyList(),
    val holdMs: Long = 0,
    val endHoldMs: Long = 0,
    val gapMs: Long = 0,
) {
    /** How long auto-play waits before this step. Macros recorded before gaps were saved get a second. */
    fun waitMs(): Long = if (gapMs <= 0) DEFAULT_GAP_MS else gapMs.coerceIn(MIN_GAP_MS, MAX_GAP_MS)

    fun describe(): String = when (type) {
        TAP -> "Tap at ${pct(x)}, ${pct(y)}"
        LONG_PRESS -> "Long-press at ${pct(x)}, ${pct(y)}"
        SWIPE -> "Swipe ${pct(x)}, ${pct(y)} → ${pct(x2)}, ${pct(y2)}"
        BACK -> "Back"
        HOME -> "Home"
        else -> type
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("x", x.toDouble()).put("y", y.toDouble())
        .put("x2", x2.toDouble()).put("y2", y2.toDouble()).put("ms", durationMs)
        .put("path", JSONArray(path.map { it.toDouble() })).put("hold", holdMs).put("end_hold", endHoldMs)
        .put("gap", gapMs)

    companion object {
        const val TAP = "tap"
        const val LONG_PRESS = "long_press"
        const val SWIPE = "swipe"
        const val BACK = "back"
        const val HOME = "home"

        const val DEFAULT_GAP_MS = 1000L
        const val MIN_GAP_MS = 300L
        const val MAX_GAP_MS = 60_000L

        /** Recorded gaps include thinking time, so they're capped lower than hand-set ones. */
        const val MAX_RECORDED_GAP_MS = 10_000L

        private fun pct(v: Float) = String.format(Locale.US, "%.0f%%", v * 100)

        fun fromJson(o: JSONObject) = MacroStep(
            o.getString("type"),
            o.fraction("x"),
            o.fraction("y"),
            o.fraction("x2"),
            o.fraction("y2"),
            o.optLong("ms"),
            o.optJSONArray("path")?.let { a -> List(a.length()) { a.optDouble(it, 0.0).toFloat().coerceIn(0f, 1f) } }
                ?.takeIf { it.size % 2 == 0 } ?: emptyList(),
            o.optLong("hold"),
            o.optLong("end_hold"),
            o.optLong("gap"),
        )

        private fun JSONObject.fraction(key: String) = optDouble(key, 0.0).toFloat().coerceIn(0f, 1f)
    }
}

/** Per-app macros, stored as JSON keyed by package name. */
object Macros {
    private fun prefs(c: Context) = c.getSharedPreferences("macros", Context.MODE_PRIVATE)

    fun get(c: Context, pkg: String?): List<MacroStep> {
        val json = pkg?.let { prefs(c).getString(it, null) } ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { MacroStep.fromJson(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            Log.w("PenScroll", "Unreadable macro for $pkg", e)
            emptyList()
        }
    }

    fun has(c: Context, pkg: String?) = pkg != null && prefs(c).contains(pkg)

    fun save(c: Context, pkg: String, steps: List<MacroStep>) = prefs(c).edit {
        if (steps.isEmpty()) remove(pkg) else putString(pkg, JSONArray(steps.map { it.toJson() }).toString())
    }

    fun delete(c: Context, pkg: String) = prefs(c).edit { remove(pkg) }

    fun packages(c: Context): List<String> = prefs(c).all.keys.sorted()

    fun appLabel(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }
}
