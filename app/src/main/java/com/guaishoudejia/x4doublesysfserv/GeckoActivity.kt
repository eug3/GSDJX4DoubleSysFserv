package com.guaishoudejia.x4doublesysfserv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.PowerManager
import android.view.KeyEvent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import com.guaishoudejia.x4doublesysfserv.ble.DomLayoutRenderer
import com.guaishoudejia.x4doublesysfserv.ocr.OcrHelper
import com.guaishoudejia.x4doublesysfserv.ocr.OcrResult
import com.guaishoudejia.x4doublesysfserv.ui.components.BleDeviceScanSheet
import com.guaishoudejia.x4doublesysfserv.ui.components.BleFloatingButton
import kotlin.coroutines.resume

class GeckoActivity : ComponentActivity() {
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var geckoView: GeckoView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var bleEspClient: BleEspClient? = null
    private lateinit var bleConnectionManager: BleConnectionManager
    private var pendingStartScan = false
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted && pendingStartScan) startScanAndShow()
        pendingStartScan = false
    }
    
    // ================= 核心状态变量 =================
    private var currentUrl by mutableStateOf("")
    private var isEbookMode by mutableStateOf(false)
    private var isLoading by mutableStateOf(false)
    private var lastStatus by mutableStateOf("就绪")
    private var logicalPageIndex by mutableIntStateOf(0)
    private val renderHistory = mutableStateListOf<Bitmap>()

    // OCR 状态
    private var isRecognizing by mutableStateOf(false)
    private var ocrResult by mutableStateOf<OcrResult?>(null)
    private var ocrError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { DEFAULT_URL }
        currentUrl = targetUrl

        // 初始化 BLE 连接管理器
        bleConnectionManager = BleConnectionManager(this, this, lifecycleScope)

        // OCR 已在 X4Application 启动时初始化，此处无需再次初始化
        Log.d("GeckoActivity", "OCR 初始化已在应用启动时完成")

        runtime = GeckoRuntime.create(this)
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP) // 统一为桌面模式
            .build()

        session = GeckoSession(settings).apply {
            open(runtime!!)
        }

        setContent {
            var fullScreenBitmap by remember { mutableStateOf<Bitmap?>(null) }
            val scope = rememberCoroutineScope()
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

                // 自动检测阅读页并启用 Ebook 模式（不自动触发同步或扫描）
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

        // 图片预览和 OCR 结果并排显示
        if (isExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .heightIn(max = 250.dp)
                    .background(Color(0xFFF5F5F5).copy(alpha = 0.95f))
                    .padding(8.dp)
            ) {
            // 左侧：图片预览
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

            // 右侧：OCR 识别结果
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(200.dp)
                    .background(Color.White)
                    .padding(8.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "OCR 识别", fontSize = 11.sp, color = Color.Gray)
                    if (isRecognizing) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // OCR 内容
                when {
                    isRecognizing -> {
                        Text("正在识别...", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    ocrError != null -> {
                        Text("识别失败", fontSize = 12.sp, color = Color.Red)
                    }
                    ocrResult != null -> {
                        // 统计信息
                        Text(
                            text = "${ocrResult!!.blocks.size} 段落",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // 文字内容（可滚动）
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                ocrResult!!.blocks.forEachIndexed { index, block ->
                                    Text(
                                        text = block.text,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                    if (index < ocrResult!!.blocks.size - 1) {
                                        HorizontalDivider(
                                            color = Color.LightGray.copy(alpha = 0.5f),
                                            thickness = 0.5.dp,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        Text("暂无识别结果", fontSize = 12.sp, color = Color.Gray)
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
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "历史: ${renderHistory.size}页", fontSize = 11.sp)
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

    // ================= 核心业务同步逻辑 =================

    private fun performSync(pageNum: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            isLoading = true
            ocrResult = null
            ocrError = null
            try {
                // 1. 处理翻页逻辑
                val diff = pageNum - logicalPageIndex
                if (diff != 0) {
                    val key = if (diff > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                    repeat(kotlin.math.abs(diff)) { dispatchArrow(key); delay(600) }
                    logicalPageIndex = pageNum
                }

                // 2. 注入 JS 直接截取 Canvas 像素数据 (应对 WeRead 的 Canvas 渲染)
                Log.d("SYNC", "开始抓取 Canvas 图片")
                val bitmap = captureCanvasViaJs()

                if (bitmap == null) {
                    Log.e("SYNC", "Canvas 抓取失败，返回 null")
                    lastStatus = "抓取失败: 页面 Canvas 未就绪"
                    isLoading = false
                    return@launch
                }

                Log.d("SYNC", "Canvas 抓取成功，尺寸: ${bitmap.width}x${bitmap.height}")
                
                // 2.5. 绘制检测框到图像上（用于预览调试）
                val boxedBitmap = withContext(Dispatchers.Default) {
                    OcrHelper.drawDetectionBoxes(bitmap)
                }
                Log.d("SYNC", "已在图像上绘制检测框")
                
                // 2.6. 对图像进行二值化处理（二值化后再用于识别）
                val binarizedBitmap = withContext(Dispatchers.Default) {
                    OcrHelper.binarizeBitmap(bitmap)
                }
                Log.d("SYNC", "图像二值化完成")
                
                // 添加带框的原始图像到历史（用于预览）
                renderHistory.add(boxedBitmap)

                // 3. 渲染并发送到 BLE 设备（如果已连接）- 使用原始 bitmap
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
                        Log.w("SYNC", "发送 BLE 数据失败（不影响 OCR）", e)
                    }
                }
                lastStatus = "同步成功: 第 $pageNum 页"

                // 4. OCR 文字识别 (在后台线程执行，独立于 BLE) - 使用二值化后的图像
                Log.d("SYNC", "开始 OCR 识别...")
                isRecognizing = true

                withContext(Dispatchers.Default) {
                    try {
                        val ocr = OcrHelper.recognizeText(binarizedBitmap)
                        Log.d("SYNC", "OCR 识别完成: ${ocr.blocks.size} 段落")

                        withContext(Dispatchers.Main) {
                            ocrResult = ocr
                            isRecognizing = false
                            lastStatus = "同步完成 (OCR: ${ocr.blocks.size} 段落)"
                        }
                    } catch (e: Exception) {
                        Log.e("SYNC", "OCR 识别异常", e)
                        withContext(Dispatchers.Main) {
                            ocrError = e.message
                            isRecognizing = false
                            lastStatus = "同步完成 (OCR 失败)"
                        }
                    }
                }
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
        Log.d("CAPTURE", "开始抓取，prevProgress=${s.progressDelegate}")

        suspendCancellableCoroutine { cont ->
            val prevProgress = s.progressDelegate
            Log.d("CAPTURE", "设置新的 progressDelegate")

            s.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    Log.d("CAPTURE", "onPageStart: $url")
                    if (url.startsWith("x4bmp://")) {
                        try {
                            val data = url.removePrefix("x4bmp://")
                            Log.d("CAPTURE", "data length: ${data.length}")
                            if (data == "ERROR") {
                                Log.w("CAPTURE", "JS 返回 ERROR")
                                if (!cont.isCompleted) cont.resume(null)
                            } else {
                                val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                                Log.d("CAPTURE", "Base64 解码成功，bytes size: ${bytes.size}")
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp == null) {
                                    Log.e("CAPTURE", "BitmapFactory.decodeByteArray 返回 null")
                                } else {
                                    Log.d("CAPTURE", "Bitmap 解码成功: ${bmp.width}x${bmp.height}")
                                }
                                if (!cont.isCompleted) cont.resume(bmp)
                            }
                        } catch (e: Exception) {
                            Log.e("CAPTURE", "解析异常", e)
                            if (!cont.isCompleted) cont.resume(null)
                        } finally {
                            s.progressDelegate = prevProgress
                            Log.d("CAPTURE", "恢复 prevProgress")
                        }
                    }
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {}
                override fun onProgressChange(session: GeckoSession, progress: Int) {}
                override fun onSecurityChange(session: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) {}
            }

            // 核心脚本：自动识别微信读书的 Canvas 并截图回传
            val jsCode = """
                (function() {
                    try {
                        var canvas = document.querySelector('canvas') || document.querySelector('.readerContent_container canvas');
                        if (!canvas) { location.href = 'x4bmp://ERROR'; return; }
                        var data = canvas.toDataURL('image/png').split(',')[1];
                        location.href = 'x4bmp://' + data;
                    } catch(e) {
                        location.href = 'x4bmp://ERROR';
                    }
                })();
            """.trimIndent().replace("\n", "").replace("    ", " ")
            Log.d("CAPTURE", "执行 JS")
            s.loadUri("javascript:$jsCode")
        }
    }

    override fun onPause() {
        super.onPause()
        // 关闭 OcrHelper 资源
        OcrHelper.close()
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
