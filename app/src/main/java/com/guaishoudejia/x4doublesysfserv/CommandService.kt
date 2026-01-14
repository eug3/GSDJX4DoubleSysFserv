package com.guaishoudejia.x4doublesysfserv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 命令服务 - 前台服务，处理蓝牙命令和 HTTP 请求
 *
 * 功能：
 * 1. 接收蓝牙命令并执行 HTTP 请求
 * 2. 支持 UI 绑定通信
 * 3. 状态通知
 */
class CommandService : Service() {

    companion object {
        private const val TAG = "CommandService"
        private const val CHANNEL_ID = "command_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_URL = "http://172.16.8.248:8080"

        const val ACTION_SYNC = "com.guaishoudejia.x4doublesysfserv.ACTION_SYNC"
        const val ACTION_NEXT_PAGE = "com.guaishoudejia.x4doublesysfserv.ACTION_NEXT_PAGE"
        const val ACTION_PREV_PAGE = "com.guaishoudejia.x4doublesysfserv.ACTION_PREV_PAGE"
        const val ACTION_GO_TO_PAGE = "com.guaishoudejia.x4doublesysfserv.ACTION_GO_TO_PAGE"
        const val ACTION_REFRESH = "com.guaishoudejia.x4doublesysfserv.ACTION_REFRESH"

        const val EXTRA_PAGE_NUMBER = "extra_page_number"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 状态流
    private val _serviceStatus = MutableStateFlow(ServiceStatus.IDLE)
    val serviceStatus: StateFlow<ServiceStatus> = _serviceStatus.asStateFlow()

    private val _lastCommand = MutableStateFlow<String?>(null)
    val lastCommand: StateFlow<String?> = _lastCommand.asStateFlow()

    private val _lastResponse = MutableStateFlow<String?>(null)
    val lastResponse: StateFlow<String?> = _lastResponse.asStateFlow()

    private var remoteServeUrl = DEFAULT_URL

    enum class ServiceStatus {
        IDLE,
        PROCESSING,
        SUCCESS,
        ERROR
    }

    inner class LocalBinder : Binder() {
        fun getService(): CommandService = this@CommandService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CommandService 创建")
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "CommandService 绑定")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "收到命令: ${intent?.action}")

        when (intent?.action) {
            ACTION_SYNC -> executeCommand("SYNC")
            ACTION_NEXT_PAGE -> executeCommand("NEXT")
            ACTION_PREV_PAGE -> executeCommand("PREV")
            ACTION_REFRESH -> executeCommand("REFRESH")
            ACTION_GO_TO_PAGE -> {
                val page = intent.getIntExtra(EXTRA_PAGE_NUMBER, 0)
                executeCommand("PAGE:$page")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CommandService 销毁")
        serviceScope.cancel()
    }

    /**
     * 执行命令
     */
    fun executeCommand(command: String) {
        _serviceStatus.value = ServiceStatus.PROCESSING
        _lastCommand.value = command
        updateNotification("处理命令: $command")

        serviceScope.launch {
            try {
                val result = when (command.uppercase()) {
                    "SYNC" -> syncPage()
                    "NEXT" -> nextPage()
                    "PREV" -> prevPage()
                    "REFRESH" -> refreshPage()
                    else -> {
                        if (command.startsWith("PAGE:")) {
                            val page = command.substringAfter("PAGE:").toIntOrNull()
                            if (page != null) goToPage(page) else "无效页码"
                        } else {
                            "未知命令: $command"
                        }
                    }
                }
                _lastResponse.value = result
                _serviceStatus.value = ServiceStatus.SUCCESS
                updateNotification("命令完成: $command")
            } catch (e: Exception) {
                Log.e(TAG, "执行命令失败", e)
                _lastResponse.value = "错误: ${e.message}"
                _serviceStatus.value = ServiceStatus.ERROR
                updateNotification("命令失败: ${e.message}")
            }
        }
    }

    /**
     * 执行 HTTP 请求
     */
    suspend fun httpRequest(
        path: String,
        method: String = "GET",
        body: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val url = URL("$remoteServeUrl$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 10000

            body?.let {
                conn.doOutput = true
                conn.outputStream.use { os ->
                    os.write(it.toByteArray())
                }
            }

            val responseCode = conn.responseCode
            val response = conn.inputStream.bufferedReader().use { it.readText() }

            Log.d(TAG, "HTTP $method $path -> $responseCode")
            response
        } catch (e: Exception) {
            Log.e(TAG, "HTTP 请求失败: $path", e)
            throw e
        }
    }

    /**
     * 同步当前页
     */
    private suspend fun syncPage(): String {
        Log.d(TAG, "执行同步命令")
        val response = httpRequest("/api/weread/reader/screenshot", "POST")
        val json = JSONObject(response)
        return "同步${if (json.optBoolean("success")) "成功" else "失败"}"
    }

    /**
     * 下一页
     */
    private suspend fun nextPage(): String {
        Log.d(TAG, "执行下一页命令")
        val response = httpRequest("/api/weread/reader/next", "POST")
        val json = JSONObject(response)
        return if (json.optBoolean("success")) "下一页成功" else "下一页失败"
    }

    /**
     * 上一页
     */
    private suspend fun prevPage(): String {
        Log.d(TAG, "执行上一页命令")
        val response = httpRequest("/api/weread/reader/prev", "POST")
        val json = JSONObject(response)
        return if (json.optBoolean("success")) "上一页成功" else "上一页失败"
    }

    /**
     * 刷新当前页
     */
    private suspend fun refreshPage(): String {
        Log.d(TAG, "执行刷新命令")
        return syncPage()
    }

    /**
     * 跳转到指定页
     */
    private suspend fun goToPage(pageNum: Int): String {
        Log.d(TAG, "执行跳转到第 $pageNum 页")
        val body = JSONObject().put("page", pageNum).toString()
        val response = httpRequest("/api/weread/reader/open", "POST", body)
        val json = JSONObject(response)
        return if (json.optBoolean("success")) "跳转成功" else "跳转失败"
    }

    /**
     * 设置 RemoteServe URL
     */
    fun setRemoteServeUrl(url: String) {
        remoteServeUrl = url
        Log.d(TAG, "更新 RemoteServe URL: $url")
    }

    /**
     * 获取服务状态
     */
    fun getStatus(): ServiceStatus = _serviceStatus.value

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "命令服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于后台处理蓝牙命令的服务"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 更新通知
     */
    private fun updateNotification(content: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("命令服务")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * 启动前台服务
     */
    fun startForeground() {
        updateNotification("服务已启动")
        Log.d(TAG, "前台服务已启动")
    }

    /**
     * 停止前台服务
     */
    fun stopForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "前台服务已停止")
    }
}
