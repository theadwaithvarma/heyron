package com.adwaithvarma.heyron

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Tap path for the wake notification (v2.4).
 *
 * The notification's contentIntent routes here so the wake-path log can record
 * TAP — otherwise a tap-triggered launch is invisible in the diagnostics.
 * Fires the assist channel (the one Muse actually implements), same as the
 * full-screen intent.
 */
class WakeTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WakeService.logWake(context, "TAP", "user tapped the wake notification")
        try {
            context.startActivity(
                Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // Assistant unset or otherwise unlaunchable — the checklist UI
            // explains this; never crash from a tap.
        }
    }
}
