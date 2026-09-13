package com.devtools.remoteagent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps ControlService alive through as many disruptions as possible without root:
 *  - START_STICKY + foreground service (base)
 *  - WorkManager periodic watchdog (every 15 min) — survives most kills
 *  - AlarmManager one-shot restart on task-removal / destroy
 *  - Boot / package-replaced receivers
 *  - battery-optimization exemption (requested in the app) so background
 *    starts are permitted on Android 12+
 */
object ServiceLauncher {
    private const val WATCHDOG = "agent_keepalive"
    private const val RESTART_REQ = 4711

    fun ensureRunning(ctx: Context) {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ControlService::class.java))
        } catch (_: Exception) {
            // background-start may be refused if battery optimization isn't exempt;
            // the watchdog + alarm will retry.
        }
    }

    fun scheduleWatchdog(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WATCHDOG, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun scheduleRestart(ctx: Context, delayMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, RESTART_REQ, Intent(ctx, RestartReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + delayMs
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class KeepAliveWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        ServiceLauncher.ensureRunning(applicationContext)
        return Result.success()
    }
}

class RestartReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ServiceLauncher.ensureRunning(context)
    }
}
