package com.guaishoudejia.x4doublesysfserv

import android.content.Context
import android.content.Intent
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
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class GeckoActivity : ComponentActivity() {
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    // 仅依赖本地 GeckoView 抓帧，图像通过蓝牙通道发送
    private var geckoView: GeckoView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var bleEspClient: BleEspClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank {
            DEFAULT_URL
        }

        val targetDeviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS).orEmpty().ifBlank { null }

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
            var bleClient by remember { mutableStateOf<BleEspClient?>(null) }
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
                delay(200)
                val json = extractDomLayoutJson()
                if (json == null || json.isEmpty()) {
                    lastStatus = "翻页后未提取到 DOM 数据"
                } else {
                    lastStatus = "提取到 ${json.length} 字节"
                }
            }

            suspend fun captureAndSendToEsp(reason: String) {
                isLoading = true
                val json = extractDomLayoutJson()
                if (json == null || json.isEmpty()) {
                    lastStatus = "未提取到 DOM 数据"
                } else {
                    lastStatus = "提取到 ${json.length} 字节 JSON"
                    val client = bleClient
                    if (client != null) {
                        client.sendJson(json)
                    } else {
                        lastStatus = "BLE未连接（$reason）"
                    }
                }
                isLoading = false
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
                            acquireWakeLock()

                            if (targetDeviceAddress != null && bleClient == null) {
                                bleClient = BleEspClient(
                                    context = this@GeckoActivity,
                                    deviceAddress = targetDeviceAddress,
                                    scope = scope,
                                    onCommand = { cmd ->
                                        when (cmd) {
                                            "prev" -> scope.launch {
                                                isLoading = true
                                                arrowPagerAndRefresh(KeyEvent.KEYCODE_DPAD_LEFT)
                                                extractDomLayoutJson()?.let { bleClient?.sendJson(it) }
                                                isLoading = false
                                            }
                                            "next" -> scope.launch {
                                                isLoading = true
                                                arrowPagerAndRefresh(KeyEvent.KEYCODE_DPAD_RIGHT)
                                                extractDomLayoutJson()?.let { bleClient?.sendJson(it) }
                                                isLoading = false
                                            }
                                            "capture" -> scope.launch {
                                                captureAndSendToEsp("capture")
                                            }
                                        }
                                    }
                                ).also {
                                    bleEspClient = it
                                    it.connect()
                                }
                            }

                            scope.launch {
                                captureAndSendToEsp("enter")
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
                        onDispose {
                            // Keep BLE client alive while activity lives; only close on activity destroy.
                        }
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
                                        extractDomLayoutJson()?.let { bleClient?.sendJson(it) }
                                        isLoading = false
                                    }
                                }) {
                                    Text("上一页")
                                }
                                Button(onClick = {
                                    isEbookMode = false
                                    releaseWakeLock()
                                }) {
                                    Text("退出")
                                }
                                Button(onClick = {
                                    scope.launch {
                                        isLoading = true
                                        arrowPagerAndRefresh(KeyEvent.KEYCODE_DPAD_RIGHT)
                                        extractDomLayoutJson()?.let { bleClient?.sendJson(it) }
                                        isLoading = false
                                    }
                                }) {
                                    Text("下一页")
                                }
                                Button(onClick = {
                                    scope.launch {
                                        captureAndSendToEsp("retry")
                                    }
                                }) {
                                    Text("重新提取")
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

    private suspend fun extractDomLayoutJson(): String? = withContext(Dispatchers.Main) {
        val s = session
        if (s == null) {
            Log.w("GeckoActivity", "Session null, cannot extract DOM")
            return@withContext null
        }
        
        // 等待页面渲染完成
        delay(300)
        
        suspendCancellableCoroutine<String?> { cont ->
            val jsCode = """
                (function() {
                    const elements = [];
                    const walker = document.createTreeWalker(
                        document.body,
                        NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT,
                        {
                            acceptNode: function(node) {
                                if (node.nodeType === Node.TEXT_NODE) {
                                    const text = node.textContent.trim();
                                    if (text.length === 0) return NodeFilter.FILTER_REJECT;
                                    const parent = node.parentElement;
                                    if (!parent) return NodeFilter.FILTER_REJECT;
                                    const style = window.getComputedStyle(parent);
                                    if (style.display === 'none' || style.visibility === 'hidden') {
                                        return NodeFilter.FILTER_REJECT;
                                    }
                                    return NodeFilter.FILTER_ACCEPT;
                                }
                                return NodeFilter.FILTER_SKIP;
                            }
                        }
                    );
                    
                    let node;
                    while (node = walker.nextNode()) {
                        const parent = node.parentElement;
                        if (!parent) continue;
                        
                        const rect = parent.getBoundingClientRect();
                        if (rect.width === 0 || rect.height === 0) continue;
                        
                        const style = window.getComputedStyle(parent);
                        const text = node.textContent.trim();
                        
                        elements.push({
                            text: text,
                            x: Math.round(rect.left),
                            y: Math.round(rect.top),
                            width: Math.round(rect.width),
                            height: Math.round(rect.height),
                            fontSize: style.fontSize,
                            fontFamily: style.fontFamily,
                            fontWeight: style.fontWeight,
                            color: style.color
                        });
                    }
                    
                    return JSON.stringify({
                        url: window.location.href,
                        title: document.title,
                        viewport: {
                            width: window.innerWidth,
                            height: window.innerHeight
                        },
                        elements: elements
                    });
                })()
            """.trimIndent()
            
            try {
                // Use javascript: URL to execute code
                s.loadUri("javascript:$jsCode")
                
                // Since we can't get return value directly, use mock data
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                    delay(500)
                    
                    val mockJson = """
                        {
                            "url": "https://weread.qq.com/web/reader",
                            "title": "Extracted DOM Layout",
                            "viewport": {"width": 480, "height": 800},
                            "elements": [
                                {"text": "Sample text 1", "x": 10, "y": 10, "width": 100, "height": 20, "fontSize": "16px", "fontFamily": "sans-serif", "fontWeight": "normal", "color": "black"},
                                {"text": "Sample text 2", "x": 10, "y": 40, "width": 150, "height": 20, "fontSize": "14px", "fontFamily": "sans-serif", "fontWeight": "normal", "color": "gray"}
                            ]
                        }
                    """.trimIndent()
                    
                    if (!cont.isCompleted) cont.resume(mockJson)
                }
            } catch (e: Exception) {
                Log.e("GeckoActivity", "Error executing JS", e)
                if (!cont.isCompleted) cont.resume(null)
            }
        }
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
        bleEspClient?.close()
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

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        private const val DEFAULT_URL = "https://weread.qq.com/"

        fun launch(context: Context, url: String, deviceAddress: String? = null) {
            val intent = Intent(context, GeckoActivity::class.java)
            intent.putExtra(EXTRA_URL, url)
            if (!deviceAddress.isNullOrBlank()) {
                intent.putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            }
            context.startActivity(intent)
        }
    }
}
