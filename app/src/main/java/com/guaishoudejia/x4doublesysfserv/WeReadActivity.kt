package com.guaishoudejia.x4doublesysfserv

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.webkit.*
import android.widget.Toast
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WeReadActivity : Activity() {
    private lateinit var webView: WebView
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var currentUrl = ""

    companion object {
        private const val TAG = "WeReadActivity"
        private const val WEREAD_URL = "https://weread.qq.com"
        private const val READER_PATH_PREFIX = "https://weread.qq.com/web/reader/"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weread)

        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let {
                    currentUrl = it
                    Log.d(TAG, "页面加载完成: $it")

                    if (it.startsWith(READER_PATH_PREFIX)) {
                        Log.d(TAG, "检测到阅读器页面: $it")
                        requestOCRText(it)
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        webView.webChromeClient = WebChromeClient()

        webView.loadUrl(WEREAD_URL)
    }

    private fun requestOCRText(url: String) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "请求 OCR 服务: $url")

                val json = JSONObject().apply {
                    put("url", url)
                }

                val request = Request.Builder()
                    .url("$WEREAD_URL/api/weread/reader/ocr")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val result = JSONObject(responseBody)
                        val text = result.optString("text", "")
                        Log.d(TAG, "OCR 识别成功，文本长度: ${text.length}")
                        showOCRText(text)
                    } else {
                        Log.e(TAG, "OCR 请求失败: $responseBody")
                        Toast.makeText(this@WeReadActivity, "文本识别失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR 请求异常", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WeReadActivity, "文本识别异常: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showOCRText(text: String) {
        sendTextViaBLE(text)
        Toast.makeText(this, "已识别文本 ${text.length} 字", Toast.LENGTH_SHORT).show()
    }

    private fun sendTextViaBLE(text: String) {
        Log.d(TAG, "待发送文本: ${text.take(100)}...")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        webView.destroy()
    }
}
