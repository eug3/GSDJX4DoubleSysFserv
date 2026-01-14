# GeckoView 网络请求拦截 API 文档

## 📋 概述

GeckoView 提供了 **WebRequestDelegate** 接口用于拦截和修改 HTTP/HTTPS 请求。这是原生支持的网络请求拦截机制，可以拦截所有类型的请求，包括：
- 主文档请求
- 子资源请求（图片、脚本、样式表、字体等）
- AJAX/Fetch 请求
- 重定向请求

---

## ✅ 正式 API 接口

### 1. **GeckoSession.WebRequestDelegate** 接口

#### 接口位置
```
org.mozilla.geckoview.GeckoSession.WebRequestDelegate
```

#### 核心方法签名

```kotlin
public interface WebRequestDelegate {
    
    /**
     * 拦截网络请求
     * @param session GeckoSession 实例
     * @param request WebRequest 对象，包含请求信息
     * @return LoadRequestReturn 对象，包含处理结果
     */
    @Nullable
    fun onLoadRequest(
        session: GeckoSession,
        request: WebRequest
    ): GeckoSession.WebRequestDelegate.LoadRequestReturn?
}
```

---

### 2. **WebRequest 类**

请求对象包含以下属性：

| 属性 | 类型 | 说明 |
|------|------|------|
| `uri` | String | 请求的完整 URI |
| `method` | String | HTTP 方法（GET、POST、PUT 等） |
| `headers` | Map<String, String> | 请求头字典 |
| `cacheMode` | int | 缓存模式标志 |
| `isTopLevel` | boolean | 是否是顶级文档请求 |
| `isDirectNavigation` | boolean | 是否是直接导航 |

#### 常见的 WebRequest 缓存模式
- `CACHE_MODE_DEFAULT` - 默认缓存模式
- `CACHE_MODE_BYPASS` - 绕过缓存
- `CACHE_MODE_ONLY` - 仅使用缓存

---

### 3. **LoadRequestReturn 类**

返回值用于指定如何处理请求：

```kotlin
public class LoadRequestReturn {
    
    /**
     * 允许请求继续（使用修改后的请求）
     * @param request 修改后的 WebRequest 对象
     */
    constructor(request: WebRequest)
    
    /**
     * 获取最终的请求对象
     */
    fun getRequest(): WebRequest
}
```

---

## 💻 实现示例

### 示例 1：基础网络拦截

```kotlin
// 在 GeckoActivity 中设置拦截器
private fun setupRequestInterceptor(session: GeckoSession) {
    session.webRequestDelegate = object : GeckoSession.WebRequestDelegate {
        
        override fun onLoadRequest(
            session: GeckoSession,
            request: WebRequest
        ): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
            
            val uri = request.uri
            Log.d("NetworkInterceptor", "拦截请求: $uri")
            
            // 1. 跳过某些特殊请求
            if (shouldSkipProxy(uri)) {
                return null  // 使用默认处理
            }
            
            // 2. 修改请求（例如改为代理 URL）
            val modifiedUri = convertToProxyUrl(uri)
            
            // 3. 创建修改后的请求
            val modifiedRequest = WebRequest.Builder(modifiedUri)
                .method(request.method)
                .apply {
                    // 复制原始请求头
                    request.headers?.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                .cacheMode(request.cacheMode)
                .build()
            
            // 4. 返回修改后的请求
            return GeckoSession.WebRequestDelegate.LoadRequestReturn(modifiedRequest)
        }
    }
}

// 判断是否跳过代理
private fun shouldSkipProxy(uri: String): Boolean {
    return uri.startsWith("data:") ||
           uri.startsWith("about:") ||
           uri.startsWith("blob:") ||
           uri.startsWith("moz-extension:") ||
           uri.startsWith("file://")
}

// URL 转换示例
private fun convertToProxyUrl(originalUrl: String): String {
    return try {
        val url = java.net.URL(originalUrl)
        val scheme = url.protocol      // https
        val host = url.host            // example.com
        val path = url.path            // /api/data
        val query = url.query          // param=value
        val fullPath = path + (query?.let { "?$it" } ?: "")
        
        // 转换为代理地址
        "http://your-proxy:8080/proxy/$scheme/$host$fullPath"
    } catch (e: Exception) {
        originalUrl  // 转换失败时返回原始 URL
    }
}
```

### 示例 2：阻止特定资源

```kotlin
override fun onLoadRequest(
    session: GeckoSession,
    request: WebRequest
): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
    
    val uri = request.uri
    
    // 阻止加载广告域名
    if (uri.contains("ads.example.com") || 
        uri.contains("analytics.example.com")) {
        return null  // null 表示阻止请求
    }
    
    return null  // 允许其他请求
}
```

### 示例 3：添加自定义请求头

```kotlin
override fun onLoadRequest(
    session: GeckoSession,
    request: WebRequest
): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
    
    val modifiedRequest = WebRequest.Builder(request.uri)
        .method(request.method)
        .apply {
            // 复制原始请求头
            request.headers?.forEach { (key, value) ->
                addHeader(key, value)
            }
            // 添加自定义请求头
            addHeader("User-Agent", "Custom-Mobile-Browser/1.0")
            addHeader("X-Custom-Header", "CustomValue")
            addHeader("Authorization", "Bearer your-token")
        }
        .cacheMode(request.cacheMode)
        .build()
    
    return GeckoSession.WebRequestDelegate.LoadRequestReturn(modifiedRequest)
}
```

### 示例 4：记录所有网络请求

```kotlin
override fun onLoadRequest(
    session: GeckoSession,
    request: WebRequest
): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
    
    // 记录请求详情
    val requestLog = """
        === 网络请求 ===
        方法: ${request.method}
        URI: ${request.uri}
        请求头: ${request.headers?.entries?.joinToString { "${it.key}: ${it.value}" } ?: "无"}
        缓存模式: ${request.cacheMode}
        顶级: ${request.isTopLevel}
    """.trimIndent()
    
    Log.d("NetworkLog", requestLog)
    
    return null  // 允许请求继续
}
```

---

## 📊 与 NavigationDelegate 的区别

GeckoView 还提供了 **NavigationDelegate** 用于处理导航事件，但它只能拦截**顶级**文档请求：

| 特性 | WebRequestDelegate | NavigationDelegate |
|------|-------------------|-------------------|
| 拦截所有请求 | ✅ 是 | ❌ 否（仅顶级） |
| 拦截子资源 | ✅ 是 | ❌ 否 |
| 拦截 AJAX/Fetch | ✅ 是 | ❌ 否 |
| 修改请求 | ✅ 支持 | ❌ 不支持 |
| 获取完整请求头 | ✅ 是 | ❌ 否 |

### NavigationDelegate 用法（仅供参考）

```kotlin
session.navigationDelegate = object : GeckoSession.NavigationDelegate {
    
    override fun onLoadRequest(
        session: GeckoSession,
        request: NavigationDelegate.LoadRequest
    ): GeckoResult<AllowOrDeny>? {
        // 仅用于拦截顶级文档导航
        return GeckoResult.allow()
    }
    
    override fun onSubframeLoadRequest(
        session: GeckoSession,
        request: NavigationDelegate.LoadRequest
    ): GeckoResult<AllowOrDeny>? {
        // 仅用于拦截子框架导航
        return GeckoResult.allow()
    }
}
```

---

## 🔒 设置拦截器的完整代码

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // 创建或获取 GeckoSession
    val session = GeckoSession()
    val runtime = GeckoRuntime.create(this)
    session.open(runtime)
    
    // 设置网络请求拦截器
    setupRequestInterceptor(session)
    
    // 将 Session 附加到 GeckoView
    geckoView.setSession(session)
    
    // 加载网页
    session.loadUri("https://example.com")
}

private fun setupRequestInterceptor(session: GeckoSession) {
    session.webRequestDelegate = object : GeckoSession.WebRequestDelegate {
        
        override fun onLoadRequest(
            session: GeckoSession,
            request: WebRequest
        ): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
            
            val uri = request.uri
            
            // 你的拦截逻辑
            Log.d("Interceptor", "请求: $uri")
            
            // 允许请求继续（可选修改）
            return null
        }
    }
}
```

---

## 🎯 常见使用场景

### 1. 缓存本地资源
```kotlin
override fun onLoadRequest(
    session: GeckoSession,
    request: WebRequest
): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
    
    // 如果是特定的大文件，使用本地缓存
    if (request.uri.contains("large-library.js")) {
        val localPath = "file:///android_asset/cache/large-library.js"
        return GeckoSession.WebRequestDelegate.LoadRequestReturn(
            WebRequest.Builder(localPath).build()
        )
    }
    return null
}
```

### 2. 修改 User-Agent
```kotlin
override fun onLoadRequest(
    session: GeckoSession,
    request: WebRequest
): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
    
    val modifiedRequest = WebRequest.Builder(request.uri)
        .method(request.method)
        .apply {
            request.headers?.forEach { (key, value) ->
                if (key != "User-Agent") {
                    addHeader(key, value)
                }
            }
            addHeader("User-Agent", "Custom-Browser/1.0 (Android)")
        }
        .build()
    
    return GeckoSession.WebRequestDelegate.LoadRequestReturn(modifiedRequest)
}
```

### 3. 转发到代理服务器
```kotlin
override fun onLoadRequest(
    session: GeckoSession,
    request: WebRequest
): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
    
    if (!request.uri.startsWith("data:") && !request.uri.startsWith("blob:")) {
        val url = java.net.URL(request.uri)
        val proxyUrl = "http://proxy-server:8080/forward?" +
            "scheme=${url.protocol}&" +
            "host=${url.host}&" +
            "path=${url.path}"
        
        return GeckoSession.WebRequestDelegate.LoadRequestReturn(
            WebRequest.Builder(proxyUrl)
                .method(request.method)
                .apply {
                    request.headers?.forEach { (k, v) ->
                        addHeader(k, v)
                    }
                }
                .build()
        )
    }
    return null
}
```

---

## ⚠️ 注意事项

1. **线程安全**：`onLoadRequest` 在主线程（UI Thread）中调用
2. **性能**：避免在拦截器中执行耗时操作（网络请求、数据库操作）
3. **特殊 URI**：某些 URI 不能被拦截，包括：
   - `data:` 开头的 Data URL
   - `blob:` 开头的 Blob URL
   - `moz-extension:` 扩展 URL
   - `about:` 特殊页面

4. **null 返回值含义**：
   - 返回 `null` = 允许请求继续，不进行修改
   - 返回 `LoadRequestReturn` = 使用修改后的请求

---

## 🔍 对比其他方案

### GeckoView 官方支持方案
✅ **WebRequestDelegate**（推荐）
- 拦截所有 HTTP/HTTPS 请求
- 可修改请求内容
- 支持子资源请求

### 替代方案（不推荐）

❌ **系统代理 + Proxy.setDefault()**
- 需要 Android 系统级代理配置
- 不能针对应用级别配置
- 对某些请求可能不生效

❌ **JavaScript 注入**
- 需要 JS 代码修改
- 无法拦截图片、样式等二进制资源
- 性能开销大

❌ **ContentBlockingController**
- 仅用于块列表拦截
- 无法修改请求
- 功能有限

---

## 📚 API 参考链接

- [GeckoView 官方文档](https://mozilla.github.io/geckoview/)
- [GeckoSession 源码](https://searchfox.org/mozilla-central/source/mobile/android/geckoview)
- [WebRequest API 详情](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebRequest.html)

---

## ✅ 工作区中的实现示例

您的项目在 [GeckoActivity.kt](GeckoActivity.kt#L370) 中已经实现了完整的网络请求拦截器：

```kotlin
// 文件: app/src/main/java/com/guaishoudejia/x4doublesysfserv/GeckoActivity.kt
// 方法: setupRequestInterceptor()

private fun setupRequestInterceptor(session: GeckoSession) {
    session.webRequestDelegate = object : GeckoSession.WebRequestDelegate {
        override fun onLoadRequest(
            session: GeckoSession,
            request: WebRequest
        ): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
            val originalUri = request.uri
            
            // 跳过特殊协议
            if (shouldSkipProxyForUri(originalUri)) {
                return null
            }
            
            // 转换为代理 URL
            val proxyUri = convertToProxyUrl(originalUri)
            
            // 创建修改后的请求
            val proxyRequest = WebRequest.Builder(proxyUri)
                .method(request.method)
                .apply {
                    request.headers?.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                .cacheMode(request.cacheMode)
                .build()
            
            return GeckoSession.WebRequestDelegate.LoadRequestReturn(proxyRequest)
        }
    }
}
```

---

## 总结

| 项目 | 内容 |
|------|------|
| **正式 API** | `GeckoSession.WebRequestDelegate` ✅ |
| **方法名** | `onLoadRequest(session, request)` |
| **返回类型** | `LoadRequestReturn` 或 `null` |
| **拦截范围** | 所有 HTTP/HTTPS 请求 + 子资源 |
| **修改支持** | ✅ 支持 URI、方法、请求头 |
| **推荐指数** | ⭐⭐⭐⭐⭐ |

