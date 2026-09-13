package com.devtools.remoteagent

import android.content.Intent
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

        val relayInput = EditText(this).apply { hint = "wss://your-relay.example.com"; setText(Config.relayUrl(this@MainActivity)) }
        val tokenInput = EditText(this).apply { hint = "relay token"; setText(Config.token(this@MainActivity)) }
        val deviceInput = EditText(this).apply { hint = "device id"; setText(Config.deviceId(this@MainActivity)) }

        val saveBtn = Button(this).apply { text = "Save & Start Agent" }
        val batteryBtn = Button(this).apply { text = "Exempt from battery optimization" }
        val overlayBtn = Button(this).apply { text = "Allow display over other apps (for background launch)" }
        val accBtn = Button(this).apply { text = "Enable Accessibility service (input, gestures, screen read)" }

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
        }
        setContentView(layout)
    }
}
