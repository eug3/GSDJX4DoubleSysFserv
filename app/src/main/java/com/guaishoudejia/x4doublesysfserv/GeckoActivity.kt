package com.guaishoudejia.x4doublesysfserv

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
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
import com.guaishoudejia.x4doublesysfserv.ble.DomLayoutRenderer
import com.guaishoudejia.x4doublesysfserv.ui.components.BleDeviceScanSheet
import com.guaishoudejia.x4doublesysfserv.ui.components.BleFloatingButton
import kotlinx.coroutines.*
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.util.Locale

class GeckoActivity : ComponentActivity() {
    private var geckoView: GeckoView? = null
    private var geckoSession: GeckoSession = GeckoSession()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { DEFAULT_URL }
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
                            setSession(geckoSession)
                            
                            // 初始加载
                            geckoSession.loadUri(targetUrl)
                            
                            // 状态监听
                            geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
                                override fun onPageStart(session: GeckoSession, url: String) {
                                    currentUrl = url
                                    isLoading = true
                                }
                                override fun onPageStop(session: GeckoSession, success: Boolean) {
                                    isLoading = false
                                }
                            }
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
                        onDeviceSelected = { address, name ->
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
        // GeckoRuntimeSettings.Builder 并不直接提供 domStorageEnabled，GeckoView 默认开启
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .build()
        
        geckoRuntime = GeckoRuntime.create(this, settings)
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
                // 注意：在最新版本的 GeckoView 中，使用 screenshot() 代替 captureThumbnail()
                geckoSession.screenshot().accept { bitmap: Bitmap? ->
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
        geckoSession.close()
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
                blePermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
            } else {
                startScanAndShow()
            }
        }
    }

    private fun startScanAndShow() {
        bleConnectionManager.showScanSheet = true
        if (!bleConnectionManager.isScanning) bleConnectionManager.startScanning()
    }

    private fun checkRemoteServe() {
        lifecycleScope.launch {
            remoteServeAvailable = WeReadProxyClient.isAvailable()
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
