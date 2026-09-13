package com.devtools.remoteagent

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes actions using only regular app-level Android APIs (no root, no ADB).
 * Actions that the OS blocks for a normal app return ok=false with a clear reason
 * rather than pretending to work.
 */
class CommandExecutor(private val ctx: Context) {

    companion object {
        private var ringtone: Ringtone? = null
        private var torchId: String? = null
    }

    private val audio get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun execute(action: String, args: JSONObject): JSONObject {
        return try {
            when (action) {
                // ---- sound ----
                "volume_up" -> { adjust(AudioManager.ADJUST_RAISE); ok() }
                "volume_down" -> { adjust(AudioManager.ADJUST_LOWER); ok() }
                "volume_mute" -> { adjust(AudioManager.ADJUST_MUTE); ok() }
                "set_volume" -> setVolume(args.optInt("level", 50))
                "ringer_normal" -> setRinger(AudioManager.RINGER_MODE_NORMAL)
                "ringer_vibrate" -> setRinger(AudioManager.RINGER_MODE_VIBRATE)
                "ringer_silent" -> setRinger(AudioManager.RINGER_MODE_SILENT)
                // ---- media transport ----
                "media_play_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "media_next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                "media_prev" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "media_stop" -> mediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
                // ---- device ----
                "battery_status" -> batteryStatus()
                "device_info" -> deviceInfo()
                "get_state" -> getState()
                "vibrate" -> vibrate(args.optLong("ms", 500))
                "flashlight_on" -> torch(true)
                "flashlight_off" -> torch(false)
                "ring_device" -> ringDevice(true)
                "ring_stop" -> ringDevice(false)
                "notify" -> notifyDevice(args.optString("title", "Message"), args.optString("text", ""))
                // ---- apps ----
                "list_apps" -> listApps()
                "launch_app" -> launchApp(args.optString("package"))
                "kill_app" -> killApp(args.optString("package"))
                "open_url" -> openUrl(args.optString("url"))
                // ---- accessibility: navigation ----
                "acc_back","acc_home","acc_recents","acc_notifications","acc_quick_settings",
                "acc_power_dialog","acc_split_screen","acc_lock","acc_screenshot","acc_headset_hook",
                "acc_a11y_button","acc_all_apps","acc_dismiss_shade","acc_menu","acc_media_play_pause",
                "acc_dpad_up","acc_dpad_down","acc_dpad_left","acc_dpad_right","acc_dpad_center" ->
                    accGlobal(action.removePrefix("acc_"))
                "acc_global" -> accGlobal(args.optString("name"))
                // ---- accessibility: gestures ----
                "acc_tap" -> accGesture { it.tap(args.optInt("x"), args.optInt("y")) }
                "acc_double_tap" -> accGesture { it.doubleTap(args.optInt("x"), args.optInt("y")) }
                "acc_long_press" -> accGesture { it.longPress(args.optInt("x"), args.optInt("y"), args.optLong("ms", 600)) }
                "acc_swipe" -> accGesture { it.swipe(args.optInt("x1"), args.optInt("y1"), args.optInt("x2"), args.optInt("y2"), args.optLong("ms", 300)) }
                // ---- accessibility: UI automation ----
                "acc_click_text" -> accGesture { it.clickByText(args.optString("text")) }
                "acc_click_desc" -> accGesture { it.clickByDesc(args.optString("desc")) }
                "acc_click_id" -> accGesture { it.clickById(args.optString("id")) }
                "acc_set_text" -> accGesture { it.setText(args.optString("text")) }
                "acc_scroll" -> accGesture { it.scroll(args.optString("dir", "forward") != "backward") }
                // ---- accessibility: reads ----
                "acc_read_screen" -> accRead { ok { put("screen", it.readScreen()) } }
                "acc_current_app" -> accRead { ok { put("app", it.currentApp()) } }
                "acc_notifications" -> accRead { ok { put("notifications", it.notifications()) } }
                "acc_capture" -> accRead { it.captureResult(args.optInt("max", 720), args.optInt("q", 40)) }
                // ---- accessibility: combos ----
                "acc_force_stop" -> forceStopViaUi(args.optString("package"))
                "acc_open_app_settings" -> openAppSettings(args.optString("package"))
                // ---- restricted (documented) ----
                "wifi_toggle" -> fail("Wi-Fi toggle needs Shizuku (svc wifi) or use acc_quick_settings + tap the tile.")
                "screen_lock" -> accGlobal("lock")
                else -> fail("unknown action: $action")
            }
        } catch (e: Exception) {
            fail(e.message ?: "error")
        }
    }

    private fun ok(extra: JSONObject.() -> Unit = {}) = JSONObject().put("ok", true).apply(extra)
    private fun fail(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private fun adjust(dir: Int) = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)

    private fun setVolume(level: Int): JSONObject {
        val clamped = level.coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, clamped * max / 100, AudioManager.FLAG_SHOW_UI)
        return ok { put("level", clamped) }
    }

    private fun setRinger(mode: Int): JSONObject {
        if (mode == AudioManager.RINGER_MODE_SILENT) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                return fail("Silent mode needs 'Do Not Disturb access' — grant it in the app once.")
            }
        }
        audio.ringerMode = mode
        return ok()
    }

    private fun mediaKey(code: Int): JSONObject {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ok()
    }

    private fun batteryStatus(): JSONObject {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return ok {
            put("percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("charging", bm.isCharging)
        }
    }

    private fun getState(): JSONObject {
        val o = ok()
        // battery
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        o.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        o.put("charging", bm.isCharging)
        // screen
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        o.put("screenOn", pm.isInteractive)
        // audio
        val am = audio
        o.put("ringer", when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        })
        val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        o.put("volumeMusic", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxMusic)
        // foreground app (needs accessibility)
        o.put("foreground", AccessibilityControlService.instance?.currentApp() ?: "")
        // network
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        o.put("network", when {
            caps == null -> "offline"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        })
        // storage (internal data partition)
        try {
            val st = android.os.StatFs(ctx.filesDir.absolutePath)
            o.put("storageFreeMB", st.availableBytes / (1024 * 1024))
            o.put("storageTotalMB", st.totalBytes / (1024 * 1024))
        } catch (_: Exception) {}
        // ram
        try {
            val amgr = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo(); amgr.getMemoryInfo(mi)
            o.put("ramAvailMB", mi.availMem / (1024 * 1024))
            o.put("ramTotalMB", mi.totalMem / (1024 * 1024))
            o.put("lowMemory", mi.lowMemory)
        } catch (_: Exception) {}
        // identity
        o.put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        o.put("android", Build.VERSION.RELEASE)
        o.put("sdk", Build.VERSION.SDK_INT)
        o.put("time", System.currentTimeMillis())
        return o
    }

    private fun deviceInfo(): JSONObject {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return ok {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        }
    }

    private fun vibrate(ms: Long): JSONObject {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(50, 5000), VibrationEffect.DEFAULT_AMPLITUDE))
        return ok()
    }

    private fun torch(on: Boolean): JSONObject {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (torchId == null) {
            torchId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }
        val id = torchId ?: return fail("no flash on this device")
        cm.setTorchMode(id, on)
        return ok()
    }

    private fun ringDevice(on: Boolean): JSONObject {
        if (on) {
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone?.stop()
            ringtone = RingtoneManager.getRingtone(ctx, uri).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
            vibrate(1500)
        } else {
            ringtone?.stop(); ringtone = null
        }
        return ok()
    }

    private fun notifyDevice(title: String, text: String): JSONObject {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = "agent_messages"
        if (nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(ch, "Messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = androidx.core.app.NotificationCompat.Builder(ctx, ch)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true).build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        return ok()
    }

    private fun listApps(): JSONObject {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = JSONArray()
        pm.queryIntentActivities(main, 0)
            .map { it.activityInfo }
            .distinctBy { it.packageName }
            .map { Pair(it.loadLabel(pm).toString(), it.packageName) }
            .sortedBy { it.first.lowercase() }
            .forEach { (label, pkg) ->
                apps.put(JSONObject().put("name", label).put("package", pkg))
            }
        return ok { put("apps", apps) }
    }

    private fun launchApp(pkg: String): JSONObject {
        if (pkg.isBlank()) return fail("missing package")
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return fail("not installed / no launcher: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return ok()
    }

    private fun killApp(pkg: String): JSONObject {
        if (pkg.isBlank()) return fail("missing package")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(pkg)
        return ok { put("note", "stops background processes only; a foreground app can't be force-stopped without ADB") }
    }

    // ---- accessibility helpers ----
    private val a11yHint = "Enable the Accessibility service for Remote Agent (Settings ▸ Accessibility ▸ Remote Agent)."

    private fun accGlobal(name: String): JSONObject {
        val svc = AccessibilityControlService.instance ?: return fail(a11yHint)
        return if (svc.global(name)) ok() else fail("global action failed or unsupported: $name")
    }

    private fun accGesture(block: (AccessibilityControlService) -> Boolean): JSONObject {
        val svc = AccessibilityControlService.instance ?: return fail(a11yHint)
        return if (block(svc)) ok() else fail("gesture/action failed (target not found or dispatch rejected)")
    }

    private fun accRead(block: (AccessibilityControlService) -> JSONObject): JSONObject {
        val svc = AccessibilityControlService.instance ?: return fail(a11yHint)
        return block(svc)
    }

    private fun forceStopViaUi(pkg: String): JSONObject {
        if (pkg.isBlank()) return fail("missing package")
        openAppSettings(pkg)
        val svc = AccessibilityControlService.instance ?: return fail(a11yHint + " (opened App Info; enable service to auto-tap)")
        Thread.sleep(1200)
        val stopped = svc.clickByText("Force stop") || svc.clickByText("Force Stop")
        if (!stopped) return fail("opened App Info but couldn't find the Force stop button (label varies by OEM)")
        Thread.sleep(700)
        svc.clickByText("OK"); svc.clickByText("Force stop")
        return ok { put("note", "attempted force-stop via App Info UI") }
    }

    private fun openAppSettings(pkg: String): JSONObject {
        if (pkg.isBlank()) return fail("missing package")
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return ok()
    }

    private fun openUrl(url: String): JSONObject {
        if (url.isBlank()) return fail("missing url")
        val fixed = if (url.startsWith("http")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fixed)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return ok()
    }
}
