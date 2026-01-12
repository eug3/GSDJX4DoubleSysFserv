package com.guaishoudejia.x4doublesysfserv.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ONNX OCR 识别助手 - 使用 ONNX Runtime Mobile 运行 PP-OCRv5 模型
 *
 * 模型要求:
 * - Detection Model: ppocrv5_mobile_det.onnx (输入: [1,3,H,W], 输出: [1,1,H,W])
 * - Recognition Model: ppocrv5_mobile_rec.onnx (输入: [1,3,48,W], 输出: [1,T,C])
 * - Classification Model: ppocrv5_mobile_cls.onnx (可选)
 *
 * 环境要求:
 * - ONNX Runtime Mobile 1.20.0+
 * - OpenCV 4.9.0+
 */
class OnnxOcrHelper {

    companion object {
        private const val TAG = "OnnxOcrHelper"

        @Volatile
        private var isOpenCvReady: Boolean = false

        // 模型文件名
        private const val MODEL_DIR = "models_onnx"
        private const val DET_MODEL = "ppocrv5_mobile_det.onnx"
        private const val REC_MODEL = "ppocrv5_mobile_rec.onnx"
        private const val CLS_MODEL = "pplcnet_x0_25_textline_ori.onnx"
        private const val DICT_FILE = "dict/ppocrv5_dict.txt"

        // 检测模型参数
        private const val DET_LIMIT_SIDE_LEN = 960f
        private const val DET_DB_THRESH = 0.3f
        private const val DET_DB_BOX_THRESH = 0.6f

        // 识别模型参数
        private const val REC_IMAGE_HEIGHT = 48
        private const val REC_MAX_WIDTH = 320
        private const val REC_MIN_WIDTH = 8
    }

    private fun ensureOpenCvLoaded() {
        if (isOpenCvReady) return
        val ok = try {
            OpenCVLoader.initDebug()
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            throw IllegalStateException(
                "OpenCV 初始化失败：原生库未加载（OpenCVLoader.initDebug() 返回 false）。" +
                    " 请确认依赖 org.opencv:opencv 已正确打包到 APK。"
            )
        }
        isOpenCvReady = true
        Log.i(TAG, "OpenCV 初始化成功")
    }

    /**
     * 展平多维浮点数数组为 1D FloatArray
     */
    private fun flattenFloatArray(obj: Any?): FloatArray {
        if (obj == null) return floatArrayOf()
        
        return when (obj) {
            is FloatArray -> obj
            is Array<*> -> {
                val result = mutableListOf<Float>()
                for (element in obj) {
                    when (element) {
                        is Float -> result.add(element)
                        is FloatArray -> result.addAll(element.toList())
                        is Array<*> -> result.addAll(flattenFloatArray(element).toList())
                        else -> {}
                    }
                }
                result.toFloatArray()
            }
            else -> floatArrayOf()
        }
    }

    // 模型会话
    private var ortEnv: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var clsSession: OrtSession? = null

    // 字典标签
    private var wordLabels: List<String> = emptyList()

    // 识别模型期望的输入尺寸
    private var recExpectedHeight = REC_IMAGE_HEIGHT
    private var recExpectedWidth = REC_MAX_WIDTH

    // 初始化状态
    @Volatile
    private var isInitialized = false
    private val initLock = Mutex()
    private var initDeferred = CompletableDeferred<Boolean>()

    /**
     * 判断 OCR 是否已准备就绪
     */
    fun isReady(): Boolean = isInitialized

    /**
     * 初始化 ONNX OCR
     */
    suspend fun init(context: Context): Unit = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        initLock.withLock {
            if (isInitialized) return@withContext

            try {
                Log.d(TAG, "开始初始化 ONNX OCR (PP-OCRv5)...")

                // OpenCV 原生库必须先加载，否则 Mat/Imgproc 会直接崩溃
                ensureOpenCvLoaded()

                // 1. 复制模型文件到缓存目录
                val cacheDir = File(context.cacheDir, MODEL_DIR)
                if (!cacheDir.exists()) cacheDir.mkdirs()

                listOf(DET_MODEL, REC_MODEL, CLS_MODEL).forEach { modelName ->
                    copyAssetFile(context, "$MODEL_DIR/$modelName", File(cacheDir, modelName))
                }

                // 2. 加载字典
                wordLabels = loadDict(context, DICT_FILE)
                Log.d(TAG, "字典大小: ${wordLabels.size}")

                // 3. 创建 ONNX Runtime 环境
                ortEnv = OrtEnvironment.getEnvironment()

                // 4. 创建会话选项
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // 禁用 PerExecutionThreads 以避免线程问题
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                }

                // 5. 加载检测模型
                val detModelPath = File(cacheDir, DET_MODEL).absolutePath
                Log.d(TAG, "加载检测模型: $detModelPath")
                detSession = ortEnv!!.createSession(detModelPath, sessionOptions)
                logInputOutputInfo(detSession, "检测模型")

                // 6. 加载识别模型
                val recModelPath = File(cacheDir, REC_MODEL).absolutePath
                Log.d(TAG, "加载识别模型: $recModelPath")
                recSession = ortEnv!!.createSession(recModelPath, sessionOptions)
                logInputOutputInfo(recSession, "识别模型")

                // 获取识别模型的输入尺寸
                try {
                    val inputName = recSession!!.inputNames.iterator().next()
                    val nodeInfo = recSession!!.getInputInfo().getValue(inputName)
                    val valueInfo = nodeInfo.info
                    if (valueInfo is TensorInfo) {
                        val inputShape = valueInfo.shape
                        Log.d(TAG, "【识别模型输入形状】: [${inputShape.joinToString(", ")}]")

                        if (inputShape.size >= 4) {
                            recExpectedHeight = inputShape[2].toInt()
                            recExpectedWidth = inputShape[3].toInt()
                            Log.d(TAG, "【识别模型期望输入】: ${recExpectedWidth}x${recExpectedHeight}")
                        }
                    } else {
                        Log.d(TAG, "识别模型输入不是 TensorInfo: ${valueInfo.javaClass.simpleName}")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "使用默认识别模型尺寸: ${recExpectedWidth}x${recExpectedHeight}")
                }

                // 7. 加载分类模型（可选）
                val clsModelPath = File(cacheDir, CLS_MODEL).absolutePath
                Log.d(TAG, "加载分类模型: $clsModelPath")
                clsSession = ortEnv!!.createSession(clsModelPath, sessionOptions)
                logInputOutputInfo(clsSession, "分类模型")

                isInitialized = true
                initDeferred.complete(true)
                Log.i(TAG, "ONNX OCR 初始化成功 (PP-OCRv5)")
            } catch (e: Exception) {
                Log.e(TAG, "ONNX OCR 初始化失败", e)
                isInitialized = false
                initDeferred.completeExceptionally(e)
                initDeferred = CompletableDeferred()
                throw e
            }
        }
    }

    /**
     * 记录模型的输入输出信息
     */
    private fun logInputOutputInfo(session: OrtSession?, modelName: String) {
        if (session == null) return
        try {
            Log.d(TAG, "【$modelName 输入节点】:")
            for (name in session.inputNames) {
                val nodeInfo = session.getInputInfo().getValue(name)
                val valueInfo = nodeInfo.info
                if (valueInfo is TensorInfo) {
                    Log.d(TAG, "  $name: [${valueInfo.shape.joinToString(", ")}] ${valueInfo.type}")
                } else {
                    Log.d(TAG, "  $name: ${valueInfo.javaClass.simpleName}")
                }
            }
            Log.d(TAG, "【$modelName 输出节点】:")
            for (name in session.outputNames) {
                val nodeInfo = session.getOutputInfo().getValue(name)
                val valueInfo = nodeInfo.info
                if (valueInfo is TensorInfo) {
                    Log.d(TAG, "  $name: [${valueInfo.shape.joinToString(", ")}] ${valueInfo.type}")
                } else {
                    Log.d(TAG, "  $name: ${valueInfo.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 $modelName 信息失败", e)
        }
    }

    /**
     * 识别图片中的文字
     */
    suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.d(TAG, "OCR 尚未就绪，等待初始化...")
            try {
                initDeferred.await()
            } catch (e: Exception) {
                throw IllegalStateException("OCR 初始化失败: ${e.message}")
            }
        }

        try {
            Log.d(TAG, "【OCR 输入】原始图像: ${bitmap.width}x${bitmap.height}")

            // 0. 二值化预处理 - 与 v3 保持一致，检测和识别都用二值化后的图
            val binaryBitmap = binarizeBitmap(bitmap)
            Log.d(TAG, "【OCR 输入】二值化图像: ${binaryBitmap.width}x${binaryBitmap.height}")

            // 1. 文本检测（使用二值化图像）
            val startTime = System.currentTimeMillis()
            val detBoxes = textDetection(binaryBitmap)
            val detTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "检测到 ${detBoxes.size} 个文本区域 (耗时: ${detTime}ms)")

            if (detBoxes.isEmpty()) {
                binaryBitmap.recycle()
                return@withContext OcrResult("", emptyList(), "")
            }

            // 2. 对每个文本区域进行识别（裁剪二值化图像）
            val results = mutableListOf<OcrResultModel>()
            for ((index, box) in detBoxes.withIndex()) {
                try {
                    val result = OcrResultModel()
                    for (point in box) {
                        result.addPoints(point.x, point.y)
                    }

                    val cropped = cropBox(binaryBitmap, box)
                    val (_, text, confidence) = recognizeTextInternal(cropped)
                    result.setLabel(text)
                    result.setConfidence(confidence)

                    results.add(result)
                    Log.d(TAG, "文本块 $index: $text (置信度: $confidence)")
                } catch (e: Exception) {
                    Log.e(TAG, "识别文本块 $index 失败", e)
                }
            }

            // 3. 构建返回结果
            val blocks = mutableListOf<TextBlock>()
            val textLines = mutableListOf<String>()

            results.forEachIndexed { index, result ->
                val text = result.getLabel() ?: ""
                val confidence = result.getConfidence()
                blocks.add(TextBlock(text, confidence, index))
                if (text.isNotEmpty()) {
                    textLines.add(text)
                }
            }

            val formattedText = textLines.joinToString("\n")
            binaryBitmap.recycle()
            OcrResult(formattedText, blocks, formattedText)

        } catch (e: Exception) {
            Log.e(TAG, "文字识别失败", e)
            throw e
        }
    }

    /**
     * 文本检测
     */
    private fun textDetection(bitmap: Bitmap): List<Array<Point>> {
        if (detSession == null) {
            return emptyList()
        }

        try {
            // 1. 缩放图像
            val (scaledBitmap, ratio) = scaleImage(bitmap, DET_LIMIT_SIDE_LEN.toInt())
            Log.d(TAG, "【检测输入】缩放后图像: ${scaledBitmap.width}x${scaledBitmap.height}, ratio=$ratio")

            // 2. 预处理
            val inputData = preprocessImage(scaledBitmap)
            Log.d(TAG, "【检测输入】预处理数据大小: ${inputData.size} (期望: ${scaledBitmap.width * scaledBitmap.height * 3})")

            // 3. 创建输入 tensor
            val inputName = detSession!!.inputNames.iterator().next()
            val inputShape = longArrayOf(1, 3, scaledBitmap.height.toLong(), scaledBitmap.width.toLong())
            Log.d(TAG, "【检测输入】Tensor 形状: [${inputShape.joinToString(", ")}]")

            val buffer = ByteBuffer.allocateDirect(inputData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(inputData)
            buffer.flip()

            val inputTensor = OnnxTensor.createTensor(ortEnv!!, buffer, inputShape)

            // 4. 运行推理
            val startTime = System.currentTimeMillis()
            val outputs = detSession!!.run(mapOf(inputName to inputTensor))
            val outputTensor = outputs.get(0) as OnnxTensor
            val inferTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "检测推理耗时: ${inferTime}ms")

            // 5. 后处理
            val outputShape = outputTensor.info.shape
            Log.d(TAG, "检测输出形状: [${outputShape.joinToString(", ")}]")

            val outputData = when (val outputValue = outputTensor.value) {
                is ByteBuffer -> {
                    val floatBuffer = outputValue.asFloatBuffer()
                    FloatArray(floatBuffer.remaining()).also { floatBuffer.get(it) }
                }
                is Array<*> -> {
                    // 处理多维数组 float[][][][] 或其他形式
                    flattenFloatArray(outputValue)
                }
                else -> {
                    Log.w(TAG, "未知的输出数据类型: ${outputValue?.javaClass?.simpleName}")
                    floatArrayOf()
                }
            }

            val boxes = postprocessDetection(
                outputData, outputShape, bitmap.width, bitmap.height, ratio
            )

            // 清理
            inputTensor.close()
            outputTensor.close()

            return boxes

        } catch (e: Exception) {
            Log.e(TAG, "文本检测失败", e)
            return emptyList()
        }
    }

    /**
     * 文本识别（内部函数）
     */
    private fun recognizeTextInternal(bitmap: Bitmap): Triple<Int, String, Float> {
        if (recSession == null) {
            Log.w(TAG, "识别模型未加载")
            return Triple(0, "", 0f)
        }

        try {
            // 1. 计算目标尺寸
            val whRatio = bitmap.width.toFloat() / bitmap.height
            var targetWidth = (recExpectedHeight * whRatio).toInt()
            targetWidth = max(REC_MIN_WIDTH, min(REC_MAX_WIDTH, targetWidth))
            // 对齐到 8 的倍数
            targetWidth = ((targetWidth + 7) / 8) * 8

            // 2. Resize
            val resizedBitmap = Bitmap.createScaledBitmap(
                bitmap, targetWidth, recExpectedHeight, true
            )

            // 3. 预处理
            val inputData = preprocessImageForRecognition(resizedBitmap)

            // 4. 创建输入 tensor
            val inputName = recSession!!.inputNames.iterator().next()
            val inputShape = longArrayOf(
                1, 3, recExpectedHeight.toLong(), targetWidth.toLong()
            )

            val buffer = ByteBuffer.allocateDirect(inputData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(inputData)
            buffer.flip()

            val inputTensor = OnnxTensor.createTensor(ortEnv!!, buffer, inputShape)

            val startTime = System.currentTimeMillis()

            // 5. 运行推理
            val outputs = recSession!!.run(mapOf(inputName to inputTensor))
            val outputTensor = outputs.get(0) as OnnxTensor

            val inferTime = System.currentTimeMillis() - startTime

            // 6. 后处理
            val outputShape = outputTensor.info.shape

            val outputData = when (val outputValue = outputTensor.value) {
                is ByteBuffer -> {
                    val floatBuffer = outputValue.asFloatBuffer()
                    FloatArray(floatBuffer.remaining()).also { floatBuffer.get(it) }
                }
                is Array<*> -> {
                    // 处理多维数组 float[][][][] 或其他形式
                    flattenFloatArray(outputValue)
                }
                else -> {
                    Log.w(TAG, "未知的输出数据类型: ${outputValue?.javaClass?.simpleName}")
                    floatArrayOf()
                }
            }

            val (text, conf) = postprocessRecognition(outputData, outputShape)

            // 清理
            inputTensor.close()
            outputTensor.close()
            resizedBitmap.recycle()

            return Triple(0, text, conf)

        } catch (e: Exception) {
            Log.e(TAG, "文本识别失败", e)
            return Triple(0, "", 0f)
        }
    }

    /**
     * 预处理图像（检测用）- RGB 顺序，归一化到 [0, 1]
     */
    private fun preprocessImage(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val channelSize = width * height

        val floatArray = FloatArray(channelSize * 3)
        val pixels = IntArray(channelSize)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            // RGB 顺序（与 v3 一致）
            val r = ((pixel shr 16) and 0xFF).toFloat() / 255f
            val g = ((pixel shr 8) and 0xFF).toFloat() / 255f
            val b = (pixel and 0xFF).toFloat() / 255f

            floatArray[i] = r
            floatArray[i + channelSize] = g
            floatArray[i + channelSize * 2] = b
        }

        return floatArray
    }

    /**
     * 预处理图像（识别用）- 使用灰度值，归一化到 [-1, 1]，三通道相同
     * 与 PaddleOCR v3 保持一致
     */
    private fun preprocessImageForRecognition(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val channelSize = width * height

        val floatArray = FloatArray(channelSize * 3)
        val pixels = IntArray(channelSize)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            // 使用蓝色通道作为灰度值（二值化后 R=G=B）
            val grayValue = (pixel and 0xFF).toFloat() / 255f
            // 归一化到 [-1, 1]: (x - 0.5) * 2
            val normalizedValue = (grayValue - 0.5f) * 2f

            // 三通道都使用相同的归一化灰度值
            floatArray[i] = normalizedValue
            floatArray[i + channelSize] = normalizedValue
            floatArray[i + channelSize * 2] = normalizedValue
        }

        return floatArray
    }

    /**
     * 缩放图像
     */
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

    /**
     * 裁剪文本框
     */
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

        // 扩展边界
        val rawW = max(1, maxX - minX)
        val rawH = max(1, maxY - minY)
        val extraY = (rawH * 0.85f).toInt().coerceIn(12, 96)
        val extraX = (rawW * 0.06f).toInt().coerceIn(6, 48)

        minX = max(0, minX - extraX)
        minY = max(0, minY - extraY)
        maxX = min(bitmap.width, maxX + extraX)
        maxY = min(bitmap.height, maxY + extraY)

        val width = max(1, maxX - minX)
        val height = max(1, maxY - minY)

        return Bitmap.createBitmap(bitmap, minX, minY, width, height)
    }

    /**
     * DBNet 后处理
     */
    private fun postprocessDetection(
        output: FloatArray,
        shape: LongArray,
        srcWidth: Int,
        srcHeight: Int,
        scaleRatio: Float
    ): List<Array<Point>> {
        val batch = shape[0].toInt()
        val channels = shape[1].toInt()
        val h = shape[2].toInt()
        val w = shape[3].toInt()

        Log.d(TAG, "DBNet 输出形状: batch=$batch, channels=$channels, h=$h, w=$w")

        val boxes = mutableListOf<Array<Point>>()
        val threshold = 0.2f

        // 创建掩码
        val mask = BooleanArray(h * w)
        for (i in 0 until h * w) {
            mask[i] = output[i] > threshold
        }

        // 膨胀操作
        val dilatedMask = dilateMask(mask, h, w, iterations = 3)

        // 连通域分析
        val visited = BooleanArray(h * w)
        val directions = arrayOf(
            Pair(-1, -1), Pair(-1, 0), Pair(-1, 1),
            Pair(0, -1), Pair(0, 1),
            Pair(1, -1), Pair(1, 0), Pair(1, 1)
        )

        for (idx in dilatedMask.indices) {
            if (!dilatedMask[idx] || visited[idx]) continue

            val y = idx / w
            val x = idx % w
            val component = mutableListOf<Pair<Int, Int>>()
            val queue = mutableListOf(Pair(y, x))
            visited[idx] = true

            var minX = x
            var maxX = x
            var minY = y
            var maxY = y

            while (queue.isNotEmpty()) {
                val (cy, cx) = queue.removeAt(0)
                component.add(Pair(cy, cx))

                minX = min(minX, cx)
                maxX = max(maxX, cx)
                minY = min(minY, cy)
                maxY = max(maxY, cy)

                for ((dy, dx) in directions) {
                    val ny = cy + dy
                    val nx = cx + dx
                    if (ny in 0 until h && nx in 0 until w) {
                        val nidx = ny * w + nx
                        if (dilatedMask[nidx] && !visited[nidx]) {
                            visited[nidx] = true
                            queue.add(Pair(ny, nx))
                        }
                    }
                }
            }

            // 过滤小连通域
            val compW = maxX - minX + 1
            val compH = maxY - minY + 1
            val compArea = compW * compH

            if (compArea >= 30 && component.size >= 15) {
                // 还原到原图坐标并添加扩展
                val expandX = max(4, ((maxX - minX) * 0.08f).toInt())
                val expandY = max(12, ((maxY - minY) * 0.40f).toInt())

                val realMinX = max(0, (minX / scaleRatio).toInt() - expandX)
                val realMinY = max(0, (minY / scaleRatio).toInt() - expandY)
                val realMaxX = min(srcWidth - 1, (maxX / scaleRatio).toInt() + expandX)
                val realMaxY = min(srcHeight - 1, (maxY / scaleRatio).toInt() + expandY)

                boxes.add(arrayOf(
                    Point(realMinX, realMinY),
                    Point(realMaxX, realMinY),
                    Point(realMaxX, realMaxY),
                    Point(realMinX, realMaxY)
                ))
            }
        }

        // 兜底：返回全图
        if (boxes.isEmpty()) {
            Log.w(TAG, "未检测到文本框，返回全图")
            boxes.add(arrayOf(
                Point(0, 0),
                Point(srcWidth - 1, 0),
                Point(srcWidth - 1, srcHeight - 1),
                Point(0, srcHeight - 1)
            ))
        }

        return boxes
    }

    /**
     * 膨胀操作
     */
    private fun dilateMask(mask: BooleanArray, h: Int, w: Int, iterations: Int): BooleanArray {
        var result = mask.copyOf()
        val directions = arrayOf(
            Pair(-1, -1), Pair(-1, 0), Pair(-1, 1),
            Pair(0, -1), Pair(0, 1),
            Pair(1, -1), Pair(1, 0), Pair(1, 1)
        )

        repeat(iterations) {
            val temp = BooleanArray(h * w)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var dilated = result[y * w + x]
                    if (!dilated) {
                        for ((dy, dx) in directions) {
                            val ny = y + dy
                            val nx = x + dx
                            if (ny in 0 until h && nx in 0 until w) {
                                if (result[ny * w + nx]) {
                                    dilated = true
                                    break
                                }
                            }
                        }
                    }
                    temp[y * w + x] = dilated
                }
            }
            result = temp
        }
        return result
    }

    /**
     * CTC 解码 - 使用字典映射索引到字符
     */
    private fun postprocessRecognition(
        output: FloatArray,
        shape: LongArray
    ): Pair<String, Float> {
        if (shape.isEmpty() || wordLabels.isEmpty()) {
            return Pair("", 0.5f)
        }

        // 解析维度
        val batch: Int
        val seqLen: Int
        val numClasses: Int

        when (shape.size) {
            3 -> {
                batch = shape[0].toInt()
                seqLen = shape[1].toInt()
                numClasses = shape[2].toInt()
            }
            2 -> {
                batch = 1
                seqLen = shape[0].toInt()
                numClasses = shape[1].toInt()
            }
            else -> {
                return Pair("", 0.5f)
            }
        }

        // CTC 解码：blank 索引为 0，字符索引从 1 开始
        val sb = StringBuilder()
        var sumConf = 0f
        var count = 0
        var prevClass = -1
        val blankIndex = 0

        for (t in 0 until seqLen) {
            var maxVal = Float.NEGATIVE_INFINITY
            var maxIdx = 0
            for (c in 0 until numClasses) {
                val idx = t * numClasses + c
                if (output[idx] > maxVal) {
                    maxVal = output[idx]
                    maxIdx = c
                }
            }

            // 跳过重复字符
            if (maxIdx == prevClass) continue
            prevClass = maxIdx

            // 跳过 blank
            if (maxIdx == blankIndex) continue

            // 使用字典映射：索引 1 对应字典第 0 个字符
            val dictIdx = maxIdx - 1
            if (dictIdx >= 0 && dictIdx < wordLabels.size) {
                val char = wordLabels[dictIdx]
                sb.append(char)
                sumConf += maxVal
                count++
            }
        }

        val conf = if (count > 0) {
            val avgConf = sumConf / count
            (1f / (1f + kotlin.math.exp(-avgConf))).coerceIn(0f, 1f)
        } else 0.5f

        val text = sb.toString()
        Log.d(TAG, "识别结果: '$text' (置信度: $conf)")

        return Pair(text, conf)
    }

    /**
     * 绘制检测框
     */
    suspend fun drawDetectionBoxes(bitmap: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            initDeferred.await()
        }

        ensureOpenCvLoaded()

        try {
            val boxes = textDetection(bitmap)
            val result = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(result)

            val paint = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f
                color = android.graphics.Color.RED
            }

            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.YELLOW
                textSize = 24f
            }

            for ((index, box) in boxes.withIndex()) {
                val path = android.graphics.Path()
                path.moveTo(box[0].x.toFloat(), box[0].y.toFloat())
                for (i in 1 until box.size) {
                    path.lineTo(box[i].x.toFloat(), box[i].y.toFloat())
                }
                path.close()
                canvas.drawPath(path, paint)

                if (box.isNotEmpty()) {
                    canvas.drawText(
                        "$index",
                        box[0].x.toFloat(),
                        (box[0].y - 10).toFloat(),
                        textPaint
                    )
                }
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "绘制检测框失败", e)
            bitmap
        }
    }

    /**
     * 二值化处理
     */
    suspend fun binarizeBitmap(bitmap: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        ensureOpenCvLoaded()

        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        val grayMat = Mat()
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        val binaryMat = Mat()
        Imgproc.threshold(
            grayMat, binaryMat,
            0.0, 255.0,
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU
        )

        val resultBitmap = Bitmap.createBitmap(
            binaryMat.cols(), binaryMat.rows(), Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(binaryMat, resultBitmap)

        mat.release()
        grayMat.release()
        binaryMat.release()

        resultBitmap
    }

    /**
     * 关闭并释放资源
     */
    fun close() {
        detSession?.close()
        recSession?.close()
        clsSession?.close()
        ortEnv?.close()

        detSession = null
        recSession = null
        clsSession = null
        ortEnv = null

        isInitialized = false
        if (initDeferred.isActive) initDeferred.cancel()
        initDeferred = CompletableDeferred()
        Log.d(TAG, "资源已释放")
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
        return context.assets.open(dictPath).bufferedReader(charset = Charsets.UTF_8).use {
            it.readLines().filter { line -> line.isNotEmpty() }
        }
    }

    /**
     * 点数据类
     */
    data class Point(val x: Int, val y: Int)
}
