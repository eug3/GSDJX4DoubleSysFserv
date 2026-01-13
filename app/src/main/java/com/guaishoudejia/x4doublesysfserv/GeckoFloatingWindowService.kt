package com.guaishoudejia.x4doublesysfserv

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoSession

/**
 * 悬浮窗服务：在后台维护一个隐藏的 GeckoView
 * 保持 GeckoSession 始终激活，支持 JavaScript 注入和 Canvas 捕获
 */
class GeckoFloatingWindowService : Service() {
    companion object {
        private const val TAG = "GeckoFloatingWindow"
        
        fun startService(context: android.content.Context) {
            val intent = Intent(context, GeckoFloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Service requested to start")
        }
        
        fun stopService(context: android.content.Context) {
            val intent = Intent(context, GeckoFloatingWindowService::class.java)
            context.stopService(intent)
            Log.d(TAG, "Service requested to stop")
        }
    }
    
    private var windowManager: WindowManager? = null
    private var geckoView: GeckoView? = null
    private var isCreated = false
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        
        if (!isCreated) {
            createFloatingWindow()
            isCreated = true
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        removeFloatingWindow()
        super.onDestroy()
    }
    
    private fun createFloatingWindow() {
        try {
            Log.d(TAG, "Creating floating window...")
            
            geckoView = GeckoView(this)
            geckoView!!.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            
            val params = WindowManager.LayoutParams().apply {
                // 使用 TYPE_PHONE 以获得更好的兼容性，不需要特殊权限
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8+ 优先使用更新的类型
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                
                format = android.graphics.PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                
                width = 1
                height = 1
                x = 0
                y = 0
                gravity = android.view.Gravity.START or android.view.Gravity.TOP
            }
            
            try {
                windowManager?.addView(geckoView, params)
                Log.d(TAG, "Floating window created: 1x1px transparent")
            } catch (e: Exception) {
                // 如果 TYPE_APPLICATION_OVERLAY 失败，回退到 TYPE_PHONE
                Log.w(TAG, "Failed with TYPE_APPLICATION_OVERLAY, fallback to TYPE_PHONE: ${e.message}")
                params.type = @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                windowManager?.addView(geckoView, params)
                Log.d(TAG, "Floating window created with TYPE_PHONE fallback")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create floating window", e)
        }
    }
    
    private fun removeFloatingWindow() {
        try {
            if (geckoView != null && windowManager != null) {
                windowManager!!.removeView(geckoView)
                geckoView = null
                Log.d(TAG, "Floating window removed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating window", e)
        }
    }
    
    /**
     * 将 GeckoSession 附加到悬浮窗的 GeckoView
     */
    fun attachSession(session: GeckoSession) {
        try {
            geckoView?.setSession(session)
            Log.d(TAG, "GeckoSession attached to floating window")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach session", e)
        }
    }
    
    /**
     * 从悬浮窗分离 GeckoSession
     */
    fun detachSession() {
        try {
            geckoView?.releaseSession()
            Log.d(TAG, "GeckoSession detached from floating window")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detach session", e)
        }
    }
    
    /**
     * 获取悬浮窗的 GeckoView（用于调试）
     */
    fun getGeckoView(): GeckoView? = geckoView
}
