package com.guaishoudejia.x4doublesysfserv.ocr

import android.graphics.Bitmap
import android.util.Log
import com.baidu.paddle.lite.MobileConfig
import com.baidu.paddle.lite.PaddlePredictor
import com.baidu.paddle.lite.PowerMode

/**
 * 简化版 PaddleOCR 预测器 - 用于演示和测试
 * 
 * 模型已加载，返回排版好的演示结果
 */
class SimplePaddleOcrPredictor(
    private val detModelPath: String,
    private val recModelPath: String,
    private val clsModelPath: String,
    private val wordLabels: List<String> = emptyList(),
    private val cpuThreadNum: Int = 4
) {
    
    private val TAG = "SimplePaddleOcr"
    
    private var detPredictor: PaddlePredictor? = null
    private var recPredictor: PaddlePredictor? = null
    private var clsPredictor: PaddlePredictor? = null
    
    init {
        initPredictors()
    }
    
    private fun initPredictors() {
        try {
            Log.d(TAG, "初始化 Paddle-Lite 预测器...")
            
            detPredictor = createPredictor(detModelPath)
            Log.d(TAG, "✓ 检测模型加载成功")
            
            recPredictor = createPredictor(recModelPath)
            Log.d(TAG, "✓ 识别模型加载成功")
            
            clsPredictor = createPredictor(clsModelPath)
            Log.d(TAG, "✓ 分类模型加载成功")
            
            Log.d(TAG, "所有模型加载完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败", e)
            throw e
        }
    }
    
    private fun createPredictor(modelPath: String): PaddlePredictor {
        val config = MobileConfig()
        config.setModelFromFile(modelPath)
        config.setThreads(cpuThreadNum)
        config.setPowerMode(PowerMode.LITE_POWER_HIGH)
        return PaddlePredictor.createPaddlePredictor(config)
    }
    
    /**
     * 执行 OCR 识别 - 返回排版好的文字
     */
    fun runImage(
        bitmap: Bitmap,
        maxSideLen: Int = 960,
        runDet: Int = 1,
        runCls: Int = 1,
        runRec: Int = 1
    ): List<OcrResultModel> {
        
        Log.d(TAG, "识别图像: ${bitmap.width}x${bitmap.height}")
        Log.i(TAG, "✓ 模型已加载，返回演示结果")
        
        // 演示文本（排版好）
        val demoTexts = listOf(
            "PaddleOCR 文本识别演示",
            "图像已正确加载",
            "使用 Paddle-Lite 进行推理",
            "完整功能开发中..."
        )
        
        val results = mutableListOf<OcrResultModel>()
        
        for ((index, text) in demoTexts.withIndex()) {
            val result = OcrResultModel()
            result.setLabel(text)
            result.setConfidence(0.8f + (index * 0.03f).coerceAtMost(0.99f))
            
            val y = index * 50
            result.addPoints(0, y)
            result.addPoints(bitmap.width, y)
            result.addPoints(bitmap.width, y + 40)
            result.addPoints(0, y + 40)
            
            result.setClsIdx(0f)
            result.setClsConfidence(0.95f)
            
            results.add(result)
            Log.d(TAG, "[$index] $text")
        }
        
        return results
    }
    
    /**
     * 释放资源
     */
    fun destroy() {
        detPredictor = null
        recPredictor = null
        clsPredictor = null
        Log.d(TAG, "资源已释放")
    }
}
