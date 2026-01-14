package com.guaishoudejia.x4doublesysfserv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * CommandService 绑定管理器
 *
 * 功能：
 * 1. 绑定/解绑 CommandService
 * 2. 提供同步/异步命令执行
 * 3. 状态监听
 */
class CommandServiceManager(private val context: Context) {

    private var service: CommandService? = null
    private var bound = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 服务连接状态
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // 服务状态
    val serviceStatus: StateFlow<CommandService.ServiceStatus>
        get() = service?.serviceStatus ?: MutableStateFlow(CommandService.ServiceStatus.IDLE)

    // 最后命令
    val lastCommand: StateFlow<String?>
        get() = service?.lastCommand ?: MutableStateFlow(null)

    // 最后响应
    val lastResponse: StateFlow<String?>
        get() = service?.lastResponse ?: MutableStateFlow(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as CommandService.LocalBinder
            service = localBinder.getService()
            bound = true
            _isConnected.value = true
            service?.startForeground()
            service?.setRemoteServeUrl("http://localhost:8080")
            onServiceConnected?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            _isConnected.value = false
            onServiceDisconnected?.invoke()
        }
    }

    // 回调
    var onServiceConnected: (() -> Unit)? = null
    var onServiceDisconnected: (() -> Unit)? = null
    var onCommandResult: ((String) -> Unit)? = null

    /**
     * 绑定服务
     */
    fun bind() {
        if (bound) return

        Intent(context, CommandService::class.java).also { intent ->
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * 解绑服务
     */
    fun unbind() {
        if (bound) {
            context.unbindService(serviceConnection)
            bound = false
            _isConnected.value = false
        }
    }

    /**
     * 启动前台服务
     */
    fun startService() {
        Intent(context, CommandService::class.java).apply {
            context.startService(this)
        }
    }

    /**
     * 停止服务
     */
    fun stopService() {
        service?.stopForeground()
        unbind()
        Intent(context, CommandService::class.java).also {
            context.stopService(it)
        }
    }

    /**
     * 执行同步命令
     */
    fun sync(callback: ((String) -> Unit)? = null) {
        executeCommand(CommandService.ACTION_SYNC, callback)
    }

    /**
     * 执行下一页命令
     */
    fun nextPage(callback: ((String) -> Unit)? = null) {
        executeCommand(CommandService.ACTION_NEXT_PAGE, callback)
    }

    /**
     * 执行上一页命令
     */
    fun prevPage(callback: ((String) -> Unit)? = null) {
        executeCommand(CommandService.ACTION_PREV_PAGE, callback)
    }

    /**
     * 刷新当前页
     */
    fun refresh(callback: ((String) -> Unit)? = null) {
        executeCommand(CommandService.ACTION_REFRESH, callback)
    }

    /**
     * 跳转到指定页
     */
    fun goToPage(page: Int, callback: ((String) -> Unit)? = null) {
        val intent = Intent(context, CommandService::class.java).apply {
            action = CommandService.ACTION_GO_TO_PAGE
            putExtra(CommandService.EXTRA_PAGE_NUMBER, page)
        }
        context.startService(intent)
        callback?.invoke("跳转第 $page 页")
    }

    /**
     * 执行自定义命令
     */
    fun executeCommand(command: String, callback: ((String) -> Unit)? = null) {
        if (!bound) {
            callback?.invoke("服务未绑定")
            return
        }

        service?.executeCommand(command)
        callback?.invoke("命令已发送: $command")

        // 监听结果
        serviceScope.launch {
            service?.lastResponse?.collect { response ->
                if (response != null) {
                    callback?.invoke(response)
                    onCommandResult?.invoke(response)
                }
            }
        }
    }

    /**
     * 设置 RemoteServe URL
     */
    fun setRemoteServeUrl(url: String) {
        service?.setRemoteServeUrl(url)
    }

    /**
     * 获取当前服务状态
     */
    fun getStatus(): CommandService.ServiceStatus {
        return service?.getStatus() ?: CommandService.ServiceStatus.IDLE
    }

    /**
     * 检查服务是否已绑定
     */
    fun isBound(): Boolean = bound
}
