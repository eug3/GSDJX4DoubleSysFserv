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

            fun convertScreenshotTo1bit(sourceBitmap: Bitmap): ByteArray {
                // 缩放到480x800
                val scaledBitmap = if (sourceBitmap.width != 480 || sourceBitmap.height != 800) {
                    Bitmap.createScaledBitmap(sourceBitmap, 480, 800, true)
                } else {
                    sourceBitmap
                }
                
                // 转为1bit格式：480x800 = 48000字节
                val bytesPerRow = 60  // (480 + 7) / 8
                val buffer = ByteArray(48000)
                buffer.fill(0xFF.toByte())  // 初始化为全白
                
                val pixels = IntArray(480)
                for (y in 0 until 800) {
                    scaledBitmap.getPixels(pixels, 0, 480, 0, y, 480, 1)
                    
                    for (x in 0 until 480) {
                        val rgb = pixels[x]
                        // 提取RGB并转灰度
                        val r = (rgb shr 16) and 0xFF
                        val g = (rgb shr 8) and 0xFF
                        val b = rgb and 0xFF
                        val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                        
                        // 阈值127：>127白(1), <=127黑(0)
                        val bit = if (gray > 127) 1 else 0
                        
                        // 写入位缓冲：MSB优先
                        val byteIndex = y * bytesPerRow + (x / 8)
                        val bitIndex = 7 - (x % 8)
                        if (bit == 0) {
                            buffer[byteIndex] = (buffer[byteIndex].toInt() and (1 shl bitIndex).inv()).toByte()
                        }
                    }
                }
                
                Log.d("GeckoActivity", "Converted screenshot to 1bit: 480x800, ${buffer.size} bytes")
                return buffer
            }

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

                val shot = captureViewportBitmap()
                val render = DomLayoutRenderer.renderTo1bpp48k(layoutJson, shot)
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

            suspend fun sendPageByNumber(pageNum: Int) {
                isLoading = true
                try {
                    // 跳转到指定页码
                    val layoutJson = gotoLogicalPage(pageNum)
                    if (layoutJson.isNullOrEmpty()) {
                        lastStatus = "跳转到页码 $pageNum 失败"
                        isLoading = false
                        return
                    }
                    
                    // 获取该页的截图（用于提取图片）
                    val screenshot = captureViewportBitmap()
                    
                    // 渲染为 1-bit 位图
                    val render = DomLayoutRenderer.renderTo1bpp48k(layoutJson, screenshot)
                    lastStatus = "已跳转到第 $pageNum 页并渲染"
                    
                    // 通过 BLE 发送给电子书
                    val client = bleClient
                    if (client != null) {
                        client.sendRawBitmap(render.pageBytes48k)
                    } else {
                        lastStatus = "BLE 未连接"
                    }
                } catch (e: Exception) {
                    lastStatus = "页码跳转失败: ${e.message}"
                    Log.e("GeckoActivity", "sendPageByNumber error", e)
                }
                isLoading = false
            }

            suspend fun captureAndSendToEsp(reason: String) {
                isLoading = true
                try {
                    // 截获浏览器当前画面
                    val screenshot = captureViewportBitmap()
                    if (screenshot == null) {
                        lastStatus = "截图失败"
                        isLoading = false
                        return
                    }
                    
                    // 将截图转换为480x800的1bit位图
                    val bitmap1bit = convertScreenshotTo1bit(screenshot)
                    lastStatus = "已截图并转换为1bit (${bitmap1bit.size} 字节)"
                    
                    // 通过BLE发送给电子书
                    val client = bleClient
                    if (client != null) {
                        client.sendRawBitmap(bitmap1bit)
                    } else {
                        lastStatus = "BLE未连接（$reason）"
                    }
                } catch (e: Exception) {
                    lastStatus = "处理截图失败: ${e.message}"
                    Log.e("GeckoActivity", "captureAndSendToEsp error", e)
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
                                        // 处理电子书发来的页码请求: "PAGE:123"
                                        if (cmd.startsWith("PAGE:")) {
                                            val pageNum = cmd.substringAfter("PAGE:").toIntOrNull()
                                            if (pageNum != null) {
                                                scope.launch {
                                                    Log.d("GeckoActivity", "电子书请求页码: $pageNum")
                                                    sendPageByNumber(pageNum)
                                                }
                                            }
                                        } else {
                                            Log.w("GeckoActivity", "未知的 BLE 命令: $cmd")
                                        }
                                    }
                                ).also {
                                    bleEspClient = it
                                    it.connect()
                                }
                            }

                            scope.launch {
                                sendPageByNumber(logicalPageIndex)
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
                                            dispatchArrow(KeyEvent.KEYCODE_DPAD_LEFT)
                                            delay(200)
                                            captureAndSendToEsp("prevButton")
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
                                            dispatchArrow(KeyEvent.KEYCODE_DPAD_RIGHT)
                                            delay(200)
                                            captureAndSendToEsp("nextButton")
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
                " const images=[];" +
                " const walker=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);" +
                " let n;" +
                " while(n=walker.nextNode()){" +
                "   var t=n.textContent.trim(); if(!t) continue;" +
                "   var p=n.parentElement; if(!p) continue;" +
                "   var cs=getComputedStyle(p); if(cs.display==='none'||cs.visibility==='hidden') continue;" +
                "   var r=p.getBoundingClientRect(); if(r.width===0||r.height===0) continue;" +
                "   elements.push({text:t,x:Math.round(r.left),y:Math.round(r.top),width:Math.round(r.width),height:Math.round(r.height),fontSize:cs.fontSize,fontFamily:cs.fontFamily,fontWeight:cs.fontWeight,color:cs.color});" +
                " }" +
                " const imgs=document.images?Array.from(document.images):[];" +
                " for (var i=0;i<imgs.length;i++){" +
                "   var im=imgs[i];" +
                "   if(!im) continue;" +
                "   var cs=getComputedStyle(im); if(cs.display==='none'||cs.visibility==='hidden') continue;" +
                "   var r=im.getBoundingClientRect(); if(r.width===0||r.height===0) continue;" +
                "   var src=im.currentSrc||im.src||'';" +
                "   images.push({x:Math.round(r.left),y:Math.round(r.top),width:Math.round(r.width),height:Math.round(r.height),src:src});" +
                " }" +
                " const payload={url:location.href,title:document.title,viewport:{width:innerWidth,height:innerHeight,dpr:window.devicePixelRatio||1},elements:elements,images:images};" +
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

    private suspend fun captureViewportBitmap(): Bitmap? = withContext(Dispatchers.Main) {
        val v = geckoView ?: return@withContext null
        suspendCancellableCoroutine<Bitmap?> { cont ->
            try {
                val r: GeckoResult<Bitmap> = v.capturePixels()
                r.accept({ bmp ->
                    if (!cont.isCompleted) cont.resume(bmp)
                }, { _ ->
                    if (!cont.isCompleted) cont.resume(null)
                })
            } catch (_: Exception) {
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
