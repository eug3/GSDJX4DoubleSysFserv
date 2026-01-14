# Android App + RemoteServe 代理集成说明

## 现状

✅ **完全集成完成** - Android App 现在自动使用 RemoteServe 透明代理

## 工作流程

```
Android App (GeckoView)
    ↓
convertToProxyUrl() - URL 转换
    ↓
https://weread.qq.com/... → http://172.16.8.248:8080/proxy/https/weread.qq.com/...
    ↓
RemoteServe 代理服务器
    ├─ 转发到真实服务器
    ├─ 获取原始响应
    ├─ 应用内容修改 (JS/CSS 注入)
    └─ 返回修改后的内容
    ↓
GeckoView 渲染网页
```

## 快速开始

### 1. 启动 RemoteServe 代理服务器

```bash
cd /Users/beijihu/Github/BleReadBook/RemoteServe
go run .
```

输出：
```
✅ 内容修改器已初始化
🚀 服务器启动在 http://*:8080
📡 透明代理: /proxy/{scheme}/{host}/{path}
🔧 内容修改: POST /api/modify/config
📋 API 代理: POST /api/weread/proxy
🍪 Cookie 同步: GET/POST /api/weread/cookies
```

### 2. 配置代理功能（可选）

默认情况下，代理已启用。如果需要添加修改，可以配置：

```bash
# 添加调试悬浮窗
curl -X POST http://localhost:8080/api/modify/config \
  -H "Content-Type: application/json" \
  -d '{"preset": "debug_overlay"}'

# 添加自定义 JavaScript
curl -X POST http://localhost:8080/api/modify/config \
  -H "Content-Type: application/json" \
  -d '{
    "action": "add_script",
    "content": "console.log(\"✅ RemoteServe Proxy Active\");"
  }'
```

### 3. 编译并安装 Android App

```bash
cd /Users/beijihu/Github/GSDJX4DoubleSysFserv

# 编译
./gradlew :app:assembleDebug

# 安装
./gradlew :app:installDebug

# 或者直接一步到位
./gradlew :app:installDebug
```

### 4. 运行应用

App 启动时会自动：
1. 加载 URL（默认 `https://weread.qq.com/`）
2. 转换为代理 URL（`http://172.16.8.248:8080/proxy/https/weread.qq.com/`）
3. 通过 GeckoView 请求代理 URL
4. RemoteServe 收到请求并转发到真实服务器
5. 获取响应并应用修改
6. 返回给 GeckoView 渲染

## 代码改动

### GeckoActivity.kt 变更

```kotlin
// ✅ 新增：URL 转换函数
private fun convertToProxyUrl(originalUrl: String): String {
    return try {
        val url = java.net.URL(originalUrl)
        val scheme = url.protocol
        val host = url.host
        val path = url.path
        val query = url.query
        val fullPath = path + (query?.let { "?$it" } ?: "")
        
        "http://172.16.8.248:8080/proxy/$scheme/$host$fullPath"
    } catch (e: Exception) {
        Log.e("GeckoActivity", "URL转换失败: ${e.message}", e)
        originalUrl
    }
}

// ✅ 修改：加载 URL 时使用代理
val proxyUrl = convertToProxyUrl(targetUrl)
session.loadUri(proxyUrl)
```

## 如何验证代理是否生效

### 方式 1: 查看日志

Android Studio 的 Logcat 中查看：
```
GeckoActivity: 原始URL: https://weread.qq.com/
GeckoActivity: 代理URL: http://172.16.8.248:8080/proxy/https/weread.qq.com/
```

RemoteServe 服务器日志中查看：
```
Universal proxy: GET /proxy/https/weread.qq.com/ -> https://weread.qq.com/
修改响应内容: weread.qq.com (text/html)
✅ 内容修改: weread.qq.com (text/html) - 修改前 169 -> 修改后 1114 字节
```

### 方式 2: 查看响应头

如果启用了调试悬浮窗，网页上会显示绿色悬浮窗：
```
📡 RemoteServe
🌐 Host: weread.qq.com
请求: 0
```

### 方式 3: 命令行测试

```bash
# 测试代理是否工作
curl -i http://localhost:8080/proxy/https/weread.qq.com/ 2>&1 | grep X-Proxy

# 应该看到响应头：
# X-Proxy-By: RemoteServe
# X-Proxy-Modified: true
```

## 常见配置

### 修改 RemoteServe 地址

如果代理服务器地址不是 `172.16.8.248:8080`，修改 GeckoActivity.kt 中的：

```kotlin
private fun convertToProxyUrl(originalUrl: String): String {
    // ...
    "http://YOUR_SERVER_IP:8080/proxy/$scheme/$host$fullPath"
}
```

### 修改默认 URL

在 GeckoActivity.kt 中修改：

```kotlin
companion object {
    const val DEFAULT_URL = "https://weread.qq.com/"  // ← 修改这里
}
```

### 添加其他域名支持

修改 RemoteServe 的 [handler/content_modifier.go](handler/content_modifier.go)：

```go
EnabledDomains: []string{
    "weread.qq.com",
    "r.qq.com",
    "api.weread.qq.com",
    // 添加更多域名
}
```

## RemoteServe API 端点

| 端点 | 方法 | 用途 |
|------|------|------|
| `/proxy/{scheme}/{host}/{path}` | GET/POST | 透明代理任意 URL |
| `/api/modify/config` | POST | 添加修改规则 |
| `/api/modify/config` | GET | 获取当前配置 |
| `/api/modify/config` | DELETE | 清除所有配置 |
| `/health` | GET | 健康检查 |

## 调试技巧

### 1. 启用所有日志

在 GeckoActivity 中增加日志输出：

```kotlin
Log.d("GeckoActivity", "=== 代理调试 ===")
Log.d("GeckoActivity", "原始URL: $targetUrl")
Log.d("GeckoActivity", "代理URL: $proxyUrl")
Log.d("GeckoActivity", "RemoteServe: 172.16.8.248:8080")
```

### 2. 测试特定修改规则

```bash
# 测试调试悬浮窗
curl -X POST http://localhost:8080/api/modify/config \
  -H "Content-Type: application/json" \
  -d '{"preset": "debug_overlay"}'

# 测试自定义脚本
curl -X POST http://localhost:8080/api/modify/config \
  -H "Content-Type: application/json" \
  -d '{"action":"add_script","content":"alert(\"Test\");"}'
```

### 3. 实时查看配置

```bash
# 查看当前所有修改
curl http://localhost:8080/api/modify/config | jq .
```

### 4. 清除所有配置

```bash
# 恢复到默认（无修改）
curl -X DELETE http://localhost:8080/api/modify/config
```

## 架构文件

| 文件 | 说明 |
|------|------|
| [GeckoActivity.kt](../GSDJX4DoubleSysFserv/app/src/main/java/com/guaishoudejia/x4doublesysfserv/GeckoActivity.kt) | Android 应用主活动 - 包含 URL 转换 |
| [RemoteServeProxy.kt](../GSDJX4DoubleSysFserv/app/src/main/java/com/guaishoudejia/x4doublesysfserv/network/RemoteServeProxy.kt) | 代理工具类（用于 HTTP 请求） |
| [content_modifier.go](handler/content_modifier.go) | 内容修改引擎 |
| [modify_config.go](handler/modify_config.go) | 配置管理 API |
| [proxy_handler.go](handler/proxy_handler.go) | 代理处理和修改集成 |
| [main.go](main.go) | 服务器启动和路由 |

## 完整流程示例

```bash
# 1. 启动代理服务器
cd /Users/beijihu/Github/BleReadBook/RemoteServe
go run .

# 2. 配置修改（在另一个终端）
curl -X POST http://localhost:8080/api/modify/config \
  -H "Content-Type: application/json" \
  -d '{"preset": "debug_overlay"}'

# 3. 编译 Android App
cd /Users/beijihu/Github/GSDJX4DoubleSysFserv
./gradlew :app:installDebug

# 4. 运行应用
# App 会自动走代理，网页上会显示绿色调试悬浮窗
```

## 下一步

### 添加更多修改规则

```bash
# 注入微信读书优化样式
curl -X POST http://localhost:8080/api/modify/config \
  -H "Content-Type: application/json" \
  -d '{"preset": "weread_css"}'

# 添加监控脚本
curl -X POST http://localhost:8080/api/modify/config \
  -H "Content-Type: application/json" \
  -d '{
    "action": "add_script",
    "content": "
      window.addEventListener(\"load\", () => {
        console.log(\"Page loaded via RemoteServe proxy\");
      });
    "
  }'
```

### 动态修改规则

可以在应用运行时，通过调用 API 来动态添加或修改规则，无需重启服务器或重新编译应用。

## 总结

✅ **完整的透明代理方案**
- Android App 自动转换 URL
- RemoteServe 无缝代理转发
- 支持动态内容修改
- 调试友好的日志和 API

享受强大的代理功能！
