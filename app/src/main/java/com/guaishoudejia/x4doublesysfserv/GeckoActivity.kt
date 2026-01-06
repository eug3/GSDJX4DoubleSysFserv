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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.mozilla.geckoview.GeckoResult
import com.guaishoudejia.x4doublesysfserv.ble.BleBookProtocol
import com.guaishoudejia.x4doublesysfserv.ble.BleBookServer
import com.guaishoudejia.x4doublesysfserv.ble.DomLayoutRenderer
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
    private var bleBookServer: BleBookServer? = null

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
            var jsonText by remember { mutableStateOf("") }
            var isLoading by remember { mutableStateOf(false) }
            var lastStatus by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            var bleClient by remember { mutableStateOf<BleEspClient?>(null) }
            var bleServerRunning by remember { mutableStateOf(false) }
            var bleServerInfo by remember { mutableStateOf("") }
            var logicalPageIndex by remember { mutableStateOf(0) }
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

            suspend fun arrowPagerAndRefresh(keyCode: Int): String? {
                dispatchArrow(keyCode)
                delay(200)
                val json = extractDomLayoutJson()
                if (json == null || json.isEmpty()) {
                    lastStatus = "翻页后未提取到 DOM 数据"
                } else {
                    lastStatus = "提取到 ${json.length} 字节"
                    jsonText = json
                }
                return json  // 返回提取的 JSON，避免重复提取
            }

            suspend fun gotoLogicalPage(target: Int): String? {
                var json: String? = null
                val diff = target - logicalPageIndex
                if (diff == 0) {
                    json = extractDomLayoutJson()
                    if (!json.isNullOrEmpty()) jsonText = json
                    return json
                }
                val stepKey = if (diff > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                val steps = kotlin.math.abs(diff)
                for (i in 0 until steps) {
                    json = arrowPagerAndRefresh(stepKey)
                    if (json.isNullOrEmpty()) break
                    logicalPageIndex += if (diff > 0) 1 else -1
                    delay(150)
                }
                return json
            }

            suspend fun sendOnePageOverServer(bookId: Int, pageNum: Int, layoutJson: String) {
                val server = bleBookServer ?: return
                val dev = server.getConnectedDevice() ?: run {
                    lastStatus = "BLE Server: 未有设备连接"
                    return
                }

                val render = DomLayoutRenderer.renderTo1bpp48k(layoutJson)
                lastStatus = "渲染并发送: book=$bookId page=$pageNum ${render.debugStats}"

                val page = render.pageBytes48k
                var offset = 0
                while (offset < page.size) {
                    val len = (page.size - offset).coerceAtMost(BleBookProtocol.MAX_DATA_BYTES_PER_CHUNK)
                    val pkt = BleBookProtocol.buildDataChunk(
                        bookId = bookId,
                        pageNum = pageNum,
                        pageSize = page.size,
                        offset = offset,
                        data = page,
                        dataOffset = offset,
                        dataLen = len,
                    )
                    val ok = server.notify(dev, pkt)
                    if (!ok) {
                        lastStatus = "BLE Server: 发送失败 offset=$offset"
                        return
                    }
                    offset += len
                }
            }

            suspend fun captureAndSendToEsp(reason: String) {
                isLoading = true
                val json = extractDomLayoutJson()
                if (json == null || json.isEmpty()) {
                    lastStatus = "未提取到 DOM 数据"
                } else {
                    lastStatus = "提取到 ${json.length} 字节 JSON"
                    jsonText = json
                    val client = bleClient
                    if (client != null) {
                        client.sendJson(json)
                    } else {
                        lastStatus = "BLE未连接（$reason）"
                    }
                }
                isLoading = false
            }

            fun ensureBleServer() {
                if (bleBookServer != null) return
                bleBookServer = BleBookServer(
                    context = this@GeckoActivity,
                    scope = scope,
                    onRequest = { req ->
                        withContext(Dispatchers.Main) {
                            isLoading = true
                            try {
                                val start = req.startPage
                                val count = req.pageCount
                                val original = logicalPageIndex
                                var last = start

                                // Jump to start page, generate & send sequential pages.
                                gotoLogicalPage(start)?.let { layoutJson ->
                                    sendOnePageOverServer(req.bookId, start, layoutJson)
                                }
                                last = start
                                for (i in 1 until count) {
                                    val p = start + i
                                    val j = gotoLogicalPage(p)
                                    if (!j.isNullOrEmpty()) {
                                        sendOnePageOverServer(req.bookId, p, j)
                                        last = p
                                    } else {
                                        break
                                    }
                                }

                                // Restore to original page to reduce UI drift.
                                gotoLogicalPage(original)

                                // Send END.
                                val server = bleBookServer
                                if (server != null) {
                                    val dev = server.getConnectedDevice()
                                    if (dev != null) {
                                        server.notify(dev, BleBookProtocol.buildEnd(req.bookId, last))
                                    }
                                }

                                lastStatus = "请求完成 book=${req.bookId} pages=${req.startPage}-${last}"
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                )
                val ok = bleBookServer?.start() == true
                bleServerRunning = ok
                bleServerInfo = if (ok) {
                    "Server UUID=${bleBookServer?.getServiceUuid()}"
                } else {
                    "Server 启动失败（检查蓝牙/权限）"
                }
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
                            ensureBleServer()

                            if (targetDeviceAddress != null && bleClient == null) {
                                bleClient = BleEspClient(
                                    context = this@GeckoActivity,
                                    deviceAddress = targetDeviceAddress,
                                    scope = scope,
                                    onCommand = { cmd ->
                                        when (cmd) {
                                            "prev" -> scope.launch {
                                                isLoading = true
                                                arrowPagerAndRefresh(KeyEvent.KEYCODE_DPAD_LEFT)?.let { json ->
                                                    bleClient?.sendJson(json)
                                                }
                                                isLoading = false
                                            }
                                            "next" -> scope.launch {
                                                isLoading = true
                                                arrowPagerAndRefresh(KeyEvent.KEYCODE_DPAD_RIGHT)?.let { json ->
                                                    bleClient?.sendJson(json)
                                                }
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

                // 底部 JSON 面板（电子书阅读模式）
                if (isEbookMode) {
                    DisposableEffect(isEbookMode) {
                        onDispose {
                            // Keep BLE client alive while activity lives; only close on activity destroy.
                        }
                    }

                    // 蓝牙连接状态提示（旧：BLE client；新：BLE server）
                    val connectionStatus = when {
                        bleClient == null -> "未连接"
                        bleEspClient?.let { cl ->
                            try {
                                val f = cl::class.java.getField("isConnected")
                                f.getBoolean(cl)
                            } catch (_: Exception) {
                                false
                            }
                        } == true -> "已连接"
                        else -> "连接中..."
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color(0xFFF5F5F5))
                    ) {
                        // 顶部栏：状态 + 按钮
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "BLE Client: $connectionStatus\nBLE Server: ${if (bleServerRunning) "运行中" else "未运行"} ${bleServerInfo}",
                                fontSize = 12.sp,
                                color = if (connectionStatus == "已连接") Color(0xFF4CAF50) else Color.Gray
                            )
                            Row {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isLoading = true
                                            arrowPagerAndRefresh(KeyEvent.KEYCODE_DPAD_LEFT)?.let { json ->
                                                bleClient?.sendJson(json)
                                            }
                                            isLoading = false
                                        }
                                    },
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Text("上一页", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isLoading = true
                                            arrowPagerAndRefresh(KeyEvent.KEYCODE_DPAD_RIGHT)?.let { json ->
                                                bleClient?.sendJson(json)
                                            }
                                            isLoading = false
                                        }
                                    },
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Text("下一页", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            captureAndSendToEsp("retry")
                                        }
                                    },
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Text("刷新", fontSize = 12.sp)
                                }
                                Button(onClick = {
                                    isEbookMode = false
                                    releaseWakeLock()
                                    try {
                                        bleBookServer?.stop()
                                    } catch (_: Exception) {
                                    }
                                    bleBookServer = null
                                    bleServerRunning = false
                                }) {
                                    Text("退出", fontSize = 12.sp)
                                }
                            }
                        }

                        // JSON 内容区域
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp, max = 200.dp)
                                .background(Color.White)
                                .padding(8.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = if (jsonText.isNotBlank()) jsonText else lastStatus.ifBlank { "暂无数据" },
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.Black,
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                )
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
            // 临时进度代理：利用标题变更回传 JSON
            val prevProgress = s.progressDelegate
            val delegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    if (url.startsWith("x4json://")) {
                        try {
                            val encoded = url.removePrefix("x4json://")
                            val json = java.net.URLDecoder.decode(encoded, "UTF-8")
                            if (!cont.isCompleted) cont.resume(json)
                        } catch (e: Exception) {
                            Log.w("GeckoActivity", "decode x4json url failed", e)
                            if (!cont.isCompleted) cont.resume(null)
                        } finally {
                            s.progressDelegate = prevProgress
                        }
                    }
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {}
            }
            s.progressDelegate = delegate

            val jsCode = (
                "(function(){" +
                "try {" +
                " const elements=[];" +
                " const walker=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);" +
                " let n;" +
                " while(n=walker.nextNode()){" +
                "   var t=n.textContent.trim(); if(!t) continue;" +
                "   var p=n.parentElement; if(!p) continue;" +
                "   var cs=getComputedStyle(p); if(cs.display==='none'||cs.visibility==='hidden') continue;" +
                "   var r=p.getBoundingClientRect(); if(r.width===0||r.height===0) continue;" +
                "   elements.push({text:t,x:Math.round(r.left),y:Math.round(r.top),width:Math.round(r.width),height:Math.round(r.height),fontSize:cs.fontSize,fontFamily:cs.fontFamily,fontWeight:cs.fontWeight,color:cs.color});" +
                " }" +
                " const payload={url:location.href,title:document.title,viewport:{width:innerWidth,height:innerHeight},elements:elements};" +
                " const out=encodeURIComponent(JSON.stringify(payload));" +
                " location.href='x4json://'+out;" +
                "} catch(e){ location.href='x4json://'+encodeURIComponent(JSON.stringify({error:String(e)})); }" +
                "})();"
            )

            try {
                s.loadUri("javascript:" + jsCode)
            } catch (e: Exception) {
                Log.e("GeckoActivity", "exec js error", e)
                if (!cont.isCompleted) {
                    s.progressDelegate = prevProgress
                    cont.resume(null)
                }
            }

            // 超时保护，恢复 delegate
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                delay(2000)
                if (!cont.isCompleted) {
                    s.progressDelegate = prevProgress
                    cont.resume(null)
                }
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
