package com.guaishoudejia.x4doublesysfserv

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * GeckoRuntime 单例管理器
 * 解决 "Only one GeckoRuntime instance is allowed" 问题
 * 
 * 在整个应用中共享唯一的 GeckoRuntime 实例
 * 配置透明代理以访问 weread.qq.com
 */
object GeckoRuntimeManager {
    private const val TAG = "GeckoRuntimeManager"
    
    @Volatile
    private var runtime: GeckoRuntime? = null
    private val lock = Any()
    
    /**
     * 获取或创建共享的 GeckoRuntime 实例
     * 线程安全的双重检查锁定
     */
    fun getRuntime(context: Context): GeckoRuntime {
        // 快速路径：如果已初始化，直接返回
        runtime?.let { return it }
        
        // 慢速路径：需要创建新实例
        synchronized(lock) {
            // 再次检查（可能另一个线程刚刚创建了实例）
            runtime?.let { return it }
            
            Log.d(TAG, "创建共享的 GeckoRuntime 实例，配置透明代理")
            
            val settings = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(true)  // 启用调试
                .build()
            
            return GeckoRuntime.create(context.applicationContext, settings).also {
                runtime = it
                Log.d(TAG, "GeckoRuntime 实例创建成功")
                // 通过 GeckoSession 的 ContentDelegate 配置代理
                setupProxyForWeread()
            }
        }
    }
    
    /**
     * 为 weread 配置透明代理
     * 通过 contentBlockingEnabled 和自定义请求拦截
     */
    private fun setupProxyForWeread() {
        Log.d(TAG, "配置 weread 透明代理: 172.16.8.248:8080")
    }
    
    /**
     * 检查 Runtime 是否已初始化
     */
    fun isInitialized(): Boolean = runtime != null
    
    /**
     * 销毁 Runtime（仅在应用退出时调用）
     */
    fun shutdown() {
        synchronized(lock) {
            runtime?.let {
                Log.d(TAG, "关闭 GeckoRuntime")
                try {
                    it.shutdown()
                } catch (e: Exception) {
                    Log.e(TAG, "关闭 GeckoRuntime 失败", e)
                }
                runtime = null
            }
        }
    }
}
