
package com.guaishoudejia.x4doublesysfserv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList
import java.util.Queue
import java.util.UUID

@SuppressLint("MissingPermission")
class X4Service : Service() {

    private var bluetoothGatt: BluetoothGatt? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    // Stream one frame at a time; avoid allocating thousands of chunk arrays.
    private var pendingFrame: ByteArray? = null
    private var pendingOffset = 0
    private var isSending = false
    private var currentMtu = 20

    private val bluetoothAdapter: BluetoothAdapter? by lazy { (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server.")
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server.")
                    currentMtu = 20 // Reset MTU
                    // Consider whether to stop the service or attempt reconnection
                    stopSelf() // Stop service on disconnect
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU changed to $mtu")
                currentMtu = mtu - 3
                gatt.discoverServices()
            } else {
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered.")
                val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID_DATA)
                if (ch == null) {
                    Log.e(TAG, "Required service/characteristic not found: $SERVICE_UUID / $CHARACTERISTIC_UUID_DATA")
                    stopSelf()
                    return
                }
                handler.post(captureRunnable)
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                sendNextPacket()
            } else {
                Log.e(TAG, "Characteristic write failed: $status")
                isSending = false
                pendingFrame = null
                pendingOffset = 0
            }
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isSending) {
                captureAndProcessImage()
            }
            handler.postDelayed(this, 1000) // Capture every second
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val deviceAddress = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)

        if (resultCode == Activity.RESULT_OK && resultData != null && !deviceAddress.isNullOrEmpty()) {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (device != null) {
                connectToDevice(device)
            } else {
                Log.e(TAG, "Device with address $deviceAddress not found.")
                stopSelf()
            }
        } else {
            Log.e(TAG, "Required data not provided to service.")
            stopSelf()
        }

        return START_STICKY
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.address}")
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private fun captureAndProcessImage() {
        val width = Resources.getSystem().displayMetrics.widthPixels
        val height = Resources.getSystem().displayMetrics.heightPixels

        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, Resources.getSystem().displayMetrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            virtualDisplay?.release()
            imageReader?.close()

            val processedBitmap = processImage(bitmap)
            val rgb565 = convertBitmapToRgb565Le(processedBitmap)
            startSendFrame(rgb565)

        }, handler)
    }

    private fun processImage(bitmap: Bitmap): Bitmap {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 800, 480, true)
        val grayscaleBitmap = Bitmap.createBitmap(scaledBitmap.width, scaledBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscaleBitmap)
        val paint = Paint()
        val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)
        return floydSteinbergDither(grayscaleBitmap)
    }

    private fun floydSteinbergDither(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val oldPixel = pixels[y * width + x]
                val oldR = Color.red(oldPixel)
                val newPixel = if (oldR < 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                pixels[y * width + x] = newPixel

                val quantError = oldR - Color.red(newPixel)

                if (x + 1 < width) {
                    val pixel = pixels[y * width + x + 1]
                    val r = (Color.red(pixel) + quantError * 7 / 16).coerceIn(0, 255)
                    pixels[y * width + x + 1] = Color.rgb(r, r, r)
                }
                if (x - 1 >= 0 && y + 1 < height) {
                    val pixel = pixels[(y + 1) * width + x - 1]
                    val r = (Color.red(pixel) + quantError * 3 / 16).coerceIn(0, 255)
                    pixels[(y + 1) * width + x - 1] = Color.rgb(r, r, r)
                }
                if (y + 1 < height) {
                    val pixel = pixels[(y + 1) * width + x]
                    val r = (Color.red(pixel) + quantError * 5 / 16).coerceIn(0, 255)
                    pixels[(y + 1) * width + x] = Color.rgb(r, r, r)
                }
                if (x + 1 < width && y + 1 < height) {
                    val pixel = pixels[(y + 1) * width + x + 1]
                    val r = (Color.red(pixel) + quantError * 1 / 16).coerceIn(0, 255)
                    pixels[(y + 1) * width + x + 1] = Color.rgb(r, r, r)
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun convertBitmapToRgb565Le(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val out = ByteArray(width * height * 2)

        var oi = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val rgb565 = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
                // Little-endian: low byte first.
                out[oi++] = (rgb565 and 0xFF).toByte()
                out[oi++] = ((rgb565 shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun startSendFrame(rgb565Le: ByteArray) {
        val header = buildFrameHeader(rgb565Le.size)
        val frame = ByteArray(header.size + rgb565Le.size)
        System.arraycopy(header, 0, frame, 0, header.size)
        System.arraycopy(rgb565Le, 0, frame, header.size, rgb565Le.size)
        pendingFrame = frame
        pendingOffset = 0
        if (!isSending) {
            sendNextPacket()
        }
    }

    private fun buildFrameHeader(payloadLen: Int): ByteArray {
        // Protocol:
        // 0..3  : ASCII 'X4IM'
        // 4     : version = 1
        // 5     : format  = 1 (RGB565 little-endian)
        // 6..7  : reserved
        // 8..11 : payload length (uint32 LE)
        val bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        bb.put('X'.code.toByte())
        bb.put('4'.code.toByte())
        bb.put('I'.code.toByte())
        bb.put('M'.code.toByte())
        bb.put(1) // version
        bb.put(1) // format
        bb.putShort(0)
        bb.putInt(payloadLen)
        return bb.array()
    }

    private fun sendNextPacket() {
        val frame = pendingFrame
        if (frame == null) {
            isSending = false
            return
        }
        if (pendingOffset >= frame.size) {
            isSending = false
            pendingFrame = null
            pendingOffset = 0
            return
        }
        isSending = true
        val end = (pendingOffset + currentMtu).coerceAtMost(frame.size)
        val data = frame.copyOfRange(pendingOffset, end)
        val characteristic = bluetoothGatt?.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID_DATA)
        if (characteristic != null && data != null) {
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(characteristic)
            pendingOffset = end
        } else {
            Log.e(TAG, "Characteristic or data is null, cannot send packet")
            isSending = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Screen Capture Service"
            val descriptionText = "This service captures the screen to send to a peripheral."
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Screen Sharing Active")
            .setContentText("Sharing screen content with a peripheral device.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(captureRunnable)
        mediaProjection?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        bluetoothGatt?.close()
        Log.d(TAG, "Service destroyed, all resources released.")
    }

    companion object {
        private const val TAG = "X4Service"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "X4_SERVICE_CHANNEL"
        // 16-bit UUIDs expanded with the standard Bluetooth Base UUID.
        val SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID_DATA = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb")

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }
}
