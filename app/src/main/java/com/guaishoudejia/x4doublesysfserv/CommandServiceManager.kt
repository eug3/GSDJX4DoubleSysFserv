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

class CommandServiceManager(private val context: Context) {

    private var service: CommandService? = null
    private var bound = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    val serviceStatus: StateFlow<CommandService.ServiceStatus>
        get() = service?.serviceStatus ?: MutableStateFlow(CommandService.ServiceStatus.IDLE)

    val lastCommand: StateFlow<String?>
        get() = service?.lastCommand ?: MutableStateFlow(null)

    val lastResponse: StateFlow<String?>
        get() = service?.lastResponse ?: MutableStateFlow(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as CommandService.LocalBinder
            service = localBinder.getService()
            bound = true
            _isConnected.value = true
            service?.startForeground()
            onServiceConnected?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            _isConnected.value = false
            onServiceDisconnected?.invoke()
        }
    }

    var onServiceConnected: (() -> Unit)? = null
    var onServiceDisconnected: (() -> Unit)? = null
    var onCommandResult: ((String) -> Unit)? = null

    fun bind() {
        if (bound) return

        Intent(context, CommandService::class.java).also { intent ->
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbind() {
        if (bound) {
            context.unbindService(serviceConnection)
            bound = false
            _isConnected.value = false
        }
    }

    fun startService() {
        Intent(context, CommandService::class.java).apply {
            context.startService(this)
        }
    }

    fun stopService() {
        service?.stopForeground()
        unbind()
        Intent(context, CommandService::class.java).also {
            context.stopService(it)
        }
    }

    fun sync(callback: ((String) -> Unit)? = null) {
        executeCommand(CommandService.ACTION_SYNC, callback)
    }

    fun nextPage(callback: ((String) -> Unit)? = null) {
        executeCommand(CommandService.ACTION_NEXT_PAGE, callback)
    }

    fun prevPage(callback: ((String) -> Unit)? = null) {
        executeCommand(CommandService.ACTION_PREV_PAGE, callback)
    }

    fun refresh(callback: ((String) -> Unit)? = null) {
        executeCommand(CommandService.ACTION_REFRESH, callback)
    }

    fun goToPage(page: Int, callback: ((String) -> Unit)? = null) {
        val intent = Intent(context, CommandService::class.java).apply {
            action = CommandService.ACTION_GO_TO_PAGE
            putExtra(CommandService.EXTRA_PAGE_NUMBER, page)
        }
        context.startService(intent)
        callback?.invoke("跳转第 $page 页")
    }

    fun executeCommand(command: String, callback: ((String) -> Unit)? = null) {
        if (!bound) {
            callback?.invoke("服务未绑定")
            return
        }

        service?.executeCommand(command)
        callback?.invoke("命令已发送: $command")

        serviceScope.launch {
            service?.lastResponse?.collect { response ->
                if (response != null) {
                    callback?.invoke(response)
                    onCommandResult?.invoke(response)
                }
            }
        }
    }

    fun getStatus(): CommandService.ServiceStatus {
        return service?.getStatus() ?: CommandService.ServiceStatus.IDLE
    }

    fun isBound(): Boolean = bound
}
