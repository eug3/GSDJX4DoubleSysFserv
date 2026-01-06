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
    private const val LOGICAL_W = 480
    private const val LOGICAL_H = 800

    // Device framebuffer is physical 800x480 (rotated 270 for logical portrait).
    private const val PHYS_W = 800
    private const val PHYS_H = 480

    private const val LUMA_THRESHOLD = 160

    data class RenderResult(
        val pageBytes48k: ByteArray,
        val debugStats: String,
    )

    /**
     * Render DOM layout JSON (from Gecko JS) into a 1bpp framebuffer page.
     * Output layout matches device physical framebuffer (800x480, MSB first, 1=white, 0=black), padded to 48KB.
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
            val textSizePx = (cssFontSize * sy).coerceIn(10f, 48f)

            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = textSizePx
            }

            val clampedX = x.coerceIn(0, LOGICAL_W - 1)
            val clampedY = y.coerceIn(0, LOGICAL_H - 1)
            val maxW = (LOGICAL_W - clampedX).coerceAtLeast(1)
            val layoutW = min(w, maxW).coerceAtLeast(1)

            val staticLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, tp, layoutW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false)
                .build()

            canvas.save()
            canvas.translate(clampedX.toFloat(), clampedY.toFloat())
            // Clip to bounding box height (if provided), to avoid drawing across regions.
            canvas.clipRect(0f, 0f, layoutW.toFloat(), h.toFloat())
            staticLayout.draw(canvas)
            canvas.restore()

            drawnText++
        }

        val packed = packLogicalBitmapToDevicePhysical1bpp(bitmap)
        val out48k = ByteArray(BleBookProtocol.PAGE_SIZE_BYTES)
        System.arraycopy(packed, 0, out48k, 0, packed.size)

        return RenderResult(
            pageBytes48k = out48k,
            debugStats = "viewport=${vw}x${vh} sx=${"%.3f".format(sx)} sy=${"%.3f".format(sy)} textNodes=$drawnText",
        )
    }

    private fun parseCssPx(s: String, fallback: Float): Float {
        val t = s.trim().lowercase()
        return when {
            t.endsWith("px") -> t.removeSuffix("px").toFloatOrNull() ?: fallback
            else -> t.toFloatOrNull() ?: fallback
        }
    }

    /**
     * Pack the logical 480x800 bitmap into device physical framebuffer bytes (800x480, ROTATE_270 mapping).
     * Bit order: MSB is left-most pixel, 1=white, 0=black (matches GUI_Paint.c).
     */
    private fun packLogicalBitmapToDevicePhysical1bpp(logical: Bitmap): ByteArray {
        val physBytes = ByteArray((PHYS_W * PHYS_H) / 8) { 0xFF.toByte() } // default white

        val pixels = IntArray(LOGICAL_W * LOGICAL_H)
        logical.getPixels(pixels, 0, LOGICAL_W, 0, 0, LOGICAL_W, LOGICAL_H)

        fun setPhysPixel(xPhys: Int, yPhys: Int, black: Boolean) {
            if (xPhys !in 0 until PHYS_W) return
            if (yPhys !in 0 until PHYS_H) return
            val byteIndex = (yPhys * (PHYS_W / 8)) + (xPhys / 8)
            val mask = (0x80 ushr (xPhys % 8)) and 0xFF
            val cur = physBytes[byteIndex].toInt() and 0xFF
            physBytes[byteIndex] = if (black) {
                (cur and mask.inv()).toByte()
            } else {
                (cur or mask).toByte()
            }
        }

        for (yLog in 0 until LOGICAL_H) {
            val rowOff = yLog * LOGICAL_W
            for (xLog in 0 until LOGICAL_W) {
                val p = pixels[rowOff + xLog]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                val black = luma < LUMA_THRESHOLD

                // Mapping from logical (xLog,yLog) to physical buffer (xPhys,yPhys) for ROTATE_270:
                // GUI_Paint.c: X = Ypoint; Y = HeightMemory - Xpoint - 1
                val xPhys = yLog
                val yPhys = PHYS_H - xLog - 1
                setPhysPixel(xPhys, yPhys, black)
            }
        }

        return physBytes
    }
}
