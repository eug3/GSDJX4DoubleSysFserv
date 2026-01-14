# Android 应用中直接调用 RemoteServe 透明代理

## 概述

直接在 Android 应用中调用 RemoteServe 代理，比使用 `browser.proxy.onRequest` API 更简单、更高效。可以完全绕过浏览器层，直接进行网络请求。

## 核心优势

| 方式 | 优点 | 缺点 |
|------|------|------|
| **直接调用 RemoteServe** | ✅ 简单直接 | 需要在 Android 代码中集成 |
| | ✅ 高效（无浏览器开销） | |
| | ✅ 全面控制（请求头、超时等） | |
| | ✅ 易于调试 | |
| **browser.proxy.onRequest** | ✅ 可在扩展中控制 | ❌ 需要浏览器支持 |
| | | ❌ 性能开销大 |
| | | ❌ 兼容性问题 |

## 快速开始

### 1. 基础使用

```kotlin
import com.guaishoudejia.x4doublesysfserv.network.RemoteServeProxy

class MyActivity : AppCompatActivity() {
    private val proxy = RemoteServeProxy("172.16.8.248:8080")
    
    fun fetchWereadData() {
        lifecycleScope.launch {
            val response = proxy.proxyRequest(
                targetUrl = "https://weread.qq.com/api/bookshelf",
                method = "GET",
                headers = mapOf(
                    "Cookie" to "your_cookie_here"
                )
            )
            
            if (response.success) {
                Log.d("TAG", "数据: ${response.body}")
            }
        }
    }
}
```

### 2. 三种代理方式

#### 方式 A: 透明代理（推荐）

```kotlin
val proxy = RemoteServeProxy()

val response = proxy.proxyRequest(
    targetUrl = "https://weread.qq.com/api/user/info",
    method = "GET"
)

// 内部自动转换为:
// http://172.16.8.248:8080/proxy/https/weread.qq.com/api/user/info
```

**特点:**
- ✅ URL 自动转换，无需手动拼接
- ✅ 支持查询参数 (?param=value)
- ✅ 最简洁

#### 方式 B: WeRead 专用端点

```kotlin
val response = proxy.proxyWeReadRequest(
    targetUrl = "https://weread.qq.com/api/search",
    method = "POST",
    body = """{"query": "Python"}"""
)

// 发送 POST 请求到:
// http://172.16.8.248:8080/api/weread/proxy
```

**特点:**
- ✅ JSON 格式请求体自动处理
- ✅ 专为 WeRead API 优化
- ✅ 更好的错误处理

#### 方式 C: 直接获取 OkHttpClient

```kotlin
val httpClient = proxy.getHttpClient()

val request = Request.Builder()
    .url("https://weread.qq.com/api/...")
    .build()

val response = httpClient.newCall(request).execute()
```

**特点:**
- ✅ 完全控制，集成现有代码
- ✅ 适合已有 OkHttp 的项目

## 实际应用场景

### 场景 1: 在 GeckoActivity 中加载内容

```kotlin
class GeckoActivity : ComponentActivity() {
    private val proxy by lazy { RemoteServeProxy() }
    
    private fun loadWereadViaProxy(url: String) {
        lifecycleScope.launch {
            try {
                isLoading = true
                
                val response = proxy.proxyRequest(
                    targetUrl = url,
                    method = "GET",
                    headers = getCurrentHeaders()
                )
                
                if (response.isSuccessful()) {
                    // 方式 1: 加载到 GeckoView
                    geckoSession?.loadString(response.body, "text/html")
                    
                    // 方式 2: 解析内容后自己处理
                    val doc = parseHtmlResponse(response.body)
                    updateUI(doc)
                } else {
                    showError("加载失败: ${response.message}")
                }
            } finally {
                isLoading = false
            }
        }
    }
    
    private fun getCurrentHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 Android GeckoView",
            "Accept-Language" to "zh-CN",
            "Cookie" to getCookieString()
        )
    }
}
```

### 场景 2: 代理文件下载

```kotlin
suspend fun downloadEpubBook(bookId: String): ByteArray? {
    val proxy = RemoteServeProxy()
    
    val response = proxy.proxyRequest(
        targetUrl = "https://weread.qq.com/api/book/$bookId/download",
        method = "POST",
        body = """{"format": "epub"}"""
    )
    
    return if (response.isSuccessful()) {
        response.body.toByteArray()
    } else {
        null
    }
}
```

### 场景 3: OCR 识别流程

```kotlin
suspend fun recognizeText(imageBitmap: Bitmap): String? {
    val imageBase64 = bitmapToBase64(imageBitmap)
    val proxy = RemoteServeProxy()
    
    val response = proxy.proxyRequest(
        targetUrl = "https://weread.qq.com/api/ocr/recognize",
        method = "POST",
        headers = mapOf("Content-Type" to "application/json"),
        body = """{"image": "$imageBase64", "lang": "ch"}"""
    )
    
    return if (response.isSuccessful()) {
        parseOcrResult(response.body)
    } else {
        null
    }
}
```

### 场景 4: 批量请求

```kotlin
suspend fun fetchMultipleBooks(bookIds: List<String>): Map<String, ProxyResponse> {
    val proxy = RemoteServeProxy()
    
    return bookIds.associate { bookId ->
        bookId to proxy.proxyRequest(
            targetUrl = "https://weread.qq.com/api/book/$bookId/info",
            method = "GET"
        )
    }
}
```

## 单例模式使用

如果希望全应用使用同一个代理实例：

```kotlin
// 在应用启动时（Application.onCreate 或 MainActivity.onCreate）
RemoteServeProxyManager.init("172.16.8.248:8080")

// 之后任何地方都可以：
suspend fun someFunction() {
    val proxy = RemoteServeProxyManager.getInstance()
    val response = proxy.proxyRequest(...)
}
```

## 快捷函数

```kotlin
// 快速 GET 请求
val response = proxyGet("https://weread.qq.com/api/user/info")

// 快速 POST 请求
val response = proxyPost(
    "https://weread.qq.com/api/search",
    body = """{"query": "Python"}"""
)
```

## 超时和错误处理

```kotlin
val proxy = RemoteServeProxy(
    remoteServeAddr = "172.16.8.248:8080",
    connectTimeout = 30,    // 连接超时（秒）
    readTimeout = 60,       // 读取超时（秒）
    writeTimeout = 30       // 写入超时（秒）
)

try {
    val response = proxy.proxyRequest(...)
    
    when {
        response.isSuccessful() -> {
            // 处理成功（2xx 状态码）
            Log.d("TAG", response.body)
        }
        response.statusCode in 400..499 -> {
            // 客户端错误
            Log.e("TAG", "请求错误: ${response.message}")
        }
        response.statusCode in 500..599 -> {
            // 服务器错误
            Log.e("TAG", "服务器错误: ${response.message}")
        }
        else -> {
            // 代理错误（网络问题等）
            Log.e("TAG", "代理失败: ${response.message}")
        }
    }
} catch (e: Exception) {
    Log.e("TAG", "异常: ${e.message}")
}
```

## 响应数据结构

```kotlin
data class ProxyResponse(
    val success: Boolean,           // 是否成功
    val statusCode: Int,            // HTTP 状态码
    val headers: Map<String, String>, // 响应头
    val body: String,               // 响应体（文本）
    val message: String = ""        // 错误信息
)

// 使用
val response = proxy.proxyRequest(...)
if (response.isSuccessful()) {  // 检查 200-299
    val contentType = response.headers["content-type"]
    val body = response.body
}
```

## RemoteServe 支持的端点

| 端点 | 用途 | 例子 |
|------|------|------|
| `/proxy/{scheme}/{host}/{path}` | 透明代理任意 URL | `/proxy/https/weread.qq.com/api/user` |
| `/api/weread/proxy` | WeRead API 专用 | POST JSON 请求 |
| `/health` | 健康检查 | `curl http://...:8080/health` |

## 与 GeckoView 集成

```kotlin
class GeckoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化代理
        RemoteServeProxyManager.init("172.16.8.248:8080")
        
        setupGeckoRuntime()
        // ... 其他初始化代码
    }
    
    private fun navigateToUrl(url: String) {
        lifecycleScope.launch {
            // 通过代理加载
            val response = RemoteServeProxyManager.getInstance().proxyRequest(
                targetUrl = url,
                method = "GET",
                headers = getDefaultHeaders()
            )
            
            if (response.isSuccessful()) {
                geckoSession?.loadString(response.body, "text/html")
            }
        }
    }
}
```

## 调试技巧

### 1. 启用日志

```kotlin
val proxy = RemoteServeProxy()
// 已自动添加 Log.d 和 Log.e，在 Logcat 中过滤 "RemoteServeProxy"
```

### 2. 检查代理可用性

```kotlin
lifecycleScope.launch {
    if (proxy.isAvailable()) {
        Log.d("TAG", "✅ RemoteServe 可用")
    } else {
        Log.d("TAG", "❌ RemoteServe 不可用")
    }
}
```

### 3. 查看原始 URL 转换

```kotlin
val targetUrl = "https://weread.qq.com/api/user/info?param=value"
val proxyUrl = proxy.getProxyUrl(targetUrl)
Log.d("TAG", "原始: $targetUrl")
Log.d("TAG", "代理: $proxyUrl")
// 输出: http://172.16.8.248:8080/proxy/https/weread.qq.com/api/user/info?param=value
```

## 常见问题

### Q: 如何处理 Cookie？
```kotlin
val response = proxy.proxyRequest(
    targetUrl = url,
    method = "GET",
    headers = mapOf("Cookie" to cookieString)
)
```

### Q: 如何上传文件？
```kotlin
val fileContent = readFileAsString("/path/to/file")
val response = proxy.proxyRequest(
    targetUrl = "https://weread.qq.com/api/upload",
    method = "POST",
    body = fileContent
)
```

### Q: 如何处理重定向？
OkHttpClient 自动跟随重定向（最多 20 次）。无需额外处理。

### Q: 如何设置请求超时？
```kotlin
val proxy = RemoteServeProxy(
    remoteServeAddr = "172.16.8.248:8080",
    connectTimeout = 60,
    readTimeout = 120,
    writeTimeout = 60
)
```

## 总结

- ✅ **直接调用最简单** - 只需 3 行代码
- ✅ **性能最好** - 无浏览器开销
- ✅ **最容易调试** - 完整的日志和错误信息
- ✅ **最灵活** - 支持所有 HTTP 方法和请求类型

相比 `browser.proxy.onRequest` API，直接调用 RemoteServe 是更好的选择！
