package dev.parth.penscroll

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var speedLabel: TextView
    private lateinit var speed: SeekBar
    private lateinit var pageLabel: TextView
    private lateinit var pageSeconds: SeekBar
    private lateinit var toggleRemote: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        speedLabel = findViewById(R.id.speed_label)
        speed = findViewById(R.id.speed)
        pageLabel = findViewById(R.id.page_label)
        pageSeconds = findViewById(R.id.page_seconds)

        setUpTabs()
        bindInfo(R.id.info_app, R.string.info_app_title, R.string.info_app)
        bindInfo(R.id.info_pen, R.string.section_pen, R.string.info_pen)
        bindInfo(R.id.info_clicks, R.string.section_clicks, R.string.info_clicks)
        bindInfo(R.id.info_style, R.string.section_style, R.string.info_style)
        bindInfo(R.id.info_macros, R.string.section_macros, R.string.info_macros)
        bindInfo(R.id.info_setup, R.string.section_setup, R.string.info_setup)

        findViewById<Button>(R.id.open_a11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        speed.max = (Prefs.MAX_SPEED - Prefs.MIN_SPEED) / SPEED_STEP
        speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) Prefs.setSpeed(this@MainActivity, Prefs.MIN_SPEED + progress * SPEED_STEP)
                speedLabel.text = speedLabelText()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        pageSeconds.max = Prefs.MAX_PAGE_SECONDS - Prefs.MIN_PAGE_SECONDS
        pageSeconds.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) Prefs.setPageSeconds(this@MainActivity, Prefs.MIN_PAGE_SECONDS + progress)
                pageLabel.text = pageLabelText()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        bindRadioGroup(
            R.id.long_press,
            mapOf(R.id.long_remote to Prefs.LONG_REMOTE, R.id.long_scroll to Prefs.LONG_SCROLL),
            Prefs.longPress(this),
        ) { Prefs.setLongPress(this, it) }

        val clickActions = listOf(
            Prefs.ACT_START_STOP to R.string.act_start_stop,
            Prefs.ACT_PAGE to R.string.act_page,
            Prefs.ACT_REVERSE to R.string.act_reverse,
            Prefs.ACT_JUMP to R.string.act_jump,
            Prefs.ACT_FASTER to R.string.act_faster,
            Prefs.ACT_SLOWER to R.string.act_slower,
            Prefs.ACT_NONE to R.string.act_none,
        )
        bindSpinner(R.id.click_action, clickActions, Prefs.clickAction(this)) {
            Prefs.setClickAction(this, it)
            PenScrollService.instance?.notifyListeners()
        }
        bindSpinner(R.id.double_click_action, clickActions, Prefs.doubleClickAction(this)) {
            Prefs.setDoubleClickAction(this, it)
        }
        bindSpinner(R.id.triple_click_action, clickActions, Prefs.tripleClickAction(this)) {
            Prefs.setTripleClickAction(this, it)
        }

        toggleRemote = findViewById(R.id.toggle_remote)
        toggleRemote.setOnClickListener {
            val service = PenScrollService.instance ?: return@setOnClickListener
            service.setRemoteMode(!service.remoteMode)
        }

        bindRadioGroup(
            R.id.macro_play,
            mapOf(R.id.macro_step to "step", R.id.macro_auto to "auto"),
            if (Prefs.macroAutoPlay(this)) "auto" else "step",
        ) { Prefs.setMacroAutoPlay(this, it == "auto") }

        bindRadioGroup(
            R.id.style,
            mapOf(
                R.id.style_continuous to Prefs.CONTINUOUS,
                R.id.style_page_swipe to Prefs.PAGE_SWIPE,
                R.id.style_page_tap to Prefs.PAGE_TAP,
            ),
            Prefs.style(this),
        ) {
            Prefs.setStyle(this, it)
            PenScrollService.instance?.notifyListeners()
        }

        bindCheckBox(R.id.opt_toasts, Prefs.toasts(this)) { Prefs.setToasts(this, it) }
        bindCheckBox(R.id.opt_floating, Prefs.floating(this)) {
            Prefs.setFloating(this, it)
            PenScrollService.instance?.notifyListeners()
        }
        bindCheckBox(R.id.opt_app_switch, Prefs.stopOnAppSwitch(this)) { Prefs.setStopOnAppSwitch(this, it) }
    }

    override fun onStart() {
        super.onStart()
        // Pen remote shows a media notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        PenScrollService.stateListener = { runOnUiThread(::refresh) }
        refresh()
    }

    override fun onPause() {
        PenScrollService.stateListener = null
        super.onPause()
    }

    private fun refresh() {
        val service = PenScrollService.instance
        status.setText(
            when {
                service == null -> R.string.status_off
                service.direction == PenScrollService.Direction.DOWN -> R.string.status_down
                service.direction == PenScrollService.Direction.UP -> R.string.status_up
                service.macroMode && service.remoteMode -> R.string.status_macro
                service.remoteMode -> R.string.status_remote
                else -> R.string.status_ready
            },
        )
        val (bg, fg) = when {
            service == null -> R.color.status_warn to R.color.status_warn_text
            service.remoteMode || service.direction != null -> R.color.status_remote to R.color.status_remote_text
            else -> R.color.status_ok to R.color.status_ok_text
        }
        findViewById<View>(R.id.status_card).backgroundTintList = getColorStateList(bg)
        status.setTextColor(getColor(fg))
        toggleRemote.isEnabled = service != null
        toggleRemote.setText(if (service?.remoteMode == true) R.string.remote_turn_off else R.string.remote_turn_on)
        speed.progress = (Prefs.speed(this) - Prefs.MIN_SPEED) / SPEED_STEP
        speedLabel.text = speedLabelText()
        pageSeconds.progress = Prefs.pageSeconds(this) - Prefs.MIN_PAGE_SECONDS
        pageLabel.text = pageLabelText()
        refreshMacros()
    }

    private val tabs = listOf(
        R.id.tab_remote to R.id.page_remote,
        R.id.tab_scroll to R.id.page_scroll,
        R.id.tab_macros to R.id.page_macros,
        R.id.tab_setup to R.id.page_setup,
    )

    private fun setUpTabs() {
        tabs.forEachIndexed { i, (tab, _) -> findViewById<View>(tab).setOnClickListener { showTab(i) } }
        showTab(Prefs.uiTab(this).coerceIn(0, tabs.lastIndex))
    }

    private fun showTab(index: Int) {
        Prefs.setUiTab(this, index)
        tabs.forEachIndexed { i, (tab, page) ->
            val selected = i == index
            findViewById<TextView>(tab).apply {
                setBackgroundResource(if (selected) R.drawable.tab_selected else 0)
                setTextColor(getColor(if (selected) R.color.text_primary else R.color.text_secondary))
            }
            findViewById<View>(page).visibility = if (selected) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.pages_scroll).scrollTo(0, 0)
    }

    private fun bindInfo(id: Int, title: Int, text: Int) {
        findViewById<View>(id).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(withHangingIndents(getString(text)))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Indents the wrapped lines of "• " and "1. " list items under their text. */
    private fun withHangingIndents(text: String): CharSequence {
        val out = SpannableStringBuilder()
        val paint = TextPaint().apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        }
        text.split("\n").forEachIndexed { i, line ->
            if (i > 0) out.append("\n")
            val start = out.length
            out.append(line)
            val marker = LIST_MARKER.find(line)?.value ?: return@forEachIndexed
            out.setSpan(LeadingMarginSpan.Standard(0, paint.measureText(marker).toInt()), start, out.length, 0)
        }
        return out
    }

    private fun refreshMacros() {
        val list = findViewById<LinearLayout>(R.id.macro_list)
        list.removeAllViews()
        val packages = Macros.packages(this)
        if (packages.isEmpty()) {
            list.addView(card().apply {
                addView(TextView(this@MainActivity).apply {
                    setText(R.string.macro_empty_hint)
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                })
            })
        }
        for (pkg in packages) {
            val steps = Macros.get(this, pkg)
            val box = card()
            list.addView(box)
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(this@MainActivity).apply {
                        text = resources.getQuantityString(
                            R.plurals.macro_title, steps.size, Macros.appLabel(this@MainActivity, pkg), steps.size,
                        )
                        textSize = 16f
                        setTextColor(getColor(R.color.text_primary))
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(smallButton(R.string.macro_set_all_waits, R.color.accent) {
                    askSeconds(getString(R.string.macro_set_all_waits), steps.getOrNull(1)?.waitMs()) { ms ->
                        Macros.save(this@MainActivity, pkg, steps.mapIndexed { i, s -> if (i == 0) s else s.copy(gapMs = ms) })
                        refresh()
                    }
                })
                addView(smallButton(R.string.macro_delete, R.color.status_warn_text) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.macro_delete_title, Macros.appLabel(this@MainActivity, pkg)))
                        .setPositiveButton(R.string.macro_delete) { _, _ ->
                            Macros.delete(this@MainActivity, pkg)
                            PenScrollService.instance?.notifyListeners()
                            refresh()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                })
            })
            steps.forEachIndexed { i, step ->
                box.addView(TextView(this).apply {
                    textSize = 13f
                    setTextColor(getColor(R.color.text_primary))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    if (i > 0) setBackgroundResource(android.R.drawable.list_selector_background)
                    // Step 1 runs right on the click, so it has no wait.
                    text = if (i == 0) {
                        getString(R.string.macro_first_step, step.describe())
                    } else {
                        getString(R.string.macro_step_row, i + 1, step.describe(), seconds(step.waitMs()))
                    }
                    if (i > 0) {
                        setOnClickListener {
                            askSeconds(getString(R.string.macro_wait_title, i + 1), step.waitMs()) { ms ->
                                Macros.save(this@MainActivity, pkg, steps.toMutableList().also { it[i] = step.copy(gapMs = ms) })
                                refresh()
                            }
                        }
                    }
                })
            }
            box.addView(TextView(this).apply {
                setText(R.string.macro_wait_hint)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(12), dp(6), 0, 0)
            })
        }
    }

    /** A card like the layout's, for the macro list. */
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card)
        setPadding(dp(18), dp(12), dp(10), dp(14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun smallButton(label: Int, color: Int, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(label)
            setTextColor(getColor(color))
            textSize = 14f
            minWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.1f", ms / 1000f)

    /** Asks for a wait in seconds and passes it back in milliseconds. */
    private fun askSeconds(title: String, currentMs: Long?, onSet: (Long) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(currentMs?.let { seconds(it) } ?: "")
            selectAll()
            hint = "1.5"
        }
        // Inset the field to line up with the dialog's title and message.
        val frame = FrameLayout(this).apply {
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(getString(R.string.macro_wait_message))
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val s = input.text.toString().toFloatOrNull() ?: return@setPositiveButton
                onSet((s * 1000).toLong().coerceIn(MacroStep.MIN_GAP_MS, MacroStep.MAX_GAP_MS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun speedLabelText() = getString(R.string.speed_value, Prefs.speed(this))

    private fun pageLabelText() = getString(R.string.page_interval, Prefs.pageSeconds(this))

    private fun bindSpinner(id: Int, options: List<Pair<String, Int>>, current: String, onChange: (String) -> Unit) {
        findViewById<Spinner>(id).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                R.layout.spinner_item,
                options.map { getString(it.second) },
            ).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }
            setSelection(options.indexOfFirst { it.first == current }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, rowId: Long) =
                    onChange(options[pos].first)

                override fun onNothingSelected(parent: AdapterView<*>) = Unit
            }
        }
    }

    private fun bindRadioGroup(id: Int, options: Map<Int, String>, current: String, onChange: (String) -> Unit) {
        findViewById<RadioGroup>(id).apply {
            check(options.entries.firstOrNull { it.value == current }?.key ?: options.keys.first())
            setOnCheckedChangeListener { _, checked -> onChange(options.getValue(checked)) }
        }
    }

    private fun bindCheckBox(id: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<CheckBox>(id).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
    }

    companion object {
        private const val SPEED_STEP = 10
        private val LIST_MARKER = Regex("""^(•\s|\d+\.\s)""")
    }
}
