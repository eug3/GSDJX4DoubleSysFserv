package com.guaishoudejia.x4doublesysfserv

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WeReadActivity : ComponentActivity() {

    private var webView: WebView? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var bleClient: BleEspClient? = null
    private var isBleConnected by mutableStateOf(false)
    private var connectedDeviceName by mutableStateOf("")
    private val processedUrls = mutableSetOf<String>()

    private val bluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    companion object {
        private const val TAG = "WeReadActivity"
        private const val OCR_SERVER = "http://172.16.8.248:8080"
        private const val READER_PATH = "/web/reader/"
        private const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    databaseEnabled = true
//                                    userAgentString = PC_UA
                                    javaScriptCanOpenWindowsAutomatically = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }
                                
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                                        Log.d("WeReadJS", "[${cm?.messageLevel()}] ${cm?.message()}")
                                        return true
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        return false
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        Log.d(TAG, "页面加载完成: $url")

                                        // 严格匹配首页
                                        if (url == "https://weread.qq.com" || url == "https://weread.qq.com/") {
                                            view?.evaluateJavascript("""
                                                (function() {
                                                    // 如果已经开始检测或已经点击，则不再执行
                                                    if (window.__weReadLoginDetectionStarted) return;
                                                    window.__weReadLoginDetectionStarted = true;
                                                    
                                                    console.log('开始检测登录按钮...');
                                                    var count = 0;
                                                    var timer = setInterval(function() {
                                                        count++;
                                                        
                                                        // 再次检查标记，防止异步竞争
                                                        if (window.__weReadLoginClicked) {
                                                            clearInterval(timer);
                                                            return;
                                                        }

                                                        var loginLink = null;
                                                        // 1. 获取所有具有该类名的 a 标签并寻找包含“登录”文本的
                                                        var candidates = document.querySelectorAll('a.wr_index_page_top_section_header_action_link');
                                                        for (var i = 0; i < candidates.length; i++) {
                                                            if (candidates[i].innerText.indexOf('登录') !== -1) {
                                                                loginLink = candidates[i];
                                                                break;
                                                            }
                                                        }
                                                        
                                                        // 2. 如果没找到，兜底：遍历页面所有 a 标签寻找“登录”
                                                        if (!loginLink) {
                                                            var anchors = document.getElementsByTagName('a');
                                                            for (var j = 0; j < anchors.length; j++) {
                                                                if (anchors[j].innerText.indexOf('登录') !== -1) {
                                                                    loginLink = anchors[j];
                                                                    break;
                                                                }
                                                            }
                                                        }

                                                        if (loginLink) {
                                                            console.log('执行点击: ' + loginLink.innerText);
                                                            // 标记已点击，防止重复执行
                                                            window.__weReadLoginClicked = true;
                                                            loginLink.click();
                                                            
                                                            // 模拟原生点击事件
                                                            var clickEvent = new MouseEvent('click', {
                                                                'view': window,
                                                                'bubbles': true,
                                                                'cancelable': true
                                                            });
                                                            loginLink.dispatchEvent(clickEvent);
                                                            
                                                            clearInterval(timer);
                                                        }

                                                        if (count > 30) {
                                                            clearInterval(timer);
                                                        }
                                                    }, 500);
                                                })();
                                            """.trimIndent(), null)
                                        }
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                        super.doUpdateVisitedHistory(view, url, isReload)
                                        url?.let {
                                            if (isReaderUrl(it)) {
                                                checkAndOcr(it)
                                            }
                                        }
                                    }
                                }
                                loadUrl("https://weread.qq.com")
                                webView = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    FloatingActionButton(
                        onClick = { if (isBleConnected) showDevice() else scanDevice() },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(56.dp),
                        shape = CircleShape,
                        containerColor = if (isBleConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    ) {
                        Text(if (isBleConnected) "✓" else "📡")
                    }
                }
            }
        }
    }

    private fun isReaderUrl(url: String): Boolean {
        return url.contains(READER_PATH) && url.substringAfter(READER_PATH).length > 20 &&  url.substringAfter(READER_PATH).contains("k")
    }

    private fun checkAndOcr(url: String) {
        if (processedUrls.contains(url)) return
        processedUrls.add(url)
        ocr(url) { processedUrls.remove(url) }
    }

    private fun ocr(url: String, onComplete: () -> Unit) {
        val cookies = CookieManager.getInstance().getCookie(url)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject().apply {
                    put("url", url)
                    put("cookies", cookies ?: "")
                    put("device_id", deviceId ?: "")
                }

                val resp = okHttpClient.newCall(Request.Builder()
                    .url("$OCR_SERVER/api/weread/reader/ocr")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()).execute()

                val bodyStr = resp.body?.string()
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful && bodyStr != null) {
                        val text = JSONObject(bodyStr).optString("text", "")
                        sendBle(text)
                    }
                    onComplete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR error: ${e.message}")
                onComplete()
            }
        }
    }

    private fun sendBle(text: String) {
        if (!isBleConnected || bleClient == null) return
        lifecycleScope.launch {
            try {
                bleClient?.sendJson(JSONObject().apply {
                    put("type", "text")
                    put("content", text)
                }.toString())
            } catch (e: Exception) {
                Log.e(TAG, "BLE error: ${e.message}")
            }
        }
    }

    private fun scanDevice() = startActivity(Intent(this, DeviceScanActivity::class.java))
    private fun showDevice() = Toast.makeText(this, connectedDeviceName, Toast.LENGTH_SHORT).show()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == Activity.RESULT_OK) {
            data?.getStringExtra(DeviceScanActivity.EXTRA_DEVICE_ADDRESS)?.let { addr ->
                bleClient = BleEspClient(
                    context = this,
                    deviceAddress = addr,
                    scope = lifecycleScope,
                    onCommand = { Log.d(TAG, "BLE: $it") }
                )
                lifecycleScope.launch {
                    bleClient?.connect()
                    isBleConnected = true
                    connectedDeviceName = bluetoothAdapter?.getRemoteDevice(addr)?.name ?: addr
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView?.let {
            if (it.canGoBack()) it.goBack()
            else @Suppress("DEPRECATION") super.onBackPressed()
        } ?: super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        bleClient?.close()
    }
}
