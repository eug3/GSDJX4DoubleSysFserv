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
 * OCR 识别助手 - 使用 PaddleOCR (Java API 实现)
 * 
 * 注意：使用 PaddleOcrPredictor (Java API) 而不是 OcrNative (C++ JNI)
 * 原因：禁用了 Native C++ 编译，避免 libocr_native.so 加载失败
 */
object OcrHelper {

    private const val TAG = "OcrHelper"
    
    // 模型路径
    private const val MODEL_DIR = "models"
    private const val DET_MODEL = "ch_PP-OCRv3_det_slim_opt.nb"
    private const val REC_MODEL = "ch_PP-OCRv3_rec_infer.nb"
    private const val CLS_MODEL = "ch_ppocr_mobile_v2.0_cls_slim_opt.nb"
    private const val DICT_FILE = "dict/ppocr_keys_v1.txt"
    
    // 改用 Java API 实现
    private var ocrPredictor: PaddleOcrPredictor? = null
    private var wordLabels: List<String> = emptyList()
    private var enableRecognition: Boolean = true
    
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
     * 初始化 PaddleOCR
     */
    suspend fun init(context: Context): Unit = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        
        initLock.withLock {
            if (isInitialized) return@withLock
            
            try {
                Log.d(TAG, "开始初始化 PaddleOCR (Java API)...")

                // 1. 复制模型文件（当前工程只保留一份 DET/REC/CLS 模型）
                val cacheDir = File(context.cacheDir, MODEL_DIR)
                if (!cacheDir.exists()) cacheDir.mkdirs()

                listOf(DET_MODEL, REC_MODEL, CLS_MODEL).forEach { modelName ->
                    copyAssetFile(context, "$MODEL_DIR/$modelName", File(cacheDir, modelName))
                }

                // 3. 加载字典
                wordLabels = loadDict(context, DICT_FILE)
                
                // 3. 创建 Java API 预测器 (不需要 libocr_native.so)
                ocrPredictor = PaddleOcrPredictor(
                    detModelPath = File(cacheDir, DET_MODEL).absolutePath,
                    recModelPath = File(cacheDir, REC_MODEL).absolutePath,
                    clsModelPath = File(cacheDir, CLS_MODEL).absolutePath,
                    wordLabels = wordLabels,
                    cpuThreadNum = 1,
                    safeMode = false
                )
                
                isInitialized = true
                initDeferred.complete(true) // 通知所有等待者：初始化已完成
                Log.i(TAG, "PaddleOCR (Java API) 加载成功")
            } catch (e: Exception) {
                Log.e(TAG, "PaddleOCR 加载失败", e)
                isInitialized = false
                initDeferred.completeExceptionally(e)
                // 重置 Deferred 允许后续重试
                initDeferred = CompletableDeferred()
                throw e
            }
        }
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
        
        val predictor = ocrPredictor ?: throw IllegalStateException("OCR 预测器为空")
        predictor.applyBinarization(bitmap)
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
        
        val predictor = ocrPredictor ?: throw IllegalStateException("OCR 预测器为空")
        predictor.drawDetectionBoxes(bitmap)
    }

    /**
     * 在已二值化的图像上绘制检测框（用于确保与 det 输入是同一张 bitmap）
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

        val predictor = ocrPredictor ?: throw IllegalStateException("OCR 预测器为空")
        predictor.drawDetectionBoxesOnBinary(binaryBitmap, maxSideLen)
    }
    
    /**
     * 识别图片中的文字
     * 改进：如果引擎未就绪，会阻塞挂起当前协程，直到初始化完成
     */
    suspend fun recognizeText(bitmap: Bitmap, alreadyBinarized: Boolean = false): OcrResult = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.d(TAG, "OCR 尚未就绪，挂起请求等待初始化...")
            try {
                initDeferred.await() // 阻塞挂起，等待完成信号
            } catch (e: Exception) {
                throw IllegalStateException("OCR 初始化之前已失败: ${e.message}")
            }
        }
        
        val predictor = ocrPredictor ?: throw IllegalStateException("OCR 预测器为空")
        
        try {
            // 使用 Java API 实现
            // 若启用了识别（v2 模型可用），则 runRec=1，否则仅做检测
            // 启用方向分类 (runCls=1) 以纠正文字方向，提高识别准确率
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
            OcrResult(formattedText, blocks, formattedText)
        } catch (e: Exception) {
            Log.e(TAG, "文字识别失败", e)
            throw e
        }
    }

    fun close() {
        ocrPredictor = null
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
