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
    private val cpuThreadNum: Int = 4,
    private val safeMode: Boolean = false
) {
    
    private val TAG = "PaddleOcrPredictor"
    
    private var detPredictor: PaddlePredictor? = null
    private var recPredictor: PaddlePredictor? = null
    private var clsPredictor: PaddlePredictor? = null
    
    // 模型期望的输入尺寸（PP-OCRv3 识别使用 32x320）
    private var recExpectedHeight = 32
    private var recExpectedWidth = 320

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
            
            // 检查识别模型期望的输入尺寸
            try {
                val inputTensor = recPredictor!!.getInput(0)
                val inputShape = inputTensor.shape()
                Log.d(TAG, "✓ 识别模型加载成功")
                Log.d(TAG, "【识别模型输入形状】: [${inputShape.joinToString(", ")}]")
                
                if (inputShape.size >= 4) {
                    recExpectedHeight = inputShape[2].toInt()
                    recExpectedWidth = inputShape[3].toInt()
                    Log.d(TAG, "【识别模型期望输入】: ${recExpectedWidth}x${recExpectedHeight}")
                } else {
                    Log.w(TAG, "无法获取输入形状，使用默认 ${recExpectedWidth}x${recExpectedHeight}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "✓ 识别模型加载成功（无法获取输入形状信息）")
                Log.e(TAG, "获取输入形状异常，使用默认 ${recExpectedWidth}x${recExpectedHeight}", e)
            }
            
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
     * 对图像应用 Otsu 二值化，返回黑白图像（用于预览/调试）
     * 公开方法，外部可调用
     */
    fun applyBinarization(bitmap: Bitmap): Bitmap {
        return binarizeImage(bitmap)
    }
    
    /**
     * 对图像应用 Otsu 二值化，返回黑白图像
     * 该方法用于 OCR 管线前处理，确保 det 和 rec 都基于高对比度的二值化输入
     */
    private fun binarizeImage(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // 提取像素并灰度化
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val grayPixels = IntArray(width * height)
        var minGray = 255
        var maxGray = 0
        var sumGray = 0L
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            grayPixels[i] = gray
            minGray = minOf(minGray, gray)
            maxGray = maxOf(maxGray, gray)
            sumGray += gray
        }
        
        val avgGray = sumGray / grayPixels.size
        Log.d(TAG, "灰度统计: min=$minGray, max=$maxGray, avg=$avgGray")
        
        // Otsu 自动阈值计算
        val histogram = IntArray(256)
        for (gray in grayPixels) {
            histogram[gray]++
        }
        
        val total = width * height
        var sum = 0.0
        for (i in 0 until 256) {
            sum += i * histogram[i]
        }
        
        var sumB = 0.0
        var wB = 0
        var maxVariance = 0.0
        var otsuThreshold = 128
        
        for (t in 0 until 256) {
            wB += histogram[t]
            if (wB == 0) continue
            
            val wF = total - wB
            if (wF == 0) break
            
            sumB += t * histogram[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            
            val variance = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            
            if (variance > maxVariance) {
                maxVariance = variance
                otsuThreshold = t
            }
        }
        
        Log.d(TAG, "Otsu 阈值: $otsuThreshold")
        
        // 判断是深色背景还是浅色背景
        // 如果平均灰度 < 128，说明是深色背景（黑底白字），需要反转
        val isDarkBackground = avgGray < 128
        Log.d(TAG, "背景类型: ${if (isDarkBackground) "深色背景(黑底白字)" else "浅色背景(黑底白字)"}")
        
        // 应用二值化 - 使用批量设置提高性能
        val binaryPixels = IntArray(width * height)
        var blackCount = 0
        var whiteCount = 0
        
        for (i in grayPixels.indices) {
            val gray = grayPixels[i]
            
            // 根据背景类型调整二值化策略
            val binaryValue = if (isDarkBackground) {
                // 深色背景：高于阈值的是文字(白色)，低于的是背景(黑色)
                // 但为了OCR识别，我们需要反转：让文字显示为黑色
                if (gray > otsuThreshold) 0 else 255
            } else {
                // 浅色背景：低于阈值的是文字(黑色)，高于的是背景(白色)
                if (gray < otsuThreshold) 0 else 255
            }
            
            binaryPixels[i] = (0xFF shl 24) or (binaryValue shl 16) or (binaryValue shl 8) or binaryValue
            
            if (binaryValue == 0) blackCount++ else whiteCount++
        }
        
        Log.d(TAG, "二值化结果: 黑色像素=$blackCount, 白色像素=$whiteCount")
        
        val binaryBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        binaryBitmap.setPixels(binaryPixels, 0, width, 0, 0, width, height)
        
        return binaryBitmap
    }
    
    /**
     * 在图像上绘制检测框（用于调试可视化）
     */
    fun drawDetectionBoxes(bitmap: Bitmap): Bitmap {
        try {
            // 先对输入图像进行二值化，确保预览图与 det 模型输入一致
            val binaryBitmap = binarizeImage(bitmap)

            val detBoxes = detectText(binaryBitmap, 960)
            Log.d(TAG, "绘制 ${detBoxes.size} 个检测框")
            
            // 在二值化图像上绘制检测框
            val config = binaryBitmap.config ?: Bitmap.Config.ARGB_8888
            val result = binaryBitmap.copy(config, true)
            val canvas = android.graphics.Canvas(result)
            val paint = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f
                color = android.graphics.Color.RED
            }
            
            for ((index, box) in detBoxes.withIndex()) {
                // 绘制四边形框
                if (box.size >= 4) {
                    val path = android.graphics.Path()
                    path.moveTo(box[0].x.toFloat(), box[0].y.toFloat())
                    for (i in 1 until box.size) {
                        path.lineTo(box[i].x.toFloat(), box[i].y.toFloat())
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                
                // 绘制索引号
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.YELLOW
                    textSize = 24f
                }
                if (box.isNotEmpty()) {
                    canvas.drawText("$index", box[0].x.toFloat(), (box[0].y - 10).toFloat(), textPaint)
                }
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "绘制检测框失败", e)
            return bitmap
        }
    }

    /**
     * 在已二值化的图像上绘制检测框（用于确保与 det 输入是同一张 bitmap）
     */
    fun drawDetectionBoxesOnBinary(binaryBitmap: Bitmap, maxSideLen: Int = 960): Bitmap {
        try {
            val detBoxes = detectText(binaryBitmap, maxSideLen)
            Log.d(TAG, "绘制 ${detBoxes.size} 个检测框(已二值化输入)")

            val config = binaryBitmap.config ?: Bitmap.Config.ARGB_8888
            val result = binaryBitmap.copy(config, true)
            val canvas = android.graphics.Canvas(result)
            val paint = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f
                color = android.graphics.Color.RED
            }

            for ((index, box) in detBoxes.withIndex()) {
                if (box.size >= 4) {
                    val path = android.graphics.Path()
                    path.moveTo(box[0].x.toFloat(), box[0].y.toFloat())
                    for (i in 1 until box.size) {
                        path.lineTo(box[i].x.toFloat(), box[i].y.toFloat())
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }

                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.YELLOW
                    textSize = 24f
                }
                if (box.isNotEmpty()) {
                    canvas.drawText("$index", box[0].x.toFloat(), (box[0].y - 10).toFloat(), textPaint)
                }
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "绘制检测框失败(已二值化输入)", e)
            return binaryBitmap
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
        runRec: Int = 1,
        alreadyBinarized: Boolean = false
    ): List<OcrResultModel> {
        
        try {
            Log.d(TAG, "开始 OCR 识别，图像: ${bitmap.width}x${bitmap.height}")
            
            // 0. 二值化预处理：在管线开始对输入图像进行二值化
            // 这样可以确保 det 和 rec 都基于清晰、高对比度的二值化图像
            // 如果上层已经二值化（并希望 det/预览/rec 共享同一张 bitmap），则跳过
            val binaryBitmap = if (alreadyBinarized) bitmap else binarizeImage(bitmap)
            
            if (runDet == 0) {
                return listOf(recognizeFullImage(binaryBitmap, runCls == 1, runRec == 1))
            }
            
            // 1. 文本检测（基于二值化图像）
            val detBoxes = detectText(binaryBitmap, maxSideLen)
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
                    
                    // 调试：打印检测框坐标
                    val boxCoords = box.map { "(${it.x},${it.y})" }.joinToString(" ")
                    val minX = box.minOf { it.x }
                    val maxX = box.maxOf { it.x }
                    val minY = box.minOf { it.y }
                    val maxY = box.maxOf { it.y }
                    Log.d(TAG, "文本框 $index: 四边形[$boxCoords], 矩形范围: (${minX},${minY})-(${maxX},${maxY}), 尺寸: ${maxX-minX}x${maxY-minY}")
                    
                    val cropped = cropBox(binaryBitmap, box)
                    
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
     *
     * 官方方案：严格遵循 PP-OCRv3 识别模型要求
     * - 高度固定为 32（PP-OCRv3 的硬性要求，官方 REC_IMAGE_SHAPE = {3, 32, 320}）
     * - 宽度根据宽高比动态计算：imgW = int(32 * wh_ratio)，并限制最大值为 320
     * - 直接提取像素，使用官方的归一化方式：(pixel / 255.0 - 0.5) * 2.0
     */
    private fun recognizeText(bitmap: Bitmap): Pair<String, Float> {
        if (recPredictor == null) {
            Log.w(TAG, "⚠️  识别模型未加载")
            return Pair("", 0f)
        }

        try {
            Log.d(TAG, "【识别输入】原始图像: ${bitmap.width}x${bitmap.height}")

            // 官方实现：根据宽高比动态计算目标宽度
            val targetHeight = recExpectedHeight  // 32
            val whRatio = bitmap.width.toFloat() / bitmap.height

            // 计算目标宽度：imgW = int(32 * wh_ratio)，限制在 [8, 320] 并对齐到 8 的倍数
            var targetWidth = (targetHeight * whRatio).toInt()
            targetWidth = max(8, min(320, targetWidth))
            targetWidth = ((targetWidth + 7) / 8) * 8

            if (safeMode) {
                // 临时兼容策略：为避免 FC 维度 4800/1320!==120 的崩溃，强制使用最小安全宽度 8
                // 说明：当前使用的识别模型与运行时不匹配，Lite 内部 FC 期望 K=120，但输入被当作 T*120 展平。
                // 使用 8 宽可令 T=1，从而 K=120，避免崩溃。替换为 PP-OCRv2 rec 模型后会自动恢复动态宽度。
                targetWidth = 8
                Log.w(TAG, "识别 SAFE 模式启用：强制使用 8x32 输入（等待更换识别模型）")
            }

            // 步骤 1: 直接 resize 到目标尺寸（不需要 padding，直接用动态宽度）
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

            // 步骤 2: 提取像素并转换为 float 张量（已是二值化图像）
            val pixels = IntArray(targetWidth * targetHeight)
            resizedBitmap.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

            // 步骤 3: 转换为 [1, 3, targetHeight, targetWidth] 的 float 数组
            // 二值化图像的像素已经是 0 或 255，三通道使用相同值（灰度）
            val inputData = FloatArray(3 * targetHeight * targetWidth)
            for (y in 0 until targetHeight) {
                for (x in 0 until targetWidth) {
                    val baseIdx = y * targetWidth + x
                    val pixel = pixels[baseIdx]
                    // 二值化图像：r = g = b（灰度值 0 或 255）
                    val grayValue = (pixel and 0xFF).toFloat() / 255.0f
                    val normalizedValue = (grayValue - 0.5f) * 2.0f  // 归一化到 [-1, 1]
                    
                    // 三通道使用相同的值
                    inputData[0 * targetHeight * targetWidth + baseIdx] = normalizedValue
                    inputData[1 * targetHeight * targetWidth + baseIdx] = normalizedValue
                    inputData[2 * targetHeight * targetWidth + baseIdx] = normalizedValue
                }
            }

            resizedBitmap.recycle()

            val inputTensor = recPredictor!!.getInput(0)

            // 【关键】设置输入形状为 [1, 3, targetHeight, targetWidth]
            inputTensor.resize(longArrayOf(1, 3, targetHeight.toLong(), targetWidth.toLong()))

            Log.d(TAG, "✓ 输入形状已设置: [1, 3, $targetHeight, $targetWidth]")
            
            // 验证输入数据大小
            val expectedDataSize = 3 * targetHeight * targetWidth
            if (inputData.size != expectedDataSize) {
                Log.e(TAG, "❌ 输入数据大小不匹配! 期望: $expectedDataSize, 实际: ${inputData.size}")
                return Pair("", 0f)
            }
            Log.d(TAG, "✓ 输入数据大小验证通过: ${inputData.size}")

            // 设置数据并推理
            try {
                inputTensor.setData(inputData)
                Log.d(TAG, "✓ 输入数据已设置到张量")
                
                Log.d(TAG, "开始推理...")
                recPredictor!!.run()
                Log.d(TAG, "✓ 推理完成成功")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 推理执行失败! 错误: ${e.message}", e)
                Log.e(TAG, "这通常表示模型与库版本不兼容，或模型文件损坏")
                Log.e(TAG, "请检查: app/src/main/assets/models/ch_PP-OCRv3_rec_slim_opt.nb")
                throw e
            }

            // 获取输出
            val outputTensor = recPredictor!!.getOutput(0)
            val outputShape = outputTensor.shape()
            val outputData = outputTensor.getFloatData()

            Log.d(TAG, "✓ 识别输出形状: [${outputShape.joinToString(", ")}], 数据长度: ${outputData.size}")

            val (text, conf) = postprocessRecognition(outputData, outputShape)
            return Pair(text, conf)

        } catch (e: Exception) {
            Log.e(TAG, "❌ 文本识别失败: ${e.message}", e)
            e.printStackTrace()
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
        
        // 额外扩展：尽量扩到纯背景（白边），避免笔画/标点被切掉
        // 上下扩更多、左右扩少量；并限制范围，避免吞并上下行/左右相邻内容
        val rawW = (maxX - minX).coerceAtLeast(1)
        val rawH = (maxY - minY).coerceAtLeast(1)
        val extraY = (rawH * 0.85f).toInt().coerceIn(12, 96)
        val extraX = (rawW * 0.06f).toInt().coerceIn(6, 48)

        // 这里把 maxX/maxY 作为“右/下边界（排他）”来处理，避免裁剪少 1px
        // 确保不超出图像边界
        minX = max(0, minX - extraX)
        minY = max(0, minY - extraY)
        maxX = min(bitmap.width, maxX + extraX)
        maxY = min(bitmap.height, maxY + extraY)

        val width = maxX - minX
        val height = maxY - minY
        
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "❌ 检测框无效: width=$width, height=$height")
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        
        Log.d(TAG, "✓ 裁剪框(左右扩展$extraX/上下扩展$extraY): ($minX, $minY) - ($maxX, $maxY), 尺寸: ${width}x${height}")
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
        // 降低阈值到 0.2 以扩展掩码覆盖范围，确保文字区域完整
        val threshold = 0.2f
        
        // 查找高置信度的像素
        val mask = BooleanArray(h * w)
        for (i in 0 until h * w) {
            mask[i] = output[i] > threshold
        }
        
        // 对mask进行膨胀（dilate）操作来扩大文字区域，防止边界过紧导致文字被截断
        // 增加膨胀次数从 2 到 3，确保文字边界有足够的扩展
        val dilatedMask = dilate(mask, h, w, iterations = 3)
        
        // 简单的连通分量标记
        val visited = BooleanArray(h * w)
        // 使用 8 邻域，避免字符笔画/相邻字符因对角连接而被拆分
        val directions = arrayOf(
            Pair(-1, -1), Pair(-1, 0), Pair(-1, 1),
            Pair(0, -1),               Pair(0, 1),
            Pair(1, -1),  Pair(1, 0),  Pair(1, 1)
        )
        
        for (idx in dilatedMask.indices) {
            if (!dilatedMask[idx] || visited[idx]) continue
            
            val y = idx / w
            val x = idx % w
            val box = mutableListOf<Point>()
            val queue = mutableListOf(Pair(y, x))
            visited[idx] = true

            var minCx = x
            var maxCx = x
            var minCy = y
            var maxCy = y
            
            while (queue.isNotEmpty()) {
                val (cy, cx) = queue.removeAt(0)
                box.add(Point(cx, cy))

                if (cx < minCx) minCx = cx
                if (cx > maxCx) maxCx = cx
                if (cy < minCy) minCy = cy
                if (cy > maxCy) maxCy = cy
                
                for ((dy, dx) in directions) {
                    val ny = cy + dy
                    val nx = cx + dx
                    if (ny in 0 until h && nx in 0 until w) {
                        val nidx = ny * w + nx
                        // 关键：连通域扩展必须基于膨胀后的掩码，否则膨胀不会生效
                        if (dilatedMask[nidx] && !visited[nidx]) {
                            visited[nidx] = true
                            queue.add(Pair(ny, nx))
                        }
                    }
                }
            }

            // 过滤过小连通域：避免噪声，但不要把单字/标点误删
            val compW = maxCx - minCx + 1
            val compH = maxCy - minCy + 1
            val compArea = compW * compH
            if (compArea >= 30 && box.size >= 15) {
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
     * 膨胀操作（Dilate）：扩大二值图像中的白色区域
     * 用于扩展文字检测框，防止边界过紧导致文字被截断
     */
    private fun dilate(mask: BooleanArray, h: Int, w: Int, iterations: Int = 1): BooleanArray {
        var result = mask.copyOf()
        for (iter in 0 until iterations) {
            val temp = BooleanArray(h * w)
            val directions = arrayOf(
                Pair(-1, -1), Pair(-1, 0), Pair(-1, 1),
                Pair(0, -1),               Pair(0, 1),
                Pair(1, -1),  Pair(1, 0), Pair(1, 1)
            )
            
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var dilated = false
                    for ((dy, dx) in directions) {
                        val ny = y + dy
                        val nx = x + dx
                        if (ny in 0 until h && nx in 0 until w) {
                            val idx = ny * w + nx
                            if (result[idx]) {
                                dilated = true
                                break
                            }
                        }
                    }
                    temp[y * w + x] = dilated || result[y * w + x]
                }
            }
            result = temp
        }
        return result
    }
    
    /**
     * 从点集计算外接矩形，并添加边界扩展以确保文字完整性
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
        
        // 添加边界扩展：上下更大，左右小幅；目标是把标点/细小笔画也包进去
        val expandX = max(4, ((maxX - minX) * 0.08f).toInt())
        val expandY = max(12, ((maxY - minY) * 0.40f).toInt())
        
        minX = max(0, minX - expandX)
        minY = max(0, minY - expandY)
        maxX += expandX
        maxY += expandY
        
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
     * 简化版：更灵活地处理不同格式的输出
     * 根据输出数据大小和字典大小自动推断维度
     */
    private fun postprocessRecognition(
        output: FloatArray,
        shape: LongArray
    ): Pair<String, Float> {
        if (shape.isEmpty() || wordLabels.isEmpty()) {
            Log.w(TAG, "识别输出形状为空或字典未加载")
            return Pair("", 0.5f)
        }
        
        Log.d(TAG, "CRNN 输出形状: [${shape.joinToString(", ")}], 数据长度: ${output.size}, 字典大小: ${wordLabels.size}")
        
        // 解析输出维度：支持 [batch, seq_len, num_classes] 或 [seq_len, num_classes]
        val batch: Int
        val seqLen: Int
        val numClasses: Int
        
        when (shape.size) {
            3 -> {
                // 格式: [batch, seq_len, num_classes]
                batch = shape[0].toInt()
                seqLen = shape[1].toInt()
                numClasses = shape[2].toInt()
                Log.d(TAG, "3D 输出格式: batch=$batch, seq_len=$seqLen, num_classes=$numClasses")
            }
            2 -> {
                // 格式: [seq_len, num_classes]
                batch = 1
                seqLen = shape[0].toInt()
                numClasses = shape[1].toInt()
                Log.d(TAG, "2D 输出格式: seq_len=$seqLen, num_classes=$numClasses")
            }
            else -> {
                Log.e(TAG, "不支持的输出维度: ${shape.size}")
                return Pair("", 0.5f)
            }
        }
        
        // 验证数据大小
        val expectedSize = batch * seqLen * numClasses
        if (output.size != expectedSize) {
            Log.e(TAG, "数据大小不匹配: 期望=$expectedSize, 实际=${output.size}")
            return Pair("", 0.5f)
        }
        
        data class DecodeAttempt(
            val text: String,
            val avgConf: Float,
            val keptCount: Int,
            val blankCount: Int,
            val unknownCount: Int,
            val blankIndex: Int,
            val dictOffset: Int,
            val extraSpaceIndex: Int?
        )

        fun decodeWith(blankIndex: Int, dictOffset: Int, extraSpaceIndex: Int?): DecodeAttempt {
            val dictSize = wordLabels.size
            val sb = StringBuilder()
            var sumConf = 0f
            var keptCount = 0
            var blankCount = 0
            var unknownCount = 0
            var prevClass = -1

            for (t in 0 until seqLen) {
                var maxVal = Float.NEGATIVE_INFINITY
                var maxIdx = 0
                for (c in 0 until numClasses) {
                    val idx = t * numClasses + c
                    val v = output[idx]
                    if (v > maxVal) {
                        maxVal = v
                        maxIdx = c
                    }
                }

                // 先去重
                if (maxIdx == prevClass) {
                    continue
                }
                prevClass = maxIdx

                // 再处理 blank / space / normal
                when {
                    maxIdx == blankIndex -> {
                        blankCount++
                    }
                    extraSpaceIndex != null && maxIdx == extraSpaceIndex -> {
                        sb.append(' ')
                        sumConf += maxVal
                        keptCount++
                    }
                    else -> {
                        val dictIdx = maxIdx - dictOffset
                        if (dictIdx in 0 until dictSize) {
                            sb.append(wordLabels[dictIdx])
                            sumConf += maxVal
                            keptCount++
                        } else {
                            unknownCount++
                        }
                    }
                }
            }

            val avgConf = if (keptCount > 0) {
                val confidence = sumConf / keptCount
                (1f / (1f + kotlin.math.exp(-confidence))).coerceIn(0f, 1f)
            } else {
                0.5f
            }

            return DecodeAttempt(
                text = sb.toString(),
                avgConf = avgConf,
                keptCount = keptCount,
                blankCount = blankCount,
                unknownCount = unknownCount,
                blankIndex = blankIndex,
                dictOffset = dictOffset,
                extraSpaceIndex = extraSpaceIndex
            )
        }

        val dictSize = wordLabels.size
        // PaddleOCR 常见情况：num_classes = dictSize + 2（blank + dict + space）
        val defaultExtraSpaceIndex = if (numClasses == dictSize + 2) dictSize + 1 else null

        // 候选：自动尝试几种常见 blank/index 对齐方式
        val candidates = mutableListOf<DecodeAttempt>()
        // 1) PaddleOCR 官方：blank=0, dictOffset=1
        candidates.add(decodeWith(blankIndex = 0, dictOffset = 1, extraSpaceIndex = defaultExtraSpaceIndex))
        // 2) 兼容一些导出：blank=dictSize, dictOffset=0
        if (dictSize in 0 until numClasses) {
            candidates.add(decodeWith(blankIndex = dictSize, dictOffset = 0, extraSpaceIndex = null))
        }
        // 3) 兼容一些导出：blank=dictSize+1, dictOffset=0
        if (dictSize + 1 in 0 until numClasses) {
            candidates.add(decodeWith(blankIndex = dictSize + 1, dictOffset = 0, extraSpaceIndex = null))
        }
        // 4) 兜底：blank=numClasses-1 / numClasses-2
        candidates.add(decodeWith(blankIndex = numClasses - 1, dictOffset = 0, extraSpaceIndex = null))
        if (numClasses - 2 >= 0) {
            candidates.add(decodeWith(blankIndex = numClasses - 2, dictOffset = 0, extraSpaceIndex = null))
        }

        // 选择最合理的解码：优先 blank 多（CTC 正常应大量 blank），其次 unknown 少，其次置信度高
        val best = candidates
            .sortedWith(
                compareByDescending<DecodeAttempt> { it.blankCount }
                    .thenBy { it.unknownCount }
                    .thenByDescending { it.avgConf }
            )
            .first()

        Log.d(
            TAG,
            "识别结果: '${best.text}' (置信度: ${best.avgConf}, 字符数: ${best.keptCount}, blank数: ${best.blankCount}, unknown数: ${best.unknownCount}, blankIndex=${best.blankIndex}, dictOffset=${best.dictOffset}, spaceIndex=${best.extraSpaceIndex})"
        )

        return Pair(best.text, best.avgConf)
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
