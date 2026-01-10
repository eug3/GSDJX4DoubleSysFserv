package com.guaishoudejia.x4doublesysfserv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.os.PowerManager
import android.view.KeyEvent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import kotlin.coroutines.resume

class GeckoActivity : ComponentActivity() {
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var geckoView: GeckoView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var bleEspClient: BleEspClient? = null
    
    // ================= 类成员状态：确保逻辑与 UI 共享 =================
    private var currentUrl by mutableStateOf("")
    private var isEbookMode by mutableStateOf(false)
    private var isLoading by mutableStateOf(false)
    private var lastStatus by mutableStateOf("")
    private var logicalPageIndex by mutableIntStateOf(0)
    private val renderHistory = mutableStateListOf<Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { DEFAULT_URL }
        currentUrl = targetUrl
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
            var fullScreenBitmap by remember { mutableStateOf<Bitmap?>(null) }
            val scope = rememberCoroutineScope()

            val density = androidx.compose.ui.platform.LocalDensity.current
            val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
            val aspect = 480f / 800f
            val wPx = metrics.widthPixels
            val hPx = (wPx / aspect).toInt().coerceAtLeast(1)
            val wDp = with(density) { wPx.toDp() }
            val hDp = with(density) { hPx.toDp() }

            // 监听 URL 变化
            DisposableEffect(session) {
                val delegate = object : GeckoSession.ProgressDelegate {
                    override fun onPageStart(session: GeckoSession, url: String) { currentUrl = url }
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

                if (currentUrl.contains("weread.qq.com/web/reader/") && !isEbookMode) {
                    Button(
                        onClick = {
                            isEbookMode = true
                            acquireWakeLock()
                            if (targetDeviceAddress != null && bleEspClient == null) {
                                bleEspClient = BleEspClient(
                                    context = this@GeckoActivity,
                                    deviceAddress = targetDeviceAddress,
                                    scope = scope,
                                    onCommand = { cmd ->
                                        if (cmd.startsWith("PAGE:")) {
                                            cmd.substringAfter("PAGE:").toIntOrNull()?.let { 
                                                performRenderRequest(it) 
                                            }
                                        }
                                    }
                                ).apply { connect() }
                            }
                            performRenderRequest(logicalPageIndex)
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                    ) { Text("电子书阅读模式") }
                }

                if (isEbookMode) {
                    EbookControlPanel(
                        onRefresh = { performRenderRequest(logicalPageIndex) },
                        onPageClick = { fullScreenBitmap = it },
                        onExit = { 
                            isEbookMode = false
                            releaseWakeLock()
                            renderHistory.clear()
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
        onRefresh: () -> Unit,
        onPageClick: (Bitmap) -> Unit,
        onExit: () -> Unit
    ) {
        val pagerState = rememberPagerState(pageCount = { renderHistory.size })
        
        LaunchedEffect(renderHistory.size) {
            if (renderHistory.isNotEmpty()) {
                pagerState.animateScrollToPage(renderHistory.size - 1)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xFFF5F5F5).copy(alpha = 0.95f))
        ) {
            // 照片浏览器式历史预览
            if (renderHistory.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp).padding(vertical = 8.dp)) {
                    HorizontalPager(
                        state = pagerState,
                        contentPadding = PaddingValues(horizontal = 64.dp),
                        pageSpacing = 16.dp,
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

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp), 
                horizontalArrangement = Arrangement.SpaceBetween, 
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "预览历史: ${renderHistory.size}页 | BLE: ${if (bleEspClient?.isConnected() == true) "在线" else "离线"}", fontSize = 11.sp)
                Row {
                    Button(onClick = onRefresh, modifier = Modifier.padding(end = 4.dp)) { Text("刷新", fontSize = 11.sp) }
                    Button(onClick = onExit) { Text("退出", fontSize = 11.sp) }
                }
            }
            
            Box(modifier = Modifier.fillMaxWidth().height(40.dp).background(Color.White).padding(horizontal = 8.dp)) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(20.dp), strokeWidth = 2.dp)
                else Text(text = lastStatus, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.CenterStart))
            }
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
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    contentScale = ContentScale.Fit
                )
                Text("点击关闭", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp).clickable { onClose() })
            }
        }
        BackHandler { onClose() }
    }

    // ================= 辅助业务函数 (作为类成员方法，确保稳定访问) =================

    private fun performRenderRequest(pageNum: Int) {
        lifecycleScope.launch {
            isLoading = true
            try {
                val layoutJson = gotoLogicalPage(pageNum)
                if (!layoutJson.isNullOrEmpty()) {
                    val render = DomLayoutRenderer.renderTo1bpp48k(layoutJson)
                    lastStatus = "渲染成功: 第 $pageNum 页"
                    renderHistory.add(render.previewBitmap)
                    bleEspClient?.let { if (it.isConnected()) it.sendRawBitmap(render.pageBytes48k) }
                } else {
                    lastStatus = "提取 DOM 失败 (P$pageNum)"
                }
            } catch (e: Exception) {
                lastStatus = "处理出错: ${e.message}"
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

    private suspend fun arrowPagerAndRefresh(keyCode: Int): String? {
        dispatchArrow(keyCode)
        delay(200)
        return extractDomLayoutJson()
    }

    private suspend fun gotoLogicalPage(target: Int): String? {
        val diff = target - logicalPageIndex
        if (diff == 0) return extractDomLayoutJson()
        val stepKey = if (diff > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val steps = kotlin.math.abs(diff)
        var json: String? = null
        for (i in 0 until steps) {
            json = arrowPagerAndRefresh(stepKey)
            if (json.isNullOrEmpty()) break
            logicalPageIndex += if (diff > 0) 1 else -1
            delay(150)
        }
        return json
    }

    private suspend fun extractDomLayoutJson(): String? = withContext(Dispatchers.Main) {
        val s = session ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            val prevProgress = s.progressDelegate
            s.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    if (url.startsWith("x4json://")) {
                        val json = Uri.decode(url.removePrefix("x4json://"))
                        if (!cont.isCompleted) cont.resume(json)
                        s.progressDelegate = prevProgress
                    }
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {}
                override fun onProgressChange(session: GeckoSession, progress: Int) {}
                override fun onSecurityChange(session: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) {}
            }
            val jsCode = "(function(){const elements=[];const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null);let n;while(n=walker.nextNode()){var t=n.textContent.trim();if(!t)continue;var p=n.parentElement;if(!p)continue;var cs=getComputedStyle(p);if(cs.display==='none'||cs.visibility==='hidden')continue;var r=p.getBoundingClientRect();if(r.width===0||r.height===0)continue;elements.push({text:t,x:Math.round(r.left),y:Math.round(r.top),width:Math.round(r.width),height:Math.round(r.height),fontSize:cs.fontSize,fontFamily:cs.fontFamily});}location.href='x4json://'+encodeURIComponent(JSON.stringify({viewport:{width:innerWidth,height:innerHeight},elements:elements}));})();"
            s.loadUri("javascript:$jsCode")
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

    private fun getCharBits(char: Char): List<Int> {
        return when (char) {
            'P' -> listOf(0xFF, 0x81, 0x81, 0x81, 0x7F, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01)
            'A' -> listOf(0x7E, 0x81, 0x81, 0x81, 0x7F, 0x81, 0x81, 0x81, 0x81, 0x81, 0x81, 0x81)
            'G' -> listOf(0x7E, 0x81, 0x81, 0x01, 0x1D, 0x21, 0x41, 0x41, 0x41, 0x21, 0x3F, 0x00)
            'E' -> listOf(0x7F, 0x41, 0x41, 0x01, 0x01, 0x1F, 0x01, 0x01, 0x01, 0x41, 0x41, 0x7F)
            '_' -> listOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x3F, 0x00, 0x00)
            '0' -> listOf(0x7E, 0x81, 0x81, 0x81, 0x81, 0x81, 0x81, 0x81, 0x81, 0x81, 0x81, 0x7E)
            '1' -> listOf(0x00, 0x02, 0x02, 0x06, 0x06, 0x02, 0x02, 0x02, 0x02, 0x7F, 0x00, 0x00)
            '2' -> listOf(0x7E, 0x81, 0x01, 0x01, 0x01, 0x06, 0x08, 0x10, 0x20, 0x41, 0x81, 0x7E)
            '3' -> listOf(0x7E, 0x81, 0x01, 0x01, 0x06, 0x02, 0x02, 0x01, 0x01, 0x81, 0x81, 0x7E)
            '4' -> listOf(0x01, 0x01, 0x01, 0x01, 0x01, 0x7F, 0x41, 0x41, 0x41, 0x41, 0x41, 0x00)
            '5' -> listOf(0x7F, 0x41, 0x41, 0x41, 0x41, 0x7F, 0x01, 0x01, 0x01, 0x01, 0x81, 0x7E)
            '6' -> listOf(0x7E, 0x81, 0x81, 0x41, 0x41, 0x7F, 0x49, 0x49, 0x49, 0x49, 0x81, 0x7E)
            '7' -> listOf(0x7F, 0x41, 0x41, 0x01, 0x01, 0x02, 0x02, 0x04, 0x04, 0x08, 0x08, 0x10)
            '8' -> listOf(0x7E, 0x81, 0x81, 0x81, 0x81, 0x7E, 0x81, 0x81, 0x81, 0x81, 0x81, 0x7E)
            '9' -> listOf(0x7E, 0x81, 0x81, 0x81, 0x81, 0x7F, 0x01, 0x01, 0x01, 0x01, 0x81, 0x7E)
            else -> listOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
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
