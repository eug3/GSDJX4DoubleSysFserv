package com.guaishoudejia.x4doublesysfserv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.os.PowerManager
import android.view.KeyEvent
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class GeckoActivity : ComponentActivity() {
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private val client = OkHttpClient()
    private val backendUrl = "http://10.0.2.2:18080" // Emulator: 10.0.2.2; Real device: replace with PC LAN IP
    private val backendEnabled = false // 关闭远端，使用本地 GeckoView 抓帧
    private var geckoView: GeckoView? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank {
            DEFAULT_URL
        }

        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .build()

        runtime = GeckoRuntime.create(this)
        session = GeckoSession(settings).apply {
            open(runtime!!)
        }

        setContent {
            var currentUrl by remember { mutableStateOf(targetUrl) }
            var isEbookMode by remember { mutableStateOf(false) }
            var renderedImage by remember { mutableStateOf<Bitmap?>(null) }
            var isLoading by remember { mutableStateOf(false) }
            var lastStatus by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            val density = androidx.compose.ui.platform.LocalDensity.current
            val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
            // 固定比例 480:800，宽度拉满设备宽度，高度按比例推算
            val aspect = 480f / 800f
            val wPx = metrics.widthPixels
            val hPx = (wPx / aspect).toInt().coerceAtLeast(1)
            val wDp = with(density) { wPx.toDp() }
            val hDp = with(density) { hPx.toDp() }

            fun dispatchArrow(keyCode: Int) {
                val view = geckoView ?: return
                view.requestFocus()
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }

            suspend fun arrowPagerAndRefresh(keyCode: Int) {
                dispatchArrow(keyCode)
                // WeRead supports ArrowLeft/ArrowRight for prev/next.
                // Give the page a moment to update URL/state before requesting backend render.
                // (Avoid coordinate-based actions; this is semantic key navigation.)
                delay(200)  // 缩短等待，提升响应
                val bmp = fetchRenderedImage(currentUrl)
                if (bmp == null) {
                    lastStatus = "翻页后未获取到图片，请检查后端服务/网络"
                } else {
                    lastStatus = ""
                }
                renderedImage = bmp
            }

            // Update currentUrl when navigation happens
            DisposableEffect(session) {
                val delegate = object : GeckoSession.ProgressDelegate {
                    override fun onPageStart(session: GeckoSession, url: String) {
                        currentUrl = url
                    }
                    override fun onPageStop(session: GeckoSession, success: Boolean) {
                        Log.d("GeckoActivity", "Page loaded: $currentUrl success=$success")
                    }
                }
                session?.progressDelegate = delegate
                onDispose { session?.progressDelegate = null }
            }

            DisposableEffect(Unit) {
                onDispose {
                    releaseWakeLock()
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier
                        .size(wDp, hDp)
                        .align(Alignment.Center),
                    factory = { context ->
                        org.mozilla.geckoview.GeckoView(context).apply {
                            this@GeckoActivity.session?.let { setSession(it) }
                            geckoView = this
                        }
                    }
                )

                // "电子书阅读" Button
                if (currentUrl.contains("weread.qq.com/web/reader/") && !isEbookMode) {
                    Button(
                        onClick = {
                            isEbookMode = true
                            RemoteControlService.start(
                                this@GeckoActivity,
                                RemoteControlClient.EMULATOR_HOST,
                                RemoteControlClient.DEFAULT_DEVICE_ID
                            )
                            acquireWakeLock()
                            scope.launch {
                                isLoading = true
                                val bmp = fetchRenderedImage(currentUrl)
                                if (bmp == null) {
                                    lastStatus = "未获取到图片，请检查后端服务/网络"
                                } else {
                                    lastStatus = ""
                                }
                                renderedImage = bmp
                                bmp?.let { RemoteControlService.sendImage(this@GeckoActivity, bitmapToBase64(it)) }
                                isLoading = false
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                    ) {
                        Text("电子书阅读")
                    }
                }

                // E-book Mode Overlay
                if (isEbookMode) {
                    DisposableEffect(isEbookMode) {
                        if (!isEbookMode) return@DisposableEffect onDispose { }
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                if (intent?.action != RemoteControlService.ACTION_COMMAND) return
                                when (intent.getStringExtra(RemoteControlService.EXTRA_ACTION)) {
                                    "prev" -> scope.launch {
                                        RemoteControlService.sendStatus(this@GeckoActivity, "busy", "prev")
                                        isLoading = true
                                        arrowPagerAndRefresh(KeyEvent.KEYCODE_DPAD_LEFT)
                                        renderedImage?.let { bmp ->
                                            RemoteControlService.sendImage(this@GeckoActivity, bitmapToBase64(bmp))
                                        }
                                        RemoteControlService.sendStatus(this@GeckoActivity, "ok", "prev done")
                                        isLoading = false
                                    }
                                    "next" -> scope.launch {
                                        RemoteControlService.sendStatus(this@GeckoActivity, "busy", "next")
                                        isLoading = true
                                        arrowPagerAndRefresh(KeyEvent.KEYCODE_DPAD_RIGHT)
                                        renderedImage?.let { bmp ->
                                            RemoteControlService.sendImage(this@GeckoActivity, bitmapToBase64(bmp))
                                        }
                                        RemoteControlService.sendStatus(this@GeckoActivity, "ok", "next done")
                                        isLoading = false
                                    }
                                    "capture" -> scope.launch {
                                        RemoteControlService.sendStatus(this@GeckoActivity, "busy", "capture")
                                        isLoading = true
                                        val bmp = fetchRenderedImage(currentUrl)
                                        if (bmp != null) {
                                            renderedImage = bmp
                                            lastStatus = ""
                                            RemoteControlService.sendImage(this@GeckoActivity, bitmapToBase64(bmp))
                                            RemoteControlService.sendStatus(this@GeckoActivity, "ok", "capture done")
                                        } else {
                                            lastStatus = "重试仍未获取到图片"
                                            RemoteControlService.sendStatus(this@GeckoActivity, "failed", "capture null")
                                        }
                                        isLoading = false
                                    }
                                }
                            }
                        }
                        val filter = IntentFilter(RemoteControlService.ACTION_COMMAND)
                        registerReceiver(receiver, filter)
                        onDispose { unregisterReceiver(receiver) }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        } else {
                            renderedImage?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Rendered Page",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } ?: run {
                                Text(
                                    text = lastStatus.ifBlank { "未收到后端位图，保持白板" },
                                    modifier = Modifier.align(Alignment.Center),
                                    color = Color.Black
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Button(onClick = {
                                    scope.launch {
                                        isLoading = true
                                        arrowPagerAndRefresh(KeyEvent.KEYCODE_DPAD_LEFT)
                                        isLoading = false
                                    }
                                }) {
                                    Text("上一页")
                                }
                                Button(onClick = {
                                    isEbookMode = false
                                    RemoteControlService.stop(this@GeckoActivity)
                                    releaseWakeLock()
                                }) {
                                    Text("退出")
                                }
                                Button(onClick = {
                                    scope.launch {
                                        isLoading = true
                                        arrowPagerAndRefresh(KeyEvent.KEYCODE_DPAD_RIGHT)
                                        isLoading = false
                                    }
                                }) {
                                    Text("下一页")
                                }
                                Button(onClick = {
                                    scope.launch {
                                        isLoading = true
                                        val bmp = fetchRenderedImage(currentUrl)
                                        if (bmp == null) lastStatus = "重试仍未获取到图片" else lastStatus = ""
                                        renderedImage = bmp
                                        isLoading = false
                                    }
                                }) {
                                    Text("重试截图")
                                }
                            }
                        }
                    }
                }
            }
        }

        session?.loadUri(targetUrl)
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private suspend fun fetchRenderedImage(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (!backendEnabled) {
                
                val captured = captureLocalFrame()
                return@withContext captured?.let { ditherTo1Bit(it, it.width, it.height) }
            }

            val request = Request.Builder()
                .url("$backendUrl/render?url=${url}")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val inputStream: InputStream = response.body?.byteStream() ?: return@withContext null
                BitmapFactory.decodeStream(inputStream)
            } else {
                Log.w("GeckoActivity", "Render request failed code=${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("GeckoActivity", "Error fetching image", e)
            null
        }
    }

    private suspend fun captureLocalFrame(): Bitmap? = withContext(Dispatchers.Main) {
        val view = geckoView
        if (view == null) {
            Log.w("GeckoActivity", "GeckoView null, cannot capture")
            return@withContext null
        }
        var attempts = 0
        while ((view.width == 0 || view.height == 0) && attempts < 10) {
            attempts++
            delay(80)
        }
        if (view.width == 0 || view.height == 0) {
            Log.w("GeckoActivity", "GeckoView size still 0, cannot capture")
            return@withContext null
        }
        // 等待页面渲染完成（视口变化后需要更多时间）
        delay(300)
        // 优先用 GeckoView.capturePixels 获取 Surface 内容；失败再降级为 view.draw（可能为空白）
        val fromPixels = capturePixelsSuspend(view)
        if (fromPixels != null) {
            Log.d("GeckoActivity", "Captured via pixels ${view.width}x${view.height}")
            return@withContext fromPixels
        }

        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)
        Log.d("GeckoActivity", "Captured via draw ${view.width}x${view.height}")
        bmp
    }

    private suspend fun capturePixelsSuspend(view: GeckoView): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            view.capturePixels().accept({ bmp: Bitmap? ->
                if (!cont.isCompleted) cont.resume(bmp)
            }, { err: Throwable? ->
                Log.w("GeckoActivity", "capturePixels failed", err)
                if (!cont.isCompleted) cont.resume(null)
            })
        } catch (e: Exception) {
            Log.w("GeckoActivity", "capturePixels threw", e)
            if (!cont.isCompleted) cont.resume(null)
        }
    }

    private fun ditherTo1Bit(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        // 直接缩放到目标尺寸，然后灰度 + 自适应阈值二值化（无模糊、无超采），保证白面积略多于黑
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        val gray = FloatArray(targetW * targetH)
        val row = IntArray(targetW)
        for (y in 0 until targetH) {
            scaled.getPixels(row, 0, targetW, 0, y, targetW, 1)
            val base = y * targetW
            for (x in 0 until targetW) {
                val c = row[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lin = 0.299f * r + 0.587f * g + 0.114f * b
                // 可选轻度伽马（保留对比度），如需更锐利可改为 1.0（关闭）
                gray[base + x] = ((lin / 255f).toDouble().pow(1.0 / 2.2)).toFloat() * 255f
            }
        }

        // 直方图求自适应阈值：目标 30% 白
        val hist = IntArray(256)
        for (v in gray) {
            val idx = v.roundToInt().coerceIn(0, 255)
            hist[idx]++
        }
        val total = gray.size
        val targetWhite = (total * 0.30f).roundToInt()
        var cumulative = 0
        var threshold = 128
        for (i in 0..255) {
            cumulative += hist[i]
            if (cumulative >= targetWhite) { threshold = i; break }
        }

        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        var whiteCount = 0
        var blackCount = 0
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val g = gray[y * targetW + x]
                val color = if (g > threshold) AndroidColor.WHITE else AndroidColor.BLACK
                if (color == AndroidColor.WHITE) whiteCount++ else blackCount++
                out.setPixel(x, y, color)
            }
        }
        if (whiteCount <= blackCount) {
            for (y in 0 until targetH) {
                for (x in 0 until targetW) {
                    val c = out.getPixel(x, y)
                    out.setPixel(x, y, if (c == AndroidColor.WHITE) AndroidColor.BLACK else AndroidColor.WHITE)
                }
            }
        }
        return out
    }

    private fun createPlaceholderBitmap(
        width: Int,
        height: Int,
        title: String,
        subtitle: String,
        footer: String,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(AndroidColor.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.BLACK
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.BLACK
            textSize = 24f
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.GRAY
            textSize = 20f
        }

        canvas.drawText(title, 24f, 80f, titlePaint)
        val subtitleLines = subtitle.split("\n")
        subtitleLines.forEachIndexed { idx, line ->
            canvas.drawText(line, 24f, 140f + idx * 34f, bodyPaint)
        }
        canvas.drawText("URL: $footer", 24f, height - 40f, footerPaint)
        return bmp
    }

    override fun onStart() {
        super.onStart()
        session?.setActive(true)
    }

    override fun onStop() {
        session?.setActive(false)
        releaseWakeLock()
        super.onStop()
    }

    override fun onDestroy() {
        try {
            session?.close()
            runtime?.shutdown()
        } catch (e: Exception) {
            Log.w("Gecko", "Error shutting down", e)
        }
        RemoteControlService.stop(this)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "x4:remoteControl").apply {
            setReferenceCounted(false)
            try { acquire(30 * 60 * 1000L) } catch (_: Exception) {}
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val DEFAULT_URL = "https://weread.qq.com/"

        fun launch(context: Context, url: String) {
            val intent = Intent(context, GeckoActivity::class.java)
            intent.putExtra(EXTRA_URL, url)
            context.startActivity(intent)
        }
    }
}
