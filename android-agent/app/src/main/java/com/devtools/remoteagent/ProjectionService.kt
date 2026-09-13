package com.devtools.remoteagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

/**
 * Higher-FPS screen capture via MediaProjection. Keeps the latest frame ready
 * as a JPEG; the dashboard polls mp_frame at whatever rate it wants (that poll
 * rate is the effective FPS). Requires one-time on-device consent (screen-cast
 * indicator is shown by Android and cannot be hidden without root/Shizuku).
 */
class ProjectionService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var latestJpegB64: String? = null
        @Volatile var fullW = 0
        @Volatile var fullH = 0

        var pendingResultCode = 0
        var pendingResultData: Intent? = null

        var maxDim = 720
        var quality = 45
        private const val MIN_ENCODE_INTERVAL_MS = 60L // cap encode ~16 fps
    }

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastEncode = 0L

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotif()
        val code = pendingResultCode
        val data = pendingResultData
        if (code == 0 || data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        if (projection == null) { stopSelf(); return START_NOT_STICKY }

        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { teardown() }
        }, Handler(mainLooper))

        val dm = resources.displayMetrics
        val sw = dm.widthPixels; val sh = dm.heightPixels
        fullW = sw; fullH = sh
        val scale = maxDim.toFloat() / maxOf(sw, sh).coerceAtLeast(1)
        val cw = (sw * (if (scale < 1) scale else 1f)).toInt().coerceAtLeast(1)
        val ch = (sh * (if (scale < 1) scale else 1f)).toInt().coerceAtLeast(1)

        thread = HandlerThread("cap").apply { start() }
        handler = Handler(thread!!.looper)
        reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r -> onFrame(r, cw, ch) }, handler)

        vdisplay = projection!!.createVirtualDisplay(
            "agent-cap", cw, ch, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        running = true
        return START_NOT_STICKY
    }

    private fun onFrame(r: ImageReader, w: Int, h: Int) {
        val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastEncode < MIN_ENCODE_INTERVAL_MS) return
            lastEncode = now
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            val cropped = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 90), baos)
            latestJpegB64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            if (cropped !== bmp) cropped.recycle()
            bmp.recycle()
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun teardown() {
        running = false; latestJpegB64 = null
        try { vdisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        vdisplay = null; reader = null; projection = null; thread = null; handler = null
    }

    override fun onDestroy() { teardown(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotif() {
        val ch = "agent_projection"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(NotificationChannel(ch, "HD live view", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, ch)
            .setContentTitle("HD live view active")
            .setContentText("Screen is being captured")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(2, n)
        } catch (_: Exception) { try { startForeground(2, n) } catch (_: Exception) {} }
    }
}
