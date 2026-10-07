package com.adwaithvarma.heyron

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

/**
 * Plain-View control panel: status, start/stop, detection-threshold slider,
 * and the v2.4 diagnostics checklist — every row shows live status plus a
 * one-tap fix, so a failed wake explains itself instead of looking broken.
 *
 * Rows (all reads permission-free):
 *  1. Default assistant — from Settings.Secure "assistant" key
 *  2. Display over other apps — instant, DND-proof direct launch
 *  3. Battery — Unrestricted + Samsung never-sleeping guidance
 *  4. Notifications — allowed? wake channel still HIGH?
 *  5. Do Not Disturb — informational (direct launch is DND-proof anyway)
 *  6. Last wake log — timestamp, path taken, what defeated the rest
 *  7. Engine state — listening / paused-for-call / degraded
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var keywordText: TextView
    private lateinit var toggleButton: Button
    private lateinit var checkModel: TextView
    private lateinit var checkAssistant: TextView
    private lateinit var assistantButton: Button
    private lateinit var checkNotif: TextView
    private lateinit var checkOverlay: TextView
    private lateinit var overlayButton: Button
    private lateinit var checkBattery: TextView
    private lateinit var batteryButton: Button
    private lateinit var samsungBatteryNote: TextView
    private lateinit var checkDnd: TextView
    private lateinit var checkLastWake: TextView
    private lateinit var checkEngine: TextView
    private lateinit var thresholdLabel: TextView
    private lateinit var thresholdSeek: SeekBar

    private val neededPermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE
            )
            if (Build.VERSION.SDK_INT >= 33) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        keywordText = findViewById(R.id.keywordText)
        toggleButton = findViewById(R.id.toggleButton)
        checkModel = findViewById(R.id.checkModel)

        checkAssistant = findViewById(R.id.checkAssistant)
        assistantButton = findViewById(R.id.assistantButton)
        assistantButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }

        checkNotif = findViewById(R.id.checkNotif)

        checkOverlay = findViewById(R.id.checkOverlay)
        overlayButton = findViewById(R.id.overlayButton)
        overlayButton.setOnClickListener {
            // SYSTEM_ALERT_WINDOW can't be requested via requestPermissions();
            // it needs the special Settings screen.
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        checkBattery = findViewById(R.id.checkBattery)
        batteryButton = findViewById(R.id.batteryButton)
        batteryButton.setOnClickListener {
            // Pops the system "let app always run in background?" dialog.
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
        samsungBatteryNote = findViewById(R.id.samsungBatteryNote)

        checkDnd = findViewById(R.id.checkDnd)
        checkLastWake = findViewById(R.id.checkLastWake)
        checkEngine = findViewById(R.id.checkEngine)

        thresholdLabel = findViewById(R.id.thresholdLabel)
        thresholdSeek = findViewById(R.id.thresholdSeek)

        // Slider range 0.10 – 0.90, default 0.50 (openWakeWord's recommendation).
        thresholdSeek.max = 80
        thresholdSeek.progress = ((WakeService.getThreshold(this) * 100).toInt() - 10)
            .coerceIn(0, 80)
        thresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                val t = (progress + 10) / 100f
                getSharedPreferences(WakeService.PREFS, MODE_PRIVATE).edit()
                    .putFloat(WakeService.KEY_THRESHOLD, t).apply()
                thresholdLabel.text = "Detection threshold: %.2f".format(t)
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        toggleButton.setOnClickListener {
            if (WakeService.isEnabled(this)) {
                startService(Intent(this, WakeService::class.java).setAction(WakeService.ACTION_STOP))
            } else {
                ensurePermissionsThenStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun ensurePermissionsThenStart() {
        val missing = neededPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1001)
        } else {
            startWakeService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startWakeService()
        } else {
            refresh()
        }
    }

    private fun startWakeService() {
        val intent = Intent(this, WakeService::class.java).setAction(WakeService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Let the service settle, then refresh the status line.
        statusText.postDelayed({ refresh() }, 600)
    }

    private fun refresh() {
        val enabled = WakeService.isEnabled(this)
        val paused = WakeService.isPausedForCall(this)
        val degraded = WakeService.isDegraded(this)
        val retries = WakeService.degradedRetries(this)

        statusText.text = when {
            !enabled -> "Status: Stopped"
            paused -> "Status: Paused (on call)"
            degraded -> "Status: Degraded — mic retry $retries"
            else -> "Status: Listening"
        }
        toggleButton.text = if (enabled) "Stop listening" else "Start listening"

        thresholdLabel.text = "Detection threshold: %.2f".format(WakeService.getThreshold(this))

        val names = try {
            assets.list("")?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
        val hasCustom = names.contains("hey_ron.onnx")
        val hasFallback = names.contains("hey_jarvis_v0.1.onnx")
        checkModel.text = when {
            hasCustom -> "✓ Hey Ron model: hey_ron.onnx found"
            hasFallback -> "• Hey Ron model: not trained yet — listening for \"Hey Jarvis\" (fallback)"
            else -> "✗ No wake-word model in assets — rebuild needed"
        }
        keywordText.text = if (hasCustom) "Keyword: Hey Ron" else "Keyword: Hey Jarvis (fallback)"

        // 1. Default assistant — read straight from the Secure settings key.
        // This row alone would have explained the Google/Perplexity picker.
        val assistant = WakeService.defaultAssistantLabel(this)
        checkAssistant.text = if (assistant != null) {
            "✓ Default assistant: $assistant"
        } else {
            "✗ No default assistant set — the wake word has nowhere to go"
        }
        assistantButton.visibility = if (assistant != null) View.GONE else View.VISIBLE

        // 4. Notifications + wake-channel importance (a downgraded channel
        // silently kills the full-screen auto-launch).
        val nm = getSystemService(NotificationManager::class.java)
        val notifOk = nm.areNotificationsEnabled()
        val channelOk = WakeService.isWakeChannelHigh(this)
        checkNotif.text = when {
            !notifOk -> "✗ Notifications: blocked — the tap fallback can't show"
            !channelOk -> "✗ Wake channel downgraded below HIGH — reset it in App info → Notifications"
            else -> "✓ Notifications: allowed, wake channel HIGH"
        }

        // 2. Overlay grant = instant, DND-proof assistant launch on wake.
        val overlayOk = Settings.canDrawOverlays(this)
        checkOverlay.text = if (overlayOk) {
            "✓ Display over other apps: granted — wake word opens Muse instantly"
        } else {
            "✗ Display over other apps: not granted — grant it for instant wake (no tap)"
        }
        overlayButton.visibility = if (overlayOk) View.GONE else View.VISIBLE

        // 3. Battery — Unrestricted, plus Samsung's real permanent whitelist.
        val powerManager = getSystemService(PowerManager::class.java)
        val batteryOk = powerManager.isIgnoringBatteryOptimizations(packageName)
        checkBattery.text = if (batteryOk) {
            "✓ Battery: unrestricted — mic stays alive in background"
        } else {
            "✗ Battery: optimized — the listener can be killed; set Unrestricted"
        }
        batteryButton.visibility = if (batteryOk) View.GONE else View.VISIBLE
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            samsungBatteryNote.visibility = View.VISIBLE
            samsungBatteryNote.text =
                "On Samsung, Unrestricted alone isn't permanent: also add Hey Ron to " +
                "Settings → Battery and device care → Battery → Background usage limits → " +
                "Never sleeping apps, or One UI re-sleeps it after ~3 days."
        } else {
            samsungBatteryNote.visibility = View.GONE
        }

        // 5. DND — informational. The direct launch is DND-proof; only the
        // full-screen auto-launch is suppressed by DND.
        val dndOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        checkDnd.text = if (dndOn) {
            "• Do Not Disturb is ON — auto-open uses the direct path only (tap still works)"
        } else {
            "✓ Do Not Disturb: off"
        }

        // 6. Last wake log — which path fired, what defeated the rest.
        checkLastWake.text = WakeService.lastWakeSummary(this)

        // 7. Engine state.
        checkEngine.text = when {
            !enabled -> "• Engine: stopped"
            paused -> "• Engine: paused for phone call"
            degraded -> "✗ Engine: degraded — mic reclaim retry $retries (backing off)"
            else -> "✓ Engine: listening"
        }
    }
}
