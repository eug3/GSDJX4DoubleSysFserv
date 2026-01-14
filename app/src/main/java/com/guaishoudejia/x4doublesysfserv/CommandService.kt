package com.guaishoudejia.x4doublesysfserv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 命令服务 - 前台服务，处理蓝牙命令
 *
 * 功能：
 * 1. 接收蓝牙命令
 * 2. 支持 UI 绑定通信
 * 3. 状态通知
 */
class CommandService : Service() {

    companion object {
        private const val TAG = "CommandService"
        private const val CHANNEL_ID = "command_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_SYNC = "com.guaishoudejia.x4doublesysfserv.ACTION_SYNC"
        const val ACTION_NEXT_PAGE = "com.guaishoudejia.x4doublesysfserv.ACTION_NEXT_PAGE"
        const val ACTION_PREV_PAGE = "com.guaishoudejia.x4doublesysfserv.ACTION_PREV_PAGE"
        const val ACTION_GO_TO_PAGE = "com.guaishoudejia.x4doublesysfserv.ACTION_GO_TO_PAGE"
        const val ACTION_REFRESH = "com.guaishoudejia.x4doublesysfserv.ACTION_REFRESH"

        const val EXTRA_PAGE_NUMBER = "extra_page_number"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _serviceStatus = MutableStateFlow(ServiceStatus.IDLE)
    val serviceStatus: StateFlow<ServiceStatus> = _serviceStatus.asStateFlow()

    private val _lastCommand = MutableStateFlow<String?>(null)
    val lastCommand: StateFlow<String?> = _lastCommand.asStateFlow()

    private val _lastResponse = MutableStateFlow<String?>(null)
    val lastResponse: StateFlow<String?> = _lastResponse.asStateFlow()

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
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CommandService 销毁")
        serviceScope.cancel()
    }

    fun executeCommand(command: String) {
        _serviceStatus.value = ServiceStatus.PROCESSING
        _lastCommand.value = command
        updateNotification("处理命令: $command")
    }

    fun setResponse(response: String) {
        _lastResponse.value = response
        _serviceStatus.value = ServiceStatus.SUCCESS
        updateNotification("命令完成")
    }

    fun setError(error: String) {
        _lastResponse.value = "错误: $error"
        _serviceStatus.value = ServiceStatus.ERROR
        updateNotification("命令失败: $error")
    }

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

    fun startForeground() {
        updateNotification("服务已启动")
        Log.d(TAG, "前台服务已启动")
    }

    fun stopForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "前台服务已停止")
    }

    fun getStatus(): ServiceStatus = _serviceStatus.value
}
