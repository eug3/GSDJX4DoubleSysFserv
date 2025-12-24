package com.guaishoudejia.x4doublesysfserv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.guaishoudejia.x4doublesysfserv.ui.theme.GSDJX4DoubleSysFservTheme
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Color
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView.WebViewTransport
import android.os.Message
import android.webkit.ConsoleMessage
import android.view.View

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"

        private const val EMULATOR_UPLOAD_URL = "http://10.0.2.2:18080/image"
        private const val DEVICE_UPLOAD_URL = "http://192.168.31.105:18080/image"

        private fun isEmulator(): Boolean {
            val fp = Build.FINGERPRINT
            return fp.contains("generic") || fp.contains("emulator") || fp.contains("sdk_gphone")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            // Permissions granted, ensure battery optimization exemption then ask for screen capture
            Log.i(TAG, "Runtime permissions granted: ${permissions.keys.joinToString()}")
            ensureBatteryOptimizationExemptThenCapture()
        } else {
            // Handle the case where the user denies the permissions
            // You might want to show a message to the user
            val denied = permissions.filterValues { !it }.keys
            Log.w(TAG, "Runtime permissions denied: ${denied.joinToString()}")
        }
    }

    private val batteryOptLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Regardless of user choice, proceed to start service.
        Log.i(TAG, "Returned from battery optimization settings; starting service")
        startX4Service(pendingStartUrl)
        pendingStartUrl = null
    }

    private var pendingStartUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GSDJX4DoubleSysFservTheme {
                WeReadBrowserScreen(
                    modifier = Modifier.fillMaxSize(),
                    onStartRender = { url ->
                        pendingStartUrl = url
                        requestBlePermissions()
                    }
                )
            }
        }
    }

    private fun requestBlePermissions() {
        Log.i(TAG, "Start button clicked; requesting runtime permissions")
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsNotGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNotGranted.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: ${permissionsNotGranted.joinToString()}")
            requestPermissionLauncher.launch(permissionsNotGranted.toTypedArray())
        } else {
            Log.i(TAG, "All runtime permissions already granted")
            ensureBatteryOptimizationExemptThenCapture()
        }
    }

    private fun ensureBatteryOptimizationExemptThenCapture() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        if (ignoring) {
            Log.i(TAG, "Battery optimizations already ignored; starting service")
            startX4Service(pendingStartUrl)
            pendingStartUrl = null
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        if (intent.resolveActivity(packageManager) == null) {
            Log.w(TAG, "Battery optimization request activity not found; starting service directly")
            startX4Service(pendingStartUrl)
            pendingStartUrl = null
            return
        }
        batteryOptLauncher.launch(intent)
    }

    private fun startX4Service(startUrl: String?) {
        val uploadUrl = if (isEmulator()) EMULATOR_UPLOAD_URL else DEVICE_UPLOAD_URL
        Log.i(TAG, "Starting X4Service with uploadUrl=$uploadUrl startUrl=$startUrl")

        // Ensure cookies from the interactive WebView are flushed before the
        // headless WebView in the service tries to load the reader page.
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }

        val intent = Intent(this, X4Service::class.java).apply {
            putExtra(X4Service.EXTRA_UPLOAD_URL, uploadUrl)
            putExtra(X4Service.EXTRA_ENABLE_BLE, false)
            if (!startUrl.isNullOrBlank()) {
                putExtra(X4Service.EXTRA_TARGET_URL, startUrl)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun WeReadBrowserScreen(
    modifier: Modifier = Modifier,
    onStartRender: (String) -> Unit,
) {
    val logTag = "WeReadBrowser"
    var currentUrl by remember { mutableStateOf("https://weread.qq.com/") }
    var status by remember { mutableStateOf("就绪") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun isWeReadReaderLandingUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val prefix = "https://weread.qq.com/web/reader/"
        if (!url.startsWith(prefix)) return false
        // Heuristic: real reader URLs usually contain a chapter suffix like 'k...'
        // Landing/book pages often don't.
        return !url.contains("k")
    }

    fun tryEnterWeReadReading(wv: WebView) {
        try {
            val js = """
                (function(){
                    try {
                        function clickByText(txt){
                            var els = document.querySelectorAll('button,a,div,span');
                            for (var i=0;i<els.length;i++){
                                var el = els[i];
                                if (!el) continue;
                                var t = (el.innerText||'').trim();
                                if (t === txt){
                                    try { el.click(); return 'clicked:'+txt; } catch(e) {}
                                }
                            }
                            return 'no:'+txt;
                        }
                        var r = clickByText('阅读');
                        if (r.indexOf('clicked') === 0) return r;
                        r = clickByText('开始阅读');
                        if (r.indexOf('clicked') === 0) return r;
                        r = clickByText('下一页');
                        return r;
                    } catch(e){ return 'err:' + e; }
                })();
            """.trimIndent()
            wv.evaluateJavascript(js, null)
        } catch (_: Throwable) {
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewRef?.destroy()
            } catch (_: Throwable) {
            }
            webViewRef = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        webViewRef?.loadUrl("https://weread.qq.com/")
                    }) {
                        Text("微信读书")
                    }
                    Button(onClick = {
                        val url = (webViewRef?.url ?: currentUrl).trim()
                        if (url.isNotBlank()) onStartRender(url)
                    }) {
                        Text("电子书阅读")
                    }
                }
                Text(
                    text = "当前页：$currentUrl",
                    maxLines = 2,
                )
                Text(
                    text = "状态：$status",
                    maxLines = 1,
                )
            }
        }
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            factory = { ctx ->
                // IMPORTANT: Use an interactive WebView here so user can log in/search/select book.
                // The service will reuse the same app cookie store when rendering headlessly.
                val wv = WebView(ctx)
                webViewRef = wv

                wv.setBackgroundColor(Color.WHITE)

                try {
                    WebView.setWebContentsDebuggingEnabled(true)
                } catch (_: Throwable) {
                }

                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                try {
                    cm.setAcceptThirdPartyCookies(wv, true)
                } catch (_: Throwable) {
                }

                val s = wv.settings
                s.javaScriptEnabled = true
                s.domStorageEnabled = true
                s.loadsImagesAutomatically = true
                // Better on-phone browsing behavior.
                s.useWideViewPort = true
                s.loadWithOverviewMode = true
                // Browser-like behavior: allow window.open/target=_blank, but route it back
                // into the same visible WebView via WebChromeClient.onCreateWindow.
                s.javaScriptCanOpenWindowsAutomatically = true
                s.setSupportMultipleWindows(true)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                // Use Android Chrome UA (system default). This typically improves WeRead compatibility
                // and reduces GPU tile memory pressure compared to desktop UA.
                try {
                    s.userAgentString = WebSettings.getDefaultUserAgent(ctx)
                } catch (_: Throwable) {
                }

                wv.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message
                    ): Boolean {
                        // Route the popup/new window into the same WebView.
                        return try {
                            val transport = resultMsg.obj as? WebViewTransport
                            if (transport != null) {
                                transport.webView = view
                                resultMsg.sendToTarget()
                                status = "新窗口 -> 当前页"
                                Log.i(logTag, "UI WebView onCreateWindow routed to same WebView")
                                true
                            } else {
                                false
                            }
                        } catch (t: Throwable) {
                            Log.w(logTag, "UI WebView onCreateWindow error", t)
                            false
                        }
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        if (consoleMessage != null) {
                            Log.i(
                                logTag,
                                "console ${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}"
                            )
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val u = request?.url?.toString()
                        if (!u.isNullOrBlank()) {
                            Log.i(logTag, "UI WebView nav url=$u")
                            currentUrl = u
                        }
                        val scheme = request?.url?.scheme?.lowercase()
                        // Keep everything in WebView for http(s).
                        // IMPORTANT: do NOT blanket-block non-http(s) schemes; WeRead/Chromium frequently uses
                        // about:blank / blob: / data: for internal navigation and document bootstrapping.
                        if (scheme != null && scheme != "http" && scheme != "https") {
                            val allowSchemes = setOf("about", "blob", "data", "file", "javascript")
                            if (!allowSchemes.contains(scheme)) {
                                status = "拦截外部跳转: $scheme"
                                Log.w(logTag, "UI WebView blocked external scheme=$scheme url=$u")
                                return true
                            }
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        status = "加载中"
                        if (!url.isNullOrBlank()) {
                            currentUrl = url
                        }
                        Log.i(logTag, "UI WebView onPageStarted url=$url")

                        // Workaround for blank reader page on some devices:
                        // Chromium may hit GPU tile memory limits and draw nothing.
                        // Switching the reader page to software layer avoids tile raster memory pressure.
                        try {
                            val v = view
                            if (v != null) {
                                val shouldSoftware = url?.contains("weread.qq.com/web/reader/") == true
                                val targetLayer = if (shouldSoftware) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE
                                if (v.layerType != targetLayer) {
                                    v.setLayerType(targetLayer, null)
                                    Log.i(logTag, "UI WebView layerType -> ${if (shouldSoftware) "SOFTWARE" else "HARDWARE"} url=$url")
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(logTag, "UI WebView setLayerType failed", t)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (!url.isNullOrBlank()) {
                            currentUrl = url
                        }
                        status = "完成"
                        Log.i(logTag, "UI WebView onPageFinished url=$url")

                        // If we landed on the book page, try to enter the real reader mode.
                        val v = view
                        if (v != null && isWeReadReaderLandingUrl(url)) {
                            status = "尝试进入阅读..."
                            tryEnterWeReadReading(v)
                            v.postDelayed({ tryEnterWeReadReading(v) }, 1200)
                        }

                        try {
                            CookieManager.getInstance().flush()
                        } catch (_: Throwable) {
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        status = "错误: ${error?.errorCode ?: "?"}"
                        Log.w(logTag, "UI WebView onReceivedError url=${request?.url} code=${error?.errorCode}")
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                        status = "渲染进程崩溃"
                        Log.w(logTag, "UI WebView onRenderProcessGone didCrash=${detail?.didCrash()}")
                        return true
                    }
                }

                wv.loadUrl(currentUrl)
                wv
            },
            update = { wv ->
                webViewRef = wv
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    GSDJX4DoubleSysFservTheme {
        WeReadBrowserScreen(onStartRender = {})
    }
}