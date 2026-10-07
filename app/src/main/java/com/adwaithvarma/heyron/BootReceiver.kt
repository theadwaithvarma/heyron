package com.adwaithvarma.heyron

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * After a reboot, restart the listener if it was enabled when the phone
 * went down. (M12 + One UI: also needs "auto-start" allowed for the app in
 * device settings, otherwise the system may still suppress us.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!WakeService.isEnabled(context)) return
        val service = Intent(context, WakeService::class.java).setAction(WakeService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
