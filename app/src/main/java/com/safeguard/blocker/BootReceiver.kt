package com.safeguard.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-applies protection after a reboot, respecting the schedule. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.protectionEnabled(context)) return

        ScheduleManager.scheduleDailyAlarms(context)
        if (ScheduleManager.shouldBeActiveNow(context)) {
            ProtectionController.startFiltering(context)
        }
    }
}
