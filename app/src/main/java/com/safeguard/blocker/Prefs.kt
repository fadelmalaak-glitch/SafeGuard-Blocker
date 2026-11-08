package com.safeguard.blocker

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Lightweight persisted settings. Holds the user's lock PIN (hashed), the daily
 * schedule window, the "always on" toggle, and the active/paused flag.
 */
object Prefs {

    private const val FILE = "safeguard_prefs"

    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_ALWAYS_ON = "always_on"
    private const val KEY_START_HOUR = "start_hour"
    private const val KEY_END_HOUR = "end_hour"
    private const val KEY_PROTECTION_ENABLED = "protection_enabled"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- PIN (self-control lock) ----

    fun hasPin(ctx: Context): Boolean = sp(ctx).contains(KEY_PIN_HASH)

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.toHex()
        sp(ctx).edit()
            .putString(KEY_PIN_SALT, saltHex)
            .putString(KEY_PIN_HASH, hashPin(pin, saltHex))
            .apply()
    }

    fun checkPin(ctx: Context, pin: String): Boolean {
        val salt = sp(ctx).getString(KEY_PIN_SALT, null) ?: return false
        val expected = sp(ctx).getString(KEY_PIN_HASH, null) ?: return false
        return hashPin(pin, salt) == expected
    }

    private fun hashPin(pin: String, saltHex: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(saltHex.toByteArray())
        md.update(pin.toByteArray())
        return md.digest().toHex()
    }

    // ---- Schedule ----

    /** Default active window: 16:00 (4 PM) until 05:00 (5 AM) next day. */
    fun startHour(ctx: Context): Int = sp(ctx).getInt(KEY_START_HOUR, 16)
    fun endHour(ctx: Context): Int = sp(ctx).getInt(KEY_END_HOUR, 5)

    fun setWindow(ctx: Context, startHour: Int, endHour: Int) {
        sp(ctx).edit()
            .putInt(KEY_START_HOUR, startHour.coerceIn(0, 23))
            .putInt(KEY_END_HOUR, endHour.coerceIn(0, 23))
            .apply()
    }

    fun alwaysOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ALWAYS_ON, false)
    fun setAlwaysOn(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ALWAYS_ON, value).apply()

    // ---- Protection flag (user intent, survives reboot) ----

    fun protectionEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_PROTECTION_ENABLED, false)

    fun setProtectionEnabled(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean(KEY_PROTECTION_ENABLED, value).apply()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
