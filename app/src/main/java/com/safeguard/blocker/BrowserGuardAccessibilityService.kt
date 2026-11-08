package com.safeguard.blocker

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Layer 2 — real-time adult-content guard.
 *
 * Reads the URL bar / on-screen text of browsers and WebView apps and, if adult
 * content is detected, navigates back and returns to the home screen. This catches
 * cases the DNS layer can miss (e.g. a browser using its own DNS-over-HTTPS).
 *
 * It is active only while protection is enabled and within the active window.
 */
class BrowserGuardAccessibilityService : AccessibilityService() {

    private val browserPackages = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.brave.browser",
        "com.opera.browser", "com.opera.mini.native", "com.opera.gx",
        "com.microsoft.emmx", "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta", "com.duckduckgo.mobile.android",
        "com.UCMobile.intl", "mark.via.gp", "com.kiwibrowser.browser",
        "com.vivaldi.browser", "org.torproject.torbrowser", "com.yandex.browser",
        "com.heytap.browser", "com.coloros.browser", "com.android.browser",
        "com.google.android.googlequicksearchbox"
    )

    private var lastBlockAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!Prefs.protectionEnabled(this)) return
        if (!ScheduleManager.shouldBeActiveNow(this)) return

        val pkg = event.packageName?.toString()?.lowercase() ?: return
        if (pkg == packageName.lowercase()) return
        if (browserPackages.none { pkg.contains(it) }) return

        val root = rootInActiveWindow ?: return
        val texts = StringBuilder()
        collect(root, texts, 0)
        val haystack = texts.toString().lowercase()
        if (haystack.isBlank()) return

        if (Blocklists.adultPatterns.any { haystack.contains(it) }) {
            val now = System.currentTimeMillis()
            if (now - lastBlockAt < 1500) return
            lastBlockAt = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(
                applicationContext,
                "تم حظر محتوى غير لائق بواسطة SafeGuard 🚫",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > 40) return
        node.text?.let { if (it.isNotBlank()) out.append(it).append(' ') }
        node.contentDescription?.let { if (it.isNotBlank()) out.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
        }
    }

    override fun onInterrupt() {}
}
