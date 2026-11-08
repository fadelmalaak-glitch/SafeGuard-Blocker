package com.safeguard.blocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Decides whether filtering should currently be active (based on the daily window
 * or the always-on toggle) and schedules inexact wake-ups at the window boundaries.
 */
object ScheduleManager {

    private const val REQ_START = 2001
    private const val REQ_END = 2002

    /** True if now is inside the active window, or always-on is enabled. */
    fun shouldBeActiveNow(ctx: Context): Boolean {
        if (Prefs.alwaysOn(ctx)) return true
        val start = Prefs.startHour(ctx)
        val end = Prefs.endHour(ctx)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (start == end) {
            true // full day
        } else if (start < end) {
            hour in start until end
        } else {
            // Window wraps past midnight, e.g. 16 -> 5
            hour >= start || hour < end
        }
    }

    /** Register (or refresh) the daily boundary alarms. */
    fun scheduleDailyAlarms(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val start = Prefs.startHour(ctx)
        val end = Prefs.endHour(ctx)

        setDailyAlarm(ctx, am, start, ScheduleReceiver.ACTION_WINDOW_START, REQ_START)
        setDailyAlarm(ctx, am, end, ScheduleReceiver.ACTION_WINDOW_END, REQ_END)
    }

    private fun setDailyAlarm(
        ctx: Context,
        am: AlarmManager,
        hour: Int,
        action: String,
        reqCode: Int
    ) {
        val intent = Intent(ctx, ScheduleReceiver::class.java).setAction(action)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val pi = PendingIntent.getBroadcast(ctx, reqCode, intent, flags)

        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        // Exact + allow-while-idle fires even in Doze AND grants a short window where the
        // app may start its foreground service from the background. One-shot, so the
        // ScheduleReceiver re-arms the next occurrence after it fires.
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
            }
        } catch (_: SecurityException) {
            // Exact alarms not permitted: fall back to inexact (best effort).
            am.set(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
        }
    }
}
