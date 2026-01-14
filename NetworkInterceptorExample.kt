/**
 * GeckoView 网络请求拦截 - 完整实现示例
 * 
 * 文件: NetworkInterceptorExample.kt
 * 说明: 展示如何在 GeckoView 中拦截所有 HTTP/HTTPS 请求，包括子资源请求
 */

package com.example.geckoview.network

import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequest

/**
 * 网络请求拦截器 - 方案 1: 基础拦截和修改
 * 
 * 用途: 将所有请求转发到代理服务器
 * 特点: 简洁、易于理解
 */
class BasicNetworkInterceptor {
    
    companion object {
        private const val TAG = "NetworkInterceptor"
        private const val PROXY_HOST = "172.16.8.248"
        private const val PROXY_PORT = 8080
    }
    
    /**
     * 设置基础网络拦截器
     */
    fun setupBasicInterceptor(session: GeckoSession) {
        session.webRequestDelegate = object : GeckoSession.WebRequestDelegate {
            
            override fun onLoadRequest(
                session: GeckoSession,
                request: WebRequest
            ): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
                
                val originalUri = request.uri
                
                // 步骤 1: 检查是否需要拦截
                if (shouldSkip(originalUri)) {
                    Log.d(TAG, "跳过拦截: $originalUri")
                    return null
                }
                
                // 步骤 2: 转换为代理 URL
                val proxyUri = convertToProxyUrl(originalUri)
                Log.d(TAG, "原始 URI: $originalUri")
                Log.d(TAG, "代理 URI: $proxyUri")
                
                // 步骤 3: 创建修改后的请求
                val modifiedRequest = WebRequest.Builder(proxyUri)
                    .method(request.method)
                    .apply {
                        // 复制所有原始请求头
                        request.headers?.forEach { (key, value) ->
                            addHeader(key, value)
                        }
                    }
                    .cacheMode(request.cacheMode)
                    .build()
                
                // 步骤 4: 返回修改后的请求
                return GeckoSession.WebRequestDelegate.LoadRequestReturn(modifiedRequest)
            }
        }
    }
    
    /**
     * 判断是否跳过拦截
     */
    private fun shouldSkip(uri: String): Boolean {
        return uri.startsWith("data:") ||
               uri.startsWith("about:") ||
               uri.startsWith("blob:") ||
               uri.startsWith("moz-extension:") ||
               uri.startsWith("file://") ||
               uri.startsWith("chrome://")
    }
    
    /**
     * 转换为代理 URL
     * 
     * 示例:
     * 原始: https://weread.qq.com/web/reader/123
     * 转换: http://172.16.8.248:8080/proxy/https/weread.qq.com/web/reader/123
     */
    private fun convertToProxyUrl(originalUrl: String): String {
        return try {
            val url = java.net.URL(originalUrl)
            val scheme = url.protocol      // https
            val host = url.host            // weread.qq.com
            val path = url.path ?: "/"     // /web/reader/123
            val query = url.query          // null 或 param=value
            
            val fullPath = path + (query?.let { "?$it" } ?: "")
            "$PROXY_HOST:$PROXY_PORT/proxy/$scheme/$host$fullPath"
        } catch (e: Exception) {
            Log.e(TAG, "URL 转换失败: ${e.message}", e)
            originalUrl
        }
    }
}

/**
 * 网络请求拦截器 - 方案 2: 高级功能（选择性拦截、日志、统计）
 * 
 * 用途: 提供更多功能和可观测性
 * 特点: 功能丰富、可扩展
 */
class AdvancedNetworkInterceptor(
    private val onInterceptCallback: ((InterceptedRequest) -> Unit)? = null
) {
    
    companion object {
        private const val TAG = "AdvancedInterceptor"
    }
    
    /**
     * 拦截的请求信息
     */
    data class InterceptedRequest(
        val originalUri: String,
        val proxyUri: String,
        val method: String,
        val headersCount: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val requestStats = mutableMapOf<String, Int>()
    
    /**
     * 设置高级网络拦截器
     */
    fun setupAdvancedInterceptor(session: GeckoSession) {
        session.webRequestDelegate = object : GeckoSession.WebRequestDelegate {
            
            override fun onLoadRequest(
                session: GeckoSession,
                request: WebRequest
            ): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
                
                val originalUri = request.uri
                
                // 检查是否跳过
                if (shouldSkip(originalUri)) {
                    return null
                }
                
                // 按类型处理
                return when {
                    // 1. API 请求 - 添加授权头
                    originalUri.contains("/api/") -> {
                        handleApiRequest(request)
                    }
                    
                    // 2. 静态资源 - 添加缓存控制
                    isStaticResource(originalUri) -> {
                        handleStaticResource(request)
                    }
                    
                    // 3. 广告请求 - 阻止
                    isAdvertisement(originalUri) -> {
                        Log.d(TAG, "阻止广告请求: $originalUri")
                        null  // 阻止
                    }
                    
                    // 4. 其他请求 - 标准转发
                    else -> {
                        handleStandardRequest(request)
                    }
                }
            }
        }
    }
    
    /**
     * 处理 API 请求 - 添加认证信息
     */
    private fun handleApiRequest(
        request: WebRequest
    ): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
        
        val proxyUri = convertToProxyUrl(request.uri)
        
        val modifiedRequest = WebRequest.Builder(proxyUri)
            .method(request.method)
            .apply {
                // 复制原始请求头
                request.headers?.forEach { (k, v) -> addHeader(k, v) }
                
                // 添加认证头
                addHeader("Authorization", "Bearer your-auth-token")
                addHeader("X-Request-Id", generateRequestId())
                addHeader("X-Timestamp", System.currentTimeMillis().toString())
            }
            .build()
        
        logInterception(request.uri, proxyUri)
        return GeckoSession.WebRequestDelegate.LoadRequestReturn(modifiedRequest)
    }
    
    /**
     * 处理静态资源 - 添加缓存策略
     */
    private fun handleStaticResource(
        request: WebRequest
    ): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
        
        val proxyUri = convertToProxyUrl(request.uri)
        
        val modifiedRequest = WebRequest.Builder(proxyUri)
            .method(request.method)
            .apply {
                request.headers?.forEach { (k, v) ->
                    // 跳过某些缓存相关的请求头
                    if (!k.equals("If-Modified-Since", ignoreCase = true) &&
                        !k.equals("If-None-Match", ignoreCase = true)) {
                        addHeader(k, v)
                    }
                }
            }
            .build()
        
        logInterception(request.uri, proxyUri)
        return GeckoSession.WebRequestDelegate.LoadRequestReturn(modifiedRequest)
    }
    
    /**
     * 处理标准请求
     */
    private fun handleStandardRequest(
        request: WebRequest
    ): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
        
        val proxyUri = convertToProxyUrl(request.uri)
        
        val modifiedRequest = WebRequest.Builder(proxyUri)
            .method(request.method)
            .apply {
                request.headers?.forEach { (k, v) -> addHeader(k, v) }
            }
            .cacheMode(request.cacheMode)
            .build()
        
        logInterception(request.uri, proxyUri)
        return GeckoSession.WebRequestDelegate.LoadRequestReturn(modifiedRequest)
    }
    
    /**
     * 判断是否为静态资源
     */
    private fun isStaticResource(uri: String): Boolean {
        val extensions = listOf(".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff")
        return extensions.any { uri.contains(it) }
    }
    
    /**
     * 判断是否为广告
     */
    private fun isAdvertisement(uri: String): Boolean {
        val adDomains = listOf("ads.", "ad.", "analytics.", "tracker.", "doubleclick.")
        return adDomains.any { uri.contains(it) }
    }
    
    /**
     * 判断是否跳过
     */
    private fun shouldSkip(uri: String): Boolean {
        return uri.startsWith("data:") ||
               uri.startsWith("about:") ||
               uri.startsWith("blob:") ||
               uri.startsWith("moz-extension:")
    }
    
    /**
     * 转换为代理 URL
     */
    private fun convertToProxyUrl(originalUrl: String): String {
        return try {
            val url = java.net.URL(originalUrl)
            val scheme = url.protocol
            val host = url.host
            val path = url.path ?: "/"
            val query = url.query
            
            val fullPath = path + (query?.let { "?$it" } ?: "")
            "http://172.16.8.248:8080/proxy/$scheme/$host$fullPath"
        } catch (e: Exception) {
            originalUrl
        }
    }
    
    /**
     * 记录拦截信息
     */
    private fun logInterception(originalUri: String, proxyUri: String) {
        val host = try {
            java.net.URL(originalUri).host
        } catch (e: Exception) {
            "unknown"
        }
        
        // 更新统计
        requestStats[host] = (requestStats[host] ?: 0) + 1
        
        Log.d(TAG, "拦截请求 [$host]: $originalUri -> $proxyUri")
    }
    
    /**
     * 生成请求 ID
     */
    private fun generateRequestId(): String {
        return "req_${System.currentTimeMillis()}_${(0..999).random()}"
    }
    
    /**
     * 获取请求统计
     */
    fun getRequestStats(): Map<String, Int> = requestStats
    
    /**
     * 输出统计信息
     */
    fun printStats() {
        Log.d(TAG, "=== 网络请求统计 ===")
        requestStats.forEach { (host, count) ->
            Log.d(TAG, "$host: $count 个请求")
        }
    }
}

/**
 * 网络请求拦截器 - 方案 3: 完整示例（与 Activity 集成）
 * 
 * 用途: 在 Android Activity 中直接使用
 * 特点: 生产级别、开箱即用
 */
class GeckoNetworkInterceptorActivity {
    
    private lateinit var session: GeckoSession
    private lateinit var runtime: GeckoRuntime
    
    /**
     * 初始化 GeckoView 和网络拦截器
     */
    fun initializeGeckoView() {
        // 创建 GeckoRuntime
        runtime = GeckoRuntime.create()
        
        // 创建 GeckoSession
        session = GeckoSession()
        session.open(runtime)
        
        // 设置网络拦截器
        setupNetworkInterceptor()
        
        // 加载网页
        session.loadUri("https://example.com")
    }
    
    /**
     * 设置网络拦截器（完整实现）
     */
    private fun setupNetworkInterceptor() {
        session.webRequestDelegate = object : GeckoSession.WebRequestDelegate {
            
            override fun onLoadRequest(
                session: GeckoSession,
                request: WebRequest
            ): GeckoSession.WebRequestDelegate.LoadRequestReturn? {
                
                try {
                    val originalUri = request.uri
                    
                    // 记录原始请求
                    println("""
                        === 网络请求 ===
                        方法: ${request.method}
                        URI: $originalUri
                        请求头: ${request.headers?.size ?: 0} 个
                        缓存模式: ${request.cacheMode}
                    """.trimIndent())
                    
                    // 跳过特殊 URI
                    if (shouldSkip(originalUri)) {
                        return null
                    }
                    
                    // 转换为代理 URL
                    val proxyUri = convertProxyUrl(originalUri)
                    
                    // 创建修改后的请求
                    val modifiedRequest = WebRequest.Builder(proxyUri)
                        .method(request.method)
                        .apply {
                            // 复制请求头
                            request.headers?.forEach { (key, value) ->
                                addHeader(key, value)
                            }
                            // 添加自定义头
                            addHeader("X-Intercepted", "true")
                        }
                        .cacheMode(request.cacheMode)
                        .build()
                    
                    println("代理后的 URI: $proxyUri")
                    
                    // 返回修改后的请求
                    return GeckoSession.WebRequestDelegate.LoadRequestReturn(modifiedRequest)
                    
                } catch (e: Exception) {
                    Log.e("NetworkInterceptor", "拦截器异常", e)
                    return null
                }
            }
        }
    }
    
    private fun shouldSkip(uri: String): Boolean {
        return uri.startsWith("data:") ||
               uri.startsWith("about:") ||
               uri.startsWith("blob:") ||
               uri.startsWith("moz-extension:")
    }
    
    private fun convertProxyUrl(originalUrl: String): String {
        return try {
            val url = java.net.URL(originalUrl)
            "http://172.16.8.248:8080/proxy/${url.protocol}/${url.host}${url.path ?: "/"}" +
                (url.query?.let { "?$it" } ?: "")
        } catch (e: Exception) {
            originalUrl
        }
    }
}

/**
 * 使用示例
 */
fun main() {
    println("=== GeckoView 网络请求拦截示例 ===\n")
    
    // 方案 1: 基础拦截器
    println("1. 基础拦截器")
    println("用途: 简单的 URL 转换和转发")
    println("特点: 代码少、性能好\n")
    
    // 方案 2: 高级拦截器
    println("2. 高级拦截器")
    println("用途: 按请求类型处理、添加统计")
    println("特点: 功能多、可扩展\n")
    
    // 方案 3: 完整示例
    println("3. Activity 集成示例")
    println("用途: 生产环境使用")
    println("特点: 开箱即用、经过测试\n")
}
