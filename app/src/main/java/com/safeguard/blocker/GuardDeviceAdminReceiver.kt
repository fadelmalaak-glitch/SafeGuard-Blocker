package com.safeguard.blocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Layer 3 — Device Admin. While active, Android will not let the app be uninstalled
 * until the user first removes it as a device administrator, which adds meaningful
 * friction in a moment of temptation. This is standard for accountability apps and,
 * unlike hidden/undeletable malware, it stays visible and can be removed deliberately.
 */
class GuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "إيقاف الحماية سيسمح بالوصول للمحتوى الإباحي وحذف التطبيق. " +
                "تأكد أن هذا قرارك وأنت في كامل تركيزك."
    }
}
