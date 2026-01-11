package com.guaishoudejia.x4doublesysfserv.ocr

import android.util.Log

class RecPredictorNative(
    modelPath: String,
    useOpencl: Int = 0,
    cpuThreadNum: Int = 4,
    cpuPowerMode: String = "LITE_POWER_HIGH"
) {
    companion object {
        init {
            System.loadLibrary("RecNative")
        }
    }
    private val TAG = "RecPredictorNative"
    private var nativePtr: Long = 0

    init {
        nativePtr = init(modelPath, useOpencl, cpuThreadNum, cpuPowerMode)
        Log.i(TAG, "native init ptr=$nativePtr")
    }

    fun run(inputCHW: FloatArray, height: Int, width: Int): FloatArray {
        return forward(nativePtr, inputCHW, height, width)
    }

    fun destroy() {
        if (nativePtr != 0L) {
            release(nativePtr)
            nativePtr = 0
        }
    }

    private external fun init(modelPath: String, useOpencl: Int, threadNum: Int, cpuMode: String): Long
    private external fun forward(ptr: Long, input: FloatArray, height: Int, width: Int): FloatArray
    private external fun release(ptr: Long)
}
