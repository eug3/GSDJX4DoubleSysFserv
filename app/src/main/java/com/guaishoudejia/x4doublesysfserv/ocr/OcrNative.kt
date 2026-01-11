package com.guaishoudejia.x4doublesysfserv.ocr

import android.graphics.Bitmap
import android.util.Log

/**
 * Native OCR 预测器 - 使用官方 demo 的 C++ 实现
 *
 * 这个实现直接使用官方 PaddleOCR demo 的 C++ 代码，
 * 避免了 Java/Kotlin JNI API 的兼容性问题
 */
class OcrNative(
    private val detModelPath: String,
    private val recModelPath: String,
    private val clsModelPath: String,
    private val cpuThreadNum: Int = 1,
    private val cpuPowerMode: String = "LITE_POWER_HIGH"
) {
    private val TAG = "OcrNative"

    private var nativePtr: Long = 0

    companion object {
        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("paddle_lite_jni")
            System.loadLibrary("ocr_native")
        }
    }

    init {
        Log.d(TAG, "初始化 Native OCR...")
        nativePtr = init(detModelPath, recModelPath, clsModelPath, 0, cpuThreadNum, cpuPowerMode)
        if (nativePtr == 0L) {
            throw RuntimeException("Native OCR 初始化失败")
        }
        Log.d(TAG, "Native OCR 初始化成功, ptr=$nativePtr")
    }

    /**
     * 执行 OCR 识别
     *
     * @param bitmap 输入图像
     * @param maxSizeLen 检测图像的最大边长
     * @param runDet 是否运行检测 (1=运行, 0=跳过)
     * @param runCls 是否运行分类 (1=运行, 0=跳过)
     * @param runRec 是否运行识别 (1=运行, 0=跳过)
     * @return 识别结果列表
     */
    fun forward(
        bitmap: Bitmap,
        maxSizeLen: Int = 960,
        runDet: Int = 1,
        runCls: Int = 0,
        runRec: Int = 1
    ): List<OcrResultModel> {
        if (nativePtr == 0L) {
            Log.e(TAG, "nativePtr is null")
            return emptyList()
        }

        try {
            val rawResults = forward(nativePtr, bitmap, maxSizeLen, runDet, runCls, runRec)
            return postprocess(rawResults)
        } catch (e: Exception) {
            Log.e(TAG, "forward failed", e)
            return emptyList()
        }
    }

    private fun postprocess(raw: FloatArray): List<OcrResultModel> {
        val results = mutableListOf<OcrResultModel>()
        var begin = 0

        while (begin < raw.size) {
            val pointNum = raw[begin].toInt()
            val wordNum = raw[begin + 1].toInt()
            val res = parse(raw, begin + 2, pointNum, wordNum)
            begin += 2 + 1 + pointNum * 2 + wordNum + 2
            results.add(res)
        }

        return results
    }

    private fun parse(raw: FloatArray, begin: Int, pointNum: Int, wordNum: Int): OcrResultModel {
        var current = begin
        val res = OcrResultModel()

        // confidence score
        res.setConfidence(raw[current])
        current++

        // points
        for (i in 0 until pointNum) {
            res.addPoints(raw[current + i * 2].toInt(), raw[current + i * 2 + 1].toInt())
        }
        current += pointNum * 2

        // word indices (will be converted to text later)
        for (i in 0 until wordNum) {
            res.addWordIndex(raw[current + i].toInt())
        }
        current += wordNum

        // cls result
        res.setClsIdx(raw[current])
        res.setClsConfidence(raw[current + 1])

        return res
    }

    fun destroy() {
        if (nativePtr != 0L) {
            release(nativePtr)
            nativePtr = 0
            Log.d(TAG, "Native OCR 已释放")
        }
    }

    private external fun init(
        detModelPath: String,
        recModelPath: String,
        clsModelPath: String,
        useOpencl: Int,
        threadNum: Int,
        cpuMode: String
    ): Long

    private external fun forward(
        ptr: Long,
        bitmap: Bitmap,
        maxSizeLen: Int,
        runDet: Int,
        runCls: Int,
        runRec: Int
    ): FloatArray

    private external fun release(ptr: Long)
}
