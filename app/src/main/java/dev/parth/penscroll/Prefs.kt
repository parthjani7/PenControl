package dev.parth.penscroll

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object Prefs {
    // What the Air Actions long-press does.
    const val LONG_REMOTE = "remote"
    const val LONG_SCROLL = "scroll"

    // Pen remote actions for a click / double-click / triple-click.
    const val ACT_NONE = "none"
    const val ACT_START_STOP = "start_stop"
    const val ACT_PAGE = "page"
    const val ACT_REVERSE = "reverse"
    const val ACT_JUMP = "jump"
    const val ACT_FASTER = "faster"
    const val ACT_SLOWER = "slower"
    private val ACTIONS = setOf(ACT_NONE, ACT_START_STOP, ACT_PAGE, ACT_REVERSE, ACT_JUMP, ACT_FASTER, ACT_SLOWER)

    // Scroll styles.
    const val CONTINUOUS = "continuous"
    const val PAGE_SWIPE = "page_swipe"
    const val PAGE_TAP = "page_tap"
    private val STYLES = setOf(CONTINUOUS, PAGE_SWIPE, PAGE_TAP)

    const val MIN_SPEED = 20
    const val MAX_SPEED = 1500
    private const val DEFAULT_SPEED = 150

    const val MIN_PAGE_SECONDS = 2
    const val MAX_PAGE_SECONDS = 60
    private const val DEFAULT_PAGE_SECONDS = 8

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("penscroll", Context.MODE_PRIVATE)

    /** Reads a string setting, falling back to [default] for missing or retired values. */
    private fun choice(c: Context, key: String, default: String, valid: Set<String>) =
        prefs(c).getString(key, null)?.takeIf { it in valid } ?: default

    /** Scroll speed in pixels per second. */
    fun speed(c: Context) = prefs(c).getInt("speed", DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED)
    fun setSpeed(c: Context, v: Int) = prefs(c).edit { putInt("speed", v.coerceIn(MIN_SPEED, MAX_SPEED)) }

    fun longPress(c: Context) = choice(c, "long_press", LONG_REMOTE, setOf(LONG_REMOTE, LONG_SCROLL))
    fun setLongPress(c: Context, v: String) = prefs(c).edit { putString("long_press", v) }

    fun clickAction(c: Context) = choice(c, "click_action", ACT_START_STOP, ACTIONS)
    fun setClickAction(c: Context, v: String) = prefs(c).edit { putString("click_action", v) }

    fun doubleClickAction(c: Context) = choice(c, "double_click_action", ACT_REVERSE, ACTIONS)
    fun setDoubleClickAction(c: Context, v: String) = prefs(c).edit { putString("double_click_action", v) }

    fun tripleClickAction(c: Context) = choice(c, "triple_click_action", ACT_JUMP, ACTIONS)
    fun setTripleClickAction(c: Context, v: String) = prefs(c).edit { putString("triple_click_action", v) }

    /** Pen remote macro mode: one click plays every step instead of one step per click. */
    fun macroAutoPlay(c: Context) = prefs(c).getBoolean("macro_auto_play", false)
    fun setMacroAutoPlay(c: Context, v: Boolean) = prefs(c).edit { putBoolean("macro_auto_play", v) }

    /** Settings tab last shown. */
    fun uiTab(c: Context) = prefs(c).getInt("ui_tab", 0)
    fun setUiTab(c: Context, v: Int) = prefs(c).edit { putInt("ui_tab", v) }

    fun style(c: Context) = choice(c, "style", CONTINUOUS, STYLES)
    fun setStyle(c: Context, v: String) = prefs(c).edit { putString("style", v) }

    /** Seconds between page turns in the page styles. */
    fun pageSeconds(c: Context) =
        prefs(c).getInt("page_seconds", DEFAULT_PAGE_SECONDS).coerceIn(MIN_PAGE_SECONDS, MAX_PAGE_SECONDS)
    fun setPageSeconds(c: Context, v: Int) =
        prefs(c).edit { putInt("page_seconds", v.coerceIn(MIN_PAGE_SECONDS, MAX_PAGE_SECONDS)) }

    fun toasts(c: Context) = prefs(c).getBoolean("toasts", true)
    fun setToasts(c: Context, v: Boolean) = prefs(c).edit { putBoolean("toasts", v) }

    fun floating(c: Context) = prefs(c).getBoolean("floating", true)
    fun setFloating(c: Context, v: Boolean) = prefs(c).edit { putBoolean("floating", v) }

    fun stopOnAppSwitch(c: Context) = prefs(c).getBoolean("stop_on_app_switch", true)
    fun setStopOnAppSwitch(c: Context, v: Boolean) = prefs(c).edit { putBoolean("stop_on_app_switch", v) }

    /** Drops settings of removed features so they don't linger in the prefs file. */
    fun removeRetired(c: Context) = prefs(c).edit {
        listOf("mode", "last_caller", "invert_tilt", "volume_keys", "hold_action").forEach(::remove)
    }
}
