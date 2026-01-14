package com.guaishoudejia.x4doublesysfserv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import com.guaishoudejia.x4doublesysfserv.ui.components.BleDeviceScanSheet
import com.guaishoudejia.x4doublesysfserv.ui.components.BleFloatingButton
import com.guaishoudejia.x4doublesysfserv.ble.DomLayoutRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebRequestError
import java.util.Locale

class GeckoActivity : ComponentActivity() {
    private var geckoView: GeckoView? = null
    private var geckoSession: GeckoSession? = null
    private var geckoRuntime: GeckoRuntime? = null
    
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var bleConnectionManager: BleConnectionManager
    private var pendingStartScan = false
    private var remoteServeAvailable by mutableStateOf(false)

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted && pendingStartScan) startScanAndShow()
        pendingStartScan = false
    }

    // 核心状态变量
    private var currentUrl by mutableStateOf("")
    private var isEbookMode by mutableStateOf(false)
    private var isLoading by mutableStateOf(false)
    private var lastStatus by mutableStateOf("就绪")
    private var logicalPageIndex by mutableIntStateOf(0)
    private val renderHistory = mutableStateListOf<Bitmap>()
    private var targetUrl: String = DEFAULT_URL  // 默认URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { DEFAULT_URL }
        currentUrl = targetUrl

        // 初始化 GeckoRuntime
        setupGeckoRuntime()

        // 初始化 BLE
        bleConnectionManager = BleConnectionManager(this, this, lifecycleScope)
        bleConnectionManager.onCommandReceived = { cmd -> handleBleCommand(cmd) }

        checkRemoteServe()

        setContent {
            var fullScreenBitmap by remember { mutableStateOf<Bitmap?>(null) }
            var isPanelExpanded by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        GeckoView(context).apply {
                            geckoView = this
                            // 使用已初始化的geckoSession
                            geckoSession?.let { session ->
                                setSession(session)
                                
                                // 🔑 设置网络请求拦截器，拦截所有流量走代理
                                setupRequestInterceptor(session)
                                
                                // 转换URL为代理URL，使浏览器走RemoteServe代理
                                val proxyUrl = convertToProxyUrl(targetUrl)
                                Log.d("GeckoActivity", "原始URL: $targetUrl")
                                Log.d("GeckoActivity", "代理URL: $proxyUrl")
                                session.loadUri(proxyUrl)
                                
                                // 状态监听
                                session.progressDelegate = object : GeckoSession.ProgressDelegate {
                                    override fun onPageStart(session: GeckoSession, url: String) {
                                        currentUrl = url
                                        isLoading = true
                                        Log.d("GeckoActivity", "页面开始加载: $url")
                                    }
                                    override fun onPageStop(session: GeckoSession, success: Boolean) {
                                        isLoading = false
                                        Log.d("GeckoActivity", "页面加载完成: $success")
                                    }
                                }
                            } ?: Log.e("GeckoActivity", "GeckoSession 未初始化")
                        }
                    }
                )

                // 自动检测阅读页
                LaunchedEffect(currentUrl) {
                    if (currentUrl.contains("weread.qq.com/web/reader/") && !isEbookMode) {
                        isEbookMode = true
                        acquireWakeLock()
                    }
                }

                if (isEbookMode) {
                    BleFloatingButton(
                        isConnected = bleConnectionManager.isConnected,
                        deviceName = bleConnectionManager.connectedDeviceName,
                        onScan = { requestBleAndStartScan() },
                        onForget = { bleConnectionManager.forgetDevice() },
                        isPanelExpanded = isPanelExpanded,
                        onTogglePanel = { isPanelExpanded = !isPanelExpanded },
                        onRefresh = { performSync(logicalPageIndex) },
                        onExit = { exitEbookMode() }
                    )

                    EbookControlPanel(
                        isExpanded = isPanelExpanded,
                        onToggleExpand = { isPanelExpanded = !isPanelExpanded },
                        onRefresh = { performSync(logicalPageIndex) },
                        onPageClick = { fullScreenBitmap = it },
                        onExit = { exitEbookMode() }
                    )

                    BleDeviceScanSheet(
                        isVisible = bleConnectionManager.showScanSheet,
                        isScanning = bleConnectionManager.isScanning,
                        deviceList = bleConnectionManager.scannedDevices,
                        onDeviceSelected = { address: String, name: String ->
                            bleConnectionManager.connectToDevice(address, name) { _ -> }
                            bleConnectionManager.showScanSheet = false
                        },
                        onDismiss = {
                            bleConnectionManager.stopScanning()
                            bleConnectionManager.showScanSheet = false
                        }
                    )
                }

                fullScreenBitmap?.let { bmp ->
                    ZoomableImageOverlay(bitmap = bmp, onClose = { fullScreenBitmap = null })
                }
            }
        }
    }

    private fun setupGeckoRuntime() {
        // 使用 GeckoRuntimeManager 获取共享的 GeckoRuntime 实例
        // 避免创建多个 GeckoRuntime 实例导致 "Only one GeckoRuntime instance is allowed" 错误
        val runtime = GeckoRuntimeManager.getRuntime(this)
        geckoRuntime = runtime
        
        // 修复参数类型不匹配问题：GeckoSession 构造函数接收 GeckoSessionSettings
        // 应该先创建 Session，然后调用 open(runtime) 关联
        val session = GeckoSession()
        session.open(runtime)
        geckoSession = session
        
        Log.d("GeckoActivity", "GeckoSession 初始化完成并已开启")
    }

    private fun exitEbookMode() {
        isEbookMode = false
        releaseWakeLock()
        renderHistory.clear()
        bleConnectionManager.disconnect()
    }

    @Composable
    fun BoxScope.EbookControlPanel(
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onRefresh: () -> Unit,
        onPageClick: (Bitmap) -> Unit,
        onExit: () -> Unit
    ) {
        val pagerState = rememberPagerState(pageCount = { renderHistory.size })
        
        if (isExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .heightIn(max = 250.dp)
                    .background(Color(0xFFF5F5F5).copy(alpha = 0.95f))
                    .padding(8.dp)
            ) {
                if (renderHistory.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f).height(200.dp).padding(end = 8.dp)) {
                        HorizontalPager(
                            state = pagerState,
                            contentPadding = PaddingValues(horizontal = 32.dp),
                            pageSpacing = 8.dp,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            Image(
                                bitmap = renderHistory[page].asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(480f / 800f)
                                    .background(Color.White)
                                    .clickable { onPageClick(renderHistory[page]) },
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).height(30.dp).background(Color.White).padding(horizontal = 8.dp)) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(16.dp), strokeWidth = 2.dp)
            else Text(text = lastStatus, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.CenterStart))
        }
    }

    @Composable
    fun ZoomableImageOverlay(bitmap: Bitmap, onClose: () -> Unit) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset += pan
                }
            }) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y), contentScale = ContentScale.Fit)
                Text("点击关闭", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp).clickable { onClose() })
            }
        }
        BackHandler { onClose() }
    }

    private fun performSync(pageNum: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            isLoading = true
            try {
                // 模拟翻页
                val diff = pageNum - logicalPageIndex
                if (diff != 0) {
                    val key = if (diff > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                    repeat(kotlin.math.abs(diff)) { 
                        geckoView?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                        geckoView?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                        delay(600) 
                    }
                    logicalPageIndex = pageNum
                }

                // GeckoView 截图 (捕获当前页面内容)
                // 注意：使用 capturePixels() 捕获当前渲染的页面内容
                geckoView?.capturePixels()?.accept { bitmap: Bitmap? ->
                    if (bitmap != null) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            renderHistory.add(bitmap)
                            val renderResult = DomLayoutRenderer.renderTo1bpp48k(bitmap)
                            val bleClient = bleConnectionManager.getBleClient()
                            if (bleClient != null && bleConnectionManager.isConnected) {
                                bleClient.sendRawBitmap(renderResult.pageBytes48k)
                            }
                            lastStatus = "同步成功: 第 $pageNum 页"
                        }
                    } else {
                        lastStatus = "截图失败"
                    }
                }
            } catch (e: Exception) {
                lastStatus = "异常: ${e.message}"
            }
            isLoading = false
        }
    }

    private fun handleBleCommand(rawCmd: String) {
        val cmd = rawCmd.trim()
        if (cmd.isBlank()) return
        when {
            cmd.equals("SYNC", ignoreCase = true) -> performSync(logicalPageIndex)
            cmd.startsWith("PAGE:", ignoreCase = true) -> {
                cmd.substringAfter(':', "").toIntOrNull()?.let { logicalPageIndex = it }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        geckoSession?.close()
        bleConnectionManager.disconnect()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeckoActivity::WakeLock")
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun checkRemoteServe() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url("http://172.16.8.248:8080/ping").build()
                val response = client.newCall(request).execute()
                remoteServeAvailable = response.isSuccessful
            } catch (e: Exception) {
                remoteServeAvailable = false
            }
        }
    }

    private fun requestBleAndStartScan() {
        pendingStartScan = true
        blePermissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    }

    private fun startScanAndShow() {
        bleConnectionManager.startScanning()
        bleConnectionManager.showScanSheet = true
    }

    /**
     * 设置 GeckoView 网络请求拦截器
     * 拦截所有 HTTP/HTTPS 请求并转发到代理服务器
     */
    private fun setupRequestInterceptor(session: GeckoSession) {
        session.webRequestDelegate = object : GeckoSession.WebRequestDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: WebRequest
            ): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
                val originalUri = request.uri
                
                // 跳过某些协议和特殊请求
                if (shouldSkipProxyForUri(originalUri)) {
                    Log.d("GeckoActivity", "跳过代理: $originalUri")
                    return null  // 使用默认处理
                }
                
                // 转换为代理 URL
                val proxyUri = convertToProxyUrl(originalUri)
                
                Log.d("GeckoActivity", "📡 拦截请求")
                Log.d("GeckoActivity", "  原始: $originalUri")
                Log.d("GeckoActivity", "  代理: $proxyUri")
                
                // 创建新的代理请求
                val proxyRequest = WebRequest.Builder(proxyUri)
                    .method(request.method)
                    .apply {
                        // 复制原始请求头
                        request.headers?.forEach { (key, value) ->
                            addHeader(key, value)
                        }
                    }
                    .cacheMode(request.cacheMode)
                    .build()
                
                return GeckoSession.WebRequestDelegate.LoadRequestReturn(proxyRequest)
            }
        }
    }

    /**
     * 判断是否应该跳过代理处理
     */
    private fun shouldSkipProxyForUri(uri: String): Boolean {
        return uri.startsWith("data:") ||
               uri.startsWith("about:") ||
               uri.startsWith("blob:") ||
               uri.startsWith("moz-extension:") ||
               uri.startsWith("file://") ||
               uri.startsWith("chrome://") ||
               uri == null ||
               uri.isEmpty()
    }

    /**
     * 将原始URL转换为代理URL
     * 支持完整URL和相对路径
     * 
     * 示例转换：
     * https://weread.qq.com/web/reader/123
     *   ↓
     * http://172.16.8.248:8080/proxy/https/weread.qq.com/web/reader/123
     * 
     * /web/reader/456 (相对路径时使用上一个主机)
     *   ↓
     * http://172.16.8.248:8080/proxy/https/weread.qq.com/web/reader/456
     */
    private fun convertToProxyUrl(originalUrl: String): String {
        return try {
            // 尝试解析为完整 URL
            val url = java.net.URL(originalUrl)
            val scheme = url.protocol          // https
            val host = url.host                // weread.qq.com
            val path = url.path                // /web/reader/123
            val query = url.query              // param=value
            val fullPath = path + (query?.let { "?$it" } ?: "")
            
            "http://172.16.8.248:8080/proxy/$scheme/$host$fullPath"
        } catch (e: Exception) {
            // 如果不是完整URL，可能是相对路径
            // 使用默认主机构建代理URL
            Log.d("GeckoActivity", "URL 转换（作为相对路径）: $originalUrl")
            
            try {
                val defaultScheme = "https"
                val defaultHost = "weread.qq.com"
                val path = if (originalUrl.startsWith("/")) {
                    originalUrl
                } else {
                    "/$originalUrl"
                }
                
                "http://172.16.8.248:8080/proxy/$defaultScheme/$defaultHost$path"
            } catch (e2: Exception) {
                Log.e("GeckoActivity", "URL转换失败: ${e2.message}", e2)
                originalUrl  // 转换失败时返回原始URL
            }
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val DEFAULT_URL = "https://weread.qq.com/"
        
        fun launch(context: Context, url: String, extraParams: String? = null) {
            val intent = Intent(context, GeckoActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                // 这里可以根据需要处理 extraParams
            }
            context.startActivity(intent)
        }
    }
}
