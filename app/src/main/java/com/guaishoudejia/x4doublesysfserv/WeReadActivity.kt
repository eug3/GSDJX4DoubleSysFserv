package com.guaishoudejia.x4doublesysfserv

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
        private const val OCR_SERVER = "http://192.168.31.183:8080"
        private const val READER_PATH = "/web/reader/"
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
                                    // 2. 开启 DOM 存储（用于保存登录状态和页面路由）
                                    domStorageEnabled = true
                                    // 3. 开启数据库存储
                                    // --- 关键修复：模拟 PC 环境必须开启以下两项 ---
                                    useWideViewPort = true       // 关键：使 WebView 支持 HTML 的 viewport 标签
                                    loadWithOverviewMode = true  // 关键：缩放至屏幕大小
                                    databaseEnabled = true
                                   // userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                                    javaScriptCanOpenWindowsAutomatically = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                                }
                                webViewClient = object : WebViewClient() {
                                    // 关键：拦截跳转逻辑，返回 false 表示由当前 WebView 处理，不跳转到系统浏览器
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        return  false;
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                        super.doUpdateVisitedHistory(view, url, isReload)
                                        url?.let {
                                            Log.d("WeReadActivity", "检测到路径变化: $it")
                                            if (isReaderUrl(it)) {
                                                checkAndOcr(it) // 触发你的 OCR 逻辑
                                            }
                                        }
                                    }
                                }
//

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

        Log.d(TAG, "OCR: $url")
        ocr(url) { processedUrls.remove(url) }
    }

    private fun ocr(url: String, onComplete: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = okHttpClient.newCall(Request.Builder()
                    .url("$OCR_SERVER/api/weread/reader/ocr")
                    .post(JSONObject().put("url", url).toString()
                        .toRequestBody("application/json".toMediaType()))
                    .build()).execute()

                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful) {
                        val text = JSONObject(resp.body?.string() ?: "{}").optString("text", "")
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
