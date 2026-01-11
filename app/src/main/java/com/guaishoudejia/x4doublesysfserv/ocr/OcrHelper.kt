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
 * OCR 识别助手 - 使用 PaddleOCR
 */
object OcrHelper {

    private const val TAG = "OcrHelper"
    
    // 模型路径
    private const val MODEL_DIR = "models"
    private const val DET_MODEL = "ch_PP-OCRv3_det_slim_opt.nb"
    private const val REC_MODEL = "ch_PP-OCRv3_rec_slim_opt.nb"
    private const val CLS_MODEL = "ch_ppocr_mobile_v2.0_cls_slim_opt.nb"
    private const val DICT_FILE = "dict/ppocr_keys_v1.txt"
    
    private var ocrPredictor: PaddleOcrPredictor? = null
    private var wordLabels: List<String> = emptyList()
    
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
    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        
        initLock.withLock {
            if (isInitialized) return@withLock
            
            try {
                Log.d(TAG, "开始初始化 OCR 引擎...")
                
                // 1. 复制模型文件
                val cacheDir = File(context.cacheDir, MODEL_DIR)
                if (!cacheDir.exists()) cacheDir.mkdirs()
                
                listOf(DET_MODEL, REC_MODEL, CLS_MODEL).forEach { modelName ->
                    copyAssetFile(context, "$MODEL_DIR/$modelName", File(cacheDir, modelName))
                }
                
                // 2. 加载字典
                wordLabels = loadDict(context, DICT_FILE)
                
                // 3. 创建预测器
                ocrPredictor = PaddleOcrPredictor(
                    detModelPath = File(cacheDir, DET_MODEL).absolutePath,
                    recModelPath = File(cacheDir, REC_MODEL).absolutePath,
                    clsModelPath = File(cacheDir, CLS_MODEL).absolutePath,
                    wordLabels = wordLabels,
                    cpuThreadNum = 1
                )
                
                isInitialized = true
                initDeferred.complete(true) // 通知所有等待者：初始化已完成
                Log.i(TAG, "OCR 引擎加载成功")
            } catch (e: Exception) {
                Log.e(TAG, "OCR 引擎加载失败", e)
                isInitialized = false
                initDeferred.completeExceptionally(e)
                // 重置 Deferred 允许后续重试
                initDeferred = CompletableDeferred()
                throw e
            }
        }
    }
    
    /**
     * 识别图片中的文字
     * 改进：如果引擎未就绪，会阻塞挂起当前协程，直到初始化完成
     */
    suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
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
            val results = predictor.runImage(bitmap, 960, 1, 0, 1)
            val blocks = mutableListOf<TextBlock>()
            val textLines = mutableListOf<String>()
            
            results.forEachIndexed { index, result ->
                val text = result.label?.trim() ?: ""
                if (text.isNotEmpty()) {
                    blocks.add(TextBlock(text, result.confidence, index))
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
        ocrPredictor?.destroy()
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
        return context.assets.open(dictPath).bufferedReader().use { 
            it.readLines().filter { line -> line.isNotBlank() } 
        }
    }
}
