package com.devtools.remoteagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ControlService : Service() {

    private lateinit var client: OkHttpClient
    private var ws: WebSocket? = null
    private var retryDelayMs = 2000L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var executor: CommandExecutor
    private var connectivity: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        executor = CommandExecutor(this)
        client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        goForeground()
        ServiceLauncher.scheduleWatchdog(this)
        ServiceLauncher.armPeriodicAlarm(this)
        registerNetworkCallback()
        connect()
    }

    private fun goForeground() {
        val n = buildNotification("Connecting...")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, n)
            }
        } catch (_: Exception) {
            try { startForeground(1, n) } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground and connection on every (re)start / watchdog kick.
        goForeground()
        ServiceLauncher.armPeriodicAlarm(this)
        if (ws == null) connect()
        return START_STICKY
    }

    private fun registerNetworkCallback() {
        connectivity = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // network came back — reconnect immediately instead of waiting for backoff
                handler.post { if (ws == null) connect() }
            }
        }
        netCallback = cb
        try {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                cb
            )
        } catch (_: Exception) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // user swiped the app from recents — many OEMs kill us; schedule a restart
        ServiceLauncher.scheduleRestart(this, 1500)
        super.onTaskRemoved(rootIntent)
    }

    @Volatile private var connecting = false

    private fun connect() {
        if (connecting || ws != null) return
        connecting = true
        // Refresh from the stable config endpoint first (blocking is fine here —
        // we're already off the main thread inside the service, and this happens
        // at most once per reconnect attempt, not per message).
        Thread {
            try {
                ConfigFetcher.refresh(this)
                connectWithCurrentConfig()
            } finally {
                connecting = false
            }
        }.start()
    }

    private fun connectWithCurrentConfig() {
        val url = Config.relayUrl(this)
        val token = Config.token(this)
        val deviceId = Config.deviceId(this)

        if (url.isBlank() || token.isBlank()) {
            updateNotification("No config yet — waiting")
            scheduleReconnect()
            return
        }

        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryDelayMs = 2000L
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("role", "agent")
                    .put("token", token)
                    .put("deviceId", deviceId)
                webSocket.send(hello.toString())
                updateNotification("Connected to relay")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ws = null
                updateNotification("Disconnected — retrying")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws = null
                updateNotification("Disconnected — retrying")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        handler.postDelayed({ connect() }, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
    }

    // Run commands off the WebSocket thread so a slow command (screenshot,
    // force-stop waits) never stalls ping/pong or blocks other commands.
    private val cmdPool = java.util.concurrent.Executors.newFixedThreadPool(3)

    private fun handleMessage(text: String) {
        cmdPool.execute {
            try {
                val msg = JSONObject(text)
                if (msg.optString("type") != "command") return@execute
                val action = msg.optString("action")
                val args = msg.optJSONObject("args") ?: JSONObject()
                val result = executor.execute(action, args)
                val response = JSONObject()
                    .put("type", "result")
                    .put("requestId", msg.optString("requestId"))
                    .put("action", action)
                for (k in result.keys()) response.put(k, result.get(k))
                ws?.send(response.toString())
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "agent_status"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Agent status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Remote Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification(text))
    }

    override fun onDestroy() {
        try { netCallback?.let { connectivity?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        ws?.close(1000, "service destroyed"); ws = null
        // if we're being torn down unexpectedly, come back
        ServiceLauncher.scheduleRestart(this, 1000)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
