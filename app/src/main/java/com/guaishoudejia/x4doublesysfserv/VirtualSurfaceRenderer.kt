package com.guaishoudejia.x4doublesysfserv

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 虚拟 Surface 渲染器
 * 使用 SurfaceTexture 创建离屏 Surface 让 GeckoSession 渲染
 * 定期捕获 Bitmap 并回调
 */
class VirtualSurfaceRenderer(
    private val geckoDisplay: GeckoDisplay,  // 从外部传入的全局 Display 对象
    private val width: Int,
    private val height: Int,
    private val scope: CoroutineScope,
    private val captureIntervalMs: Long = 0L,
    private val onFrameRendered: (Bitmap) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "VirtualSurfaceRenderer"
    }

    private val isRunning = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var renderHandler: Handler? = null
    private var renderThread: HandlerThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val captureRequests = Channel<Unit>(capacity = Channel.CONFLATED)
    
    init {
        startRendering()
    }

    private fun startRendering() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Rendering already started")
            return
        }
        
        try {
            Log.d(TAG, "Starting rendering with external GeckoDisplay")
            initializeSurface()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start rendering", e)
            onError(e.message ?: "Failed to start rendering")
            isRunning.set(false)
        }
    }

    private fun initializeSurface() {
        try {
            renderThread = HandlerThread("GeckoVirtualSurface").apply {
                start()
            }
            renderHandler = Handler(renderThread!!.looper)
            
            renderHandler?.post {
                try {
                    surfaceTexture = SurfaceTexture(0).apply {
                        setDefaultBufferSize(width, height)
                    }
                    
                    val s = Surface(surfaceTexture)
                    surface = s
                    
                    mainHandler.post {
                        try {
                            val surfaceInfo = GeckoDisplay.SurfaceInfo.Builder(s)
                                .size(width, height)
                                .build()
                            geckoDisplay.surfaceChanged(surfaceInfo)
                            Log.d(TAG, "Virtual surface attached to GeckoDisplay ($width x $height)")
                            
                            startCaptureLoop()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to set surface", e)
                            onError(e.message ?: "Failed to set surface")
                            isRunning.set(false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create surface texture", e)
                    onError(e.message ?: "Failed to create surface")
                    isRunning.set(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize surface", e)
            onError(e.message ?: "Failed to initialize surface")
            isRunning.set(false)
        }
    }

    private fun startCaptureLoop() {
        scope.launch {
            try {
                // 等待 Gecko 渲染出首帧
                delay(3000)

                while (isRunning.get() && !isReleased.get()) {
                    // 既支持按需触发（BLE 命令），也支持可选的周期抓帧。
                    if (captureIntervalMs > 0L) {
                        // 等待按需请求；超时则走周期抓帧。
                        withTimeoutOrNull(captureIntervalMs) {
                            captureRequests.receive()
                        }
                    } else {
                        // 纯按需：没有请求就不抓帧
                        captureRequests.receive()
                    }

                    if (!isRunning.get() || isReleased.get()) break

                    val bitmap = captureFrame()
                    if (bitmap != null) {
                        onFrameRendered(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture loop error", e)
                if (!isReleased.get()) onError(e.message ?: "Capture loop error")
            }
        }
    }

    /**
     * 请求抓取单帧（用于 BLE 外设触发）。
     * 如果当前 Surface 未就绪或已释放，此调用会被忽略。
     */
    fun requestCapture() {
        if (!isRunning.get() || isReleased.get()) return
        captureRequests.trySend(Unit)
    }

    private suspend fun captureFrame(): Bitmap? = suspendCancellableCoroutine { cont ->
        val s = surface
        if (s == null || !s.isValid) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        try {
            PixelCopy.request(s, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    cont.resume(bitmap)
                } else {
                    Log.w(TAG, "PixelCopy failed with error code: $result")
                    bitmap.recycle()
                    cont.resume(null)
                }
            }, renderHandler!!)
        } catch (e: Exception) {
            Log.e(TAG, "PixelCopy request failed", e)
            bitmap.recycle()
            cont.resume(null)
        }
    }

    fun release() {
        if (isReleased.getAndSet(true)) {
            return
        }
        
        isRunning.set(false)

        // 关闭请求通道，唤醒协程退出
        try {
            captureRequests.close()
        } catch (_: Exception) {
        }
        
        try {
            mainHandler.post {
                try {
                    geckoDisplay.surfaceDestroyed()
                    Log.d(TAG, "Surface destroyed notification sent to GeckoDisplay")
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying surface destroyed", e)
                }
            }
            
            renderHandler?.post {
                try {
                    surface?.release()
                    surface = null
                    
                    surfaceTexture?.release()
                    surfaceTexture = null
                    
                    Log.d(TAG, "Virtual surface released")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing surface", e)
                }
                
                renderThread?.quitSafely()
                renderThread = null
                renderHandler = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }
}
