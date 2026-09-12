package com.devtools.remoteagent

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

/**
 * Executes actions using only regular app-level Android APIs (no root, no ADB shell).
 * Some actions (killing arbitrary third-party apps, disabling Wi-Fi on Android 10+)
 * are restricted by the OS for any regular app — those return an explanatory error
 * instead of silently failing.
 */
class CommandExecutor(private val ctx: Context) {

    fun execute(action: String, args: JSONObject): JSONObject {
        val result = JSONObject()
        try {
            when (action) {
                "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
                "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
                "volume_mute" -> adjustVolume(AudioManager.ADJUST_MUTE)
                "battery_status" -> return batteryStatus()
                "wifi_toggle" -> return toggleWifi()
                "launch_app" -> return launchApp(args.optString("package"))
                "kill_app" -> return killApp(args.optString("package"))
                "screen_lock" -> return unsupported("Locking the screen requires Device Admin / Accessibility setup, not wired up in this scaffold")
                else -> return unsupported("unknown action: $action")
            }
            result.put("ok", true)
        } catch (e: Exception) {
            result.put("ok", false)
            result.put("error", e.message)
        }
        return result
    }

    private fun adjustVolume(direction: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun batteryStatus(): JSONObject {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return JSONObject().put("ok", true).put("percent", pct).put("charging", charging)
    }

    private fun toggleWifi(): JSONObject {
        // Android 10+ blocks apps from programmatically enabling/disabling Wi-Fi
        // unless the app is a device/profile owner. This is a hard OS restriction,
        // not something we can route around for a regular installed app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return unsupported("Wi-Fi toggling from a regular app is blocked on Android 10+. Use adb shell svc wifi enable/disable over a wireless-debugging tunnel instead.")
        }
        @Suppress("DEPRECATION")
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wm.isWifiEnabled = !wm.isWifiEnabled
        return JSONObject().put("ok", true)
    }

    private fun launchApp(pkg: String): JSONObject {
        if (pkg.isBlank()) return unsupported("missing package")
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return unsupported("package not found or has no launcher activity: $pkg")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return JSONObject().put("ok", true)
    }

    private fun killApp(pkg: String): JSONObject {
        if (pkg.isBlank()) return unsupported("missing package")
        // A regular app can only kill its OWN background processes via
        // ActivityManager.killBackgroundProcesses. Force-stopping arbitrary
        // third-party apps requires system/signature permissions this app
        // does not and should not have without root.
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(pkg)
        return JSONObject().put("ok", true)
            .put("note", "killBackgroundProcesses only stops background processes; it cannot force-stop a foreground app without system permissions")
    }

    private fun unsupported(msg: String) = JSONObject().put("ok", false).put("error", msg)
}
