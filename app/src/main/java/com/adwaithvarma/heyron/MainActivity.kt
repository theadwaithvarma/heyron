package com.adwaithvarma.heyron

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

/**
 * Plain-View control panel: status, start/stop, detection-threshold slider,
 * and a setup checklist (model file, notification permission).
 * No API key or account needed anymore — the engine is openWakeWord.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var keywordText: TextView
    private lateinit var toggleButton: Button
    private lateinit var checkModel: TextView
    private lateinit var checkNotif: TextView
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
        checkNotif = findViewById(R.id.checkNotif)
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
        statusText.text = if (enabled) "Status: Listening" else "Status: Stopped"
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

        val notifOk = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        checkNotif.text = if (notifOk) "✓ Notifications: allowed" else "✗ Notifications: blocked (enable in Settings)"
    }
}
