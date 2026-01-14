package com.guaishoudejia.x4doublesysfserv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 微信读书代理客户端
 * 将 GeckoView 的请求转发到 RemoteServe 代理
 */
object WeReadProxyClient {
    private const val TAG = "WeReadProxyClient"
    
    // 代理服务器基础地址
    const val BASE_URL = "http://172.16.8.248:8080"
    
    private const val PROXY_ENDPOINT = "$BASE_URL/api/weread/proxy"
    private const val COOKIES_ENDPOINT = "$BASE_URL/api/weread/cookies"
    private const val INJECT_COOKIES_ENDPOINT = "$BASE_URL/api/weread/cookies/inject"
    private const val CONFIG_ENDPOINT = "$BASE_URL/api/weread/config"

    // 本地保存的 Cookie
    private var localCookies = mutableMapOf<String, String>()

    /**
     * 代理请求
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

            val requestBody = JSONObject().apply {
                put("url", path)
                put("method", method)
                put("headers", JSONObject(extraHeaders))
                body?.let { put("body", it) }
            }.toString()

            conn.outputStream.use { os ->
                os.write(requestBody.toByteArray())
            }

            val responseCode = conn.responseCode
            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
            Log.d(TAG, "代理响应: $responseCode")
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
            cookies
        } catch (e: Exception) {
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
                cookieArray.put(JSONObject().apply {
                    put("name", name)
                    put("value", value)
                    put("domain", ".weread.qq.com")
                    put("path", "/")
                })
            }

            conn.outputStream.use { it.write(cookieArray.toString().toByteArray()) }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(response).optBoolean("success", false)
        } catch (e: Exception) {
            false
        }
    }

    fun addCookie(name: String, value: String) {
        localCookies[name] = value
    }

    fun clearCookies() {
        localCookies.clear()
    }

    private fun parseAndSaveCookies(headers: Map<String, List<String>>) {
        headers["Set-Cookie"]?.forEach { header ->
            val cookiePart = header.split(";")[0]
            val keyValue = cookiePart.split("=", limit = 2)
            if (keyValue.size == 2) {
                localCookies[keyValue[0].trim()] = keyValue[1].trim()
            }
        }
    }

    suspend fun getConfig(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL(CONFIG_ENDPOINT)
            val response = url.readText()
            JSONObject(response)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }
}
