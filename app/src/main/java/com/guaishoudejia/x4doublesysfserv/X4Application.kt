package com.guaishoudejia.x4doublesysfserv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 自定义 Application，用于全局初始化
 */
class X4Application : Application() {

    // 应用级协程作用域
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        val processName = getCurrentProcessName()

        // 只在主进程中初始化
        if (processName != packageName) {
            Log.d(TAG, "非主进程 ($processName)，跳过初始化")
            return
        }

        Log.d(TAG, "主进程启动，开始初始化...")
    }

    private fun getCurrentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        
        // 兼容旧版本的备选方案
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = am.runningAppProcesses ?: return "unknown"
        val myPid = Process.myPid()
        for (process in runningProcesses) {
            if (process.pid == myPid) {
                return process.processName
            }
        }
        return "unknown"
    }

    companion object {
        private const val TAG = "X4Application"
    }
}
