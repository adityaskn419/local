package com.devtools.remoteagent

import android.content.Context

object Config {
    private const val PREFS = "agent_config"

    // Change these to your deployed relay before building, or set them from MainActivity.
    const val DEFAULT_RELAY_URL = "wss://your-relay-host.example.com"
    const val DEFAULT_DEVICE_ID = "default"

    fun relayUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("relay_url", DEFAULT_RELAY_URL)!!

    fun token(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", "") ?: ""

    fun deviceId(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("device_id", DEFAULT_DEVICE_ID)!!

    fun save(ctx: Context, relayUrl: String, token: String, deviceId: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("relay_url", relayUrl)
            .putString("token", token)
            .putString("device_id", deviceId)
            .apply()
    }
}
