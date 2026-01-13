package com.guaishoudejia.x4doublesysfserv

import android.app.Application
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
        Log.d(TAG, "应用启动，开始初始化...")

        // ===== 关键改进：提前加载 C++ STL 库 =====
        // Paddle-Lite JNI 依赖 libc++_shared.so，必须在其他库之前加载
        try {
            System.loadLibrary("c++_shared")
            Log.d(TAG, "成功加载 libc++_shared.so")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "加载 libc++_shared.so 失败（可能已被加载）: ${e.message}")
        }

        // 后台预初始化 OCR
        initializeOcr()
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
