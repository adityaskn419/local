package com.devtools.remoteagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        executor = CommandExecutor(this)
        client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        startForeground(1, buildNotification("Connecting..."))
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun connect() {
        // Refresh from the stable config endpoint first (blocking is fine here —
        // we're already off the main thread inside the service, and this happens
        // at most once per reconnect attempt, not per message).
        Thread {
            ConfigFetcher.refresh(this)
            connectWithCurrentConfig()
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
                updateNotification("Disconnected — retrying")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updateNotification("Disconnected — retrying")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        handler.postDelayed({ connect() }, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
    }

    private fun handleMessage(text: String) {
        val msg = JSONObject(text)
        if (msg.optString("type") != "command") return
        val action = msg.optString("action")
        val args = msg.optJSONObject("args") ?: JSONObject()
        val result = executor.execute(action, args)
        val response = JSONObject()
            .put("type", "result")
            .put("requestId", msg.optString("requestId"))
            .put("action", action)
        for (k in result.keys()) response.put(k, result.get(k))
        ws?.send(response.toString())
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
        ws?.close(1000, "service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
