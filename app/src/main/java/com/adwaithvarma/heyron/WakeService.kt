package com.adwaithvarma.heyron

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * Always-on "Hey Ron" listener.
 *
 * - Runs as a foreground service (type: microphone) with a persistent notification.
 * - openWakeWord does on-device keyword spotting (melspectrogram + speech-embedding
 *   + wake-word ONNX models); no audio ever leaves the phone, no API key needed.
 * - On detection it posts a HIGH-priority notification with a full-screen intent
 *   that fires ACTION_VOICE_COMMAND -> opens the default assistant (Muse on
 *   Adwaith's phone) with voice input already toggled. Fully hands-free.
 * - Pauses while a phone call is active (a second mic holder can glitch call audio).
 */
class WakeService : Service() {

    companion object {
        const val ACTION_START = "com.adwaithvarma.heyron.START"
        const val ACTION_STOP = "com.adwaithvarma.heyron.STOP"
        const val PREFS = "heyron_prefs"
        const val KEY_ENABLED = "listening_enabled"
        const val KEY_THRESHOLD = "detection_threshold"
        const val DEFAULT_THRESHOLD = 0.5f   // openWakeWord's recommended value

        private const val CHANNEL_LISTEN = "heyron_listening"
        private const val CHANNEL_WAKE = "heyron_wake"
        private const val NOTIF_LISTEN_ID = 1
        private const val NOTIF_WAKE_ID = 2
        private const val ASSET_CUSTOM_MODEL = "hey_ron.onnx"
        private const val ASSET_FALLBACK_MODEL = "hey_jarvis_v0.1.onnx"

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun getThreshold(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
    }

    private var engine: WakeWordEngine? = null
    private var listening = false
    private var pausedForCall = false
    private lateinit var notificationManager: NotificationManager
    private lateinit var telephonyManager: TelephonyManager

    // Deprecated in API 31 but works on every API level 26-34; keeps the app lean
    // (no AndroidX, no version-branched TelephonyCallback code).
    @Suppress("DEPRECATION")
    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> pauseForCall()
                TelephonyManager.CALL_STATE_IDLE -> resumeAfterCall()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        telephonyManager = getSystemService(TelephonyManager::class.java)
        createChannels()
        @Suppress("DEPRECATION")
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            telephonyManager.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        @Suppress("DEPRECATION")
        telephonyManager.listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        stopListening()
        super.onDestroy()
    }

    // ---------- listening lifecycle ----------

    private fun startListening() {
        if (listening) return

        val (modelAsset, keywordName) = resolveModel()
        if (modelAsset == null) {
            promoteToForeground(errorNotification("No wake-word model found in assets"))
            setEnabled(false)
            return
        }

        try {
            val eng = OpenWakeWordEngine(
                context = applicationContext,
                wakeModelAsset = modelAsset,
                keywordName = keywordName,
                threshold = getThreshold(this),
            )
            eng.loadModels()
            eng.setOnWakeListener { onWakeWord() }
            eng.start()
            engine = eng
            listening = true
            setEnabled(true)
            promoteToForeground(listeningNotification(paused = false))
        } catch (e: Exception) {
            promoteToForeground(errorNotification(e.message ?: "Engine failed to start"))
            setEnabled(false)
        }
    }

    private fun stopListening() {
        listening = false
        pausedForCall = false
        try {
            engine?.stop()
        } catch (_: Exception) { /* already stopped */ }
        engine?.release()
        engine = null
        setEnabled(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIF_WAKE_ID)
    }

    private fun pauseForCall() {
        if (!listening || pausedForCall) return
        pausedForCall = true
        try {
            engine?.stop()
        } catch (_: Exception) { }
        notificationManager.notify(NOTIF_LISTEN_ID, listeningNotification(paused = true))
    }

    private fun resumeAfterCall() {
        if (!listening || !pausedForCall) return
        pausedForCall = false
        try {
            engine?.start()
        } catch (_: Exception) { }
        notificationManager.notify(NOTIF_LISTEN_ID, listeningNotification(paused = false))
    }

    // ---------- wake ----------

    /** Runs on the engine's audio thread when the keyword is spotted. */
    private fun onWakeWord() {
        // Briefly stop so the assistant's own mic use doesn't re-trigger us;
        // the service keeps running and resumes listening below.
        try {
            engine?.stop()
        } catch (_: Exception) { }

        val voiceIntent = Intent(Intent.ACTION_VOICE_COMMAND)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this, 0, voiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_WAKE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hey Ron heard you")
            .setContentText("Opening your assistant…")
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ALARM)
            // Full-screen intent: pops Muse open even from the lock screen.
            // (Apps targeting API 34+ need a Settings grant for this; we
            // deliberately target 33 so the manifest permission suffices.)
            .setFullScreenIntent(pending, true)
            // Tap fallback: if the full-screen intent doesn't fire, tapping
            // the heads-up notification does the same thing.
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIF_WAKE_ID, notification)

        // Resume listening after a beat so the assistant session isn't cut off.
        try {
            engine?.start()
        } catch (_: Exception) { }
    }

    // ---------- wake-word model ----------

    /**
     * Returns (asset filename, display name) for the wake-word model.
     * Custom "hey_ron.onnx" wins when present; otherwise the bundled
     * hey_jarvis_v0.1.onnx keeps the whole pipeline testable.
     */
    private fun resolveModel(): Pair<String?, String> {
        return try {
            val names = assets.list("")?.toSet() ?: emptySet()
            when {
                names.contains(ASSET_CUSTOM_MODEL) -> ASSET_CUSTOM_MODEL to "Hey Ron"
                names.contains(ASSET_FALLBACK_MODEL) -> ASSET_FALLBACK_MODEL to "Hey Jarvis"
                else -> null to ""
            }
        } catch (_: Exception) {
            null to ""
        }
    }

    // ---------- notifications ----------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LISTEN, "Hey Ron listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Persistent notification while listening for the wake word" }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WAKE, "Hey Ron wake",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Fires when the wake word is heard" }
        )
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_LISTEN_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_LISTEN_ID, notification)
        }
    }

    private fun listeningNotification(paused: Boolean): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val keyword = engine?.keywordName ?: "Hey Ron"
        return Notification.Builder(this, CHANNEL_LISTEN)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "Hey Ron paused (on call)" else "Hey Ron is listening")
            .setContentText(if (paused) "Resumes when the call ends" else "Say \"$keyword\" to open your assistant")
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun errorNotification(msg: String): Notification =
        Notification.Builder(this, CHANNEL_LISTEN)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Hey Ron couldn't start listening")
            .setContentText(msg)
            .setOngoing(true)
            .build()

    private fun setEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }
}
