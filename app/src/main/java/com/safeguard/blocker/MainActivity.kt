package com.safeguard.blocker

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Control screen for a transparent, self-control DNS content filter. The filtering
 * runs through a local VpnService (works across every browser and app). Protection
 * can be stopped only after entering the user's PIN.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var container: LinearLayout
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    companion object {
        private const val REQ_VPN = 1001
        private const val REQ_ADMIN = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, GuardDeviceAdminReceiver::class.java)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 72, 56, 72)
        }
        scroll.addView(container)
        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        ProtectionController.enforceDeviceOwnerLock(this)
        if (::statusText.isInitialized) updateStatus()
    }

    private fun render() {
        container.removeAllViews()
        addTitle("SafeGuard — فلتر المحتوى")
        if (!Prefs.hasPin(this)) renderPinSetup() else renderControls()
    }

    private fun renderPinSetup() {
        addText("لأول مرة: اختر كلمة سر (PIN) من 4 أرقام على الأقل. هتحتاجها عشان توقف الحماية لاحقاً.")
        val pin = EditText(this).apply {
            hint = "أدخل الـ PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val pin2 = EditText(this).apply {
            hint = "أعد إدخال الـ PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        container.addView(pin)
        container.addView(pin2)
        addButton("حفظ كلمة السر والمتابعة") {
            val a = pin.text.toString(); val b = pin2.text.toString()
            when {
                a.length < 4 -> toast("الـ PIN لازم يكون 4 أرقام على الأقل")
                a != b -> toast("الكلمتين مش متطابقتين")
                else -> { Prefs.setPin(this, a); toast("تم الحفظ ✅"); render() }
            }
        }
    }

    private fun renderControls() {
        statusText = TextView(this).apply {
            textSize = 14f; setPadding(0, 8, 0, 32)
        }
        container.addView(statusText)

        addButton("تفعيل فلتر المحتوى (يشتغل على كل المتصفحات)") { startVpnProtection() }
        addButton("تفعيل منع الحذف (Device Admin)") { activateDeviceAdmin() }

        addText("—")
        addText("مواعيد التشغيل التلقائي: من ${hour(Prefs.startHour(this))} حتى ${hour(Prefs.endHour(this))}.")
        addButton(if (Prefs.alwaysOn(this)) "الوضع الحالي: طوال اليوم (اضغط للرجوع للجدول)"
                  else "الوضع الحالي: حسب الجدول (اضغط للتشغيل طوال اليوم)") {
            Prefs.setAlwaysOn(this, !Prefs.alwaysOn(this))
            if (Prefs.protectionEnabled(this)) ProtectionController.enableProtection(this)
            render()
        }
        addButton("تعديل مواعيد الجدول") { showScheduleDialog() }

        addText("—")
        addButton("إيقاف الحماية (يتطلب كلمة السر)") { confirmStop() }

        updateStatus()
    }

    private fun startVpnProtection() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, REQ_VPN)
        } else {
            ProtectionController.enableProtection(this)
            toast("تم تفعيل الفلتر ✅")
            updateStatus()
        }
    }

    private fun activateDeviceAdmin() {
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "تمنع حذف التطبيق بسهولة وتضيف حاجز قبل إيقاف الحماية."
                )
            }
            startActivityForResult(intent, REQ_ADMIN)
        } else {
            toast("منع الحذف مفعّل بالفعل")
        }
    }

    private fun showScheduleDialog() {
        val start = EditText(this).apply {
            hint = "ساعة البداية (0-23)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.startHour(this@MainActivity).toString())
        }
        val end = EditText(this).apply {
            hint = "ساعة النهاية (0-23)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.endHour(this@MainActivity).toString())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            addView(start); addView(end)
        }
        AlertDialog.Builder(this)
            .setTitle("مواعيد التشغيل")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val s = start.text.toString().toIntOrNull() ?: 16
                val e = end.text.toString().toIntOrNull() ?: 5
                Prefs.setWindow(this, s, e)
                if (Prefs.protectionEnabled(this)) ProtectionController.enableProtection(this)
                render()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmStop() {
        val input = EditText(this).apply {
            hint = "أدخل كلمة السر"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("إيقاف الحماية")
            .setMessage("خذ لحظة. هل أنت متأكد؟")
            .setView(input)
            .setPositiveButton("إيقاف") { _, _ ->
                if (Prefs.checkPin(this, input.text.toString())) {
                    ProtectionController.disableProtection(this)
                    toast("تم إيقاف الفلتر. لحذف التطبيق: عطّل Device Admin أولاً من الإعدادات.")
                    updateStatus()
                } else {
                    toast("كلمة السر غير صحيحة")
                }
            }
            .setNegativeButton("تراجع", null)
            .show()
    }

    private fun updateStatus() {
        val on = if (Prefs.protectionEnabled(this)) "مفعّلة ✅" else "متوقفة ❌"
        val admin = if (dpm.isAdminActive(adminComponent)) "مفعّل ✅" else "غير مفعّل ❌"
        val active = if (ScheduleManager.shouldBeActiveNow(this)) "ضمن وقت التشغيل الآن ⏰"
                     else "خارج وقت التشغيل الآن"
        statusText.text = buildString {
            appendLine("الحماية: $on")
            appendLine("منع الحذف (Device Admin): $admin")
            appendLine(active)
            appendLine("الفلتر: CleanBrowsing Adult + Cloudflare Family")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            ProtectionController.enableProtection(this)
            toast("تم تفعيل الفلتر ✅")
        }
        if (requestCode == REQ_ADMIN) {
            if (dpm.isAdminActive(adminComponent)) {
                toast("تم تفعيل منع الحذف ✅")
            } else {
                showRestrictedSettingsHelp()
            }
        }
        if (::statusText.isInitialized) updateStatus()
    }

    /**
     * Android 13+ blocks sideloaded apps from requesting Device Admin until the user
     * explicitly allows "restricted settings" for the app. Guide them there.
     */
    private fun showRestrictedSettingsHelp() {
        AlertDialog.Builder(this)
            .setTitle("لازم تسمح بالإعدادات المقيّدة")
            .setMessage(
                "أندرويد بيمنع التطبيقات المتسطبة من ملف APK من تفعيل منع الحذف لحد ما تسمح بيها بنفسك:\n\n" +
                "1) دوس «فتح إعدادات التطبيق» تحت.\n" +
                "2) دوس ⋮ (التلات نقط) فوق على اليمين.\n" +
                "3) اختار «السماح بالإعدادات المقيّدة» وأكّد.\n" +
                "4) ارجع هنا ودوس «تفعيل منع الحذف» تاني."
            )
            .setPositiveButton("فتح إعدادات التطبيق") { _, _ ->
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", packageName, null))
                    )
                } catch (_: Exception) {
                    toast("افتح الإعدادات ← التطبيقات ← SafeGuard يدوياً")
                }
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    private fun hour(h: Int) = String.format("%02d:00", h)

    private fun addTitle(t: String) {
        container.addView(TextView(this).apply {
            text = t; textSize = 22f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 24)
        })
    }

    private fun addText(t: String) {
        container.addView(TextView(this).apply {
            text = t; textSize = 14f; setPadding(0, 8, 0, 8)
        })
    }

    private fun addButton(t: String, onClick: () -> Unit) {
        container.addView(Button(this).apply {
            text = t
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        })
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
