package com.guaishoudejia.x4doublesysfserv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Headless rendering service: keeps a hidden GeckoView-attached surface alive in background to allow captures when screen is off.
 * Requires SYSTEM_ALERT_WINDOW to attach an invisible window.
 */
class HeadlessRenderService : Service() {
    companion object {
        const val ACTION_RENDER = "com.guaishoudejia.x4doublesysfserv.ACTION_RENDER"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_RESULT_PATH = "extra_result_path"
        private const val CHANNEL_ID = "headless_render"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var windowManager: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundIfNeeded()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ensureGecko()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RENDER) {
            val url = intent.getStringExtra(EXTRA_URL)
            if (!url.isNullOrEmpty()) {
                scope.launch {
                    val bmp = renderUrl(url)
                    val path = bmp?.let { saveBitmap(it) }
                    Log.d("HeadlessRender", "render done url=$url path=$path")
                    // Optionally broadcast result
                    val resultIntent = Intent(ACTION_RENDER).apply {
                        putExtra(EXTRA_URL, url)
                        putExtra(EXTRA_RESULT_PATH, path)
                    }
                    sendBroadcast(resultIntent)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        session?.close()
        runtime?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureGecko() {
        if (runtime != null && session != null) return
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .build()
        runtime = GeckoRuntime.create(this)
        session = GeckoSession(settings).apply { open(runtime!!) }

        // attach a minimal window to keep surface alive
        val displayMetrics = resources.displayMetrics
        val widthPx = displayMetrics.widthPixels
        val heightPx = max((widthPx / 0.6f).toInt(), 1)
        attachDummyWindow(widthPx, heightPx)
    }

    private fun attachDummyWindow(w: Int, h: Int) {
        val view = android.view.View(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            w,
            h,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.TOP }
        windowManager?.addView(view, params)
    }

    private suspend fun renderUrl(url: String): Bitmap? {
        val s = session ?: return null
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {}
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                Log.d("HeadlessRender", "page stop success=$success")
            }
        }
        s.loadUri(url)
        // wait for load
        kotlinx.coroutines.delay(400)
        val display = s.acquireDisplay()
        return capturePixels(display)
    }

    private suspend fun capturePixels(display: org.mozilla.geckoview.GeckoDisplay): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            display.capturePixels().accept({ bmp: Bitmap? ->
                if (!cont.isCompleted) cont.resume(bmp)
            }, { err: Throwable? ->
                Log.w("HeadlessRender", "capturePixels failed", err)
                if (!cont.isCompleted) cont.resume(null)
            })
        } catch (e: Exception) {
            Log.w("HeadlessRender", "capturePixels threw", e)
            if (!cont.isCompleted) cont.resume(null)
        }
    }

    private fun saveBitmap(bmp: Bitmap): String? {
        return try {
            val file = File(cacheDir, "headless_capture_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.w("HeadlessRender", "saveBitmap failed", e)
            null
        }
    }

    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "HeadlessRender", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
            val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Rendering")
                .setContentText("Headless rendering active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
            startForeground(NOTIF_ID, notif)
        }
    }
}
