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
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Always-on "Hey Ron" listener.
 *
 * - Runs as a foreground service (type: microphone) with a persistent notification.
 * - openWakeWord does on-device keyword spotting (melspectrogram + speech-embedding
 *   + wake-word ONNX models); no audio ever leaves the phone, no API key needed.
 *
 * v2.4 wake pipeline (per ALGORITHM_VNEXT.md):
 *  1. engine.stop() synchronously — mic released before Muse can acquire it
 *  2. playDing() immediately — "I heard you" feedback, independent of launch success
 *  3. Direct launch (primary): if "Display over other apps" is granted,
 *     startActivity(ACTION_ASSIST), fall back to ACTION_VOICE_COMMAND on exception.
 *     Instant, DND-proof, works while the phone is in active use.
 *  4. Full-screen notification (secondary): HIGH channel, CATEGORY_ALARM,
 *     full-screen intent on the assist channel; tap goes through WakeTapReceiver
 *     so the wake-path log records TAP too.
 *  5. 3 s debounce after any launch attempt.
 *  6. Mic re-arm at 4 s with exponential backoff (1/2/4/8 s); on persistent
 *     failure the engine is marked DEGRADED (visible in UI) instead of dying
 *     silently.
 *  7. Every wake is logged (timestamp, path, defeat detail) → diagnostics UI.
 *
 * Muse note: Muse implements the assist entry point (ACTION_ASSIST, the
 * long-press-home channel) and ignores ACTION_VOICE_COMMAND — the assist
 * intent is first for a reason.
 *
 * Pauses while a phone call is active (a second mic holder can glitch call audio).
 */
class WakeService : Service() {

    companion object {
        const val ACTION_START = "com.adwaithvarma.heyron.START"
        const val ACTION_STOP = "com.adwaithvarma.heyron.STOP"
        const val PREFS = "heyron_prefs"
        const val KEY_ENABLED = "listening_enabled"
        const val KEY_THRESHOLD = "detection_threshold"
        const val DEFAULT_THRESHOLD = 0.5f   // openWakeWord's recommended value

        const val CHANNEL_LISTEN = "heyron_listening"
        const val CHANNEL_WAKE = "heyron_wake"
        private const val NOTIF_LISTEN_ID = 1
        private const val NOTIF_WAKE_ID = 2
        private const val ASSET_CUSTOM_MODEL = "hey_ron.onnx"
        private const val ASSET_FALLBACK_MODEL = "hey_jarvis_v0.1.onnx"

        // Wake-path log + engine-state diagnostics (read by MainActivity).
        private const val KEY_LAST_WAKE_TS = "last_wake_ts"
        private const val KEY_LAST_WAKE_PATH = "last_wake_path"
        private const val KEY_LAST_WAKE_DETAIL = "last_wake_detail"
        private const val KEY_ENGINE_DEGRADED = "engine_degraded"
        private const val KEY_ENGINE_RETRIES = "engine_retries"
        private const val KEY_PAUSED_FOR_CALL = "paused_for_call"

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun getThreshold(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)

        fun isWakeChannelHigh(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val ch = nm.getNotificationChannel(CHANNEL_WAKE) ?: return false
            return ch.importance >= NotificationManager.IMPORTANCE_HIGH
        }

        /** "None set" when no default assistant is configured. */
        fun defaultAssistantLabel(ctx: Context): String? {
            val flat = Settings.Secure.getString(ctx.contentResolver, "assistant")
                ?: return null
            if (flat.isBlank()) return null
            val cn = android.content.ComponentName.unflattenFromString(flat)
                ?: return null
            return try {
                ctx.packageManager.getApplicationLabel(
                    ctx.packageManager.getApplicationInfo(cn.packageName, 0)
                ).toString()
            } catch (_: Exception) {
                cn.packageName
            }
        }

        fun logWake(ctx: Context, path: String, detail: String) {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_LAST_WAKE_TS, System.currentTimeMillis())
                .putString(KEY_LAST_WAKE_PATH, path)
                .putString(KEY_LAST_WAKE_DETAIL, detail.take(160))
                .apply()
        }

        fun lastWakeSummary(ctx: Context): String {
            val prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
            val ts = prefs.getLong(KEY_LAST_WAKE_TS, 0)
            if (ts == 0L) return "No wake word detected yet"
            val time = SimpleDateFormat("HH:mm:ss, d MMM", Locale.getDefault()).format(Date(ts))
            val path = prefs.getString(KEY_LAST_WAKE_PATH, "?") ?: "?"
            val detail = prefs.getString(KEY_LAST_WAKE_DETAIL, "") ?: ""
            return "Last wake $time — path: $path" + if (detail.isNotBlank()) "\n$detail" else ""
        }

        fun isDegraded(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENGINE_DEGRADED, false)

        fun degradedRetries(ctx: Context): Int =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_ENGINE_RETRIES, 0)

        fun isPausedForCall(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PAUSED_FOR_CALL, false)
    }

    private var engine: WakeWordEngine? = null
    private var listening = false
    private var pausedForCall = false
    private var lastLaunchAttemptMs = 0L
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
            setPausedForCall(false)
            setEnabled(true)
            promoteToForeground(listeningNotification())
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
        setPausedForCall(false)
        setDegraded(false, 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIF_WAKE_ID)
    }

    private fun pauseForCall() {
        if (!listening || pausedForCall) return
        pausedForCall = true
        setPausedForCall(true)
        try {
            engine?.stop()
        } catch (_: Exception) { }
        notificationManager.notify(NOTIF_LISTEN_ID, listeningNotification())
    }

    private fun resumeAfterCall() {
        if (!listening || !pausedForCall) return
        pausedForCall = false
        setPausedForCall(false)
        try {
            engine?.start()
            setDegraded(false, 0)
        } catch (_: Exception) {
            // Mic didn't come back — enter the same backoff path as a wake re-arm.
            scheduleRearm()
        }
        notificationManager.notify(NOTIF_LISTEN_ID, listeningNotification())
    }

    // ---------- wake ----------

    /** Runs on the engine's audio thread when the keyword is spotted. */
    private fun onWakeWord() {
        // 3 s debounce: a repeated "hey jarvis" during launch must not stack.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAttemptMs < 3000) return
        lastLaunchAttemptMs = now

        // 1. Release the mic synchronously, BEFORE Muse can try to acquire it.
        try {
            engine?.stop()
        } catch (_: Exception) { }

        // 2. Immediate "I heard you" feedback — independent of launch success.
        playDing()

        // 3. Direct launch (primary): SYSTEM_ALERT_WINDOW exempts us from
        // background-activity-start restrictions, so the assistant opens
        // instantly and directly — DND-proof, works while the phone is in use.
        val intents = listOf(
            Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        var launched = false
        var detail = ""
        if (Settings.canDrawOverlays(this)) {
            for (intent in intents) {
                try {
                    startActivity(intent)
                    launched = true
                    break
                } catch (e: Exception) {
                    detail = e.javaClass.simpleName + ": " + (e.message ?: "no message")
                }
            }
            if (!launched && detail.isBlank()) detail = "startActivity threw with no message"
        } else {
            detail = "Display-over-other-apps not granted — direct launch skipped"
        }
        logWake(this, if (launched) "DIRECT" else "FSI_POSTED", detail)

        if (!launched) {
            // 4. Fallback: full-screen notification. Full-screen intent is the
            // system's purpose-built lock-screen path; the tap goes through
            // WakeTapReceiver so the wake log records TAP as well.
            val fsPending = PendingIntent.getActivity(
                this, 0, intents[0],
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val tapPending = PendingIntent.getBroadcast(
                this, 1, Intent(this, WakeTapReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(this, CHANNEL_WAKE)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Hey Ron heard you")
                .setContentText("Opening your assistant…")
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_ALARM)
                // Full-screen intent: pops the assistant open even from the lock screen.
                // (We deliberately target API 33: the manifest permission suffices;
                // no user-facing full-screen grant exists below Android 14.)
                .setFullScreenIntent(fsPending, true)
                // Tap fallback: if the full-screen intent doesn't fire, tapping
                // the heads-up notification does the same thing.
                .setContentIntent(tapPending)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(NOTIF_WAKE_ID, notification)
        }

        // 5. Re-arm the mic: 4 s first (a cold-start Muse needs the headroom),
        // then exponential backoff. Persistent failure → DEGRADED, never silent.
        scheduleRearm()
    }

    /** Short confirmation beep the moment the wake word fires. */
    private fun playDing() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 400)
        } catch (_: Exception) {
            // Audio focus edge cases — never crash here.
        }
    }

    // ---------- mic re-arm with backoff ----------

    private var rearmAttempt = 0
    // First retry at 4 s; then 1 s / 2 s / 4 s / 8 s between retries.
    private val rearmSchedule = longArrayOf(4000, 1000, 2000, 4000, 8000)

    private fun scheduleRearm() {
        rearmAttempt = 0
        setDegraded(false, 0)
        Handler(Looper.getMainLooper()).postDelayed({ attemptRearm() }, rearmSchedule[0])
    }

    private fun attemptRearm() {
        if (!listening || pausedForCall) return
        try {
            engine?.start()
            rearmAttempt = 0
            setDegraded(false, 0)
            notificationManager.notify(NOTIF_LISTEN_ID, listeningNotification())
        } catch (_: Exception) {
            rearmAttempt++
            if (rearmAttempt < rearmSchedule.size) {
                setDegraded(true, rearmAttempt)
                notificationManager.notify(NOTIF_LISTEN_ID, listeningNotification())
                Handler(Looper.getMainLooper()).postDelayed(
                    { attemptRearm() }, rearmSchedule[rearmAttempt]
                )
            } else {
                // Out of retries: stay DEGRADED (visible), don't silently die.
                setDegraded(true, rearmAttempt)
                notificationManager.notify(NOTIF_LISTEN_ID, listeningNotification())
            }
        }
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

    private fun listeningNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val keyword = engine?.keywordName ?: "Hey Ron"
        val degraded = isDegraded(this)
        val retries = degradedRetries(this)
        val (title, text) = when {
            pausedForCall -> "Hey Ron paused (on call)" to "Resumes when the call ends"
            degraded -> "Hey Ron degraded (mic retry $retries)" to
                "Couldn't reclaim the mic — still trying. Open the app for details."
            else -> "Hey Ron is listening" to "Say \"$keyword\" to open your assistant"
        }
        return Notification.Builder(this, CHANNEL_LISTEN)
            .setSmallIcon(
                if (degraded) android.R.drawable.ic_dialog_alert
                else android.R.drawable.ic_btn_speak_now
            )
            .setContentTitle(title)
            .setContentText(text)
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

    private fun setPausedForCall(paused: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_PAUSED_FOR_CALL, paused).apply()
    }

    private fun setDegraded(degraded: Boolean, retries: Int) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ENGINE_DEGRADED, degraded)
            .putInt(KEY_ENGINE_RETRIES, retries)
            .apply()
    }
}
