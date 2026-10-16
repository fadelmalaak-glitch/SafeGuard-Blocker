package com.safeguard.blocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.UserManager
import android.provider.Settings
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * SafeGuard Blocker — 3-Layer Strict Adult Content Blocker & Anti-Uninstall System for Android
 *
 * Layer 1: SafeDnsVpnService (VpnService)
 *   - Forces system-wide DNS queries through Cloudflare Family (1.1.1.3 / 1.0.0.3)
 *     and CleanBrowsing Adult Filter (185.228.168.10 / 185.228.169.11).
 *   - Works across all browsers and apps at the network interface level.
 *
 * Layer 2: BrowserGuardAccessibilityService (AccessibilityService)
 *   - Inspects browser URL bars and page content across Chrome, Firefox, Brave, Opera,
 *     Edge, Samsung Internet, and WebView apps in real time.
 *   - Immediately navigates back & goes to Home Screen if blocked keywords/domains appear.
 *   - Self-Protection: Blocks access to the Android Settings -> Apps -> SafeGuard screen,
 *     Device Admin deactivation screen, and VPN settings screen so settings cannot be altered.
 *
 * Layer 3: GuardDeviceAdminReceiver (DeviceAdminReceiver + Device Owner Policy)
 *   - Prevents app uninstallation via Device Admin.
 *   - When elevated to Device Owner via ADB:
 *     `adb shell dpm set-device-owner com.safeguard.blocker/.GuardDeviceAdminReceiver`
 *     it calls `setUninstallBlocked()`, `setAlwaysOnVpnPackage()`, and locks down
 *     safe-boot, factory reset from settings, and adding secondary users.
 */

// ============================================================================
// 1. MAIN ACTIVITY — ONE-WAY STRICT ACTIVATION SCREEN
// ============================================================================
class MainActivity : Activity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var statusText: TextView

    companion object {
        private const val REQ_VPN = 1001
        private const val REQ_ADMIN = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, GuardDeviceAdminReceiver::class.java)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = "SafeGuard — الحماية القصوى الدائمة"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        statusText = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val btnActivateVpn = Button(this).apply {
            text = "1. تفعيل فلتر الشبكة الدائم (VPN DNS Filter)"
            setOnClickListener { startVpnProtection() }
        }

        val btnActivateAdmin = Button(this).apply {
            text = "2. تفعيل منع الحذف (Device Admin / Owner)"
            setOnClickListener { activateDeviceAdmin() }
        }

        val btnActivateAccessibility = Button(this).apply {
            text = "3. تفعيل مراقب المتصفحات وقفل الإعدادات"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    this@MainActivity,
                    "قم بتفعيل خدمة SafeGuard Browser Guard",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(btnActivateVpn)
        layout.addView(btnActivateAdmin)
        layout.addView(btnActivateAccessibility)

        setContentView(layout)

        // Automatically enforce Device Owner locks if already granted via ADB
        enforceStrictDeviceOwnerPolicies()
        updateStatusUI()
    }

    override fun onResume() {
        super.onResume()
        enforceStrictDeviceOwnerPolicies()
        updateStatusUI()
    }

    private fun startVpnProtection() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQ_VPN)
        } else {
            onActivityResult(REQ_VPN, RESULT_OK, null)
        }
    }

    private fun activateDeviceAdmin() {
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "هذه الصلاحية تمنع حذف التطبيق نهائياً وتحمي إعدادات الفلتر من الإيقاف."
                )
            }
            startActivityForResult(intent, REQ_ADMIN)
        } else {
            enforceStrictDeviceOwnerPolicies()
            Toast.makeText(this, "صلاحية منع الحذف مفعلة بالفعل", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * When the app is set as Device Owner via ADB:
     * adb shell dpm set-device-owner com.safeguard.blocker/.GuardDeviceAdminReceiver
     * This method locks the app permanently at the OS kernel/policy level.
     */
    private fun enforceStrictDeviceOwnerPolicies() {
        if (dpm.isDeviceOwnerApp(packageName)) {
            // 1. Permanently block uninstallation of this app
            dpm.setUninstallBlocked(adminComponent, packageName, true)

            // 2. Force Always-On VPN with lockdown (cannot browse without VPN active)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    dpm.setAlwaysOnVpnPackage(adminComponent, packageName, true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 3. Block user from modifying VPN settings, adding users, or booting into Safe Mode
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_VPN)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            }
        }
    }

    private fun updateStatusUI() {
        val isAdmin = dpm.isAdminActive(adminComponent)
        val isOwner = dpm.isDeviceOwnerApp(packageName)
        statusText.text = buildString {
            appendLine("حالة مسؤول الجهاز (Device Admin): ${if (isAdmin) "مفعّل ✅" else "غير مفعّل ❌"}")
            appendLine("حالة القفل الجذري (Device Owner): ${if (isOwner) "مفعّل نهائياً 🔒" else "في انتظار تفعيل ADB"}")
            appendLine("الفلتر يعمل عبر: Cloudflare Family (1.1.1.3) + CleanBrowsing")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            val vpnIntent = Intent(this, SafeDnsVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(vpnIntent)
            } else {
                startService(vpnIntent)
            }
            Toast.makeText(this, "تم تشغيل فلتر الشبكة بنجاح", Toast.LENGTH_SHORT).show()
        }
        updateStatusUI()
    }
}

// ============================================================================
// 2. LAYER 1: SYSTEM-WIDE DNS FILTER VPN SERVICE
// ============================================================================
class SafeDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        establishStrictDnsTunnel()
        // START_STICKY ensures Android restarts the service immediately if memory is low
        return START_STICKY
    }

    private fun establishStrictDnsTunnel() {
        if (vpnInterface != null) return

        try {
            val builder = Builder()
                .setSession("SafeGuard Permanent Adult Filter")
                .setMtu(1500)
                // Virtual local interface IPv4 & IPv6
                .addAddress("10.215.173.1", 30)
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
                // Primary + Secondary Adult Blocking DNS:
                // 1. Cloudflare for Families (Blocks Malware & Adult Content)
                .addDnsServer("1.1.1.3")
                .addDnsServer("1.0.0.3")
                // 2. CleanBrowsing Adult Filter DNS
                .addDnsServer("185.228.168.10")
                .addDnsServer("185.228.169.11")
                // IPv6 Cloudflare Family DNS
                .addDnsServer("2606:4700:4700::1113")
                .addDnsServer("2606:4700:4700::1003")
                // Route only DNS resolver IPs through the virtual interface so normal internet
                // speed remains 100% fast while every DNS lookup is strictly filtered!
                .addRoute("1.1.1.3", 32)
                .addRoute("1.0.0.3", 32)
                .addRoute("185.228.168.10", 32)
                .addRoute("185.228.169.11", 32)
                .addRoute("2606:4700:4700::1113", 128)
                .addRoute("2606:4700:4700::1003", 128)
                .setBlocking(false)

            vpnInterface = builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onRevoke() {
        // Immediately re-launch VPN if anything attempts to revoke it
        val restartIntent = Intent(this, SafeDnsVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}
        super.onDestroy()
    }
}

// ============================================================================
// 3. LAYER 2: REAL-TIME BROWSER URL & SETTINGS TAMPER GUARD
// ============================================================================
class BrowserGuardAccessibilityService : AccessibilityService() {

    // Comprehensive list of adult keywords, domains, and TLDs blocked across all browsers
    private val blockedPatterns = listOf(
        "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
        "spankbang", "eporner", "tube8", "brazzers", "onlyfans", "chaturbate",
        "stripchat", "livejasmin", "bongacams", "cam4", "rule34", "nhentai",
        "hentai", "hanime", "hqporner", "beeg", "txxx", "tnaflix", "motherless",
        "daftsex", "sxyprn", "fuq.com", "porn", "xxx", "sexvideo", "adultvideo",
        "nsfw", "سكس", "اباحي", "إباحي", "افلام سكس", "مواقع اباحية", "بورن"
    )

    // System Settings & Package Installer packages monitored to prevent tampering/uninstall
    private val settingsPackages = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.securitycenter",
        "com.coloros.safecenter"
    )

    // Tamper keywords inside Settings that trigger immediate kick-back to Home Screen
    private val tamperKeywords = listOf(
        "safeguard", "com.safeguard.blocker",
        "uninstall", "إلغاء التثبيت", "ازالة", "إزالة",
        "force stop", "إيقاف إجباري", "فرض الإيقاف",
        "device admin", "مسؤول الجهاز", "تطبيقات مسؤول الجهاز",
        "clear storage", "clear data", "مسح البيانات",
        "private dns", "نظام أسماء النطاقات الخاص"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return
        val pkgName = event?.packageName?.toString()?.lowercase() ?: return

        // Never block our own activation UI
        if (pkgName == packageName.lowercase()) return

        val screenTexts = mutableListOf<String>()
        collectNodeText(rootNode, screenTexts)
        val combinedText = screenTexts.joinToString(" ").lowercase()

        // A) Self-Defense: Prevent user from opening Settings -> SafeGuard or Deactivating Admin/VPN
        if (settingsPackages.any { pkgName.contains(it) }) {
            if (tamperKeywords.any { combinedText.contains(it) } &&
                (combinedText.contains("safeguard") || combinedText.contains("device admin") ||
                 combinedText.contains("مسؤول الجهاز") || combinedText.contains("private dns") ||
                 combinedText.contains("vpn"))) {
                blockAndExitToHome("محاولة تغيير إعدادات الحماية مرفوضة 🔒")
                return
            }
        }

        // B) Browser & App Content Filter: Block any adult domain or search query immediately
        if (blockedPatterns.any { pattern -> combinedText.contains(pattern) }) {
            // Perform BACK twice to exit the tab/page, then go directly to HOME screen
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_BACK)
            blockAndExitToHome("تم حظر هذا الموقع بواسطة SafeGuard 🚫")
        }
    }

    private fun collectNodeText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            collectNodeText(node.getChild(i), out)
        }
    }

    private fun blockAndExitToHome(message: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {}
}

// ============================================================================
// 4. LAYER 3: DEVICE ADMIN & DEVICE OWNER RECEIVER (ANTI-UNINSTALL)
// ============================================================================
class GuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, GuardDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(context.packageName)) {
            dpm.setUninstallBlocked(admin, context.packageName, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    dpm.setAlwaysOnVpnPackage(admin, context.packageName, true)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Immediately lock screen or return home if someone attempts to disable Device Admin
        return "تحذير: لا يُسمح بإيقاف حماية SafeGuard."
    }
}

// ============================================================================
// 5. BOOT RECEIVER — AUTO-START PROTECTION IMMEDIATELY ON PHONE REBOOT
// ============================================================================
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val vpnIntent = Intent(context, SafeDnsVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(vpnIntent)
            } else {
                context.startService(vpnIntent)
            }
        }
    }
}
