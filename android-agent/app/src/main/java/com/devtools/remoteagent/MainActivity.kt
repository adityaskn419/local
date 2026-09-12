package com.devtools.remoteagent

import android.content.Intent
import android.net.Uri
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

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 40)
            addView(relayInput)
            addView(tokenInput)
            addView(deviceInput)
            addView(saveBtn)
            addView(batteryBtn)
        }
        setContentView(layout)
    }
}
