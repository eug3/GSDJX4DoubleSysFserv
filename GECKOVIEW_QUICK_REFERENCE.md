# GeckoView 网络请求拦截 - 快速参考指南

## 🚀 3 分钟快速开始

### 最小化代码示例

```kotlin
// 步骤 1: 创建 Session 和 Runtime
val runtime = GeckoRuntime.create(this)
val session = GeckoSession()
session.open(runtime)

// 步骤 2: 设置拦截器（核心）
session.webRequestDelegate = object : GeckoSession.WebRequestDelegate {
    override fun onLoadRequest(
        session: GeckoSession,
        request: WebRequest
    ): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
        
        // 拦截 https://example.com 的请求
        if (request.uri.contains("example.com")) {
            Log.d("TAG", "拦截请求: ${request.uri}")
        }
        
        // 修改 URI 示例
        val newUri = request.uri.replace("old", "new")
        return GeckoSession.WebRequestDelegate.LoadRequestReturn(
            WebRequest.Builder(newUri)
                .method(request.method)
                .apply {
                    request.headers?.forEach { (k, v) -> addHeader(k, v) }
                }
                .build()
        )
        
        // 或允许请求继续（不修改）
        return null
    }
}

// 步骤 3: 将 Session 附加到 GeckoView
geckoView.setSession(session)

// 步骤 4: 加载网页
session.loadUri("https://example.com")
```

---

## 📖 方法签名

```kotlin
// 接口
interface GeckoSession.WebRequestDelegate {
    fun onLoadRequest(
        session: GeckoSession,
        request: WebRequest
    ): GeckoSession.WebRequestDelegate.LoadRequestReturn?
}

// WebRequest 属性
class WebRequest {
    val uri: String                      // 请求 URI
    val method: String                   // HTTP 方法
    val headers: Map<String, String>?    // 请求头
    val cacheMode: Int                   // 缓存模式
    val isTopLevel: Boolean              // 是否顶级
}

// 返回值
class LoadRequestReturn {
    constructor(request: WebRequest)     // 创建返回值
}
```

---

## ✅ 返回值处理

| 返回值 | 含义 |
|--------|------|
| `null` | 允许请求继续，不做修改 |
| `LoadRequestReturn(modifiedRequest)` | 使用修改后的请求 |

---

## 🎯 常见操作

### 1️⃣ 拦截并阻止请求

```kotlin
if (request.uri.contains("ad")) {
    return null  // 阻止
}
```

### 2️⃣ 修改请求 URI

```kotlin
val newUri = "https://proxy.com/forward?url=" + request.uri
return GeckoSession.WebRequestDelegate.LoadRequestReturn(
    WebRequest.Builder(newUri).build()
)
```

### 3️⃣ 修改请求头

```kotlin
val modifiedRequest = WebRequest.Builder(request.uri)
    .method(request.method)
    .apply {
        addHeader("Authorization", "Bearer token")
        addHeader("X-Custom", "value")
    }
    .build()
return GeckoSession.WebRequestDelegate.LoadRequestReturn(modifiedRequest)
```

### 4️⃣ 复制所有原始请求头

```kotlin
val builder = WebRequest.Builder(newUri).method(request.method)
request.headers?.forEach { (key, value) ->
    builder.addHeader(key, value)
}
return GeckoSession.WebRequestDelegate.LoadRequestReturn(builder.build())
```

### 5️⃣ 记录请求

```kotlin
Log.d("Network", """
    URI: ${request.uri}
    Method: ${request.method}
    Headers: ${request.headers}
""".trimIndent())
return null
```

---

## 🔴 常见错误

### ❌ 错误 1: 返回错误的对象
```kotlin
// 错误：不能直接返回 WebRequest
return request  // ❌ 错误！

// 正确
return GeckoSession.WebRequestDelegate.LoadRequestReturn(request)  // ✅
```

### ❌ 错误 2: 丢失请求头
```kotlin
// 错误：创建请求时丢失原始请求头
return GeckoSession.WebRequestDelegate.LoadRequestReturn(
    WebRequest.Builder(newUri).build()  // ❌ 没有复制请求头
)

// 正确
return GeckoSession.WebRequestDelegate.LoadRequestReturn(
    WebRequest.Builder(newUri).apply {
        request.headers?.forEach { (k, v) -> addHeader(k, v) }
    }.build()
)  // ✅
```

### ❌ 错误 3: 在主线程执行耗时操作
```kotlin
override fun onLoadRequest(...) {
    Thread.sleep(5000)  // ❌ 会阻塞 UI
    return null
}

// 改用异步（但需要返回结果）
override fun onLoadRequest(...) {
    // 对于简单操作，直接在主线程执行
    // 对于耗时操作，提前在后台线程处理
    return null  // ✅
}
```

---

## 📊 与您项目的集成

您的项目已实现了完整的网络拦截器：

**文件**: `app/src/main/java/com/guaishoudejia/x4doublesysfserv/GeckoActivity.kt`

**方法**: `setupRequestInterceptor(session: GeckoSession)`

**功能**:
- ✅ 拦截所有 HTTP/HTTPS 请求
- ✅ 转换为代理 URL
- ✅ 保留原始请求头
- ✅ 跳过特殊 URI（data:、blob: 等）

---

## 🔧 调试技巧

### 日志记录
```kotlin
override fun onLoadRequest(session: GeckoSession, request: WebRequest) {
    Log.d("NetworkInterceptor", "原始: ${request.uri}")
    Log.d("NetworkInterceptor", "方法: ${request.method}")
    Log.d("NetworkInterceptor", "请求头数: ${request.headers?.size}")
    
    val modifiedUri = "https://proxy:8080/forward?url=${request.uri}"
    Log.d("NetworkInterceptor", "修改为: $modifiedUri")
    
    return GeckoSession.WebRequestDelegate.LoadRequestReturn(
        WebRequest.Builder(modifiedUri)
            .method(request.method)
            .apply {
                request.headers?.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()
    )
}
```

### 条件拦截
```kotlin
override fun onLoadRequest(session: GeckoSession, request: WebRequest) {
    return when {
        request.uri.contains("api.example.com") -> {
            // 修改 API 请求
            ...
        }
        request.uri.endsWith(".js") -> {
            // 修改 JavaScript 请求
            ...
        }
        request.uri.contains("ad") -> {
            // 阻止广告
            null
        }
        else -> null  // 允许其他请求
    }
}
```

---

## 📱 运行时测试

### 测试步骤

1. **设置拦截器**
```kotlin
setupRequestInterceptor(session)
```

2. **加载网页**
```kotlin
session.loadUri("https://weread.qq.com")
```

3. **观察日志输出**
```
D/NetworkInterceptor: 原始: https://weread.qq.com/
D/NetworkInterceptor: 修改为: http://172.16.8.248:8080/proxy/https/weread.qq.com/
D/NetworkInterceptor: 原始: https://cdn.example.com/script.js
D/NetworkInterceptor: 修改为: http://172.16.8.248:8080/proxy/https/cdn.example.com/script.js
```

4. **检查页面是否正常加载**
- 如果代理服务器配置正确，页面应该正常渲染
- 如果没有，检查代理 URL 是否正确

---

## 🎓 学习资源

| 资源 | 链接 |
|------|------|
| GeckoView 官方文档 | https://mozilla.github.io/geckoview/ |
| WebRequest JavaDoc | https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebRequest.html |
| GeckoSession JavaDoc | https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.html |
| Firefox 源码浏览器 | https://searchfox.org/mozilla-central/source/mobile/android/geckoview |

---

## 🤔 FAQ

### Q1: 能否拦截 WebSocket 请求？
**A**: WebRequestDelegate 主要用于 HTTP/HTTPS，WebSocket 需要其他机制。

### Q2: 能否拦截 CORS 预检请求？
**A**: 是的，所有请求都会被拦截，包括 OPTIONS 请求。

### Q3: 修改请求会影响性能吗？
**A**: 如果拦截器逻辑简单，性能影响可以忽略不计。避免在拦截器中执行网络 I/O。

### Q4: 是否可以异步返回修改的请求？
**A**: 当前 API 不支持异步，必须同步返回。如需异步，需要提前准备好结果。

### Q5: 能否完全替代 HTTP 代理？
**A**: 不能完全替代，因为某些系统级请求（DNS、TLS 握手）不能被拦截。

---

## 💡 最佳实践

✅ **推荐**
- 保存原始请求的所有请求头
- 简化拦截逻辑，避免复杂计算
- 使用日志记录关键请求
- 为不同的 URI 模式使用不同的处理逻辑

❌ **不推荐**
- 在拦截器中执行网络请求
- 修改关键的安全相关请求头（除非必要）
- 完全丢弃原始请求信息
- 在主线程执行耗时操作

---

## 🔗 与您项目的关联

**项目**: GSDJX4DoubleSysFserv  
**当前实现**: 网络请求转发到代理服务器  
**代理端口**: 8080  
**代理地址**: 172.16.8.248:8080  

**相关文件**:
- `GeckoActivity.kt` - 主 Activity 和拦截器设置
- `WeReadProxyClient.kt` - 代理客户端
- `RemoteServe/handler/proxy_handler.go` - 代理服务器处理程序

---

**最后更新**: 2026-01-14  
**API 版本**: GeckoView 120+  
**Android 最低版本**: API 21
