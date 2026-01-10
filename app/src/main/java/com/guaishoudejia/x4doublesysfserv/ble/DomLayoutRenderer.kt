package com.guaishoudejia.x4doublesysfserv.ble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

object DomLayoutRenderer {
    private const val TAG = "DomLayoutRenderer"
    private const val LOGICAL_W = 480
    private const val LOGICAL_H = 800

    // 设备物理帧缓冲区为 800x480
    private const val PHYS_W = 800
    private const val PHYS_H = 480
    
    private const val PAGE_SIZE_BYTES = 48000

    private const val LUMA_THRESHOLD = 160

    data class RenderResult(
        val pageBytes48k: ByteArray,
        val previewBitmap: Bitmap, // 增加预览图
        val debugStats: String,
    )

    /**
     * 仅根据 DOM 布局 JSON 渲染页面。
     */
    fun renderTo1bpp48k(layoutJson: String): RenderResult {
        val root = JSONObject(layoutJson)
        val viewport = root.optJSONObject("viewport") ?: JSONObject()
        val vw = viewport.optInt("width", 1).coerceAtLeast(1)
        val vh = viewport.optInt("height", 1).coerceAtLeast(1)

        val sx = LOGICAL_W.toFloat() / vw.toFloat()
        val sy = LOGICAL_H.toFloat() / vh.toFloat()

        val bitmap = Bitmap.createBitmap(LOGICAL_W, LOGICAL_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val elements: JSONArray = root.optJSONArray("elements") ?: JSONArray()
        var drawnText = 0

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val text = el.optString("text", "").trim()
            if (text.isEmpty()) continue

            val x = (el.optInt("x", 0) * sx).toInt()
            val y = (el.optInt("y", 0) * sy).toInt()
            val w = max(1, (el.optInt("width", 1) * sx).toInt())
            val h = max(1, (el.optInt("height", 1) * sy).toInt())

            val cssFontSize = parseCssPx(el.optString("fontSize", "16px"), fallback = 16f)
            val textSizePx = (cssFontSize * sy).coerceIn(10f, 64f)

            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = textSizePx
            }

            val clampedX = x.coerceIn(0, LOGICAL_W - 1)
            val clampedY = y.coerceIn(0, LOGICAL_H - 1)
            val layoutW = min(w, (LOGICAL_W - clampedX)).coerceAtLeast(1)

            val staticLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, tp, layoutW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false)
                .build()

            canvas.save()
            canvas.translate(clampedX.toFloat(), clampedY.toFloat())
            canvas.clipRect(0f, 0f, layoutW.toFloat(), h.toFloat())
            staticLayout.draw(canvas)
            canvas.restore()

            drawnText++
        }

        val packed = packLogicalBitmapToDevicePhysical1bpp(bitmap)
        val out48k = ByteArray(PAGE_SIZE_BYTES)
        System.arraycopy(packed, 0, out48k, 0, packed.size)

        return RenderResult(
            pageBytes48k = out48k,
            previewBitmap = bitmap, // 返回渲染后的位图用于预览
            debugStats = "vw=$vw vh=$vh sx=$sx sy=$sy textNodes=$drawnText",
        )
    }

    private fun parseCssPx(s: String, fallback: Float): Float {
        val t = s.trim().lowercase()
        return when {
            t.endsWith("px") -> t.removeSuffix("px").toFloatOrNull() ?: fallback
            else -> t.toFloatOrNull() ?: fallback
        }
    }

    private fun packLogicalBitmapToDevicePhysical1bpp(logical: Bitmap): ByteArray {
        val physBytes = ByteArray((PHYS_W * PHYS_H) / 8) { 0xFF.toByte() } 
        val pixels = IntArray(LOGICAL_W * LOGICAL_H)
        logical.getPixels(pixels, 0, LOGICAL_W, 0, 0, LOGICAL_W, LOGICAL_H)

        fun setPhysPixel(xPhys: Int, yPhys: Int, black: Boolean) {
            if (xPhys !in 0 until PHYS_W || yPhys !in 0 until PHYS_H) return
            val byteIndex = (yPhys * (PHYS_W / 8)) + (xPhys / 8)
            val mask = (0x80 ushr (xPhys % 8)) and 0xFF
            val cur = physBytes[byteIndex].toInt() and 0xFF
            physBytes[byteIndex] = if (black) (cur and mask.inv()).toByte() else (cur or mask).toByte()
        }

        for (yLog in 0 until LOGICAL_H) {
            val rowOff = yLog * LOGICAL_W
            for (xLog in 0 until LOGICAL_W) {
                val p = pixels[rowOff + xLog]
                val black = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 < LUMA_THRESHOLD
                val xPhys = yLog
                val yPhys = PHYS_H - xLog - 1
                setPhysPixel(xPhys, yPhys, black)
            }
        }
        return physBytes
    }
}
