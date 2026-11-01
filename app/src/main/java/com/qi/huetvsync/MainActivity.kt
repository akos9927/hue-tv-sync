package com.qi.huetvsync

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var errorText: TextView
    private lateinit var bridgeText: TextView
    private lateinit var areaButton: Button
    private lateinit var startButton: Button
    private lateinit var preview: PreviewView
    private lateinit var updateText: TextView
    private lateinit var updateButton: Button

    private var pendingUpdate: Updater.Release? = null
    private var busy = false
    private val settingRefreshers = mutableListOf<() -> Unit>()

    private val colorText by lazy { getColor(R.color.text) }
    private val colorDim by lazy { getColor(R.color.text_dim) }
    private val colorAccent by lazy { getColor(R.color.accent) }
    private val colorError by lazy { getColor(R.color.error) }

    private val ticker = object : Runnable {
        override fun run() {
            refreshLive()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        refreshBridge()
        settingRefreshers.forEach { it() }
        startButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ------------------------------------------------------------------ layout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(size: Float, color: Int = colorText, bold: Boolean = false, value: String = "") =
        TextView(this).apply {
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            text = value
        }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setBackgroundResource(R.drawable.button_bg)
        setTextColor(getColorStateList(R.color.button_text))
        minHeight = dp(40)
        minimumHeight = dp(40)
        minWidth = dp(48)
        minimumWidth = dp(48)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun hRow(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) {
            addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            })
        }
    }

    private fun sectionTitle(text: String) = label(15f, colorAccent, true, text).apply {
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(48), dp(28), dp(48), dp(20))
        }

        // Left column: status, preview, controls
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label(26f, colorText, true, getString(R.string.app_name)))
        left.addView(label(13f, colorDim, value = "Version ${BuildConfig.VERSION_NAME}"))
        statusText = label(18f, colorText, value = "Stopped").apply { setPadding(0, dp(14), 0, 0) }
        left.addView(statusText)
        statsText = label(14f, colorDim)
        left.addView(statsText)
        errorText = label(14f, colorError).apply { visibility = View.GONE }
        left.addView(errorText)

        preview = PreviewView(this)
        left.addView(preview, LinearLayout.LayoutParams(dp(320), dp(180)).apply { topMargin = dp(12) })

        startButton = button("Start syncing") { onStartClicked() }
        val stopButton = button("Stop") { onStopClicked() }
        val testButton = button("Test layout") { onTestLayout() }
        left.addView(hRow(startButton, stopButton, testButton).apply { setPadding(0, dp(14), 0, 0) })

        left.addView(sectionTitle("Hue Bridge"))
        bridgeText = label(15f)
        left.addView(bridgeText)
        left.addView(
            hRow(
                button("Find bridge") { onFindBridge() },
                button("Enter IP") { onEnterIp() },
                button("Pair") { onPair() },
            ).apply { setPadding(0, dp(8), 0, 0) }
        )
        areaButton = button("Choose entertainment area") { onChooseArea() }
        left.addView(hRow(areaButton).apply { setPadding(0, dp(8), 0, 0) })

        left.addView(sectionTitle("Updates"))
        updateText = label(14f, colorDim, value = "Check GitHub for a newer build.")
        left.addView(updateText)
        updateButton = button("Check for update") { onUpdate() }
        left.addView(hRow(updateButton).apply { setPadding(0, dp(8), 0, 0) })

        val leftScroll = ScrollView(this).apply { addView(left) }
        root.addView(leftScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // Right column: settings
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), 0, 0, 0)
        }
        right.addView(sectionTitle("Settings").apply { setPadding(0, dp(4), 0, dp(6)) })
        right.addView(stepper("Capture method", "Codec uses the TV's video chip. Try it if 4K video stutters.",
            { Prefs.CAPTURE_METHOD_NAMES[prefs.captureMethod] },
            { prefs.captureMethod = (prefs.captureMethod + 1) % Prefs.CAPTURE_METHOD_NAMES.size },
            { prefs.captureMethod = (prefs.captureMethod + 1) % Prefs.CAPTURE_METHOD_NAMES.size }))
        right.addView(stepper("Capture rate", "Higher is livelier but more work for the TV.",
            { "${prefs.fps} fps" },
            { prefs.fps = Prefs.FPS_STEPS.lastOrNull { it < prefs.fps } ?: Prefs.FPS_STEPS.first() },
            { prefs.fps = Prefs.FPS_STEPS.firstOrNull { it > prefs.fps } ?: Prefs.FPS_STEPS.last() }))
        right.addView(stepper("Capture quality", "Small is plenty for 7 segments and lightest on 4K.",
            { Prefs.CAPTURE_SIZE_NAMES[prefs.sizeIndex] }, { prefs.sizeIndex -= 1 }, { prefs.sizeIndex += 1 }))
        right.addView(stepper("Smoothing", "Lower reacts faster, higher is calmer.",
            { "${prefs.smoothingMs} ms" }, { prefs.smoothingMs -= 25 }, { prefs.smoothingMs += 25 }))
        right.addView(stepper("Light delay", "Raise it if the lights change before the picture.",
            { "${prefs.delayMs} ms" }, { prefs.delayMs -= 10 }, { prefs.delayMs += 10 }))
        right.addView(stepper("Brightness", null,
            { "${prefs.brightness}%" }, { prefs.brightness -= 10 }, { prefs.brightness += 10 }))
        right.addView(stepper("Saturation", "Above 100% makes colors richer.",
            { "${prefs.saturation}%" }, { prefs.saturation -= 10 }, { prefs.saturation += 10 }))
        right.addView(stepper("Gamma", "Higher gives deeper colors, lower brightens dark scenes.",
            { String.format("%.1f", prefs.gammaTenths / 10f) }, { prefs.gammaTenths -= 1 }, { prefs.gammaTenths += 1 }))
        right.addView(stepper("Black bars", "Ignore letterbox bars so the top lights follow the film.",
            { if (prefs.blackBars) "Ignore" else "Include" }, { prefs.blackBars = !prefs.blackBars }, { prefs.blackBars = !prefs.blackBars }))
        right.addView(stepper("Layout", "How the strip's segments map to the screen.",
            { Prefs.LAYOUT_NAMES[prefs.layoutMode] },
            { prefs.layoutMode = (prefs.layoutMode + Prefs.LAYOUT_NAMES.size - 1) % Prefs.LAYOUT_NAMES.size },
            { prefs.layoutMode = (prefs.layoutMode + 1) % Prefs.LAYOUT_NAMES.size }))
        right.addView(stepper("Mirror left and right", null,
            { if (prefs.mirror) "On" else "Off" }, { prefs.mirror = !prefs.mirror }, { prefs.mirror = !prefs.mirror }))
        right.addView(stepper("Start after the TV restarts", "Only after a full restart, not standby.",
            { if (prefs.startOnBoot) "On" else "Off" }, { prefs.startOnBoot = !prefs.startOnBoot }, { prefs.startOnBoot = !prefs.startOnBoot }))

        val rightScroll = ScrollView(this).apply { addView(right) }
        root.addView(rightScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    private fun stepper(
        title: String,
        hint: String?,
        value: () -> String,
        minus: () -> Unit,
        plus: () -> Unit,
    ): View {
        val valueView = label(16f, colorText, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(150)
        }
        val refresh = { valueView.text = value() }
        settingRefreshers += refresh
        val minusButton = button("−") { minus(); refresh(); applyTuning() }
        val plusButton = button("+") { plus(); refresh(); applyTuning() }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(16f, colorText, value = title))
            if (hint != null) addView(label(12f, colorDim, value = hint))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(minusButton)
            addView(valueView)
            addView(plusButton)
        }
    }

    private fun applyTuning() {
        val t = prefs.tuning()
        SyncState.engine?.updateTuning(t)
        SyncState.capturer?.configure(t.captureMethod, t.sizeIndex, t.fps)
    }

    // ------------------------------------------------------------------ live status

    private fun refreshLive() {
        statusText.text = SyncState.status
        statsText.text = if (SyncState.running) {
            String.format("Screen: %.0f fps  ·  To bridge: %.0f updates/s", SyncState.captureFps, SyncState.sendFps) +
                (if (SyncState.captureInfo.isNotEmpty()) "\n${SyncState.captureInfo}" else "")
        } else ""
        val err = SyncState.error
        errorText.visibility = if (err == null) View.GONE else View.VISIBLE
        errorText.text = err ?: ""
        SyncState.updateStatus?.let {
            updateText.text = it
            SyncState.updateStatus = null
        }
        preview.invalidate()
    }

    private fun refreshBridge() {
        bridgeText.text = when {
            prefs.bridgeIp.isEmpty() -> "Not set up yet: find the bridge or enter its IP."
            !prefs.isPaired -> "Bridge ${prefs.bridgeIp} (not paired yet)"
            else -> "Bridge ${prefs.bridgeIp} · paired"
        }
        areaButton.text = if (prefs.areaName.isEmpty()) "Choose entertainment area" else "Area: ${prefs.areaName}"
    }

    // ------------------------------------------------------------------ actions

    private fun background(work: () -> Unit) {
        if (busy) return
        busy = true
        Thread {
            try {
                work()
            } finally {
                handler.post { busy = false }
            }
        }.start()
    }

    private fun message(text: String) {
        handler.post {
            AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show()
        }
    }

    private fun onStartClicked() {
        if (!prefs.isPaired) return message("Pair with the Hue Bridge first.")
        if (!prefs.isReady) return message("Choose the entertainment area for your TV first.")
        SyncState.error = null
        startActivity(Intent(this, StartCaptureActivity::class.java))
    }

    private fun onStopClicked() {
        startService(Intent(this, SyncService::class.java).setAction(SyncService.ACTION_STOP))
    }

    private fun onTestLayout() {
        val engine = SyncState.engine ?: return message("Start syncing first, then run the test.")
        engine.startLayoutTest()
    }

    private fun onFindBridge() {
        bridgeText.text = "Searching…"
        background {
            try {
                val ips = HueBridge.discover()
                handler.post {
                    when (ips.size) {
                        0 -> {
                            refreshBridge()
                            message("No bridge found. Use Enter IP instead.")
                        }
                        1 -> setBridge(ips[0])
                        else -> AlertDialog.Builder(this)
                            .setTitle("Choose your bridge")
                            .setItems(ips.toTypedArray()) { _, which -> setBridge(ips[which]) }
                            .show()
                    }
                }
            } catch (e: Exception) {
                handler.post { refreshBridge() }
                message(describe(e))
            }
        }
    }

    private fun onEnterIp() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.bridgeIp)
            hint = "192.168.1.20"
        }
        AlertDialog.Builder(this)
            .setTitle("Hue Bridge IP address")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> setBridge(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setBridge(ip: String) {
        val clean = ip.trim()
        if (clean != prefs.bridgeIp) {
            prefs.bridgeIp = clean
            prefs.appKey = ""
            prefs.clientKey = ""
            prefs.areaId = ""
            prefs.areaName = ""
        }
        refreshBridge()
    }

    private fun onPair() {
        if (prefs.bridgeIp.isEmpty()) return message("Find the bridge or enter its IP address first.")
        AlertDialog.Builder(this)
            .setTitle("Pair with the Hue Bridge")
            .setMessage("Press the round button on top of the Hue Bridge, then choose Pair within 30 seconds.")
            .setPositiveButton("Pair") { _, _ ->
                bridgeText.text = "Pairing…"
                background {
                    try {
                        val (appKey, clientKey) = HueBridge.pair(prefs.bridgeIp)
                        prefs.appKey = appKey
                        prefs.clientKey = clientKey
                        handler.post {
                            refreshBridge()
                            onChooseArea()
                        }
                    } catch (e: Exception) {
                        handler.post { refreshBridge() }
                        message(describe(e))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onChooseArea() {
        if (!prefs.isPaired) return message("Pair with the Hue Bridge first.")
        background {
            try {
                val areas = HueBridge(prefs.bridgeIp, prefs.appKey).areas()
                handler.post {
                    if (areas.isEmpty()) {
                        message("The bridge has no entertainment areas. Create one in the Hue app: Settings → Entertainment areas → For my TV.")
                    } else {
                        val names = areas.map { "${it.name}  (${it.channels.size} segments)" }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Entertainment area")
                            .setItems(names) { _, which ->
                                prefs.areaId = areas[which].id
                                prefs.areaName = areas[which].name
                                refreshBridge()
                            }
                            .show()
                    }
                }
            } catch (e: Exception) {
                message(describe(e))
            }
        }
    }

    private fun onUpdate() {
        val pending = pendingUpdate
        if (pending != null) return installUpdate(pending)
        updateText.text = "Checking…"
        background {
            try {
                val latest = Updater.latest()
                handler.post {
                    if (latest != null && latest.build > BuildConfig.VERSION_CODE) {
                        pendingUpdate = latest
                        updateText.text = "Build ${latest.build} is available."
                        updateButton.text = "Install build ${latest.build}"
                    } else {
                        updateText.text = "You have the latest build."
                    }
                }
            } catch (e: Exception) {
                handler.post { updateText.text = "Couldn't check: ${describe(e)}" }
            }
        }
    }

    private fun installUpdate(release: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
                updateText.text = "Allow Hue TV Sync to install apps, then come back and press Install again."
            } catch (e: ActivityNotFoundException) {
                message("Allow Hue TV Sync to install apps: Settings → Apps → Security & restrictions → Unknown sources.")
            }
            return
        }
        background {
            try {
                Updater.install(this, release) { text -> handler.post { updateText.text = text } }
            } catch (e: Exception) {
                handler.post { updateText.text = "Update failed: ${describe(e)}" }
            }
        }
    }
}
