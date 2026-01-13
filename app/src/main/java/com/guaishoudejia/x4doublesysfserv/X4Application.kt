package com.guaishoudejia.x4doublesysfserv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.guaishoudejia.x4doublesysfserv.ocr.OcrHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自定义 Application，用于全局初始化
 * - 在应用启动时后台初始化 OCR，避免首次使用时等待
 */
class X4Application : Application() {
    
    // 应用级协程作用域
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        
        val processName = getCurrentProcessName()
        
        // 只在主进程中初始化重型组件
        if (processName != packageName) {
            Log.d(TAG, "非主进程 ($processName)，跳过 OCR 和 GeckoRuntime 初始化")
            return
        }

        Log.d(TAG, "主进程启动，开始初始化...")

        // ===== 提前加载 C++ STL 库 =====
        try {
            System.loadLibrary("c++_shared")
            Log.d(TAG, "成功加载 libc++_shared.so")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "加载 libc++_shared.so 失败（可能已被加载）: ${e.message}")
        }

        // 预初始化共享的 GeckoRuntime
        initializeGeckoRuntime()
        
        // 后台预初始化 OCR
        initializeOcr()
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
    
    private fun initializeGeckoRuntime() {
        try {
            Log.d(TAG, "预初始化 GeckoRuntime...")
            GeckoRuntimeManager.getRuntime(this)
            Log.d(TAG, "GeckoRuntime 预初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "GeckoRuntime 预初始化失败", e)
        }
    }

    private fun initializeOcr() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                // 检查可用内存
                val runtime = Runtime.getRuntime()
                val totalMemory = runtime.totalMemory() / 1024 / 1024  // MB
                val freeMemory = runtime.freeMemory() / 1024 / 1024    // MB
                val maxMemory = runtime.maxMemory() / 1024 / 1024      // MB
                Log.d(TAG, "内存情况 - 最大: ${maxMemory}MB, 已用: ${totalMemory - freeMemory}MB, 空闲: ${freeMemory}MB")
                
                if (freeMemory < 50) {
                    Log.w(TAG, "警告：空闲内存不足 (${freeMemory}MB)，初始化可能失败")
                }
                
                Log.i(TAG, "====== 开始初始化 OCR (PP-OCRv5 ONNX) ======")
                val startTime = System.currentTimeMillis()
                OcrHelper.init(this@X4Application)
                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "====== OCR 初始化成功，耗时 ${duration}ms，现在可以使用 OCR ======")
            } catch (e: Exception) {
                Log.e(TAG, "====== OCR 初始化失败 ======", e)
            }
        }
    }

    companion object {
        private const val TAG = "X4Application"
    }
}
