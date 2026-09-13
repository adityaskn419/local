package com.devtools.remoteagent

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val relayInput = EditText(this).apply { hint = "wss://your-relay.example.com"; setText(Config.relayUrl(this@MainActivity)) }
        val tokenInput = EditText(this).apply { hint = "relay token"; setText(Config.token(this@MainActivity)) }
        val deviceInput = EditText(this).apply { hint = "device id"; setText(Config.deviceId(this@MainActivity)) }

        val saveBtn = Button(this).apply { text = "Save & Start Agent" }
        val batteryBtn = Button(this).apply { text = "Exempt from battery optimization" }
        val overlayBtn = Button(this).apply { text = "Allow display over other apps (for background launch)" }
        val accBtn = Button(this).apply { text = "Enable Accessibility service (input, gestures, screen read)" }
        val autostartBtn = Button(this).apply { text = "Open Autostart / app-protection settings (OEM)" }
        val adminBtn = Button(this).apply { text = "Enable device admin (block uninstall)" }

        saveBtn.setOnClickListener {
            Config.save(this, relayInput.text.toString(), tokenInput.text.toString(), deviceInput.text.toString())
            ContextCompat.startForegroundService(this, Intent(this, ControlService::class.java))
            Toast.makeText(this, "Agent starting", Toast.LENGTH_SHORT).show()
        }

        batteryBtn.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }

        overlayBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }

        accBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find “Remote Agent” and turn it on", Toast.LENGTH_LONG).show()
        }

        autostartBtn.setOnClickListener { openAutostart() }

        adminBtn.setOnClickListener {
            val comp = ComponentName(this, DeviceAdminReceiver::class.java)
            val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
            if (dpm.isAdminActive(comp)) {
                Toast.makeText(this, "Device admin already active — uninstall is blocked", Toast.LENGTH_LONG).show()
            } else {
                val i = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Enables remote management and prevents accidental uninstall. Deactivate here anytime to remove.")
                startActivity(i)
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 40)
            addView(relayInput)
            addView(tokenInput)
            addView(deviceInput)
            addView(saveBtn)
            addView(batteryBtn)
            addView(overlayBtn)
            addView(accBtn)
            addView(autostartBtn)
            addView(adminBtn)
        }
        setContentView(layout)
    }

    /** Tries known OEM autostart / background-protection screens; falls back to app details. */
    private fun openAutostart() {
        val candidates = listOf(
            // Vivo
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            // Xiaomi / MIUI / HyperOS
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // Oppo / ColorOS
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            // Realme
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            // Samsung (battery/device care)
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            // Huawei
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        for (c in candidates) {
            try {
                startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(this, "Enable autostart / no-restriction for Remote Agent", Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) { /* try next */ }
        }
        // fallback: app details, where "battery unrestricted" also lives
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Set Battery to Unrestricted and allow background", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }
}
