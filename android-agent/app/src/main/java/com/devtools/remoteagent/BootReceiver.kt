package com.devtools.remoteagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the agent after reboot, quick-boot, and app updates, and re-arms
 * the watchdog.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ServiceLauncher.scheduleWatchdog(context)
                ServiceLauncher.ensureRunning(context)
            }
        }
    }
}
