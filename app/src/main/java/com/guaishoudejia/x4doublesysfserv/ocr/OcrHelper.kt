package com.guaishoudejia.x4doublesysfserv.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * OCR 识别结果
 */
data class OcrResult(
    val text: String,           // 完整识别文本
    val blocks: List<TextBlock>, // 段落块
    val rawText: String          // 原始识别文本
)

/**
 * 文本块（段落）
 */
data class TextBlock(
    val text: String,
    val confidence: Float,
    val blockIndex: Int
)

/**
 * OCR 引擎类型
 */
enum class OcrEngine {
    ONNX_RUNTIME   // PP-OCRv5 (ONNX Runtime Mobile) - 默认
}

/**
 * OCR 识别助手 - 基于 ONNX Runtime Mobile
 *
 * 使用方法：
 * ```kotlin
 * OcrHelper.init(context)
 * ```
 */
object OcrHelper {

    private const val TAG = "OcrHelper"

    // 当前使用的 OCR 引擎
    private var currentEngine: OcrEngine = OcrEngine.ONNX_RUNTIME

    // ONNX Runtime 实现
    private var onnxOcrHelper: OnnxOcrHelper? = null

    @Volatile
    private var isInitialized = false

    // 初始化锁
    private val initLock = Mutex()
    // 关键：使用 Deferred 确保 recognizeText 能够阻塞挂起直到 init 真正完成
    private var initDeferred = CompletableDeferred<Boolean>()

    /**
     * 判断 OCR 是否已准备就绪
     */
    fun isReady(): Boolean = isInitialized

    /**
     * 获取当前使用的 OCR 引擎
     */
    fun getEngine(): OcrEngine = currentEngine

    /**
     * 初始化 OCR
     * @param context 上下文
     */
    suspend fun init(context: Context): Unit = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        initLock.withLock {
            if (isInitialized) return@withLock

            currentEngine = OcrEngine.ONNX_RUNTIME

            try {
                initOnnxRuntime(context)

                isInitialized = true
                initDeferred.complete(true)
                Log.i(TAG, "OCR 初始化成功 (引擎: $currentEngine)")
            } catch (e: Exception) {
                Log.e(TAG, "OCR 初始化失败 (引擎: $currentEngine)", e)
                isInitialized = false
                initDeferred.completeExceptionally(e)
                initDeferred = CompletableDeferred()
                throw e
            }
        }
    }

    /**
     * 初始化 ONNX Runtime 引擎 (PP-OCRv5)
     */
    private suspend fun initOnnxRuntime(context: Context) {
        Log.d(TAG, "开始初始化 PP-OCRv5 (ONNX Runtime Mobile)...")

        onnxOcrHelper = OnnxOcrHelper()
        onnxOcrHelper!!.init(context)

        Log.i(TAG, "PP-OCRv5 (ONNX Runtime Mobile) 加载成功")
    }

    /**
     * 对图像应用二值化处理（用于预览显示）
     */
    suspend fun binarizeBitmap(bitmap: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.d(TAG, "OCR 尚未就绪，挂起请求等待初始化...")
            try {
                initDeferred.await()
            } catch (e: Exception) {
                throw IllegalStateException("OCR 初始化之前已失败: ${e.message}")
            }
        }

        val helper = onnxOcrHelper ?: throw IllegalStateException("ONNX OCR 助手为空")
        helper.binarizeBitmap(bitmap)
    }

    /**
     * 绘制检测框到图像上（用于调试）
     */
    suspend fun drawDetectionBoxes(bitmap: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.d(TAG, "OCR 尚未就绪，挂起请求等待初始化...")
            try {
                initDeferred.await()
            } catch (e: Exception) {
                throw IllegalStateException("OCR 初始化之前已失败: ${e.message}")
            }
        }

        val helper = onnxOcrHelper ?: throw IllegalStateException("ONNX OCR 助手为空")
        helper.drawDetectionBoxes(bitmap)
    }

    /**
     * 识别图片中的文字
     * @param bitmap 输入图像
     */
    suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.d(TAG, "OCR 尚未就绪，挂起请求等待初始化...")
            try {
                initDeferred.await()
            } catch (e: Exception) {
                throw IllegalStateException("OCR 初始化之前已失败: ${e.message}")
            }
        }

        recognizeTextOnnx(bitmap)
    }

    /**
     * 使用 ONNX Runtime 引擎识别文字
     */
    private suspend fun recognizeTextOnnx(bitmap: Bitmap): OcrResult {
        val helper = onnxOcrHelper ?: throw IllegalStateException("ONNX OCR 助手为空")

        try {
            return helper.recognizeText(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "文字识别失败 (ONNX Runtime)", e)
            throw e
        }
    }

    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        onnxOcrHelper?.close()
        onnxOcrHelper = null
        isInitialized = false
        if (initDeferred.isActive) initDeferred.cancel()
        initDeferred = CompletableDeferred()
    }
}
