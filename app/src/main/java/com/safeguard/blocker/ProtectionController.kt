package com.safeguard.blocker

import android.content.Context
import android.content.Intent
import android.os.Build

/** Single entry point for turning filtering on/off, used by UI, boot, and scheduler. */
object ProtectionController {

    /** User turned protection ON: persist intent, arm the schedule, start if in window. */
    fun enableProtection(ctx: Context) {
        Prefs.setProtectionEnabled(ctx, true)
        ScheduleManager.scheduleDailyAlarms(ctx)
        if (ScheduleManager.shouldBeActiveNow(ctx)) {
            startFiltering(ctx)
        }
    }

    /** User turned protection OFF (after passing the PIN gate). */
    fun disableProtection(ctx: Context) {
        Prefs.setProtectionEnabled(ctx, false)
        stopFiltering(ctx)
    }

    fun startFiltering(ctx: Context) {
        val intent = Intent(ctx, SafeDnsVpnService::class.java)
            .setAction(SafeDnsVpnService.ACTION_START)
        startService(ctx, intent)
    }

    fun stopFiltering(ctx: Context) {
        val intent = Intent(ctx, SafeDnsVpnService::class.java)
            .setAction(SafeDnsVpnService.ACTION_STOP)
        startService(ctx, intent)
    }

    private fun startService(ctx: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the
            // background outside an allowed window; the next trigger will retry.
            e.printStackTrace()
        }
    }
}
