package com.guaishoudejia.x4doublesysfserv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 微信读书代理客户端
 * 将 GeckoView 的请求转发到 RemoteServe 代理
 */
object WeReadProxyClient {
    private const val TAG = "WeReadProxyClient"
    private const val BASE_URL = "http://localhost:8080"
    private const val PROXY_ENDPOINT = "$BASE_URL/api/weread/proxy"
    private const val COOKIES_ENDPOINT = "$BASE_URL/api/weread/cookies"
    private const val INJECT_COOKIES_ENDPOINT = "$BASE_URL/api/weread/cookies/inject"
    private const val CONFIG_ENDPOINT = "$BASE_URL/api/weread/config"

    // 本地保存的 Cookie
    private var localCookies = mutableMapOf<String, String>()

    /**
     * 代理请求
     * @param path weread 路径 (如 "/web/reader/xxx")
     * @param method HTTP 方法
     * @param body 请求体 (可选)
     * @param extraHeaders 额外请求头
     * @return 响应字符串
     */
    suspend fun proxy(
        path: String,
        method: String = "GET",
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        try {
            val url = URL(PROXY_ENDPOINT)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000

            // 构建请求体
            val requestBody = JSONObject().apply {
                put("url", path)
                put("method", method)
                put("headers", JSONObject(extraHeaders))
                body?.let { put("body", it) }
            }.toString()

            // 发送请求
            conn.outputStream.use { os ->
                os.write(requestBody.toByteArray())
            }

            // 读取响应
            val responseCode = conn.responseCode
            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }

            Log.d(TAG, "代理响应: $responseCode, body length: ${responseBody.length}")

            // 解析并保存 Cookie
            parseAndSaveCookies(conn.headerFields)

            responseBody
        } catch (e: Exception) {
            Log.e(TAG, "代理请求失败: ${e.message}", e)
            ""
        }
    }

    /**
     * 获取远程 Cookie
     */
    suspend fun fetchRemoteCookies(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val url = URL(COOKIES_ENDPOINT)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)

            val cookies = mutableMapOf<String, String>()
            val cookieArray = json.optJSONArray("cookies")
            if (cookieArray != null) {
                for (i in 0 until cookieArray.length()) {
                    val cookie = cookieArray.getJSONObject(i)
                    cookies[cookie.getString("name")] = cookie.getString("value")
                }
            }

            Log.d(TAG, "获取远程 Cookie: ${cookies.size} 个")
            cookies
        } catch (e: Exception) {
            Log.e(TAG, "获取远程 Cookie 失败: ${e.message}", e)
            emptyMap()
        }
    }

    /**
     * 同步 Cookie 到远程
     */
    suspend fun syncCookiesToRemote(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(INJECT_COOKIES_ENDPOINT)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            val cookieArray = org.json.JSONArray()
            for ((name, value) in localCookies) {
                val cookie = JSONObject().apply {
                    put("name", name)
                    put("value", value)
                    put("domain", ".weread.qq.com")
                    put("path", "/")
                    put("secure", false)
                }
                cookieArray.put(cookie)
            }

            conn.outputStream.use { os ->
                os.write(cookieArray.toString().toByteArray())
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            json.optBoolean("success", false)
        } catch (e: Exception) {
            Log.e(TAG, "同步 Cookie 失败: ${e.message}", e)
            false
        }
    }

    /**
     * 添加本地 Cookie
     */
    fun addCookie(name: String, value: String) {
        localCookies[name] = value
        Log.d(TAG, "添加本地 Cookie: $name")
    }

    /**
     * 获取本地 Cookie 字符串
     */
    fun getCookieHeader(): String {
        return localCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 清除所有 Cookie
     */
    fun clearCookies() {
        localCookies.clear()
        Log.d(TAG, "清除所有本地 Cookie")
    }

    /**
     * 解析响应中的 Cookie 并保存
     */
    private fun parseAndSaveCookies(headers: Map<String, List<String>>) {
        val setCookieHeaders = headers["Set-Cookie"] ?: return
        for (header in setCookieHeaders) {
            // 解析 Set-Cookie 头
            val parts = header.split(";")
            if (parts.isNotEmpty()) {
                val cookiePart = parts[0]
                val keyValue = cookiePart.split("=", limit = 2)
                if (keyValue.size == 2) {
                    localCookies[keyValue[0].trim()] = keyValue[1].trim()
                    Log.d(TAG, "保存 Cookie: ${keyValue[0].trim()}")
                }
            }
        }
    }

    /**
     * 获取代理配置
     */
    suspend fun getConfig(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL(CONFIG_ENDPOINT)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(response)
        } catch (e: Exception) {
            Log.e(TAG, "获取配置失败: ${e.message}", e)
            null
        }
    }

    /**
     * 检查 RemoteServe 是否可用
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"

            val response = conn.responseCode
            response == 200
        } catch (e: Exception) {
            Log.w(TAG, "RemoteServe 不可用: ${e.message}")
            false
        }
    }
}
