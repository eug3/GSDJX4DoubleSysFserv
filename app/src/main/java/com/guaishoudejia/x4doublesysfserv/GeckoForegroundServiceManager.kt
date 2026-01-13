package com.guaishoudejia.x4doublesysfserv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

/**
 * GeckoForegroundService 管理器
 * 处理与前台服务的连接、通信和生命周期管理
 */
class GeckoForegroundServiceManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "GeckoFSvcManager"
    }

    private var service: GeckoForegroundService.GeckoBinder? = null
    private var isConnected: Boolean = false

    // 如果在绑定完成前注册回调，会先缓存，等 onServiceConnected 后再统一注册。
    private val pendingCallbacks = LinkedHashSet<GeckoForegroundService.RenderCallback>()
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder is GeckoForegroundService.GeckoBinder) {
                service = binder
                isConnected = true
                Log.d(TAG, "Service connected")

                // 绑定完成后补注册之前缓存的回调
                if (pendingCallbacks.isNotEmpty()) {
                    val callbacksToRegister = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                    callbacksToRegister.forEach { cb ->
                        try {
                            service?.registerCallback(cb)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to register pending callback", e)
                        }
                    }
                    Log.d(TAG, "Registered ${callbacksToRegister.size} pending callbacks")
                }

                onServiceConnected?.invoke()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isConnected = false
            Log.d(TAG, "Service disconnected")
            onServiceDisconnected?.invoke()
        }
    }
    
    private var onServiceConnected: (() -> Unit)? = null
    private var onServiceDisconnected: (() -> Unit)? = null
    
    /**
     * 启动前台服务并开始渲染
     */
    fun startForegroundService(
        uri: String = "about:blank",
        width: Int = 480,
        height: Int = 800
    ) {
        try {
            val intent = Intent(context, GeckoForegroundService::class.java).apply {
                action = GeckoForegroundService.ACTION_START_RENDER
                putExtra(GeckoForegroundService.EXTRA_URI, uri)
                putExtra(GeckoForegroundService.EXTRA_WIDTH, width)
                putExtra(GeckoForegroundService.EXTRA_HEIGHT, height)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            // 绑定服务以获取 Binder
            bindService()
            
            Log.d(TAG, "Foreground service started: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    /**
     * 停止前台服务
     */
    fun stopForegroundService() {
        try {
            val intent = Intent(context, GeckoForegroundService::class.java).apply {
                action = GeckoForegroundService.ACTION_STOP_RENDER
            }
            context.startService(intent)
            unbindService()
            Log.d(TAG, "Foreground service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service", e)
        }
    }

    /**
     * 加载 URI
     */
    fun loadUri(uri: String) {
        try {
            val intent = Intent(context, GeckoForegroundService::class.java).apply {
                action = GeckoForegroundService.ACTION_LOAD_URI
                putExtra(GeckoForegroundService.EXTRA_URI, uri)
            }
            context.startService(intent)
            Log.d(TAG, "Loading URI: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load URI", e)
        }
    }

    /**
     * 请求 Service 抓取单帧（用于 BLE 外设触发）。
     * 优先走 Binder（若已连接），否则用 startService 发送 action。
     */
    fun requestCaptureOnce() {
        try {
            val binder = service
            if (isConnected && binder != null) {
                binder.requestCaptureOnce()
                Log.d(TAG, "CaptureOnce requested via binder")
                return
            }

            val intent = Intent(context, GeckoForegroundService::class.java).apply {
                action = GeckoForegroundService.ACTION_CAPTURE_ONCE
            }
            context.startService(intent)
            Log.d(TAG, "CaptureOnce requested via intent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request capture once", e)
        }
    }

    /**
     * 获取 GeckoSession
     */
    fun getSession(): GeckoSession? {
        return service?.getSession()
    }

    /**
     * 获取 Binder（用于直接调用 Service 方法）
     */
    fun getBinder(): GeckoForegroundService.GeckoBinder? {
        return service
    }

    /**
     * 获取 GeckoRuntime
     */
    fun getRuntime(): GeckoRuntime? {
        return service?.getRuntime()
    }

    /**
     * 检查是否正在渲染
     */
    fun isRendering(): Boolean {
        return service?.isRendering() ?: false
    }

    /**
     * 注册渲染回调
     */
    fun registerRenderCallback(callback: GeckoForegroundService.RenderCallback) {
        val binder = service
        if (isConnected && binder != null) {
            binder.registerCallback(callback)
            Log.d(TAG, "Render callback registered")
        } else {
            pendingCallbacks.add(callback)
            // 确保已发起 bind（避免只注册回调但未 start 的场景）
            bindService()
            Log.d(TAG, "Render callback queued (service not connected yet)")
        }
    }

    /**
     * 注销渲染回调
     */
    fun unregisterRenderCallback(callback: GeckoForegroundService.RenderCallback) {
        pendingCallbacks.remove(callback)
        service?.unregisterCallback(callback)
        Log.d(TAG, "Render callback unregistered")
    }

    /**
     * 设置服务连接回调
     */
    fun setServiceConnectionCallbacks(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit
    ) {
        onServiceConnected = onConnected
        onServiceDisconnected = onDisconnected
    }

    private fun bindService() {
        try {
            val intent = Intent(context, GeckoForegroundService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "Service bind requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind service", e)
        }
    }

    private fun unbindService() {
        try {
            context.unbindService(serviceConnection)
            service = null
            isConnected = false
            Log.d(TAG, "Service unbound")
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding service", e)
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopForegroundService()
        unbindService()
        Log.d(TAG, "Manager cleaned up")
    }
}

/**
 * 创建简单的渲染回调实现
 */
class SimpleRenderCallback(
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (String) -> Unit = {}
) : GeckoForegroundService.RenderCallback {
    override fun onFrameRendered(bitmap: Bitmap) {
        onFrame(bitmap)
    }

    override fun onError(error: String) {
        onError(error)
    }
}
