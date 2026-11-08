package com.safeguard.blocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device Admin — while active, Android requires the user to first remove the app as a
 * device administrator before it can be uninstalled, adding meaningful friction in a
 * moment of temptation. Standard accountability-app behaviour: visible and removable
 * by the user deliberately, not hidden or truly undeletable.
 */
class GuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "إيقاف الحماية سيسمح بالوصول للمحتوى الإباحي وحذف التطبيق. " +
                "تأكد أن هذا قرارك وأنت في كامل تركيزك."
    }
}
