package com.devtools.remoteagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Transparent activity that asks for MediaProjection consent, then hands the
 * grant to ProjectionService. Launched from the app (button) or remotely via
 * the mp_start command (needs overlay permission to start from background).
 */
class ProjectionActivity : Activity() {
    private val REQ = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == Activity.RESULT_OK && data != null) {
            ProjectionService.pendingResultCode = resultCode
            ProjectionService.pendingResultData = data
            ContextCompat.startForegroundService(this, Intent(this, ProjectionService::class.java))
        }
        finish()
    }
}
