package com.guaishoudejia.x4doublesysfserv.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

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
    PADDLE_LITE,   // PaddleOCR v3 (Paddle-Lite)
    ONNX_RUNTIME   // PP-OCRv5 (ONNX Runtime Mobile) - 默认
}

/**
 * OCR 识别助手 - 支持 PaddleOCR 和 ONNX Runtime 两种引擎
 *
 * 使用方法：
 * ```kotlin
 * // 使用默认引擎 (Paddle-Lite)
 * OcrHelper.init(context)
 *
 * // 或指定使用 ONNX Runtime (PP-OCRv5)
 * OcrHelper.init(context, OcrEngine.ONNX_RUNTIME)
 * ```
 */
object OcrHelper {

    private const val TAG = "OcrHelper"

    // 当前使用的 OCR 引擎
    private var currentEngine: OcrEngine = OcrEngine.ONNX_RUNTIME

    // 模型路径
    private const val MODEL_DIR = "models"
    private const val DET_MODEL = "ch_PP-OCRv3_det_slim_opt.nb"
    private const val REC_MODEL = "ch_PP-OCRv3_rec_infer.nb"
    private const val CLS_MODEL = "ch_ppocr_mobile_v2.0_cls_slim_opt.nb"
    private const val DICT_FILE = "dict/ppocr_keys_v1.txt"

    // Paddle-Lite 实现
    private var ocrPredictor: PaddleOcrPredictor? = null
    private var wordLabels: List<String> = emptyList()
    private var enableRecognition: Boolean = true

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
     * @param engine OCR 引擎类型，默认使用 ONNX Runtime (PP-OCRv5)
     */
    suspend fun init(context: Context, engine: OcrEngine = OcrEngine.ONNX_RUNTIME): Unit = withContext(Dispatchers.IO) {
        if (isInitialized && currentEngine == engine) return@withContext

        initLock.withLock {
            if (isInitialized && currentEngine == engine) return@withLock

            // 切换引擎时，先清理旧引擎
            if (isInitialized && currentEngine != engine) {
                Log.d(TAG, "切换 OCR 引擎: $currentEngine -> $engine")
                closeInternal()
            }

            currentEngine = engine

            try {
                when (engine) {
                    OcrEngine.PADDLE_LITE -> initPaddleLite(context)
                    OcrEngine.ONNX_RUNTIME -> initOnnxRuntime(context)
                }

                isInitialized = true
                initDeferred.complete(true)
                Log.i(TAG, "OCR 初始化成功 (引擎: $engine)")
            } catch (e: Exception) {
                Log.e(TAG, "OCR 初始化失败 (引擎: $engine)", e)
                isInitialized = false
                initDeferred.completeExceptionally(e)
                initDeferred = CompletableDeferred()
                throw e
            }
        }
    }

    /**
     * 初始化 Paddle-Lite 引擎 (PaddleOCR v3)
     */
    private fun initPaddleLite(context: Context) {
        Log.d(TAG, "开始初始化 PaddleOCR v3 (Paddle-Lite)...")

        // 1. 复制模型文件
        val cacheDir = File(context.cacheDir, MODEL_DIR)
        if (!cacheDir.exists()) cacheDir.mkdirs()

        listOf(DET_MODEL, REC_MODEL, CLS_MODEL).forEach { modelName ->
            copyAssetFile(context, "$MODEL_DIR/$modelName", File(cacheDir, modelName))
        }

        // 2. 加载字典
        wordLabels = loadDict(context, DICT_FILE)

        // 3. 创建 Java API 预测器
        ocrPredictor = PaddleOcrPredictor(
            detModelPath = File(cacheDir, DET_MODEL).absolutePath,
            recModelPath = File(cacheDir, REC_MODEL).absolutePath,
            clsModelPath = File(cacheDir, CLS_MODEL).absolutePath,
            wordLabels = wordLabels,
            cpuThreadNum = 1,
            safeMode = false
        )

        Log.i(TAG, "PaddleOCR v3 (Paddle-Lite) 加载成功")
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

        when (currentEngine) {
            OcrEngine.PADDLE_LITE -> {
                val predictor = ocrPredictor ?: throw IllegalStateException("OCR 预测器为空")
                predictor.applyBinarization(bitmap)
            }
            OcrEngine.ONNX_RUNTIME -> {
                val helper = onnxOcrHelper ?: throw IllegalStateException("ONNX OCR 助手为空")
                helper.binarizeBitmap(bitmap)
            }
        }
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

        when (currentEngine) {
            OcrEngine.PADDLE_LITE -> {
                val predictor = ocrPredictor ?: throw IllegalStateException("OCR 预测器为空")
                predictor.drawDetectionBoxes(bitmap)
            }
            OcrEngine.ONNX_RUNTIME -> {
                val helper = onnxOcrHelper ?: throw IllegalStateException("ONNX OCR 助手为空")
                helper.drawDetectionBoxes(bitmap)
            }
        }
    }

    /**
     * 在已二值化的图像上绘制检测框（用于确保与 det 输入是同一张 bitmap）
     * 注意：仅支持 Paddle-Lite 引擎
     */
    suspend fun drawDetectionBoxesOnBinary(binaryBitmap: Bitmap, maxSideLen: Int = 960): Bitmap = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.d(TAG, "OCR 尚未就绪，挂起请求等待初始化...")
            try {
                initDeferred.await()
            } catch (e: Exception) {
                throw IllegalStateException("OCR 初始化之前已失败: ${e.message}")
            }
        }

        when (currentEngine) {
            OcrEngine.PADDLE_LITE -> {
                val predictor = ocrPredictor ?: throw IllegalStateException("OCR 预测器为空")
                predictor.drawDetectionBoxesOnBinary(binaryBitmap, maxSideLen)
            }
            OcrEngine.ONNX_RUNTIME -> {
                // ONNX 引擎不支持此方法，使用普通检测框绘制
                val helper = onnxOcrHelper ?: throw IllegalStateException("ONNX OCR 助手为空")
                helper.drawDetectionBoxes(binaryBitmap)
            }
        }
    }

    /**
     * 识别图片中的文字
     * @param bitmap 输入图像
     * @param alreadyBinarized 是否已二值化（仅 Paddle-Lite 引擎支持）
     */
    suspend fun recognizeText(bitmap: Bitmap, alreadyBinarized: Boolean = false): OcrResult = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.d(TAG, "OCR 尚未就绪，挂起请求等待初始化...")
            try {
                initDeferred.await()
            } catch (e: Exception) {
                throw IllegalStateException("OCR 初始化之前已失败: ${e.message}")
            }
        }

        when (currentEngine) {
            OcrEngine.PADDLE_LITE -> recognizeTextPaddleLite(bitmap, alreadyBinarized)
            OcrEngine.ONNX_RUNTIME -> recognizeTextOnnx(bitmap)
        }
    }

    /**
     * 使用 Paddle-Lite 引擎识别文字
     */
    private suspend fun recognizeTextPaddleLite(bitmap: Bitmap, alreadyBinarized: Boolean): OcrResult {
        val predictor = ocrPredictor ?: throw IllegalStateException("OCR 预测器为空")

        try {
            val runRec = if (enableRecognition) 1 else 0
            val rawResults = predictor.runImage(bitmap, 960, 1, 1, runRec, alreadyBinarized)

            val blocks = mutableListOf<TextBlock>()
            val textLines = mutableListOf<String>()

            rawResults.forEachIndexed { index, result ->
                val text = result.getLabel() ?: ""
                blocks.add(TextBlock(text, result.getConfidence(), index))
                if (text.isNotEmpty()) {
                    textLines.add(text)
                }
            }

            val formattedText = textLines.joinToString("\n")
            return OcrResult(formattedText, blocks, formattedText)
        } catch (e: Exception) {
            Log.e(TAG, "文字识别失败 (Paddle-Lite)", e)
            throw e
        }
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
        ocrPredictor = null
        onnxOcrHelper?.close()
        onnxOcrHelper = null
        isInitialized = false
        if (initDeferred.isActive) initDeferred.cancel()
        initDeferred = CompletableDeferred()
    }
    
    private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
        if (destFile.exists() && destFile.length() > 1000) return
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }
    
    private fun loadDict(context: Context, dictPath: String): List<String> {
        // 注意：ppocr_keys_v1.txt 内可能包含“全角空格”等空白字符（例如 U+3000）。
        // 如果用 isNotBlank() 过滤会把它丢掉，导致字典索引整体错位，从而输出乱码。
        return context.assets.open(dictPath).bufferedReader(charset = Charsets.UTF_8).use {
            it.readLines().filter { line -> line.isNotEmpty() }
        }
    }
}
