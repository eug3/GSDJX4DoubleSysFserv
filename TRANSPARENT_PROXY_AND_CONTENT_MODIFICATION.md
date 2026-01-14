# RemoteServe 透明代理 + 网页修改完整方案

## 核心原理

```
浏览器请求
    ↓
GeckoView 代理拦截器（URL 转换）
    ↓
http://172.16.8.248:8080/proxy/https/weread.qq.com/...
    ↓
RemoteServe（透明代理）
    ↓
真实 weread.qq.com
    ↓
RemoteServe（应用内容修改）
    ↓
修改后的网页内容
    ↓
浏览器渲染
    ↓
用户看到被修改的网页
```

## 三层架构

### 1. 透明代理层（GeckoView）
URL 转换，使浏览器认为访问的是真实网址，但实际走代理。

### 2. 代理转发层（RemoteServe）
转发请求到真实服务器，获取原始响应。

### 3. 内容修改层（RemoteServe）
拦截响应，修改 HTML/CSS/JS 内容，返回修改后的内容。

## 快速开始

### 步骤 1: 在 Android 中配置透明代理

```kotlin
class GeckoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ... 初始化代码 ...
        
        setupGeckoRuntime()
    }
    
    private fun setupGeckoRuntime() {
        // 初始化 GeckoSession
        geckoSession = GeckoSession()
        
        // 🔑 配置透明代理 - 关键！
        GeckoProxySetupHelper.setupTransparentProxy(
            geckoSession = geckoSession!!,
            remoteServeAddr = "172.16.8.248:8080",
            domains = listOf(
                "weread.qq.com",
                "r.qq.com",
                "*.qq.com"
            )
        )
        
        // 现在加载 URL 时，浏览器会自动走代理
        geckoSession?.loadUri("https://weread.qq.com/")
    }
}
```

### 步骤 2: 在 RemoteServe 中配置内容修改

```bash
# 注入 JavaScript
curl -X POST http://172.16.8.248:8080/api/modify/config \
  -H "Content-Type: application/json" \
  -d '{
    "action": "add_script",
    "content": "console.log(\"Hello from proxy!\");"
  }'

# 注入 CSS
curl -X POST http://172.16.8.248:8080/api/modify/config \
  -H "Content-Type: application/json" \
  -d '{
    "action": "add_style",
    "content": "body { background: #000; color: #0f0; }"
  }'

# 应用预设（调试悬浮窗）
curl -X POST http://172.16.8.248:8080/api/modify/config \
  -H "Content-Type: application/json" \
  -d '{"preset": "debug_overlay"}'

# 查看当前配置
curl http://172.16.8.248:8080/api/modify/config

# 清除所有配置
curl -X DELETE http://172.16.8.248:8080/api/modify/config
```

### 步骤 3: 在 Android 代码中动态配置修改

```kotlin
class GeckoActivity : ComponentActivity() {
    private val contentModifier = RemoteServeContentModifier("172.16.8.248:8080")
    
    private fun setupContentModifications() {
        lifecycleScope.launch {
            // 注入调试悬浮窗
            contentModifier.applyPreset("debug_overlay")
            
            // 注入自定义 JavaScript
            contentModifier.injectScript("""
                console.log('RemoteServe 已连接');
                
                // 监听所有 fetch 请求
                const originalFetch = window.fetch;
                window.fetch = function(url, opts) {
                    console.log('[API]', url);
                    return originalFetch.apply(this, arguments);
                };
            """)
            
            // 注入自定义 CSS
            contentModifier.injectStyle("""
                /* 增大字体 */
                body { font-size: 18px !important; }
                
                /* 移除某些元素 */
                .ad-banner { display: none !important; }
            """)
            
            // HTML 替换
            contentModifier.replaceHTML(
                old = "<div class=\"ads\">",
                new = "<!-- 广告已移除 -->"
            )
            
            // 正则替换
            contentModifier.regexReplace(
                pattern = "<!--(.*?)-->",  // 移除所有注释
                replacement = ""
            )
            
            // 查看当前配置
            val config = contentModifier.getConfig()
            Log.d("TAG", "当前配置: $config")
        }
    }
}
```

## 预设修改器

### 1. weread_css - 微信读书 CSS
```kotlin
contentModifier.applyPreset("weread_css")
// 效果：优化字体、行高、移除广告等
```

### 2. weread_js - 微信读书 JavaScript
```kotlin
contentModifier.applyPreset("weread_js")
// 效果：记录 API 请求、监听错误、发送就绪事件
```

### 3. debug_overlay - 调试悬浮窗
```kotlin
contentModifier.applyPreset("debug_overlay")
// 效果：显示绿色悬浮窗，显示代理状态、请求计数等
```

### 4. remove_tracking - 移除追踪脚本
```kotlin
contentModifier.applyPreset("remove_tracking")
// 效果：移除 Google Analytics、Facebook Pixel 等追踪脚本
```

## 常见修改场景

### 场景 1: 注入监控代码

```kotlin
contentModifier.injectScript("""
    window.addEventListener('load', () => {
        console.log('页面已加载');
        
        // 监听用户交互
        document.addEventListener('click', (e) => {
            console.log('点击:', e.target);
        });
    });
""")
```

### 场景 2: 自动登录

```kotlin
contentModifier.injectScript("""
    // 自动填充登录表单
    window.addEventListener('load', () => {
        const usernameInput = document.querySelector('input[name="username"]');
        const passwordInput = document.querySelector('input[name="password"]');
        
        if (usernameInput && passwordInput) {
            usernameInput.value = 'your_username';
            passwordInput.value = 'your_password';
            
            // 提交表单
            document.querySelector('form').submit();
        }
    });
""")
```

### 场景 3: 修改样式

```kotlin
contentModifier.injectStyle("""
    /* 深色模式 */
    body {
        background-color: #1a1a1a !important;
        color: #ffffff !important;
    }
    
    /* 增大可读性 */
    body, p, div, span {
        font-size: 18px !important;
        line-height: 1.8 !important;
    }
    
    /* 移除广告 */
    .ad, .advertisement, [class*="ad"], [id*="ad"] {
        display: none !important;
    }
    
    /* 隐藏侧边栏 */
    .sidebar, .right-panel {
        display: none !important;
    }
    
    /* 全屏阅读 */
    .content-wrapper {
        max-width: 100% !important;
        width: 100% !important;
    }
""")
```

### 场景 4: 修改 HTML 结构

```kotlin
// 移除所有脚本标签
contentModifier.regexReplace(
    pattern = "<script[^>]*>.*?</script>",
    replacement = ""
)

// 移除所有样式标签
contentModifier.regexReplace(
    pattern = "<style[^>]*>.*?</style>",
    replacement = ""
)

// 移除所有 iframe
contentModifier.regexReplace(
    pattern = "<iframe[^>]*>.*?</iframe>",
    replacement = ""
)
```

### 场景 5: API 拦截修改

```kotlin
contentModifier.injectScript("""
    const originalFetch = window.fetch;
    window.fetch = function(resource, init) {
        const url = typeof resource === 'string' ? resource : resource.url;
        
        // 拦截特定 API
        if (url.includes('/api/bookshelf')) {
            console.log('📚 书架请求被拦截');
            
            // 可以修改请求
            if (init && init.headers) {
                init.headers['X-Custom-Header'] = 'injected-by-proxy';
            }
        }
        
        // 继续原始请求
        return originalFetch.apply(this, arguments)
            .then(response => {
                // 修改响应
                if (url.includes('/api/bookshelf')) {
                    return response.clone().text().then(text => {
                        console.log('📚 原始响应:', text);
                        // 可以修改返回的 JSON
                        const data = JSON.parse(text);
                        // data.books = []; // 移除所有书籍
                        return new Response(JSON.stringify(data));
                    });
                }
                return response;
            });
    };
""")
```

## 响应拦截和修改流程

```
原始 HTML 响应
    ↓
ContentModifier.ModifyResponse()
    ├─ applyHTMLModifications() → 字符串替换
    ├─ applyRegexReplaces() → 正则替换
    ├─ injectScripts() → 注入 <script>
    └─ injectStyles() → 注入 <style>
    ↓
修改后的 HTML
    ↓
更新 Content-Length
    ↓
返回给浏览器
```

## API 文档

### 配置端点

#### POST /api/modify/config - 添加修改

```bash
# 注入脚本
{
  "action": "add_script",
  "content": "JavaScript code here"
}

# 注入样式
{
  "action": "add_style",
  "content": "CSS code here"
}

# HTML 替换
{
  "action": "add_replace",
  "old": "original text",
  "new": "replacement text"
}

# 正则替换
{
  "action": "add_regex",
  "pattern": "regex pattern",
  "replacement": "replacement"
}

# 应用预设
{
  "preset": "weread_css|weread_js|debug_overlay|remove_tracking"
}
```

#### GET /api/modify/config - 获取当前配置

```json
{
  "success": true,
  "config": {
    "inject_scripts": 2,
    "inject_styles": 1,
    "html_replaces": 3,
    "regex_replaces": 1,
    "enabled_domains": ["weread.qq.com", "r.qq.com"]
  }
}
```

#### DELETE /api/modify/config - 清除所有配置

```json
{
  "success": true,
  "message": "All modifications cleared"
}
```

## 透明代理 URL 转换

```
原始 URL: https://weread.qq.com/web/reader/123?page=1
    ↓
转换为代理 URL: http://172.16.8.248:8080/proxy/https/weread.qq.com/web/reader/123?page=1
    ↓
RemoteServe 识别格式：
  - scheme: https
  - host: weread.qq.com
  - path: /web/reader/123
  - query: page=1
    ↓
转发到真实服务器: https://weread.qq.com/web/reader/123?page=1
```

## 修改应用顺序（重要）

1. **HTML 替换** (applyHTMLModifications)
   - 直接字符串替换，最快
   - 适合简单的内容替换

2. **正则替换** (applyRegexReplaces)
   - 使用正则表达式替换
   - 更灵活但更慢

3. **脚本注入** (injectScripts)
   - 在 `</body>` 或 `</html>` 前注入
   - JavaScript 在 HTML 之后加载

4. **样式注入** (injectStyles)
   - 在 `</head>` 或 `<head>` 后注入
   - CSS 优先级被脚本中的样式覆盖

## 性能考虑

- 大文件响应会被全部加载到内存中
- 建议对 HTML 文件应用修改，对大型二进制文件跳过
- 正则表达式替换可能较慢，避免复杂的正则

## 调试技巧

```kotlin
// 1. 启用日志
Log.d("RemoteServeContentModifier", "修改配置: ...")

// 2. 检查配置
val config = contentModifier.getConfig()
Log.d("TAG", "脚本数: ${config?.injectScripts}")
Log.d("TAG", "样式数: ${config?.injectStyles}")

// 3. 使用调试悬浮窗
contentModifier.applyPreset("debug_overlay")
// 绿色悬浮窗会显示：
// - 代理状态
// - 请求计数
// - 修改状态

// 4. 浏览器控制台查看
// - 查看注入的脚本是否执行
// - 查看网络请求
// - 查看错误日志
```

## 安全性注意

⚠️ **重要**：内容修改功能强大，但也可能引入安全问题：

1. **XSS 风险** - 注入的脚本可以访问所有 DOM 和本地存储
2. **CSRF 风险** - 修改表单可能提交到恶意服务器
3. **数据泄露** - 注入脚本可能记录敏感信息

建议：
- ✅ 仅在开发/测试环境使用
- ✅ 审核所有注入的脚本
- ✅ 不要修改支付相关页面
- ✅ 记录所有修改操作

## 常见问题

**Q: 修改没有生效？**
A: 检查：
1. RemoteServe 是否正常运行
2. 是否正确配置了透明代理
3. 是否选中了正确的 MIME 类型
4. 浏览器缓存 - 使用 Hard Refresh (Ctrl+Shift+R)

**Q: 修改后页面崩溃？**
A: 可能是：
1. JavaScript 语法错误 - 检查浏览器控制台
2. DOM 修改冲突 - 避免移除重要元素
3. 样式冲突 - 使用 !important

**Q: 如何修改 API 响应？**
A: 使用注入脚本拦截 fetch：
```kotlin
contentModifier.injectScript("""
    const originalFetch = window.fetch;
    window.fetch = function(url, opts) {
        return originalFetch.apply(this, arguments).then(res => {
            // 修改响应
            return res;
        });
    };
""")
```

## 总结

✅ **透明代理** - 浏览器完全感知不到代理的存在
✅ **内容修改** - 可以任意修改网页内容
✅ **动态配置** - 运行时动态添加修改规则
✅ **预设支持** - 提供常用的预设修改器

这是一个强大的完整解决方案！
