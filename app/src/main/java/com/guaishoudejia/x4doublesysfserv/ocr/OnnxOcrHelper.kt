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
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ONNX OCR 识别助手 - 使用 ONNX Runtime Mobile 运行 PP-OCRv5 模型
 * 优化：直接从 Assets 加载模型，避免占用双倍存储空间
 */
class OnnxOcrHelper {

    companion object {
        private const val TAG = "OnnxOcrHelper"
        private const val MODEL_DIR = "models_onnx"
        private const val DET_MODEL = "ppocrv5_mobile_det.onnx"
        private const val REC_MODEL = "ppocrv5_mobile_rec.onnx"
        private const val CLS_MODEL = "pplcnet_x0_25_textline_ori.onnx"
        private const val DICT_FILE = "dict/ppocrv5_dict.txt"
        private const val DET_LIMIT_SIDE_LEN = 960f
        private const val REC_IMAGE_HEIGHT = 48
        private const val REC_MAX_WIDTH = 320
        private const val REC_MIN_WIDTH = 8
    }

    private var ortEnv: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var clsSession: OrtSession? = null
    private var wordLabels: List<String> = emptyList()
    private var recExpectedHeight = REC_IMAGE_HEIGHT
    private var recExpectedWidth = REC_MAX_WIDTH

    @Volatile
    private var isInitialized = false
    private val initLock = Mutex()
    private var initDeferred = CompletableDeferred<Boolean>()

    fun isReady(): Boolean = isInitialized

    suspend fun init(context: Context): Unit = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        initLock.withLock {
            if (isInitialized) return@withLock
            try {
                Log.d(TAG, "开始初始化 ONNX OCR (直接从 Assets 加载)...")
                wordLabels = loadDict(context, DICT_FILE)
                ortEnv = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                }
                detSession = loadModelFromAssets(context, "$MODEL_DIR/$DET_MODEL", sessionOptions)
                recSession = loadModelFromAssets(context, "$MODEL_DIR/$REC_MODEL", sessionOptions)
                try {
                    val inputName = recSession!!.inputNames.iterator().next()
                    val nodeInfo = recSession!!.getInputInfo().getValue(inputName)
                    val valueInfo = nodeInfo.info
                    if (valueInfo is TensorInfo) {
                        val inputShape = valueInfo.shape
                        if (inputShape.size >= 4) {
                            recExpectedHeight = inputShape[2].toInt()
                            recExpectedWidth = inputShape[3].toInt()
                        }
                    }
                } catch (e: Exception) {}
                try {
                    clsSession = loadModelFromAssets(context, "$MODEL_DIR/$CLS_MODEL", sessionOptions)
                } catch (e: Exception) {}
                isInitialized = true
                initDeferred.complete(true)
                Log.i(TAG, "ONNX OCR 初始化成功")
            } catch (e: Exception) {
                Log.e(TAG, "ONNX OCR 初始化失败", e)
                isInitialized = false
                initDeferred.completeExceptionally(e)
                initDeferred = CompletableDeferred()
                throw e
            }
        }
    }

    private fun loadModelFromAssets(context: Context, assetPath: String, options: OrtSession.SessionOptions): OrtSession {
        val modelBytes = context.assets.open(assetPath).use { it.readBytes() }
        return ortEnv!!.createSession(modelBytes, options)
    }

    private fun loadDict(context: Context, dictPath: String): List<String> {
        return context.assets.open(dictPath).bufferedReader(Charsets.UTF_8).use {
            it.readLines().filter { line -> line.isNotEmpty() }
        }
    }

    suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        if (!isInitialized) initDeferred.await()
        try {
            val binaryBitmap = binarizeBitmap(bitmap)
            val detBoxes = textDetection(binaryBitmap)
            if (detBoxes.isEmpty()) {
                binaryBitmap.recycle()
                return@withContext OcrResult("", emptyList(), "")
            }
            val results = mutableListOf<OnnxOcrResult>()
            for (box in detBoxes) {
                try {
                    val result = OnnxOcrResult()
                    for (point in box) result.addPoints(point.x, point.y)
                    val cropped = cropBox(binaryBitmap, box)
                    val (_, text, confidence) = recognizeTextInternal(cropped)
                    result.setLabel(text)
                    result.setConfidence(confidence)
                    results.add(result)
                } catch (e: Exception) {}
            }
            val blocks = groupTextByParagraphs(results)
            val formattedText = blocks.joinToString("\n\n") { it.text }
            binaryBitmap.recycle()
            OcrResult(formattedText, blocks, formattedText)
        } catch (e: Exception) { throw e }
    }

    private fun textDetection(bitmap: Bitmap): List<Array<Point>> {
        if (detSession == null) return emptyList()
        try {
            val (scaledBitmap, _, scales) = scaleImage(bitmap, DET_LIMIT_SIDE_LEN.toInt())
            val inputData = preprocessImage(scaledBitmap)
            val inputName = detSession!!.inputNames.iterator().next()
            val inputShape = longArrayOf(1, 3, scaledBitmap.height.toLong(), scaledBitmap.width.toLong())
            val buffer = ByteBuffer.allocateDirect(inputData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(inputData).flip()
            val inputTensor = OnnxTensor.createTensor(ortEnv!!, buffer, inputShape)
            val outputs = detSession!!.run(mapOf(inputName to inputTensor))
            val outputTensor = outputs.get(0) as OnnxTensor
            val outputData = flattenFloatArray(outputTensor.value)
            val boxes = postprocessDetection(outputData, outputTensor.info.shape, bitmap.width, bitmap.height, scales.first, scales.second)
            inputTensor.close()
            outputTensor.close()
            return boxes
        } catch (e: Exception) { return emptyList() }
    }

    private fun recognizeTextInternal(bitmap: Bitmap): Triple<Int, String, Float> {
        if (recSession == null) return Triple(0, "", 0f)
        try {
            val whRatio = bitmap.width.toFloat() / bitmap.height
            var targetWidth = (recExpectedHeight * whRatio).toInt()
            targetWidth = max(REC_MIN_WIDTH, min(REC_MAX_WIDTH, targetWidth))
            targetWidth = ((targetWidth + 7) / 8) * 8
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, recExpectedHeight, true)
            val inputData = preprocessImageForRecognition(resizedBitmap)
            val inputName = recSession!!.inputNames.iterator().next()
            val inputShape = longArrayOf(1, 3, recExpectedHeight.toLong(), targetWidth.toLong())
            val buffer = ByteBuffer.allocateDirect(inputData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(inputData).flip()
            val inputTensor = OnnxTensor.createTensor(ortEnv!!, buffer, inputShape)
            val outputs = recSession!!.run(mapOf(inputName to inputTensor))
            val outputTensor = outputs.get(0) as OnnxTensor
            val outputData = flattenFloatArray(outputTensor.value)
            val (text, conf) = postprocessRecognition(outputData, outputTensor.info.shape)
            inputTensor.close()
            outputTensor.close()
            resizedBitmap.recycle()
            return Triple(0, text, conf)
        } catch (e: Exception) { return Triple(0, "", 0f) }
    }

    suspend fun binarizeBitmap(bitmap: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val bgSampleCount = minOf(10, width * height)
        var bgR = 0L; var bgG = 0L; var bgB = 0L
        for (i in 0 until bgSampleCount) {
            val pixel = pixels[i]
            bgR += (pixel shr 16) and 0xFF
            bgG += (pixel shr 8) and 0xFF
            bgB += pixel and 0xFF
        }
        bgR /= bgSampleCount; bgG /= bgSampleCount; bgB /= bgSampleCount
        val threshold = 15
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF; val g = (pixel shr 8) and 0xFF; val b = pixel and 0xFF
            val diff = kotlin.math.sqrt(((r - bgR) * (r - bgR) + (g - bgG) * (g - bgG) + (b - bgB) * (b - bgB)).toDouble()).toInt()
            pixels[i] = if (diff > threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        result
    }

    suspend fun drawDetectionBoxes(bitmap: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        if (!isInitialized) initDeferred.await()
        try {
            val boxes = textDetection(bitmap)
            val result = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(result)
            val paint = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f; color = android.graphics.Color.RED
            }
            for (box in boxes) {
                val path = android.graphics.Path()
                path.moveTo(box[0].x.toFloat(), box[0].y.toFloat())
                for (i in 1 until box.size) path.lineTo(box[i].x.toFloat(), box[i].y.toFloat())
                path.close()
                canvas.drawPath(path, paint)
            }
            result
        } catch (e: Exception) { bitmap }
    }

    private fun preprocessImage(bitmap: Bitmap): FloatArray {
        val width = bitmap.width; val height = bitmap.height
        val floatArray = FloatArray(width * height * 3)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val channelSize = width * height
        for (i in pixels.indices) {
            val pixel = pixels[i]
            floatArray[i] = ((pixel shr 16) and 0xFF).toFloat() / 255f
            floatArray[i + channelSize] = ((pixel shr 8) and 0xFF).toFloat() / 255f
            floatArray[i + channelSize * 2] = (pixel and 0xFF).toFloat() / 255f
        }
        return floatArray
    }

    private fun preprocessImageForRecognition(bitmap: Bitmap): FloatArray {
        val width = bitmap.width; val height = bitmap.height
        val floatArray = FloatArray(width * height * 3)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val channelSize = width * height
        for (i in pixels.indices) {
            val grayValue = (pixels[i] and 0xFF).toFloat() / 255f
            val normalizedValue = (grayValue - 0.5f) * 2f
            floatArray[i] = normalizedValue
            floatArray[i + channelSize] = normalizedValue
            floatArray[i + channelSize * 2] = normalizedValue
        }
        return floatArray
    }

    private fun scaleImage(bitmap: Bitmap, maxSideLen: Int): Triple<Bitmap, Float, Pair<Float, Float>> {
        val width = bitmap.width; val height = bitmap.height; val maxSide = max(width, height)
        val scale = if (maxSide > 2048) 2048f / maxSide else 1f
        val alignedWidth = (((width * scale).toInt() + 31) / 32) * 32
        val alignedHeight = (((height * scale).toInt() + 31) / 32) * 32
        val scaleX = alignedWidth.toFloat() / width; val scaleY = alignedHeight.toFloat() / height
        val scaled = Bitmap.createScaledBitmap(bitmap, alignedWidth, alignedHeight, true)
        return Triple(scaled, 1f, Pair(scaleX, scaleY))
    }

    private fun cropBox(bitmap: Bitmap, box: Array<Point>): Bitmap {
        var minX = box.minOf { it.x }; var minY = box.minOf { it.y }
        var maxX = box.maxOf { it.x }; var maxY = box.maxOf { it.y }
        val rawW = max(1, maxX - minX); val rawH = max(1, maxY - minY)
        val extraY = (rawH * 0.85f).toInt().coerceIn(12, 96); val extraX = (rawW * 0.06f).toInt().coerceIn(6, 48)
        minX = max(0, minX - extraX); minY = max(0, minY - extraY)
        maxX = min(bitmap.width, maxX + extraX); maxY = min(bitmap.height, maxY + extraY)
        return Bitmap.createBitmap(bitmap, minX, minY, max(1, maxX - minX), max(1, maxY - minY))
    }

    private fun postprocessDetection(output: FloatArray, shape: LongArray, srcW: Int, srcH: Int, sX: Float, sY: Float): List<Array<Point>> {
        val h = shape[2].toInt(); val w = shape[3].toInt()
        val boxes = mutableListOf<Array<Point>>()
        val mask = BooleanArray(h * w) { output[it] > 0.2f }
        val dilated = BooleanArray(h * w) { idx ->
            val cy = idx / w; val cx = idx % w; var found = mask[idx]
            if (!found) { for (dy in -3..3) for (dx in -3..3) {
                val ny = cy + dy; val nx = cx + dx
                if (ny in 0 until h && nx in 0 until w && mask[ny * w + nx]) { found = true; break }
            }}
            found
        }
        val visited = BooleanArray(h * w)
        for (idx in dilated.indices) {
            if (!dilated[idx] || visited[idx]) continue
            val component = mutableListOf<Int>(); val queue = mutableListOf(idx); visited[idx] = true
            var minX = idx % w; var maxX = minX; var minY = idx / w; var maxY = minY
            while (queue.isNotEmpty()) {
                val curr = queue.removeAt(0); component.add(curr); val cy = curr / w; val cx = curr % w
                minX = min(minX, cx); maxX = max(maxX, cx); minY = min(minY, cy); maxY = max(maxY, cy)
                for (dy in -1..1) for (dx in -1..1) {
                    val ny = cy + dy; val nx = cx + dx
                    if (ny in 0 until h && nx in 0 until w) {
                        val nidx = ny * w + nx
                        if (dilated[nidx] && !visited[nidx]) { visited[nidx] = true; queue.add(nidx) }
                    }
                }
            }
            if (component.size >= 15) {
                val exX = max(4, ((maxX - minX) * 0.08f).toInt()); val exY = max(12, ((maxY - minY) * 0.40f).toInt())
                val rMinX = max(0, (minX / sX).toInt() - exX); val rMinY = max(0, (minY / sY).toInt() - exY)
                val rMaxX = min(srcW - 1, (maxX / sX).toInt() + exX); val rMaxY = min(srcH - 1, (maxY / sY).toInt() + exY)
                boxes.add(arrayOf(Point(rMinX, rMinY), Point(rMaxX, rMinY), Point(rMaxX, rMaxY), Point(rMinX, rMaxY)))
            }
        }
        return boxes.ifEmpty { listOf(arrayOf(Point(0,0), Point(srcW-1,0), Point(srcW-1,srcH-1), Point(0,srcH-1))) }
    }

    private fun postprocessRecognition(output: FloatArray, shape: LongArray): Pair<String, Float> {
        val seqLen = shape[1].toInt(); val numClasses = shape[2].toInt()
        val sb = StringBuilder(); var sumConf = 0f; var count = 0; var prev = -1
        for (t in 0 until seqLen) {
            var maxV = Float.NEGATIVE_INFINITY; var maxI = 0
            for (c in 0 until numClasses) {
                val v = output[t * numClasses + c]
                if (v > maxV) { maxV = v; maxI = c }
            }
            if (maxI == prev || maxI == 0) { prev = maxI; continue }
            prev = maxI
            if (maxI - 1 < wordLabels.size) { sb.append(wordLabels[maxI - 1]); sumConf += maxV; count++ }
        }
        return Pair(sb.toString(), if (count > 0) (1f / (1f + kotlin.math.exp(-sumConf / count))) else 0.5f)
    }

    private fun groupTextByParagraphs(results: List<OnnxOcrResult>): List<TextBlock> {
        val items = results.mapNotNull { r ->
            val p = r.points; if (p.isEmpty()) return@mapNotNull null
            val minY = p.minOf { it.y }; val maxY = p.maxOf { it.y }
            TextItem(r.getLabel() ?: "", r.getConfidence(), minY, maxY, maxY - minY + 1)
        }.sortedBy { it.topY }
        if (items.isEmpty()) return emptyList()
        val paragraphs = mutableListOf<MutableList<TextItem>>()
        var current = mutableListOf(items[0])
        for (i in 1 until items.size) {
            val gap = items[i].topY - items[i-1].bottomY
            if (gap <= 1.0f * (items[i].height + items[i-1].height) / 2f) current.add(items[i])
            else { paragraphs.add(current); current = mutableListOf(items[i]) }
        }
        paragraphs.add(current)
        return paragraphs.mapIndexed { i, it -> TextBlock(it.joinToString("") { it.text }, it.map { it.confidence }.average().toFloat(), i) }
    }

    private fun flattenFloatArray(obj: Any?): FloatArray {
        return when (obj) {
            is FloatArray -> obj
            is Array<*> -> {
                val list = mutableListOf<Float>()
                for (e in obj) when (e) {
                    is Float -> list.add(e)
                    is FloatArray -> list.addAll(e.toList())
                    is Array<*> -> list.addAll(flattenFloatArray(e).toList())
                }
                list.toFloatArray()
            }
            else -> floatArrayOf()
        }
    }

    fun close() {
        detSession?.close(); recSession?.close(); clsSession?.close(); ortEnv?.close()
        detSession = null; recSession = null; clsSession = null; ortEnv = null
        isInitialized = false; if (initDeferred.isActive) initDeferred.cancel(); initDeferred = CompletableDeferred()
    }

    data class Point(val x: Int, val y: Int)
    private data class TextItem(val text: String, val confidence: Float, val topY: Int, val bottomY: Int, val height: Int)
}

class OnnxOcrResult {
    val points = mutableListOf<OnnxOcrHelper.Point>()
    private var label: String? = null
    private var confidence: Float = 0f
    fun addPoints(x: Int, y: Int) = points.add(OnnxOcrHelper.Point(x, y))
    fun setLabel(l: String) { label = l }
    fun getLabel() = label
    fun setConfidence(c: Float) { confidence = c }
    fun getConfidence() = confidence
}
