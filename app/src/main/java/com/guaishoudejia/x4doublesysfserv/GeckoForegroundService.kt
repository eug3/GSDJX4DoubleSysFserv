package com.guaishoudejia.x4doublesysfserv

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 前台服务：独立管理 GeckoRuntime + GeckoSession
 * 支持虚拟 Surface 和独立 EGL context 的离屏渲染
 * 统一使用 JS 注入方式获取页面截图
 */
class GeckoForegroundService : Service() {
    companion object {
        private const val TAG = "GeckoForegroundService"
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "gecko_render_service"

        const val ACTION_START_RENDER = "com.guaishoudejia.x4doublesysfserv.START_RENDER"
        const val ACTION_STOP_RENDER = "com.guaishoudejia.x4doublesysfserv.STOP_RENDER"
        const val ACTION_LOAD_URI = "com.guaishoudejia.x4doublesysfserv.LOAD_URI"
        const val ACTION_CAPTURE_ONCE = "com.guaishoudejia.x4doublesysfserv.CAPTURE_ONCE"

        const val EXTRA_URI = "extra_uri"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"

        // 用于存储外部传入的 GeckoSession（来自 GeckoActivity）
        @Volatile
        private var externalSession: GeckoSession? = null

        /**
         * Display 释放完成监听器
         * 用于通知外部 Display 已完全释放，可以安全地重新绑定
         */
        @Volatile
        private var displayReleasedListener: (() -> Unit)? = null

        /**
         * 设置 Display 释放监听器
         */
        fun setDisplayReleasedListener(listener: (() -> Unit)?) {
            displayReleasedListener = listener
        }

        /**
         * 清除 Display 释放监听器
         */
        fun clearDisplayReleasedListener() {
            displayReleasedListener = null
        }

        /**
         * 设置外部 Session（从 GeckoActivity 调用）
         * 这样 Service 可以使用同一个 Session，只是附加第二个 Display
         */
        fun setSharedSession(session: GeckoSession?) {
            externalSession = session
            Log.d(TAG, "Shared GeckoSession set: ${session != null}")
        }
    }

    private val binder = GeckoBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null  // 可能来自外部（Activity）或自己创建
    private var isUsingExternalSession = false  // 标记是否使用外部 Session
    private var geckoDisplay: org.mozilla.geckoview.GeckoDisplay? = null  // Service 的虚拟 Display
    private var virtualSurfaceRenderer: VirtualSurfaceRenderer? = null
    private val isRunning = AtomicBoolean(false)
    
    private val renderCallbacks = mutableListOf<RenderCallback>()
    
    interface RenderCallback {
        fun onFrameRendered(bitmap: Bitmap)
        fun onError(error: String)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GeckoForegroundService created")
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 初始化 GeckoRuntime
        initGeckoRuntime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action")
        
        when (action) {
            ACTION_START_RENDER -> {
                val uri = intent?.getStringExtra(EXTRA_URI) ?: "about:blank"
                val width = intent?.getIntExtra(EXTRA_WIDTH, 480) ?: 480
                val height = intent?.getIntExtra(EXTRA_HEIGHT, 800) ?: 800
                startRendering(uri, width, height)
            }
            ACTION_STOP_RENDER -> {
                stopRendering()
            }
            ACTION_LOAD_URI -> {
                val uri = intent?.getStringExtra(EXTRA_URI) ?: "about:blank"
                loadUri(uri)
            }
            ACTION_CAPTURE_ONCE -> {
                if (isRunning.get()) {
                    virtualSurfaceRenderer?.requestCapture()
                    Log.d(TAG, "Capture requested (one-shot)")
                } else {
                    Log.w(TAG, "Capture requested but service is not rendering")
                }
            }
        }
        
        // 启动前台通知
        if (!isRunning.get()) {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }

    override fun onDestroy() {
        Log.d(TAG, "GeckoForegroundService destroyed")
        stopRendering()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun initGeckoRuntime() {
        try {
            // 使用共享的 GeckoRuntime 实例，避免 "Only one GeckoRuntime instance is allowed" 错误
            runtime = GeckoRuntimeManager.getRuntime(this)
            Log.d(TAG, "GeckoRuntime initialized (shared instance)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GeckoRuntime", e)
        }
    }

    private fun startRendering(uri: String, width: Int, height: Int) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Already rendering")
            return
        }
        
        Log.d(TAG, "startRendering on thread: ${Thread.currentThread().name}")
        
        // 确保在主线程执行 GeckoView API 调用
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                startRendering(uri, width, height)
            }
            return
        }
        
        try {
            // 步骤 1: 尝试使用外部共享的 Session（来自 GeckoActivity）
            session = externalSession
            isUsingExternalSession = session != null
            
            if (session != null) {
                Log.d(TAG, "使用外部共享的 GeckoSession（来自 Activity）")
            } else {
                // 如果没有外部 Session，创建新的
                Log.d(TAG, "创建新的独立 GeckoSession")
                val settings = GeckoSessionSettings.Builder()
                    .usePrivateMode(false)
                    .allowJavascript(true)
                    .build()
                
                session = GeckoSession(settings)
                session?.open(runtime!!)
                Log.d(TAG, "GeckoSession opened with runtime")
            }
            
            // 步骤 3: 只在创建新 Session 时设置 Delegates（外部 Session 已有 delegates）
            if (!isUsingExternalSession) {
                session?.progressDelegate = object : GeckoSession.ProgressDelegate {
                    override fun onPageStart(session: GeckoSession, url: String) {
                        Log.d(TAG, "Page load started: $url")
                    }
                    
                    override fun onPageStop(session: GeckoSession, success: Boolean) {
                        Log.d(TAG, "Page load stopped: success=$success")
                    }
                    
                    override fun onProgressChange(session: GeckoSession, progress: Int) {
                        Log.d(TAG, "Progress: $progress%")
                    }
                    
                    override fun onSecurityChange(
                        session: GeckoSession,
                        info: GeckoSession.ProgressDelegate.SecurityInformation
                    ) {
                        Log.d(TAG, "Security change")
                    }
                }
            }
            
            // 步骤 4: 获取第二个 GeckoDisplay（用于虚拟 Surface 截图）
            // 注意：如果使用外部 Session，Activity 已经有一个 Display 绑定到 GeckoView
            //       这里获取的是第二个 Display，用于离屏渲染
            try {
                if (session == null) {
                    throw IllegalStateException("Session is null")
                }
                geckoDisplay = session?.acquireDisplay()
                Log.d(TAG, "GeckoDisplay acquired for virtual surface (Display #${if (isUsingExternalSession) "2" else "1"})")
            } catch (e: Exception) {
                Log.e(TAG, "FATAL: Failed to acquire display. Session already acquired?", e)
                notifyErrorCallback("Display acquisition failed: ${e.message}")
                isRunning.set(false)
                return
            }
            
            // 步骤 5: 创建虚拟 Surface 渲染器
            virtualSurfaceRenderer = VirtualSurfaceRenderer(
                geckoDisplay = geckoDisplay!!,
                width = width,
                height = height,
                scope = serviceScope,
                captureIntervalMs = if (isUsingExternalSession) 0L else 5000L,
                onFrameRendered = { bitmap ->
                    notifyRenderCallback(bitmap)
                },
                onError = { error ->
                    notifyErrorCallback(error)
                }
            )
            Log.d(TAG, "VirtualSurfaceRenderer created")
            
            // 步骤 6: 延迟激活和加载（等待 Surface 准备就绪）
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // 重要：无论是否使用外部 Session，Service 都要接管控制权
                    // 这样即使 Activity 熄屏/暂停，Session 仍保持 active
                    session?.setActive(true)
                    Log.d(TAG, "GeckoSession activated by Service (保持后台渲染)")
                    
                    if (!isUsingExternalSession) {
                        // 只在使用新建 Session 时才需要加载
                        session?.loadUri(uri)
                        Log.d(TAG, "Loading URI: $uri")
                    } else {
                        // 使用外部 Session，内容已经加载
                        // Service 只是接管了激活状态，确保熄屏后继续渲染
                        Log.d(TAG, "使用外部 Session，已有内容（当前显示内容会同步到虚拟 Surface）")
                        Log.d(TAG, "Service 已接管激活状态，即使 Activity 熄屏也会继续渲染")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to activate/load", e)
                    notifyErrorCallback(e.message ?: "Activation failed")
                }
            }, 500) // 等待 500ms 确保 Surface 已附加
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start rendering", e)
            isRunning.set(false)
            notifyErrorCallback(e.message ?: "Unknown error")
        }
    }

    private fun stopRendering() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        
        Log.d(TAG, "Stopping rendering...")
        
        try {
            // 步骤 1: 停用 Session（只在使用自建 Session 时）
            if (!isUsingExternalSession) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        session?.setActive(false)
                        Log.d(TAG, "GeckoSession deactivated")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deactivating session", e)
                    }
                }
            } else {
                Log.d(TAG, "使用外部 Session，跳过停用（由 Activity 管理）")
            }
            
            // 步骤 2: 释放 renderer（会通知 surfaceDestroyed）
            virtualSurfaceRenderer?.release()
            virtualSurfaceRenderer = null
            Log.d(TAG, "VirtualSurfaceRenderer released")
            
            // 步骤 3: 在主线程释放 GeckoDisplay
            Handler(Looper.getMainLooper()).post {
                try {
                    geckoDisplay?.let { display ->
                        session?.releaseDisplay(display)
                        Log.d(TAG, "GeckoDisplay released")
                    }
                    geckoDisplay = null

                    // 通知 Display 已释放（在主线程，安全地通知外部）
                    displayReleasedListener?.invoke()
                    Log.d(TAG, "DisplayReleasedListener notified")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing display", e)
                }
            }
            
            // 步骤 4: 关闭 Session（只在使用自建 Session 时）
            if (!isUsingExternalSession) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        session?.close()
                        Log.d(TAG, "GeckoSession closed")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing session", e)
                    }
                }, 100) // 延迟一点确保前面的操作完成
            } else {
                Log.d(TAG, "使用外部 Session，不关闭（由 Activity 管理生命周期）")
            }
            
            session = null
            isUsingExternalSession = false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping rendering", e)
        }
    }

    private fun loadUri(uri: String) {
        session?.loadUri(uri)
        Log.d(TAG, "Loading URI: $uri")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gecko Render Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Renders web content using Gecko engine"
                setShowBadge(false)
            }
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, GeckoActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gecko Render Service")
            .setContentText("Rendering web content...")
            .setSmallIcon(android.R.drawable.ic_notification_clear_all)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyRenderCallback(bitmap: Bitmap) {
        synchronized(renderCallbacks) {
            renderCallbacks.forEach { callback ->
                try {
                    callback.onFrameRendered(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in render callback", e)
                }
            }
        }
    }

    private fun notifyErrorCallback(error: String) {
        synchronized(renderCallbacks) {
            renderCallbacks.forEach { callback ->
                try {
                    callback.onError(error)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in error callback", e)
                }
            }
        }
    }

    /**
     * Binder for service clients
     */
    inner class GeckoBinder : Binder() {
        fun getService() = this@GeckoForegroundService
        
        fun registerCallback(callback: RenderCallback) {
            synchronized(renderCallbacks) {
                renderCallbacks.add(callback)
            }
        }
        
        fun unregisterCallback(callback: RenderCallback) {
            synchronized(renderCallbacks) {
                renderCallbacks.remove(callback)
            }
        }
        
        fun getSession(): GeckoSession? = session
        fun getRuntime(): GeckoRuntime? = runtime
        fun isRendering(): Boolean = isRunning.get()
        
        fun startRendering(uri: String, width: Int, height: Int) {
            this@GeckoForegroundService.startRendering(uri, width, height)
        }
        
        fun stopRendering() {
            this@GeckoForegroundService.stopRendering()
        }
        
        fun loadUri(uri: String) {
            this@GeckoForegroundService.loadUri(uri)
        }

        fun requestCaptureOnce() {
            if (isRunning.get()) {
                virtualSurfaceRenderer?.requestCapture()
            }
        }

        /**
         * 通过 JS 注入方式捕获当前页面截图
         * 统一使用 GeckoSession.loadUri("javascript:...") 获取页面内容
         * @param timeoutMs 超时时间（毫秒）
         * @param onResult 回调返回 Bitmap，null 表示失败
         */
        fun requestCaptureViaJs(
            timeoutMs: Long = 10000,
            onResult: (Bitmap?) -> Unit
        ) {
            val currentSession = session
            if (currentSession == null) {
                Log.w(TAG, "Session is null, cannot capture via JS")
                onResult(null)
                return
            }

            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post {
                    captureViaJsSync(currentSession, timeoutMs, onResult)
                }
            } else {
                captureViaJsSync(currentSession, timeoutMs, onResult)
            }
        }

        /**
         * 同步执行 JS 注入截图
         */
        private fun captureViaJsSync(
            session: GeckoSession,
            timeoutMs: Long,
            onResult: (Bitmap?) -> Unit
        ) {
            val prevDelegate = session.progressDelegate
            val timeoutHandler = Handler(Looper.getMainLooper())
            val mainHandler = Handler(Looper.getMainLooper())

            // 用弱引用包装回调，避免内存泄漏
            val resultRef = java.lang.ref.WeakReference(onResult)
            val completed = AtomicBoolean(false)
            var timeoutRunnable: Runnable? = null

            val delegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    if (url.startsWith("x4bmp://")) {
                        if (completed.get()) return

                        val data = url.removePrefix("x4bmp://")
                        Log.d(TAG, "JS capture data length: ${data.length}")

                        // 停止超时计时器
                        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }

                        mainHandler.post {
                            if (completed.getAndSet(true)) return@post
                            session.progressDelegate = prevDelegate

                            if (data == "ERROR") {
                                Log.w(TAG, "JS returned ERROR")
                                resultRef.get()?.invoke(null)
                            } else {
                                try {
                                    val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                                    Log.d(TAG, "Base64 decoded: ${bytes.size} bytes")
                                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bitmap != null) {
                                        Log.d(TAG, "Bitmap created: ${bitmap.width}x${bitmap.height}")
                                    } else {
                                        Log.e(TAG, "BitmapFactory returned null")
                                    }
                                    resultRef.get()?.invoke(bitmap)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to decode bitmap", e)
                                    resultRef.get()?.invoke(null)
                                }
                            }
                        }
                    }
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {}
                override fun onProgressChange(session: GeckoSession, progress: Int) {}
                override fun onSecurityChange(
                    session: GeckoSession,
                    info: GeckoSession.ProgressDelegate.SecurityInformation
                ) {}
            }

            // 超时处理
            timeoutRunnable = Runnable {
                mainHandler.post {
                    if (completed.getAndSet(true)) return@post
                    session.progressDelegate = prevDelegate
                    Log.w(TAG, "JS capture timeout")
                    resultRef.get()?.invoke(null)
                }
            }
            timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)

            // 执行 JS 注入
            val jsCode = """
                (function() {
                    try {
                        var canvas = document.querySelector('canvas') ||
                            document.querySelector('.readerContent_container canvas') ||
                            document.querySelector('#canvas');
                        if (!canvas) {
                            location.href = 'x4bmp://ERROR';
                            return;
                        }
                        var data = canvas.toDataURL('image/png').split(',')[1];
                        location.href = 'x4bmp://' + data;
                    } catch(e) {
                        location.href = 'x4bmp://ERROR';
                    }
                })();
            """.trimIndent().replace("\n", "").replace("    ", " ")

            Log.d(TAG, "Executing JS capture")
            session.progressDelegate = delegate
            session.loadUri("javascript:$jsCode")
        }

        /**
         * 发送按键事件到 GeckoSession
         * 尝试多种方式触发翻页
         * @param keyCode 按键代码 (KeyEvent.KEYCODE_DPAD_LEFT 或 KEYCODE_DPAD_RIGHT)
         */
        fun dispatchKey(keyCode: Int) {
            val currentSession = session ?: run {
                Log.w(TAG, "Session is null, cannot dispatch key")
                return
            }

            val isNextPage = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT

            // 尝试多种翻页方式，按优先级排序（压缩成一行，避免 URL 编码问题）
            val jsCode = """(function(){try{var isNextPage=${isNextPage};var nextPageBtns=document.querySelectorAll('[class*="next"],[class*="Next"]');var prevPageBtns=document.querySelectorAll('[class*="prev"],[class*="Prev"]');if(isNextPage&&nextPageBtns.length>0){nextPageBtns[0].click();return'clicked_next';}if(!isNextPage&&prevPageBtns.length>0){prevPageBtns[0].click();return'clicked_prev';}var touchX=isNextPage?window.innerWidth*0.8:window.innerWidth*0.2;var touchY=window.innerHeight/2;var touchEvent=new MouseEvent('click',{bubbles:true,cancelable:true,clientX:touchX,clientY:touchY,button:0});var target=document.elementFromPoint(touchX,touchY)||document.body;target.dispatchEvent(touchEvent);var keyName=isNextPage?'ArrowRight':'ArrowLeft';var keyCode=isNextPage?39:37;var eventInit={key:keyName,code:keyName,keyCode:keyCode,which:keyCode,bubbles:true,cancelable:true,composed:true};window.dispatchEvent(new KeyboardEvent('keydown',eventInit));setTimeout(function(){window.dispatchEvent(new KeyboardEvent('keyup',eventInit));},50);return'attempted_all';}catch(e){return'error:'+e.message;}})();"""

            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post {
                    currentSession.loadUri("javascript:$jsCode")
                }
            } else {
                currentSession.loadUri("javascript:$jsCode")
            }
            Log.d(TAG, "Dispatched page turn: ${if (isNextPage) "NEXT" else "PREV"}")
        }
    }
}
