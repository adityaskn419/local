package com.devtools.remoteagent

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches the *current* relay URL/token/device id from one fixed, never-changing
 * endpoint (a raw file URL on your own GitHub repo). That lets you swap the actual
 * tunnel/relay provider (Cloudflare, ngrok, your own VM, Tailscale...) at any time
 * by just editing the file's contents — the APK never needs to be rebuilt or
 * reinstalled for that.
 */
object ConfigFetcher {

    // The ONE thing baked into the app at build time. This URL itself should never
    // need to change — only the JSON content behind it does.
    // Example: https://raw.githubusercontent.com/<you>/<repo>/main/config/relay-config.json
    const val CONFIG_ENDPOINT = "https://raw.githubusercontent.com/adityaskn419/local/main/config/relay-config.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Returns true if it fetched and cached fresh config, false if it fell back to cache/defaults. */
    fun refresh(ctx: Context): Boolean {
        return try {
            val request = Request.Builder()
                .url(CONFIG_ENDPOINT)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body?.string() ?: return false
                val json = JSONObject(body)
                val relayUrl = json.optString("relay_url")
                val token = json.optString("token")
                val deviceId = json.optString("device_id", Config.deviceId(ctx))
                if (relayUrl.isBlank() || token.isBlank()) return false
                Config.save(ctx, relayUrl, token, deviceId)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
