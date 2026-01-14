package com.guaishoudejia.x4doublesysfserv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BLE 命令接收器
 *
 * 接收蓝牙命令并转发到 CommandService 处理
 */
class BleCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BleCommandReceiver"
        const val ACTION_BLE_COMMAND = "com.guaishoudejia.x4doublesysfserv.BLE_COMMAND"
        const val EXTRA_COMMAND = "extra_command"
    }

    var onCommandReceived: ((String) -> Unit)? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_BLE_COMMAND) return

        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        Log.d(TAG, "收到 BLE 命令: $command")

        // 回调
        onCommandReceived?.invoke(command)

        // 转发到 CommandService
        val serviceIntent = Intent(context, CommandService::class.java).apply {
            when (command.uppercase()) {
                "SYNC" -> action = CommandService.ACTION_SYNC
                "NEXT" -> action = CommandService.ACTION_NEXT_PAGE
                "PREV" -> action = CommandService.ACTION_PREV_PAGE
                "REFRESH" -> action = CommandService.ACTION_REFRESH
                else -> {
                    if (command.startsWith("PAGE:")) {
                        action = CommandService.ACTION_GO_TO_PAGE
                        val page = command.substringAfter("PAGE:").toIntOrNull() ?: 0
                        putExtra(CommandService.EXTRA_PAGE_NUMBER, page)
                    }
                }
            }
        }

        context?.startService(serviceIntent)
        Log.d(TAG, "命令已转发到 CommandService: ${intent.action}")
    }
}

/**
 * 发送 BLE 命令（静态方法）
 */
object BleCommandSender {

    private const val TAG = "BleCommandSender"

    /**
     * 发送同步命令
     */
    fun sync(context: Context) {
        sendCommand(context, "SYNC")
    }

    /**
     * 发送下一页命令
     */
    fun nextPage(context: Context) {
        sendCommand(context, "NEXT")
    }

    /**
     * 发送上一页命令
     */
    fun prevPage(context: Context) {
        sendCommand(context, "PREV")
    }

    /**
     * 发送刷新命令
     */
    fun refresh(context: Context) {
        sendCommand(context, "REFRESH")
    }

    /**
     * 发送跳转页命令
     */
    fun goToPage(context: Context, page: Int) {
        sendCommand(context, "PAGE:$page")
    }

    /**
     * 发送自定义命令
     */
    fun sendCommand(context: Context, command: String) {
        val intent = Intent(BleCommandReceiver.ACTION_BLE_COMMAND).apply {
            setPackage(context.packageName)
            putExtra(BleCommandReceiver.EXTRA_COMMAND, command)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "发送命令: $command")
    }
}
