package com.devtools.remoteagent

import android.content.Context
import android.content.Intent

/**
 * Device admin component. While active, the app cannot be uninstalled until the
 * user deactivates it (Settings ▸ Security ▸ Device admin apps). This same
 * component is used if you later promote the app to Device Owner via
 *   adb shell dpm set-device-owner com.devtools.remoteagent/.DeviceAdminReceiver
 * which additionally greys out Force-stop / Disable / Uninstall.
 */
class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        ServiceLauncher.ensureRunning(context)
    }
}
