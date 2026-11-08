package com.safeguard.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires at the start/end of the daily window to enable or disable filtering. */
class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_WINDOW_START = "com.safeguard.blocker.WINDOW_START"
        const val ACTION_WINDOW_END = "com.safeguard.blocker.WINDOW_END"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Re-arm the next day's exact alarms (these are one-shot).
        ScheduleManager.scheduleDailyAlarms(context)

        if (!Prefs.protectionEnabled(context)) return

        when (intent.action) {
            ACTION_WINDOW_START -> ProtectionController.startFiltering(context)
            ACTION_WINDOW_END -> {
                // Only stop when always-on is off; honour the user's schedule otherwise.
                if (!Prefs.alwaysOn(context)) {
                    ProtectionController.stopFiltering(context)
                }
            }
        }
    }
}
