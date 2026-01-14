package com.guaishoudejia.x4doublesysfserv

import android.Manifest
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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResourceError
import org.mozilla.geckoview.WarnedResponse
import com.guaishoudejia.x4doublesysfserv.ble.DomLayoutRenderer
import com.guaishoudejia.x4doublesysfserv.ui.components.BleDeviceScanSheet
import com.guaishoudejia.x4doublesysfserv.ui.components.BleFloatingButton
import kotlin.coroutines.resume

class GeckoActivity : ComponentActivity() {
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var geckoView: GeckoView? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { DEFAULT_URL }
        currentUrl = targetUrl

        // 初始化 BLE 连接管理器
        bleConnectionManager = BleConnectionManager(this, this, lifecycleScope)
        bleConnectionManager.onCommandReceived = { cmd ->
            handleBleCommand(cmd)
        }

        // 使用共享的 GeckoRuntime 实例
        runtime = GeckoRuntimeManager.getRuntime(this)
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .build()

        session = GeckoSession(settings).apply {
            open(runtime!!)

            // 设置导航拦截器 - 将 weread.qq.com 请求重定向到本地代理
            navigationDelegate = object : NavigationDelegate {
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoSession.NavigationDelegate.OnLoadRequestCallback {
                    val url = request.url

                    // 拦截 weread.qq.com 的请求
                    if (url.contains("weread.qq.com") && !url.startsWith("http://localhost:8080") && !url.startsWith("x4bmp://")) {
                        // 提取路径部分
                        val path = url.removePrefix("https://weread.qq.com").removePrefix("http://weread.qq.com")
                        val proxyUrl = "http://localhost:8080/weread$path"
                        Log.d("GeckoActivity", "代理请求: $url -> $proxyUrl")
                        return GeckoSession.NavigationDelegate.OnLoadRequestCallback(proxyUrl)
                    }

                    // 放行其他请求（包括本地代理响应、x4bmp 协议等）
                    return GeckoSession.NavigationDelegate.OnLoadRequestCallback(request.url)
                }

                override fun onSafeBrowsingHelpRequest(
                    session: GeckoSession,
                    url: String,
                    threatTypes: Array<out String>,
                    violatedSafeBrowsingCategories: Array<out String>,
                    helpUri: String
                ) {
                    // 忽略安全警告
                }

                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.ContentPermissionRequest
                ): GeckoSession.NavigationDelegate.OnContentPermissionRequestCallback {
                    // 允许所有权限请求
                    return GeckoSession.NavigationDelegate.OnContentPermissionRequestCallback(
                        GeckoSession.PermissionDecision.ALLOW,
                        null
                    )
                }

                override fun onCookiePermissionRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.CookiePermissionRequest
                ): GeckoSession.NavigationDelegate.OnCookiePermissionRequestCallback {
                    // 允许所有 Cookie
                    return GeckoSession.NavigationDelegate.OnCookiePermissionRequestCallback(
                        GeckoSession.PermissionDecision.ALLOW
                    )
                }

                override fun onTrackingProtectionUriRequest(
                    session: GeckoSession,
                    url: String
                ): GeckoSession.NavigationDelegate.OnTrackingProtectionUriRequestCallback {
                    return GeckoSession.NavigationDelegate.OnTrackingProtectionUriRequestCallback(null)
                }

                override fun onRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.Request
                ): GeckoSession.NavigationDelegate.OnRequestCallback {
                    return GeckoSession.NavigationDelegate.OnRequestCallback(GeckoSession.PermissionDecision.ALLOW)
                }

                override fun onSubframeLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoSession.NavigationDelegate.OnLoadRequestCallback {
                    return GeckoSession.NavigationDelegate.OnLoadRequestCallback(request.url)
                }

                override fun onLoadError(
                    session: GeckoSession,
                    uri: String,
                    error: WebResourceError
                ): GeckoSession.NavigationDelegate.OnLoadErrorCallback? {
                    return null
                }
            }
        }

        // 检查 RemoteServe 是否可用
        checkRemoteServe()

        setContent {
            var fullScreenBitmap by remember { mutableStateOf<Bitmap?>(null) }
            var isPanelExpanded by remember { mutableStateOf(false) }

            val density = androidx.compose.ui.platform.LocalDensity.current
            val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
            val aspect = 480f / 800f
            val wPx = metrics.widthPixels
            val hPx = (wPx / aspect).toInt().coerceAtLeast(1)
            val wDp = with(density) { wPx.toDp() }
            val hDp = with(density) { hPx.toDp() }

            // 监听有效 URL 变化
            DisposableEffect(session) {
                val delegate = object : GeckoSession.ProgressDelegate {
                    override fun onPageStart(session: GeckoSession, url: String) {
                        if (!url.startsWith("x4bmp://")) currentUrl = url
                    }
                    override fun onPageStop(session: GeckoSession, success: Boolean) {}
                    override fun onProgressChange(session: GeckoSession, progress: Int) {}
                    override fun onSecurityChange(session: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) {}
                }
                session?.progressDelegate = delegate
                onDispose { session?.progressDelegate = null }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.size(wDp, hDp).align(Alignment.Center),
                    factory = { context ->
                        GeckoView(context).apply {
                            this@GeckoActivity.session?.let { setSession(it) }
                            geckoView = this
                        }
                    }
                )

                // 自动检测阅读页并启用 Ebook 模式
                LaunchedEffect(currentUrl) {
                    if (currentUrl.contains("weread.qq.com/web/reader/") && !isEbookMode) {
                        isEbookMode = true
                        acquireWakeLock()
                    }
                }

                if (isEbookMode) {
                    // 左侧：BLE 浮动按钮
                    BleFloatingButton(
                        isConnected = bleConnectionManager.isConnected,
                        deviceName = bleConnectionManager.connectedDeviceName,
                        onScan = { requestBleAndStartScan() },
                        onForget = { bleConnectionManager.forgetDevice() },
                        isPanelExpanded = isPanelExpanded,
                        onTogglePanel = { isPanelExpanded = !isPanelExpanded },
                        onRefresh = { performSync(logicalPageIndex) },
                        onExit = {
                            isEbookMode = false
                            releaseWakeLock()
                            renderHistory.clear()
                            bleConnectionManager.disconnect()
                        }
                    )

                    // 底部：Ebook 控制面板
                    EbookControlPanel(
                        isExpanded = isPanelExpanded,
                        onToggleExpand = { isPanelExpanded = !isPanelExpanded },
                        onRefresh = { performSync(logicalPageIndex) },
                        onPageClick = { fullScreenBitmap = it },
                        onExit = {
                            isEbookMode = false
                            releaseWakeLock()
                            renderHistory.clear()
                            bleConnectionManager.disconnect()
                        }
                    )

                    // 设备扫描底表
                    BleDeviceScanSheet(
                        isVisible = bleConnectionManager.showScanSheet,
                        isScanning = bleConnectionManager.isScanning,
                        deviceList = bleConnectionManager.scannedDevices,
                        onDeviceSelected = { address, name ->
                            bleConnectionManager.connectToDevice(address, name) { client ->
                                Log.d("GeckoActivity", "已连接到 BLE 设备: $name")
                            }
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

        session?.loadUri(targetUrl)
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
        LaunchedEffect(renderHistory.size) { if (renderHistory.isNotEmpty()) pagerState.animateScrollToPage(renderHistory.size - 1) }

        if (isExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .heightIn(max = 250.dp)
                    .background(Color(0xFFF5F5F5).copy(alpha = 0.95f))
                    .padding(8.dp)
            ) {
                // 图片预览
                if (renderHistory.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(200.dp)
                            .padding(end = 8.dp)
                    ) {
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

        // 底部按钮栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "历史: ${renderHistory.size}页", fontSize = 11.sp, modifier = Modifier.weight(1f))
        }

        // 状态栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(30.dp)
                .background(Color.White)
                .padding(horizontal = 8.dp)
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(16.dp), strokeWidth = 2.dp)
            else Text(text = lastStatus, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.CenterStart))
        }
    }

    @Composable
    fun ZoomableImageOverlay(bitmap: Bitmap, onClose: () -> Unit) {
        var scale by remember { mutableStateOf(1f) }
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

    // 核心业务同步逻辑
    private fun performSync(pageNum: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            isLoading = true
            try {
                // 处理翻页逻辑
                val diff = pageNum - logicalPageIndex
                if (diff != 0) {
                    val key = if (diff > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                    repeat(kotlin.math.abs(diff)) { dispatchArrow(key); delay(600) }
                    logicalPageIndex = pageNum
                }

                // 注入 JS 直接截取 Canvas 像素数据
                Log.d("SYNC", "开始抓取 Canvas 图片")
                val bitmap = captureCanvasViaJs()

                if (bitmap == null) {
                    Log.e("SYNC", "Canvas 抓取失败，返回 null")
                    lastStatus = "抓取失败: 页面 Canvas 未就绪"
                    isLoading = false
                    return@launch
                }

                Log.d("SYNC", "Canvas 抓取成功，尺寸: ${bitmap.width}x${bitmap.height}")

                // 添加原始图像到历史
                renderHistory.add(bitmap)

                // 渲染并发送到 BLE 设备
                val renderResult = DomLayoutRenderer.renderTo1bpp48k(bitmap)
                Log.d("SYNC", "渲染完成: ${renderResult.debugStats}")

                val bleClient = bleConnectionManager.getBleClient()
                if (bleClient != null && bleConnectionManager.isConnected) {
                    try {
                        if (bleClient.isReady()) {
                            bleClient.sendRawBitmap(renderResult.pageBytes48k)
                            Log.d("SYNC", "已发送数据到 BLE 设备")
                        } else {
                            Log.w("SYNC", "BLE 连接未就绪，跳过发送")
                        }
                    } catch (e: Exception) {
                        Log.w("SYNC", "发送 BLE 数据失败", e)
                    }
                }
                lastStatus = "同步成功: 第 $pageNum 页"
            } catch (e: Exception) {
                Log.e("SYNC", "同步异常", e)
                lastStatus = "异常: ${e.message}"
            }
            isLoading = false
        }
    }

    private fun dispatchArrow(keyCode: Int) {
        val view = geckoView ?: return
        view.requestFocus()
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private suspend fun captureCanvasViaJs(): Bitmap? = withContext(Dispatchers.Main) {
        val s = session ?: run {
            Log.e("CAPTURE", "session 为 null")
            return@withContext null
        }

        suspendCancellableCoroutine { cont ->
            val prevProgress = s.progressDelegate

            s.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    if (url.startsWith("x4bmp://")) {
                        try {
                            val data = url.removePrefix("x4bmp://")
                            if (data == "ERROR") {
                                if (!cont.isCompleted) cont.resume(null)
                            } else {
                                val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (!cont.isCompleted) cont.resume(bmp)
                            }
                        } catch (e: Exception) {
                            if (!cont.isCompleted) cont.resume(null)
                        } finally {
                            s.progressDelegate = prevProgress
                        }
                    }
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {}
                override fun onProgressChange(session: GeckoSession, progress: Int) {}
                override fun onSecurityChange(session: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) {}
            }

            val jsCode = """
                (function() {
                    var maxAttempts = 75;
                    var interval = 200;
                    var attempts = 0;

                    function tryCapture() {
                        try {
                            var canvas = document.querySelector('canvas');
                            if (!canvas || canvas.width <= 0 || canvas.height <= 0) {
                                attempts++;
                                if (attempts < maxAttempts) {
                                    setTimeout(tryCapture, interval);
                                } else {
                                    location.href = 'x4bmp://ERROR';
                                }
                                return;
                            }

                            var data = canvas.toDataURL('image/png').split(',')[1];
                            if (!data || data.length < 100) {
                                attempts++;
                                if (attempts < maxAttempts) {
                                    setTimeout(tryCapture, interval);
                                } else {
                                    location.href = 'x4bmp://ERROR';
                                }
                                return;
                            }

                            location.href = 'x4bmp://' + data;
                        } catch(e) {
                            attempts++;
                            if (attempts < maxAttempts) {
                                setTimeout(tryCapture, interval);
                            } else {
                                location.href = 'x4bmp://ERROR';
                            }
                        }
                    }

                    tryCapture();
                })();
            """.trimIndent().replace("\n", "").replace("    ", " ")
            s.loadUri("javascript:$jsCode")
        }
    }

    private fun handleBleCommand(rawCmd: String) {
        val cmd = rawCmd.trim()
        if (cmd.isBlank()) return

        when {
            cmd.equals("SYNC", ignoreCase = true) -> {
                Log.d("GeckoActivity", "BLE 触发 SYNC")
                performSync(logicalPageIndex)
            }
            cmd.startsWith("PAGE:", ignoreCase = true) -> {
                val pageNum = cmd.substringAfter(':', "").toIntOrNull()
                if (pageNum != null) {
                    Log.d("GeckoActivity", "BLE 上报页码: $pageNum")
                    logicalPageIndex = pageNum
                }
            }
            else -> {
                Log.d("GeckoActivity", "未处理的 BLE 命令: $cmd")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isEbookMode) {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "x4:remote").apply { try { acquire(30 * 60 * 1000L) } catch(_:Exception){} }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun requestBleAndStartScan() {
        if (bleConnectionManager.hasRequiredPermissions()) {
            startScanAndShow()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pendingStartScan = true
                blePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                )
            } else {
                startScanAndShow()
            }
        }
    }

    private fun startScanAndShow() {
        bleConnectionManager.showScanSheet = true
        if (!bleConnectionManager.isScanning) {
            bleConnectionManager.startScanning()
        }
    }

    /**
     * 检查 RemoteServe 是否可用
     */
    private fun checkRemoteServe() {
        lifecycleScope.launch {
            remoteServeAvailable = WeReadProxyClient.isAvailable()
            Log.d("GeckoActivity", "RemoteServe 可用: $remoteServeAvailable")
            if (remoteServeAvailable) {
                val config = WeReadProxyClient.getConfig()
                Log.d("GeckoActivity", "远程配置: $config")
            }
        }
    }

    /**
     * 从 RemoteServe 获取 Cookie 并注入到 GeckoView
     */
    private fun fetchCookiesFromRemote() {
        if (!remoteServeAvailable) return

        lifecycleScope.launch {
            try {
                val remoteCookies = WeReadProxyClient.fetchRemoteCookies()
                if (remoteCookies.isNotEmpty()) {
                    Log.d("GeckoActivity", "从远程获取到 ${remoteCookies.size} 个 Cookie")
                    for ((name, value) in remoteCookies) {
                        WeReadProxyClient.addCookie(name, value)
                    }
                    session?.loadUri(currentUrl)
                }
            } catch (e: Exception) {
                Log.e("GeckoActivity", "获取远程 Cookie 失败: ${e.message}")
            }
        }
    }

    /**
     * 将 GeckoView 的 Cookie 同步到 RemoteServe
     */
    private fun syncCookiesFromGecko() {
        if (!remoteServeAvailable) return

        lifecycleScope.launch {
            try {
                val success = WeReadProxyClient.syncCookiesToRemote()
                if (success) {
                    Log.d("GeckoActivity", "Cookie 同步成功")
                }
            } catch (e: Exception) {
                Log.e("GeckoActivity", "同步 Cookie 失败: ${e.message}")
            }
        }
    }

    companion object {
        private const val DEFAULT_URL = "https://weread.qq.com/"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        fun launch(context: Context, url: String, address: String?) {
            context.startActivity(Intent(context, GeckoActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_DEVICE_ADDRESS, address)
            })
        }
    }
}
