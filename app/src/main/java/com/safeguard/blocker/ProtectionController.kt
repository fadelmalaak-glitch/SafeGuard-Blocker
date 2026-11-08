package com.safeguard.blocker

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/** Single entry point for turning filtering on/off, used by UI, boot, and scheduler. */
object ProtectionController {

    /**
     * Optional advanced hardening: only takes effect if the user has made the app a
     * Device Owner via ADB (see README). It then, at the OS policy level:
     *   - blocks uninstall of the app,
     *   - forces this app as an always-on VPN with lockdown (no traffic without the
     *     filter, and the user cannot turn the VPN off from Settings),
     *   - forbids changing VPN configuration.
     * Fully reversible by the user from their own computer (adb / factory reset).
     */
    fun enforceDeviceOwnerLock(ctx: Context) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) return
        val admin = ComponentName(ctx, GuardDeviceAdminReceiver::class.java)
        try {
            dpm.setUninstallBlocked(admin, ctx.packageName, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // lockdownEnabled = true: block all traffic while the VPN is down.
                dpm.setAlwaysOnVpnPackage(admin, ctx.packageName, true)
            }
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_CONFIG_VPN)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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
