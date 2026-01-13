package com.guaishoudejia.x4doublesysfserv

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.guaishoudejia.x4doublesysfserv.ocr.OcrHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gecko 前台服务 + OCR 集成管理器
 * 负责启动服务、处理 Bitmap、进行 OCR 识别、管理文本分页显示
 */
class GeckoOcrIntegrationManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val ocrHelper: OcrHelper
) {
    companion object {
        private const val TAG = "GeckoOcrIntegration"
    }

    private var serviceManager: GeckoForegroundServiceManager? = null
    private var renderCallback: GeckoForegroundService.RenderCallback? = null
    
    // 状态管理
    val isServiceRunning = mutableStateOf(false)
    val isOcrProcessing = mutableStateOf(false)
    val ocrTextDisplay = OcrTextDisplay()
    val currentPageIndex = mutableStateOf(0)
    val currentPageBlocks = mutableStateOf<List<String>>(emptyList())
    
    // 回调
    var onOcrComplete: ((blocks: List<String>) -> Unit)? = null
    var onOcrError: ((error: String) -> Unit)? = null
    var onServiceStateChanged: ((isRunning: Boolean) -> Unit)? = null
    var onDispatchKey: ((keyCode: Int) -> Unit)? = null  // 用于发送按键事件的回调

    /**
     * 初始化管理器
     */
    fun initialize() {
        serviceManager = GeckoForegroundServiceManager(context, lifecycleOwner)
        
        serviceManager?.setServiceConnectionCallbacks(
            onConnected = {
                Log.d(TAG, "Service connected")
                isServiceRunning.value = true
                onServiceStateChanged?.invoke(true)
            },
            onDisconnected = {
                Log.d(TAG, "Service disconnected")
                isServiceRunning.value = false
                onServiceStateChanged?.invoke(false)
            }
        )
    }

    /**
     * 启动 Gecko 离屏渲染服务
     */
    fun startGeckoRendering(
        uri: String = "about:blank",
        width: Int = 480,
        height: Int = 800
    ) {
        Log.d(TAG, "Starting Gecko rendering service: $uri")

        try {
            // 启动前台服务
            serviceManager?.startForegroundService(uri, width, height)

            // 创建渲染回调（用于 PixelCopy 方式，当前已废弃）
            renderCallback = SimpleRenderCallback(
                onFrame = { bitmap ->
                    // 异步处理 OCR
                    processFrameWithOcr(bitmap)
                },
                onError = { error ->
                    Log.e(TAG, "Render error: $error")
                    onOcrError?.invoke(error)
                }
            )

            // 注册回调
            renderCallback?.let {
                serviceManager?.registerRenderCallback(it)
            }

            isServiceRunning.value = true

            // 延迟触发首次 OCR（等待 Service 连接完成）
            lifecycleOwner.lifecycleScope.launch {
                delay(2000)  // 等待 2 秒确保 Service 已启动和绑定
                if (isServiceRunning.value) {
                    Log.d(TAG, "Triggering initial OCR after service start")
                    requestOcrOnce()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Gecko rendering", e)
            onOcrError?.invoke(e.message ?: "启动失败")
        }
    }

    /**
     * 按需触发一次 OCR：通过 JS 注入抓取当前页面，然后进行 OCR。
     * 适用于熄屏场景由 BLE 外设触发。
     */
    fun requestOcrOnce() {
        if (isOcrProcessing.value) {
            Log.d(TAG, "OCR is already processing, ignore request")
            return
        }
        if (!isServiceRunning.value) {
            Log.w(TAG, "Service not running, cannot request OCR")
            onOcrError?.invoke("Service 未运行")
            return
        }
        
        // 立即标记为处理中，避免重复请求
        isOcrProcessing.value = true
        Log.d(TAG, "Requesting OCR via JS injection (isOcrProcessing=true)")

        // 通过 Service 的 Binder 直接调用 JS 注入截图
        val binder = serviceManager?.getBinder()
        if (binder == null) {
            Log.e(TAG, "Binder is null, cannot request OCR")
            isOcrProcessing.value = false
            onOcrError?.invoke("Service 未连接")
            return
        }
        
        binder.requestCaptureViaJs(timeoutMs = 15000) { bitmap ->
            if (bitmap != null) {
                Log.d(TAG, "JS capture success: ${bitmap.width}x${bitmap.height}")
                lifecycleOwner.lifecycleScope.launch {
                    processFrameWithOcr(bitmap)
                }
            } else {
                Log.e(TAG, "JS capture failed, resetting isOcrProcessing")
                onOcrError?.invoke("截图失败")
                isOcrProcessing.value = false
            }
        }
    }

    /**
     * 处理渲染的 Bitmap 并进行 OCR 识别
     */
    private fun processFrameWithOcr(bitmap: Bitmap) {
        Log.d(TAG, "Processing frame for OCR: ${bitmap.width}x${bitmap.height}")
        
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            try {
                // isOcrProcessing 已经在 requestOcrOnce() 中设置为 true

                // 进行 OCR 识别
                val ocrResult = withContext(Dispatchers.Default) {
                    ocrHelper.recognizeText(bitmap)
                }

                if (ocrResult != null) {
                    // 提取文本块
                    val blocks = ocrResult.blocks.map { block ->
                        block.text
                    }

                    // 添加到显示数据
                    ocrTextDisplay.addPage(blocks)
                    currentPageIndex.value = ocrTextDisplay.getTotalPages() - 1
                    currentPageBlocks.value = blocks

                    Log.d(TAG, "OCR success: ${blocks.size} blocks recognized")
                    
                    withContext(Dispatchers.Main) {
                        onOcrComplete?.invoke(blocks)
                    }
                } else {
                    val error = "OCR 识别失败"
                    Log.e(TAG, error)
                    withContext(Dispatchers.Main) {
                        onOcrError?.invoke(error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR processing error", e)
                withContext(Dispatchers.Main) {
                    onOcrError?.invoke(e.message ?: "处理失败")
                }
            } finally {
                isOcrProcessing.value = false
            }
        }
    }

    /**
     * 停止服务
     */
    fun stopService() {
        Log.d(TAG, "Stopping service")
        
        renderCallback?.let {
            serviceManager?.unregisterRenderCallback(it)
        }
        serviceManager?.stopForegroundService()
        isServiceRunning.value = false
    }

    /**
     * 加载新的 URI
     */
    fun loadUri(uri: String) {
        Log.d(TAG, "Loading URI: $uri")
        serviceManager?.loadUri(uri)

        // 清空旧的 OCR 数据
        ocrTextDisplay.clear()
        currentPageIndex.value = 0
        currentPageBlocks.value = emptyList()
    }

    /**
     * 发送按键事件到 GeckoSession（用于翻页）
     * @param keyCode 按键代码 (KEYCODE_DPAD_LEFT 或 KEYCODE_DPAD_RIGHT)
     */
    private fun dispatchKeyToSession(keyCode: Int) {
        // 直接通过 Service 的 Binder 发送按键事件
        val binder = serviceManager?.getBinder()
        if (binder != null) {
            binder.dispatchKey(keyCode)
            Log.d(TAG, "Key dispatched via binder: $keyCode")
        } else {
            Log.w(TAG, "Binder is null, cannot dispatch key")
            // 降级：尝试通过回调发送（用于 GeckoView 模式）
            onDispatchKey?.invoke(keyCode)
        }
    }


    /**
     * 翻到上一页（发送左箭头键并重新 OCR）
     */
    fun previousPage() {
        Log.d(TAG, "Previous page: sending LEFT arrow")
        dispatchKeyToSession(KeyEvent.KEYCODE_DPAD_LEFT)

        // 延迟等待翻页完成后触发 OCR，增加等待时间以确保 Canvas 完全更新
        lifecycleOwner.lifecycleScope.launch {
            delay(2000)  // 增加等待时间到 2000ms，确保页面翻转和 Canvas 重新渲染完成
            // 添加重试机制，确保 OCR 成功
            retryRequestOcrOnce(maxAttempts = 3, delayBetweenRetries = 500)
        }
    }

    /**
     * 翻到下一页（发送右箭头键并重新 OCR）
     */
    fun nextPage() {
        Log.d(TAG, "Next page: sending RIGHT arrow")
        dispatchKeyToSession(KeyEvent.KEYCODE_DPAD_RIGHT)

        // 延迟等待翻页完成后触发 OCR，增加等待时间以确保 Canvas 完全更新
        lifecycleOwner.lifecycleScope.launch {
            delay(2000)  // 增加等待时间到 2000ms，确保页面翻转和 Canvas 重新渲染完成
            // 添加重试机制，确保 OCR 成功
            retryRequestOcrOnce(maxAttempts = 3, delayBetweenRetries = 500)
        }
    }

    /**
     * 重试 OCR 识别（如果第一次失败则重试）
     * 使用简单的延迟 + 回调方式实现重试
     */
    private fun retryRequestOcrOnce(maxAttempts: Int = 2, delayBetweenRetries: Long = 300, attemptNum: Int = 1) {
        // 检查是否已经在处理 OCR
        if (isOcrProcessing.value && attemptNum == 1) {
            Log.d(TAG, "OCR already in progress, skipping retry mechanism")
            return
        }
        
        if (!isServiceRunning.value) {
            Log.w(TAG, "Service not running, cannot request OCR (attempt $attemptNum/$maxAttempts)")
            if (attemptNum < maxAttempts) {
                lifecycleOwner.lifecycleScope.launch {
                    delay(delayBetweenRetries)
                    retryRequestOcrOnce(maxAttempts, delayBetweenRetries, attemptNum + 1)
                }
            } else {
                Log.e(TAG, "OCR failed after $maxAttempts attempts: Service 未运行")
                onOcrError?.invoke("OCR 失败：Service 未运行")
                isOcrProcessing.value = false
            }
            return
        }
        
        Log.d(TAG, "Requesting OCR (attempt $attemptNum/$maxAttempts)")
        
        // 通过 Service 的 Binder 直接调用 JS 注入截图
        val binder = serviceManager?.getBinder()
        if (binder == null) {
            Log.w(TAG, "Binder is null (attempt $attemptNum/$maxAttempts)")
            if (attemptNum < maxAttempts) {
                lifecycleOwner.lifecycleScope.launch {
                    delay(delayBetweenRetries)
                    retryRequestOcrOnce(maxAttempts, delayBetweenRetries, attemptNum + 1)
                }
            } else {
                Log.e(TAG, "OCR failed after $maxAttempts attempts: Binder 为 null")
                onOcrError?.invoke("OCR 失败：无法连接服务")
                isOcrProcessing.value = false
            }
            return
        }
        
        // 设置处理标志
        isOcrProcessing.value = true
        
        binder.requestCaptureViaJs(timeoutMs = 10000) { bitmap ->
            if (bitmap != null) {
                Log.d(TAG, "OCR capture success on attempt $attemptNum: ${bitmap.width}x${bitmap.height}")
                lifecycleOwner.lifecycleScope.launch {
                    processFrameWithOcr(bitmap)
                }
            } else {
                Log.w(TAG, "OCR capture returned null (attempt $attemptNum/$maxAttempts)")
                // 重置标志以允许重试
                isOcrProcessing.value = false
                
                if (attemptNum < maxAttempts) {
                    lifecycleOwner.lifecycleScope.launch {
                        delay(delayBetweenRetries)
                        retryRequestOcrOnce(maxAttempts, delayBetweenRetries, attemptNum + 1)
                    }
                } else {
                    Log.e(TAG, "OCR failed after $maxAttempts attempts")
                    onOcrError?.invoke("OCR 失败：无法捕获页面")
                }
            }
        }
    }

    /**
     * 仅浏览历史记录的上一页（不翻页，只切换显示）
     * @deprecated 使用 previousPage() 进行实际翻页
     */
    fun browsePreviousPage(): Boolean {
        val newIndex = currentPageIndex.value - 1
        if (newIndex >= 0 && newIndex < ocrTextDisplay.getTotalPages()) {
            currentPageIndex.value = newIndex
            ocrTextDisplay.getPage(newIndex)?.let { pageData ->
                currentPageBlocks.value = pageData.blocks
            }
            return true
        }
        return false
    }

    /**
     * 仅浏览历史记录的下一页（不翻页，只切换显示）
     * @deprecated 使用 nextPage() 进行实际翻页
     */
    fun browseNextPage(): Boolean {
        val newIndex = currentPageIndex.value + 1
        if (newIndex >= 0 && newIndex < ocrTextDisplay.getTotalPages()) {
            currentPageIndex.value = newIndex
            ocrTextDisplay.getPage(newIndex)?.let { pageData ->
                currentPageBlocks.value = pageData.blocks
            }
            return true
        }
        return false
    }

    /**
     * 获取总页数
     */
    fun getTotalPages(): Int = ocrTextDisplay.getTotalPages()

    /**
     * 清空所有数据
     */
    fun clear() {
        Log.d(TAG, "Clearing data")
        ocrTextDisplay.clear()
        currentPageIndex.value = 0
        currentPageBlocks.value = emptyList()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up")
        stopService()
        serviceManager?.cleanup()
        serviceManager = null
        renderCallback = null
        clear()
    }
}
