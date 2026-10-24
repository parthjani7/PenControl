package dev.parth.penscroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Path
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlin.math.roundToInt

/**
 * Performs slow continuous scrolling by injecting one long, never-lifted finger drag
 * built from short [StrokeDescription.continueStroke] segments. Each segment reads the
 * current speed, so speed changes and stop requests apply within ~[SEGMENT_MS].
 *
 * When the finger runs out of room it holds still briefly before lifting, so the app sees
 * near-zero release velocity and doesn't fling, then re-grabs at the other end.
 *
 * The page styles instead turn one page every few seconds (a no-fling swipe, or an edge tap
 * for e-readers), and "jump" sends a burst of real flings to reach the top or bottom.
 */
class PenScrollService : AccessibilityService() {

    enum class Direction {
        DOWN, UP;

        fun opposite() = if (this == DOWN) UP else DOWN
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Requested direction; null means stop. */
    var direction: Direction? = null
        private set

    /** Direction "start", page and jump go in. */
    var lastDirection = Direction.DOWN
        private set

    var remoteMode = false
        private set

    /** Style of the current run, fixed from the moment scrolling starts. */
    private var activeStyle = Prefs.CONTINUOUS

    /** Style of the running scroll, or the one the next start will use. */
    fun currentStyle() = if (direction != null) activeStyle else Prefs.style(this)

    private var stroke: StrokeDescription? = null
    private var strokeDirection: Direction? = null
    private var fingerY = 0f
    private var inFlight = false
    private var jumpDirection = Direction.UP
    private var jumpsLeft = 0

    private var foregroundPackage: String? = null
    private var scrollPackage: String? = null
    private var ignoredPackages = emptySet<String>()
    private val activityCache = HashMap<String, Boolean>()

    private var controls: FloatingControls? = null
    private var recorder: MacroRecorder? = null

    /** In pen remote, clicks run the current app's macro instead of scrolling (if it has one). */
    var macroMode = false
        private set

    /** Index of the step the next click runs, per app. */
    private val macroIndex = HashMap<String, Int>()

    /**
     * App whose macro clicks run. It sticks while the macro is mid-way, so steps that open
     * another app (e.g. the camera) keep going there.
     */
    private var macroApp: String? = null

    /** True while auto-play is running through [macroApp]'s steps. */
    var macroPlaying = false
        private set

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stop()
            recorder?.cancel()
            // Hand the pen back so screen-off memos and the lock screen work.
            setRemoteMode(false)
            notifyListeners()
        }
    }

    /** The notification's "Turn off" action. Not exported, so only our own PendingIntent reaches it. */
    private val remoteOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = setRemoteMode(false)
    }

    private var receiversRegistered = false

    override fun onServiceConnected() {
        instance = this
        Prefs.removeRetired(this)
        controls = FloatingControls(this)
        // Windows that pop over the app being scrolled without meaning the user left it.
        ignoredPackages = setOf("android", "com.android.systemui") +
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        ContextCompat.registerReceiver(
            this,
            remoteOff,
            IntentFilter(ACTION_REMOTE_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiversRegistered = true
        notifyListeners()
    }

    /**
     * Pen remote: lets remote (Bluetooth) clicks of the S Pen button control scrolling.
     *
     * Air Actions turns remote clicks into media buttons for the app with the active media
     * notification (this needs Air command's notification access). So while this is on,
     * PenControl holds a media session with a media notification.
     */
    fun setRemoteMode(on: Boolean) {
        if (remoteMode == on) return
        remoteMode = on
        if (on) {
            startMediaSession()
            showRemoteNotification()
        } else {
            stop(announce = false)
            stopMacro()
            macroMode = false
            macroApp = null
            getSystemService(NotificationManager::class.java).cancel(REMOTE_NOTIFICATION_ID)
            stopMediaSession()
        }
        val app = macroApp?.let { Macros.appLabel(this, it) }
        toast(
            when {
                !on -> "✎ Pen remote off"
                macroMode -> "✎ Pen remote on: macro for $app"
                else -> "✎ Pen remote on: click the pen button"
            },
        )
        notifyListeners()
    }

    /**
     * Long-press: off → macro mode (if the app on screen has a macro) → scroll mode → off.
     * Apps without a macro skip macro mode.
     */
    private fun cycleRemote() {
        val app = foregroundPackage?.takeIf { Macros.has(this, it) }
        when {
            !remoteMode -> {
                macroMode = app != null
                if (app != null) {
                    macroApp = app
                    macroIndex[app] = 0
                }
                setRemoteMode(true)
            }
            macroActive() -> {
                macroMode = false
                toast("✎ Pen remote: scroll mode")
                notifyListeners()
            }
            else -> setRemoteMode(false)
        }
    }

    /** True when remote clicks should run [macroApp]'s macro. */
    fun macroActive() = remoteMode && macroMode && Macros.has(this, macroApp)

    /** (next step number, step count) of the current app's macro, or null when not in macro mode. */
    fun macroProgress(): Pair<Int, Int>? {
        val pkg = macroApp
        if (!macroActive() || pkg == null) return null
        val size = Macros.get(this, pkg).size
        if (size == 0) return null
        return (macroIndex[pkg] ?: 0) % size + 1 to size
    }

    /** Single click runs the next step, double click steps back, triple click restarts. */
    private fun onMacroKey(keyCode: Int, now: Long) {
        val pkg = macroApp ?: return
        val steps = Macros.get(this, pkg)
        if (steps.isEmpty()) return
        val index = (macroIndex[pkg] ?: 0) % steps.size
        val triple = keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
            (keyCode != KeyEvent.KEYCODE_MEDIA_NEXT && now - lastDoubleClickAt < TRIPLE_CLICK_MS)
        // A click while auto-playing pauses it; the next click resumes from the same step.
        if (macroPlaying && !triple && keyCode != KeyEvent.KEYCODE_MEDIA_NEXT) {
            stopMacro()
            toast("⏸ Macro paused at step ${(macroIndex[pkg] ?: 0) + 1}/${steps.size}")
            notifyListeners()
            return
        }
        stopMacro()
        when {
            triple -> {
                lastDoubleClickAt = 0
                macroIndex[pkg] = 0
                toast("⟲ Macro: back to step 1")
            }
            keyCode == KeyEvent.KEYCODE_MEDIA_NEXT -> {
                lastDoubleClickAt = now
                val back = (index - 1 + steps.size) % steps.size
                macroIndex[pkg] = back
                toast("◀ Macro: next is step ${back + 1}/${steps.size}")
            }
            Prefs.macroAutoPlay(this) -> {
                macroPlaying = true
                toast(
                    if (index == 0) "▶ Playing macro (${steps.size} steps), click to pause"
                    else "▶ Resuming at step ${index + 1}/${steps.size}",
                )
                playNext(pkg)
            }
            else -> {
                macroIndex[pkg] = (index + 1) % steps.size
                toast("▶ Step ${index + 1}/${steps.size}: ${steps[index].describe()}")
                performStep(steps[index])
            }
        }
        notifyListeners()
    }

    /** Auto-play: performs the next step, then waits the recorded gap before the one after. */
    private fun playNext(pkg: String) {
        if (!macroPlaying) return
        val steps = Macros.get(this, pkg)
        val index = macroIndex[pkg] ?: 0
        if (index >= steps.size) {
            macroPlaying = false
            macroIndex[pkg] = 0
            toast("✓ Macro done")
            notifyListeners()
            return
        }
        performStep(steps[index]) {
            if (!macroPlaying) return@performStep
            macroIndex[pkg] = index + 1
            notifyListeners()
            val gap = steps.getOrNull(index + 1)?.waitMs() ?: 0
            handler.postDelayed({ playNext(pkg) }, MACRO_PLAY, gap)
        }
    }

    private fun stopMacro() {
        macroPlaying = false
        handler.removeCallbacksAndMessages(MACRO_PLAY)
    }

    /** Performs one macro step on the screen; [onDone] runs once it has finished (or failed). */
    fun performStep(step: MacroStep, onDone: () -> Unit = {}) {
        when (step.type) {
            MacroStep.BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed(onDone, STEP_SETTLE_MS)
            }
            MacroStep.HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed(onDone, STEP_SETTLE_MS)
            }
            MacroStep.SWIPE -> performSwipe(step, onDone)
            MacroStep.TAP, MacroStep.LONG_PRESS -> {
                val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                val path = Path().apply { moveTo(step.x * b.width(), step.y * b.height()) }
                val ms = if (step.type == MacroStep.LONG_PRESS) {
                    step.durationMs.coerceIn(MIN_LONG_PRESS_MS, MAX_GESTURE_MS)
                } else {
                    TAP_MS
                }
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(gesture: GestureDescription) = onDone()
                    override fun onCancelled(gesture: GestureDescription) = onDone()
                }
                val gesture = GestureDescription.Builder().addStroke(StrokeDescription(path, 0, ms)).build()
                if (!dispatchGesture(gesture, callback, handler)) onDone()
            }
            // Unknown step type (e.g. from a newer version): skip it.
            else -> handler.post(onDone)
        }
    }

    /**
     * Replays a recorded swipe: rest at the start for [MacroStep.holdMs], follow the recorded
     * path, then rest for [MacroStep.endHoldMs] before lifting, as one continuous touch.
     */
    private fun performSwipe(step: MacroStep, onDone: () -> Unit) {
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val points = step.path.chunked(2).map { (px, py) -> px * w to py * h }
            .ifEmpty { listOf(step.x * w to step.y * h, step.x2 * w to step.y2 * h) }
        val hold = step.holdMs.coerceAtMost(MAX_GESTURE_MS).takeIf { it >= MIN_REST_MS }
        val endHold = step.endHoldMs.coerceAtMost(MAX_GESTURE_MS).takeIf { it >= MIN_REST_MS }
        val strokes = ArrayList<StrokeDescription>()
        var (x, y) = points.first()
        if (hold != null) {
            strokes += StrokeDescription(Path().apply { moveTo(x, y); lineTo(x, y + 1) }, 0, hold, true)
            y += 1
        }
        val move = Path().apply {
            moveTo(x, y)
            points.drop(1).forEach { (px, py) -> lineTo(px, py) }
        }
        val moveMs = step.durationMs.coerceIn(MIN_SWIPE_MS, MAX_GESTURE_MS)
        strokes += strokes.lastOrNull()?.continueStroke(move, 0, moveMs, endHold != null)
            ?: StrokeDescription(move, 0, moveMs, endHold != null)
        if (endHold != null) {
            val (ex, ey) = points.last()
            strokes += strokes.last().continueStroke(Path().apply { moveTo(ex, ey); lineTo(ex, ey + 1) }, 0, endHold, false)
        }
        dispatchChain(strokes, onDone)
    }

    /** Dispatches continued strokes one after another, then calls [onDone]. */
    private fun dispatchChain(strokes: List<StrokeDescription>, onDone: () -> Unit) {
        if (strokes.isEmpty()) return onDone()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription) = dispatchChain(strokes.drop(1), onDone)
            override fun onCancelled(gesture: GestureDescription) = onDone()
        }
        if (!dispatchGesture(GestureDescription.Builder().addStroke(strokes.first()).build(), callback, handler)) onDone()
    }

    /** Records a new macro for the app on screen, replacing its old one when saved. */
    fun startRecording() {
        val pkg = foregroundPackage ?: return toast("Open the app to record a macro for first")
        stop(announce = false)
        stopMacro()
        val label = Macros.appLabel(this, pkg)
        recorder = MacroRecorder(this) { steps ->
            recorder = null
            if (steps != null) {
                Macros.save(this, pkg, steps)
                macroIndex[pkg] = 0
                macroMode = steps.isNotEmpty()
                macroApp = pkg
                toast(if (steps.isEmpty()) "Macro for $label deleted" else "Saved ${steps.size}-step macro for $label")
            } else {
                toast("Recording cancelled")
            }
            notifyListeners()
        }
        notifyListeners()
        recorder?.show()
        toast("● Recording for $label: touch to add steps")
    }

    /**
     * A media notification tied to [mediaSession]. Air Actions sends remote clicks to the app
     * owning the active media notification when the app on screen has no media of its own.
     */
    private fun showRemoteNotification() {
        val session = mediaSession ?: return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(REMOTE_CHANNEL, "Pen remote", NotificationManager.IMPORTANCE_LOW),
        )
        val turnOff = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_REMOTE_OFF).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, REMOTE_CHANNEL)
            .setSmallIcon(R.drawable.ic_pen)
            .setContentTitle("Pen remote is on")
            .setContentText("Click the pen button to control scrolling")
            .setOngoing(true)
            .setShowWhen(false)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .addAction(Notification.Action.Builder(null, "Turn off", turnOff).build())
            .build()
        nm.notify(REMOTE_NOTIFICATION_ID, notification)
    }

    private var mediaSession: MediaSession? = null

    private fun startMediaSession() {
        val session = MediaSession(this, "PenControl")
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                Log.d(TAG, "media button $key")
                if (key?.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) onRemoteKey(key.keyCode)
                return true
            }

            // Some senders use transport controls instead of key events.
            override fun onPlay() = onRemoteKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            override fun onPause() = onRemoteKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            override fun onSkipToNext() = onRemoteKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            override fun onSkipToPrevious() = onRemoteKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }, handler)
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build(),
        )
        session.isActive = true
        mediaSession = session
        playSilence()
    }

    /**
     * Air Actions picks the media notification of the app that last played audio (Android's
     * media-button target), so play half a second of silence to become that app.
     */
    private fun playSilence() {
        releaseSilence()
        val rate = 44100
        val samples = ShortArray(rate / 2)
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            silence = track
            track.write(samples, 0, samples.size)
            track.play()
            handler.postDelayed(::releaseSilence, SILENCE, 1000)
        } catch (e: Exception) {
            // Best effort: clicks still reach PenControl when no other app holds media focus.
            Log.w(TAG, "Couldn't play silence", e)
            releaseSilence()
        }
    }

    private var silence: AudioTrack? = null

    private fun releaseSilence() {
        handler.removeCallbacksAndMessages(SILENCE)
        silence?.release()
        silence = null
    }

    private fun stopMediaSession() {
        releaseSilence()
        mediaSession?.release()
        mediaSession = null
    }

    private var lastDoubleClickAt = 0L

    /** [lastDirection] before the last double-click ran, so a triple-click can undo a reverse. */
    private var directionBeforeDouble = Direction.DOWN

    /**
     * Air Actions tells single and double presses apart (play/pause and next) but has no
     * triple press: it arrives as a double press followed by a single one, so a single press
     * shortly after a double press counts as a triple-click.
     */
    private fun onRemoteKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        Log.d(TAG, "remote key ${KeyEvent.keyCodeToString(keyCode)} ${now - lastDoubleClickAt} ms after double")
        if (recorder != null) return
        if (macroActive()) return onMacroKey(keyCode, now)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                directionBeforeDouble = lastDirection
                lastDoubleClickAt = now
                runPenAction(Prefs.doubleClickAction(this))
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> runPenAction(Prefs.tripleClickAction(this))
            else -> if (now - lastDoubleClickAt < TRIPLE_CLICK_MS) {
                lastDoubleClickAt = 0
                runPenAction(Prefs.tripleClickAction(this), directionBeforeDouble)
            } else {
                runPenAction(Prefs.clickAction(this))
            }
        }
    }

    private fun runPenAction(action: String, dir: Direction = lastDirection) {
        when (action) {
            Prefs.ACT_START_STOP -> if (direction == null) start(dir) else stop()
            Prefs.ACT_PAGE -> pageOnce(dir)
            Prefs.ACT_REVERSE -> reverse()
            Prefs.ACT_JUMP -> jump(dir)
            Prefs.ACT_FASTER -> adjust(faster = true)
            Prefs.ACT_SLOWER -> adjust(faster = false)
        }
    }

    /** While idle, only changes the direction the next start, page or jump goes in. */
    private fun setDirection(dir: Direction) {
        lastDirection = dir
        toast(if (dir == Direction.DOWN) "▼ Direction: down" else "▲ Direction: up")
        notifyListeners()
    }

    /** Turns one page in [dir], stopping any running scroll first. */
    private fun pageOnce(dir: Direction) {
        val wasScrolling = direction != null
        stop(announce = false)
        lastDirection = dir
        notifyListeners()
        // A stopped continuous scroll still has to lift its finger.
        handler.postDelayed(
            { turnPage(dir, Prefs.style(this) == Prefs.PAGE_TAP) },
            PENDING_START,
            if (wasScrolling || inFlight) LIFT_WAIT_MS else 0,
        )
    }

    override fun onDestroy() {
        recorder?.cancel()
        setRemoteMode(false)
        stop()
        handler.removeCallbacksAndMessages(null)
        controls?.hide()
        controls = null
        if (receiversRegistered) {
            unregisterReceiver(screenOff)
            unregisterReceiver(remoteOff)
            receiversRegistered = false
        }
        instance = null
        notifyListeners()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ignoredPackages || pkg.startsWith("com.samsung.android.service.")) return
        // Only full app screens count; overlays like the Samsung Pay handle are plain views.
        if (!isActivity(pkg, event.className?.toString())) return
        val appChanged = pkg != foregroundPackage
        foregroundPackage = pkg
        // Between runs (back at step 1), a different app with its own macro takes over.
        val current = macroApp
        if (macroMode && pkg != current && Macros.has(this, pkg) && (current == null || (macroIndex[current] ?: 0) == 0)) {
            macroApp = pkg
            macroIndex[pkg] = 0
        }
        // The floating bar shows the macro of the app on screen.
        if (appChanged && remoteMode) notifyListeners()
        val owner = scrollPackage
        if (direction != null && owner == null) {
            // Started before we knew the foreground app (e.g. right after the service restarted).
            scrollPackage = pkg
            return
        }
        if (direction != null && owner != null && pkg != owner && Prefs.stopOnAppSwitch(this)) {
            Log.d(TAG, "app switch $owner -> $pkg (${event.className})")
            stop()
        }
    }

    private fun isActivity(pkg: String, cls: String?): Boolean {
        if (cls == null) return false
        return activityCache.getOrPut("$pkg/$cls") {
            try {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    override fun onInterrupt() = stop()

    /** Speeds up / slows down the current style (px/s when continuous, interval when paged). */
    fun adjust(faster: Boolean) {
        val msg = if (currentStyle() == Prefs.CONTINUOUS) {
            Prefs.setSpeed(this, (Prefs.speed(this) * if (faster) 1.25f else 0.8f).roundToInt())
            "Speed: ${Prefs.speed(this)} px/s"
        } else {
            Prefs.setPageSeconds(this, Prefs.pageSeconds(this) + if (faster) -1 else 1)
            "Page every ${Prefs.pageSeconds(this)} s (${"%.1f".format(60f / Prefs.pageSeconds(this))}/min)"
        }
        Log.d(TAG, "adjust faster=$faster -> $msg")
        // The floating controls already show the value.
        if (controls?.isShowing != true) toast(msg)
        notifyListeners()
    }

    fun reverse() {
        val dir = direction ?: return setDirection(lastDirection.opposite())
        start(dir.opposite())
    }

    /** The Air Actions long-press: cycles pen remote, or starts / stops scrolling, after [delayMs]. */
    fun onLongPress(delayMs: Long = 0) {
        handler.postDelayed(
            { if (Prefs.longPress(this) == Prefs.LONG_REMOTE) cycleRemote() else toggleScroll() },
            PENDING_START,
            delayMs,
        )
    }

    /** Starts scrolling in [lastDirection] after [delayMs], or stops right away if scrolling. */
    fun toggleScroll(delayMs: Long = 0) {
        if (direction != null) return stop()
        handler.postDelayed({ start(lastDirection) }, PENDING_START, delayMs)
    }

    /** Starts or redirects scrolling in the saved style. */
    private fun start(dir: Direction) {
        if (direction == dir) return
        if (direction == null) {
            scrollPackage = foregroundPackage
            activeStyle = Prefs.style(this)
            jumpsLeft = 0
        }
        direction = dir
        lastDirection = dir
        toast(if (dir == Direction.DOWN) "▼ Scrolling down" else "▲ Scrolling up")
        notifyListeners()
        if (activeStyle == Prefs.CONTINUOUS) step() else schedulePage()
    }

    fun stop(announce: Boolean = true) {
        handler.removeCallbacksAndMessages(PENDING_START)
        handler.removeCallbacksAndMessages(PAGE_TICK)
        jumpsLeft = 0
        if (direction == null) return
        direction = null
        scrollPackage = null
        if (announce) toast("■ Stopped")
        notifyListeners()
        // An in-flight segment's completion callback will lift the finger.
    }

    fun jump(dir: Direction) {
        stop(announce = false)
        // At the top the only way is down (and vice versa), so point the next click there.
        lastDirection = dir.opposite()
        jumpDirection = dir
        jumpsLeft = JUMP_FLINGS
        toast(if (dir == Direction.UP) "⇈ Jumping to top" else "⇊ Jumping to bottom")
        // Lifts a held finger first if needed; flinging starts once idle.
        step()
    }

    private fun step() {
        if (inFlight) return
        val dir = direction
        val prev = stroke
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val top = metrics.heightPixels * 0.25f
        val bottom = metrics.heightPixels * 0.75f

        if (prev != null && dir != strokeDirection) {
            // Stop requested or direction changed: lift, then continue (or not) from scratch.
            lift(prev, x) { step() }
            return
        }
        if (dir == null || activeStyle != Prefs.CONTINUOUS) {
            onIdle()
            return
        }

        if (prev == null) fingerY = if (dir == Direction.DOWN) bottom else top
        val dy = Prefs.speed(this) * SEGMENT_MS / 1000f
        // Scrolling content down means dragging the finger up.
        val nextY = if (dir == Direction.DOWN) fingerY - dy else fingerY + dy
        if (prev != null && (nextY < top || nextY > bottom)) {
            lift(prev, x) { step() }
            return
        }

        val path = Path().apply {
            moveTo(x, fingerY)
            lineTo(x, nextY)
        }
        val next = prev?.continueStroke(path, 0, SEGMENT_MS, true)
            ?: StrokeDescription(path, 0, SEGMENT_MS, true)
        stroke = next
        strokeDirection = dir
        fingerY = nextY
        dispatch(next) { step() }
    }

    private fun onIdle() {
        if (direction == null && jumpsLeft > 0) fling()
    }

    private fun fling() {
        if (inFlight) return
        jumpsLeft--
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val top = metrics.heightPixels * 0.25f
        val bottom = metrics.heightPixels * 0.8f
        val path = Path().apply {
            // Reaching the top means flinging the finger downwards.
            if (jumpDirection == Direction.UP) moveTo(x, top) else moveTo(x, bottom)
            if (jumpDirection == Direction.UP) lineTo(x, bottom) else lineTo(x, top)
        }
        dispatch(StrokeDescription(path, 0, FLING_MS)) {
            if (jumpsLeft > 0) handler.postDelayed({ onIdle() }, PENDING_START, FLING_GAP_MS)
        }
    }

    private fun schedulePage() {
        handler.removeCallbacksAndMessages(PAGE_TICK)
        handler.postDelayed({ pageTurn() }, PAGE_TICK, Prefs.pageSeconds(this) * 1000L)
    }

    private fun pageTurn() {
        val dir = direction ?: return
        schedulePage()
        turnPage(dir, activeStyle == Prefs.PAGE_TAP)
    }

    private fun turnPage(dir: Direction, tap: Boolean) {
        if (inFlight) return
        val metrics = resources.displayMetrics
        if (tap) {
            // E-readers: right edge = next page, left edge = previous page.
            val x = metrics.widthPixels * if (dir == Direction.DOWN) 0.85f else 0.15f
            val path = Path().apply { moveTo(x, metrics.heightPixels * 0.5f) }
            dispatch(StrokeDescription(path, 0, TAP_MS)) {}
        } else {
            val x = metrics.widthPixels / 2f
            val top = metrics.heightPixels * 0.2f
            val bottom = metrics.heightPixels * 0.8f
            val from = if (dir == Direction.DOWN) bottom else top
            val to = if (dir == Direction.DOWN) top else bottom
            val path = Path().apply {
                moveTo(x, from)
                lineTo(x, to)
            }
            val swipe = StrokeDescription(path, 0, PAGE_SWIPE_MS, true)
            stroke = swipe
            strokeDirection = null
            fingerY = to
            dispatch(swipe) { lift(swipe, x) {} }
        }
    }

    /** Holds the finger (almost) still before lifting so the release doesn't fling. */
    private fun lift(prev: StrokeDescription, x: Float, then: () -> Unit) {
        val path = Path().apply {
            moveTo(x, fingerY)
            lineTo(x, fingerY + 1)
        }
        val hold = prev.continueStroke(path, 0, HOLD_MS, false)
        stroke = null
        strokeDirection = null
        dispatch(hold, then)
    }

    private fun dispatch(s: StrokeDescription, onDone: () -> Unit) {
        inFlight = true
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription) {
                inFlight = false
                onDone()
            }

            override fun onCancelled(gesture: GestureDescription) {
                inFlight = false
                stroke = null
                strokeDirection = null
                handler.postDelayed(::afterCancel, CANCEL_GRACE_MS)
            }
        }
        if (!dispatchGesture(GestureDescription.Builder().addStroke(s).build(), callback, handler)) {
            inFlight = false
            stroke = null
            strokeDirection = null
            stop()
        }
    }

    /**
     * A real touch cancels our gesture. If it landed on the floating controls, carry on;
     * otherwise the user grabbed the screen, which means "stop".
     */
    private fun afterCancel() {
        Log.d(TAG, "gesture cancelled, controls touched=${controls?.touchedRecently()}")
        if (controls?.touchedRecently() == true) {
            if (activeStyle == Prefs.CONTINUOUS) step()
        } else {
            stop()
        }
    }

    private fun toast(msg: String) {
        Log.d(TAG, msg)
        if (Prefs.toasts(this)) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    fun notifyListeners() {
        controls?.let {
            val screenOn = getSystemService(PowerManager::class.java).isInteractive
            val wanted = direction != null || remoteMode
            if (screenOn && recorder == null && Prefs.floating(this) && wanted) it.show() else it.hide()
            it.update()
        }
        TileService.requestListeningState(this, ComponentName(this, ScrollTileService::class.java))
        stateListener?.invoke()
    }

    companion object {
        private const val SEGMENT_MS = 100L
        private const val HOLD_MS = 120L
        private const val PAGE_SWIPE_MS = 350L
        private const val TAP_MS = 50L
        private const val FLING_MS = 120L
        private const val FLING_GAP_MS = 60L
        private const val JUMP_FLINGS = 10
        private const val TAG = "PenControl"
        private const val CANCEL_GRACE_MS = 150L
        private const val TRIPLE_CLICK_MS = 1200L
        private const val LIFT_WAIT_MS = 300L
        private const val STEP_SETTLE_MS = 300L
        private const val MIN_LONG_PRESS_MS = 600L
        private const val MIN_SWIPE_MS = 80L
        private const val MAX_GESTURE_MS = 3000L
        private const val MIN_REST_MS = 40L
        private val PENDING_START = Any()
        private val PAGE_TICK = Any()
        private val MACRO_PLAY = Any()
        private val SILENCE = Any()
        private const val REMOTE_CHANNEL = "remote"
        private const val REMOTE_NOTIFICATION_ID = 1
        private const val ACTION_REMOTE_OFF = "dev.parth.penscroll.action.REMOTE_OFF"

        var instance: PenScrollService? = null
            private set

        /** Set by the visible settings screen to refresh itself. */
        var stateListener: (() -> Unit)? = null
    }
}
