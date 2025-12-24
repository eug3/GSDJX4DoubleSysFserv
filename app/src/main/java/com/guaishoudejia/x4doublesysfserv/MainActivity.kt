package com.guaishoudejia.x4doublesysfserv

import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.guaishoudejia.x4doublesysfserv.ui.theme.GSDJX4DoubleSysFservTheme
import com.guaishoudejia.x4doublesysfserv.GeckoActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GSDJX4DoubleSysFservTheme {
                WeReadBrowserScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun WeReadBrowserScreen(modifier: Modifier = Modifier) {
    val logTag = "WeReadBrowser"
    var currentUrl by remember { mutableStateOf("https://weread.qq.com/") }
    var showSitesMenu by remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val openInGeckoRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            Box {
                Column(
                    modifier = Modifier.padding(start = 16.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FloatingActionButton(onClick = { showSitesMenu = !showSitesMenu }) {
                        Icon(Icons.Default.Menu, contentDescription = "读书站点")
                    }
                    DropdownMenu(
                        expanded = showSitesMenu,
                        onDismissRequest = { showSitesMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("微信读书") },
                            onClick = {
                                currentUrl = "https://weread.qq.com/"
                                openInGeckoRef.value(currentUrl)
                                showSitesMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("榕树下") },
                            onClick = {
                                currentUrl = "https://www.rongshu.com/"
                                openInGeckoRef.value(currentUrl)
                                showSitesMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("起点中文网") },
                            onClick = {
                                currentUrl = "https://www.qidian.com/"
                                openInGeckoRef.value(currentUrl)
                                showSitesMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("晋江文学城") },
                            onClick = {
                                currentUrl = "https://www.jjwxc.net/"
                                openInGeckoRef.value(currentUrl)
                                showSitesMenu = false
                            }
                        )
                    }
                }
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentUrl,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    fontSize = 12.sp
                )
                Button(onClick = { webViewRef.value?.reload() }) {
                    Text("刷新")
                }
                Button(onClick = { GeckoActivity.launch(context, currentUrl) }) {
                    Text("Gecko")
                }
            }
        }
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            factory = { context ->
                WebView(context).apply {
                    webViewRef.value = this

                    WebView.setWebContentsDebuggingEnabled(true)

                    // Helper: open URL in GeckoView activity
                    fun openInGecko(url: String) {
                        GeckoActivity.launch(context, url)
                    }
                    // Expose launcher to outer UI (menus, etc.)
                    openInGeckoRef.value = { url -> openInGecko(url) }

                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = true
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            msg?.let {
                                Log.d("WebView", "${it.messageLevel()} ${it.sourceId()}:${it.lineNumber()} ${it.message()}")
                            }
                            return true
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            // Route popup/new-window into the same WebView to keep navigation inline
                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                            transport.webView = view
                            resultMsg.sendToTarget()
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        @Suppress("DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            if (url.isNullOrBlank()) return false
                            openInGecko(url)
                            return true
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                            val url = request?.url?.toString()
                            if (url.isNullOrBlank()) return false
                            openInGecko(url)
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            url?.let { currentUrl = it }
                            Log.i("WebView", "Page loaded: $url")
                            CookieManager.getInstance().flush()
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            val ua = view?.settings?.userAgentString
                            val vp = view?.settings?.useWideViewPort
                            val ov = view?.settings?.loadWithOverviewMode
                            Log.d("WebView", "Page started: $url | UA=${ua?.take(60)}... | VP=$vp OV=$ov")
                        }
                    }

                    // Initial open: launch via Gecko
                    openInGecko(currentUrl)
                }
            },
            update = { wv ->
                webViewRef.value = wv
            }
        )
        Log.d(logTag, "UI State: currentUrl=$currentUrl")
    }
}
