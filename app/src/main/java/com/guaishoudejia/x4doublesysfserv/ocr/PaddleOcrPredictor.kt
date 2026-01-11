package com.guaishoudejia.x4doublesysfserv.ocr

import android.graphics.Bitmap
import android.util.Log
import com.baidu.paddle.lite.MobileConfig
import com.baidu.paddle.lite.PaddlePredictor
import com.baidu.paddle.lite.PowerMode
import kotlin.math.max
import kotlin.math.min

/**
 * PaddleOCR 预测器 - 使用 Paddle-Lite Java API + JNI
 * 
 * 基于 libpaddle_lite_jni.so 提供的完整 native API
 * 支持完整的模型推理流程
 */
class PaddleOcrPredictor(
    private val detModelPath: String,
    private val recModelPath: String,
    private val clsModelPath: String,
    private val wordLabels: List<String> = emptyList(),
    private val cpuThreadNum: Int = 4
) {
    
    private val TAG = "PaddleOcrPredictor"
    
    private var detPredictor: PaddlePredictor? = null
    private var recPredictor: PaddlePredictor? = null
    private var clsPredictor: PaddlePredictor? = null
    
    init {
        initPredictors()
    }
    
    private fun initPredictors() {
        try {
            Log.d(TAG, "初始化 Paddle-Lite 预测器（使用 JNI）...")
            
            // 加载检测模型
            Log.d(TAG, "正在加载检测模型: $detModelPath")
            detPredictor = createPredictor(detModelPath, "检测")
            Log.d(TAG, "✓ 检测模型加载成功")
            
            // 加载识别模型
            Log.d(TAG, "正在加载识别模型: $recModelPath")
            recPredictor = createPredictor(recModelPath, "识别")
            Log.d(TAG, "✓ 识别模型加载成功")
            
            // 分类模型已禁用，以节省内存（内存优化：跳过分类）
            Log.d(TAG, "⚠️  分类模型已禁用以节省内存，文本方向判断功能不可用")
            
            Log.d(TAG, "所有必需模型加载成功，字典大小: ${wordLabels.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败", e)
            throw e
        }
    }
    
    private fun createPredictor(modelPath: String, modelType: String): PaddlePredictor {
        try {
            Log.d(TAG, "[$modelType] 创建配置...")
            val config = MobileConfig()
            
            Log.d(TAG, "[$modelType] 从文件加载模型: $modelPath")
            config.setModelFromFile(modelPath)
            Log.d(TAG, "[$modelType] 模型文件加载到配置完成")
            
            Log.d(TAG, "[$modelType] 设置线程数: $cpuThreadNum")
            config.setThreads(cpuThreadNum)
            
            Log.d(TAG, "[$modelType] 设置电源模式为 HIGH")
            config.setPowerMode(PowerMode.LITE_POWER_HIGH)
            
            Log.d(TAG, "[$modelType] 准备创建 PaddlePredictor (这一步可能耗时)...")
            val predictor = PaddlePredictor.createPaddlePredictor(config)
            Log.d(TAG, "[$modelType] PaddlePredictor 创建成功")
            
            return predictor
            
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "[$modelType] 内存不足 (OutOfMemoryError)！可能设备内存无法支持此模型", e)
            throw RuntimeException("内存不足：无法加载 $modelType 模型，设备可用内存过低", e)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "[$modelType] JNI 库加载失败 (UnsatisfiedLinkError)！可能缺少 .so 文件", e)
            throw RuntimeException("JNI 库加载失败：缺少 libpaddle_lite_jni.so", e)
        } catch (e: Exception) {
            Log.e(TAG, "[$modelType] 创建 PaddlePredictor 失败", e)
            throw RuntimeException("失败创建 $modelType 预测器: ${e.message}", e)
        }
    }
    
    /**
     * 执行 OCR 识别
     */
    fun runImage(
        bitmap: Bitmap,
        maxSideLen: Int = 960,
        runDet: Int = 1,
        runCls: Int = 1,
        runRec: Int = 1
    ): List<OcrResultModel> {
        
        try {
            Log.d(TAG, "开始 OCR 识别，图像: ${bitmap.width}x${bitmap.height}")
            
            if (runDet == 0) {
                return listOf(recognizeFullImage(bitmap, runCls == 1, runRec == 1))
            }
            
            // 1. 文本检测
            val detBoxes = detectText(bitmap, maxSideLen)
            Log.d(TAG, "检测到 ${detBoxes.size} 个文本区域")
            
            if (detBoxes.isEmpty()) {
                return emptyList()
            }
            
            // 2. 对每个文本区域进行识别
            val results = mutableListOf<OcrResultModel>()
            for ((index, box) in detBoxes.withIndex()) {
                try {
                    val result = OcrResultModel()
                    // 添加文本框的四个顶点
                    for (point in box) {
                        result.addPoints(point.x, point.y)
                    }
                    
                    val cropped = cropBox(bitmap, box)
                    
                    // 分类
                    if (runCls == 1 && clsPredictor != null) {
                        val (clsIdx, clsConf) = classifyOrientation(cropped)
                        result.setClsIdx(clsIdx)
                        result.setClsConfidence(clsConf)
                    }
                    
                    // 识别
                    if (runRec == 1 && recPredictor != null) {
                        val (text, confidence) = recognizeText(cropped)
                        result.setLabel(text)
                        result.setConfidence(confidence)
                    }
                    
                    results.add(result)
                    Log.d(TAG, "文本块 $index: ${result.label}")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "识别文本块 $index 失败", e)
                }
            }
            
            Log.d(TAG, "OCR 识别完成，共 ${results.size} 个文本块")
            return results
            
        } catch (e: Exception) {
            Log.e(TAG, "OCR 识别失败", e)
            throw e
        }
    }
    
    /**
     * 文本检测
     */
    private fun detectText(bitmap: Bitmap, maxSideLen: Int): List<Array<Point>> {
        if (detPredictor == null) {
            return emptyList()
        }
        
        try {
            val (scaledBitmap, ratio) = scaleImage(bitmap, maxSideLen)
            val inputData = preprocessImage(scaledBitmap)
            
            // 设置输入
            val inputTensor = detPredictor!!.getInput(0)
            inputTensor.resize(longArrayOf(1, 3, scaledBitmap.height.toLong(), scaledBitmap.width.toLong()))
            inputTensor.setData(inputData)
            
            // 推理
            detPredictor!!.run()
            
            // 获取输出
            val outputTensor = detPredictor!!.getOutput(0)
            val shape = outputTensor.shape()
            Log.d(TAG, "检测输出形状: [${shape.joinToString(", ")}]")
            
            val outputData = outputTensor.getFloatData()
            
            // 后处理
            val boxes = postprocessDetection(outputData, shape, scaledBitmap.width, scaledBitmap.height, ratio)
            
            return boxes
            
        } catch (e: Exception) {
            Log.e(TAG, "文本检测失败", e)
            return emptyList()
        }
    }
    
    /**
     * 文本识别
     */
    private fun recognizeText(bitmap: Bitmap): Pair<String, Float> {
        if (recPredictor == null) {
            return Pair("", 0f)
        }
        
        try {
            val resized = resizeForRecognition(bitmap, 48)
            val inputData = preprocessImage(resized)
            
            val inputTensor = recPredictor!!.getInput(0)
            inputTensor.resize(longArrayOf(1, 3, resized.height.toLong(), resized.width.toLong()))
            inputTensor.setData(inputData)
            
            recPredictor!!.run()
            
            val outputTensor = recPredictor!!.getOutput(0)
            val shape = outputTensor.shape()
            val outputData = outputTensor.getFloatData()
            
            val (text, conf) = postprocessRecognition(outputData, shape)
            return Pair(text, conf)
            
        } catch (e: Exception) {
            Log.e(TAG, "文本识别失败", e)
            return Pair("", 0f)
        }
    }
    
    /**
     * 文本方向分类
     */
    private fun classifyOrientation(bitmap: Bitmap): Pair<Float, Float> {
        if (clsPredictor == null) {
            return Pair(0f, 1f)
        }
        
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            val inputData = preprocessImage(resized)
            
            val inputTensor = clsPredictor!!.getInput(0)
            inputTensor.resize(longArrayOf(1, 3, 224L, 224L))
            inputTensor.setData(inputData)
            
            clsPredictor!!.run()
            
            val outputTensor = clsPredictor!!.getOutput(0)
            val outputData = outputTensor.getFloatData()
            
            val clsIdx = if (outputData[0] > outputData[1]) 0f else 1f
            val clsConf = max(outputData[0], outputData[1])
            
            return Pair(clsIdx, clsConf)
            
        } catch (e: Exception) {
            Log.e(TAG, "分类失败", e)
            return Pair(0f, 1f)
        }
    }
    
    /**
     * 识别整张图
     */
    private fun recognizeFullImage(
        bitmap: Bitmap,
        runCls: Boolean,
        runRec: Boolean
    ): OcrResultModel {
        val box = arrayOf(
            Point(0, 0),
            Point(bitmap.width, 0),
            Point(bitmap.width, bitmap.height),
            Point(0, bitmap.height)
        )
        
        val result = OcrResultModel()
        for (point in box) {
            result.addPoints(point.x, point.y)
        }
        
        if (runRec) {
            val (text, conf) = recognizeText(bitmap)
            result.setLabel(text)
            result.setConfidence(conf)
        }
        
        if (runCls) {
            val (clsIdx, clsConf) = classifyOrientation(bitmap)
            result.setClsIdx(clsIdx)
            result.setClsConfidence(clsConf)
        }
        
        return result
    }
    
    // ===== 辅助方法 =====
    
    private fun preprocessImage(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val channelSize = width * height
        
        val floatArray = FloatArray(channelSize * 3)
        val pixels = IntArray(channelSize)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF).toFloat() / 255f
            val g = ((pixel shr 8) and 0xFF).toFloat() / 255f
            val b = (pixel and 0xFF).toFloat() / 255f
            
            floatArray[i] = r
            floatArray[i + channelSize] = g
            floatArray[i + channelSize * 2] = b
        }
        
        return floatArray
    }
    
    private fun scaleImage(bitmap: Bitmap, maxSideLen: Int): Pair<Bitmap, Float> {
        val width = bitmap.width
        val height = bitmap.height
        val maxSide = max(width, height)
        
        if (maxSide <= maxSideLen) {
            return Pair(bitmap, 1f)
        }
        
        val ratio = maxSideLen.toFloat() / maxSide
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        
        val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        return Pair(scaled, ratio)
    }
    
    private fun resizeForRecognition(bitmap: Bitmap, targetHeight: Int): Bitmap {
        val ratio = targetHeight.toFloat() / bitmap.height
        val targetWidth = (bitmap.width * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
    
    private fun cropBox(bitmap: Bitmap, box: Array<Point>): Bitmap {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        
        for (point in box) {
            minX = min(minX, point.x)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        }
        
        val width = maxX - minX
        val height = maxY - minY
        
        return Bitmap.createBitmap(bitmap, minX, minY, width, height)
    }
    
    /**
     * DBNet 后处理 - 从预测输出中提取文本框
     * 
     * DBNet 输出是一个热力图，需要：
     * 1. 二值化（threshold >= 0.3）
     * 2. 轮廓检测
     * 3. 多边形近似
     */
    private fun postprocessDetection(
        output: FloatArray,
        shape: LongArray,
        width: Int,
        height: Int,
        scaleRatio: Float
    ): List<Array<Point>> {
        // 简化版实现：根据热力图找出高置信度的区域
        val batch = shape[0].toInt()
        val channels = shape[1].toInt()
        val h = shape[2].toInt()
        val w = shape[3].toInt()
        
        Log.d(TAG, "DBNet 输出形状: batch=$batch channels=$channels h=$h w=$w")
        
        val boxes = mutableListOf<Array<Point>>()
        val threshold = 0.3f
        
        // 查找高置信度的像素
        val mask = BooleanArray(h * w)
        for (i in 0 until h * w) {
            mask[i] = output[i] > threshold
        }
        
        // 简单的连通分量标记
        val visited = BooleanArray(h * w)
        val directions = arrayOf(
            Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1)
        )
        
        for (idx in mask.indices) {
            if (!mask[idx] || visited[idx]) continue
            
            val y = idx / w
            val x = idx % w
            val box = mutableListOf<Point>()
            val queue = mutableListOf(Pair(y, x))
            visited[idx] = true
            
            while (queue.isNotEmpty()) {
                val (cy, cx) = queue.removeAt(0)
                box.add(Point(cx, cy))
                
                for ((dy, dx) in directions) {
                    val ny = cy + dy
                    val nx = cx + dx
                    if (ny in 0 until h && nx in 0 until w) {
                        val nidx = ny * w + nx
                        if (mask[nidx] && !visited[nidx]) {
                            visited[nidx] = true
                            queue.add(Pair(ny, nx))
                        }
                    }
                }
            }
            
            if (box.size > 50) {  // 最小面积阈值
                // 计算外接矩形
                val rect = getBoundingBox(box, scaleRatio)
                boxes.add(rect)
            }
        }
        
        // 如果没有检测到框，返回全图
        if (boxes.isEmpty()) {
            Log.w(TAG, "未检测到文本框，返回全图")
            boxes.add(arrayOf(
                Point(0, 0),
                Point(width, 0),
                Point(width, height),
                Point(0, height)
            ))
        }
        
        return boxes
    }
    
    /**
     * 从点集计算外接矩形
     */
    private fun getBoundingBox(points: List<Point>, scale: Float): Array<Point> {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        
        for (point in points) {
            minX = min(minX, point.x)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        }
        
        // 还原到原图坐标
        minX = (minX / scale).toInt()
        minY = (minY / scale).toInt()
        maxX = (maxX / scale).toInt()
        maxY = (maxY / scale).toInt()
        
        return arrayOf(
            Point(minX, minY),
            Point(maxX, minY),
            Point(maxX, maxY),
            Point(minX, maxY)
        )
    }
    
    /**
     * CRNN CTC 解码
     * 
     * CRNN 输出格式：[seq_len, num_classes]
     * 需要进行 CTC 解码获得最终文本
     */
    private fun postprocessRecognition(
        output: FloatArray,
        shape: LongArray
    ): Pair<String, Float> {
        if (shape.size < 2 || wordLabels.isEmpty()) {
            return Pair("", 0.5f)
        }
        
        val seqLen = shape[0].toInt()
        val numClasses = shape[1].toInt()
        
        Log.d(TAG, "CRNN 输出形状: seq_len=$seqLen num_classes=$numClasses")
        
        // 简单的贪心解码（最大概率）
        val result = StringBuilder()
        var sumConf = 0f
        var prevClass = -1
        
        for (i in 0 until seqLen) {
            // 找每个时间步的最大值
            val begin = i * numClasses
            val end = begin + numClasses
            
            var maxVal = Float.NEGATIVE_INFINITY
            var maxIdx = 0
            
            for (j in begin until end) {
                if (j < output.size && output[j] > maxVal) {
                    maxVal = output[j]
                    maxIdx = j - begin
                }
            }
            
            // CTC 解码：去重 + 忽略空白符（通常是最后一个类）
            if (maxIdx != prevClass && maxIdx < wordLabels.size - 1) {
                result.append(wordLabels.getOrNull(maxIdx) ?: "")
                sumConf += maxVal
            }
            prevClass = maxIdx
        }
        
        val avgConf = if (result.isNotEmpty()) sumConf / result.length else 0.5f
        return Pair(result.toString(), avgConf.coerceIn(0f, 1f))
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
    
    data class Point(val x: Int, val y: Int)
}
