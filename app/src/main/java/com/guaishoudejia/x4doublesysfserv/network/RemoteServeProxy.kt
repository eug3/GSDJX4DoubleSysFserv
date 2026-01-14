package com.guaishoudejia.x4doublesysfserv.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * RemoteServe 透明代理工具类
 * 
 * 用法示例：
 * ```kotlin
 * val proxy = RemoteServeProxy("172.16.8.248:8080")
 * 
 * // 方式1: 直接代理原始请求
 * val response = proxy.proxyRequest("https://weread.qq.com/api/...", "GET", headers)
 * 
 * // 方式2: 使用 OkHttpClient 的代理功能
 * val client = proxy.getHttpClient()  // 返回配置好代理的 OkHttpClient
 * ```
 */
class RemoteServeProxy(
    private val remoteServeAddr: String = "172.16.8.248:8080",
    private val connectTimeout: Long = 30,
    private val readTimeout: Long = 30,
    private val writeTimeout: Long = 30
) {
    companion object {
        private const val TAG = "RemoteServeProxy"
        
        // RemoteServe 支持的代理端点
        const val ENDPOINT_PROXY = "/proxy/"                    // 透明代理: /proxy/{scheme}/{host}/{path}
        const val ENDPOINT_WEREAD_PROXY = "/api/weread/proxy"  // WeRead API 代理
    }
    
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeout, TimeUnit.SECONDS)
        .readTimeout(readTimeout, TimeUnit.SECONDS)
        .writeTimeout(writeTimeout, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    
    /**
     * 检查 RemoteServe 是否可用
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val request = Request.Builder()
                .url("http://$remoteServeAddr/health")
                .build()
            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "RemoteServe 不可用: ${e.message}")
            false
        }
    }
    
    /**
     * 方式1: 使用透明代理端点 (推荐)
     * 通过 /proxy/{scheme}/{host}/{path} 格式代理任意请求
     * 
     * 示例：
     *   原URL: https://weread.qq.com/api/user/info
     *   代理URL: http://172.16.8.248:8080/proxy/https/weread.qq.com/api/user/info
     */
    suspend fun proxyRequest(
        targetUrl: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): ProxyResponse = withContext(Dispatchers.IO) {
        try {
            val url = URL(targetUrl)
            val scheme = url.protocol          // https
            val host = url.host                // weread.qq.com
            val path = url.path + (url.query?.let { "?$it" } ?: "")  // /api/user/info?param=value
            
            val proxyUrl = "http://$remoteServeAddr$ENDPOINT_PROXY$scheme/$host$path"
            Log.d(TAG, "代理请求: $method $proxyUrl")
            
            val requestBuilder = Request.Builder()
                .url(proxyUrl)
                .method(method, body?.toRequestBody())
            
            // 添加请求头
            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }
            
            val response = httpClient.newCall(requestBuilder.build()).execute()
            
            ProxyResponse(
                success = response.isSuccessful,
                statusCode = response.code,
                headers = response.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" },
                body = response.body?.string() ?: "",
                message = response.message
            )
        } catch (e: Exception) {
            Log.e(TAG, "代理请求失败: ${e.message}", e)
            ProxyResponse(
                success = false,
                statusCode = 0,
                headers = emptyMap(),
                body = "",
                message = "代理错误: ${e.message}"
            )
        }
    }
    
    /**
     * 方式2: 使用 WeRead 专用代理端点
     * 通过 POST /api/weread/proxy 发送 JSON 格式的请求
     */
    suspend fun proxyWeReadRequest(
        targetUrl: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): ProxyResponse = withContext(Dispatchers.IO) {
        try {
            val proxyUrl = "http://$remoteServeAddr$ENDPOINT_WEREAD_PROXY"
            
            // 构建 JSON 请求体
            val jsonBody = buildJsonRequest(targetUrl, method, headers, body)
            
            val request = Request.Builder()
                .url(proxyUrl)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            Log.d(TAG, "WeRead 代理请求: $targetUrl")
            
            val response = httpClient.newCall(request).execute()
            
            ProxyResponse(
                success = response.isSuccessful,
                statusCode = response.code,
                headers = response.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" },
                body = response.body?.string() ?: "",
                message = response.message
            )
        } catch (e: Exception) {
            Log.e(TAG, "WeRead 代理请求失败: ${e.message}", e)
            ProxyResponse(
                success = false,
                statusCode = 0,
                headers = emptyMap(),
                body = "",
                message = "代理错误: ${e.message}"
            )
        }
    }
    
    /**
     * 获取配置好代理的 OkHttpClient
     * 用于直接在应用中使用
     */
    fun getHttpClient(): OkHttpClient {
        return httpClient
    }
    
    /**
     * 获取代理 URL (用于 GeckoView/WebView 的代理设置)
     */
    fun getProxyUrl(targetUrl: String): String {
        val url = URL(targetUrl)
        val scheme = url.protocol
        val host = url.host
        val path = url.path + (url.query?.let { "?$it" } ?: "")
        return "http://$remoteServeAddr$ENDPOINT_PROXY$scheme/$host$path"
    }
    
    /**
     * 构建 JSON 格式的代理请求
     */
    private fun buildJsonRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?
    ): String {
        return """
            {
                "url": "$url",
                "method": "$method",
                "headers": ${headers.toJsonMap()},
                "body": "${body?.replace("\"", "\\\"") ?: ""}"
            }
        """.trimIndent()
    }
    
    /**
     * Map 转 JSON 格式
     */
    private fun Map<String, String>.toJsonMap(): String {
        return "{" + entries.joinToString(", ") { (k, v) ->
            "\"$k\": \"${v.replace("\"", "\\\"")}\"" 
        } + "}"
    }
}

/**
 * 代理响应数据类
 */
data class ProxyResponse(
    val success: Boolean,
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,
    val message: String = ""
) {
    fun isSuccessful(): Boolean = statusCode in 200..299
}

// 扩展函数：便捷调用
object RemoteServeProxyManager {
    private var instance: RemoteServeProxy? = null
    
    fun init(remoteServeAddr: String = "172.16.8.248:8080") {
        instance = RemoteServeProxy(remoteServeAddr)
    }
    
    fun getInstance(): RemoteServeProxy {
        return instance ?: run {
            instance = RemoteServeProxy()
            instance!!
        }
    }
}
