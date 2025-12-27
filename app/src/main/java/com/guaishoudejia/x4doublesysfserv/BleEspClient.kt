package com.guaishoudejia.x4doublesysfserv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BleEspClient(
    private val context: Context,
    private val deviceAddress: String,
    private val scope: CoroutineScope,
    private val onCommand: (String) -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private var imageChar: BluetoothGattCharacteristic? = null
    private var cmdChar: BluetoothGattCharacteristic? = null

    private var mtuPayload: Int = 20

    private var pendingFrame: ByteArray? = null
    private var pendingOffset: Int = 0
    private var sending: Boolean = false
    private var lastChunkSize: Int = 0

    fun connect() {
        val adapter = getAdapter() ?: run {
            Log.w(TAG, "BluetoothAdapter null")
            return
        }
        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid device address: $deviceAddress", e)
            return
        }
        close()
        gatt = connectGattCompat(device)
    }

    fun close() {
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        imageChar = null
        cmdChar = null
        sending = false
        pendingFrame = null
        pendingOffset = 0
    }

    fun sendBitmap(bitmap: Bitmap) {
        val payload = convertBitmapToRgb565Le(bitmap)
        val header = buildFrameHeader(payload.size)
        val frame = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, frame, 0, header.size)
        System.arraycopy(payload, 0, frame, header.size, payload.size)

        pendingFrame = frame
        pendingOffset = 0
        if (!sending) {
            sending = true
            sendNextPacket()
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNextPacket() {
        val g = gatt ?: run {
            sending = false
            return
        }
        val ch = imageChar ?: run {
            sending = false
            return
        }
        val frame = pendingFrame ?: run {
            sending = false
            return
        }

        if (pendingOffset >= frame.size) {
            sending = false
            return
        }

        val maxChunk = mtuPayload.coerceAtLeast(20)
        val end = (pendingOffset + maxChunk).coerceAtMost(frame.size)
        val chunk = frame.copyOfRange(pendingOffset, end)
        lastChunkSize = chunk.size

        val ok: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = g.writeCharacteristic(ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = chunk
                g.writeCharacteristic(ch)
            }
        }

        if (!ok) {
            Log.w(TAG, "writeCharacteristic returned false")
            sending = false
        }
    }

    private fun onPacketWritten(success: Boolean) {
        if (!success) {
            sending = false
            return
        }
        val frame = pendingFrame ?: run {
            sending = false
            return
        }
        pendingOffset = (pendingOffset + lastChunkSize).coerceAtMost(frame.size)
        sendNextPacket()
    }

    private fun getAdapter(): BluetoothAdapter? {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter
    }

    @SuppressLint("MissingPermission")
    private fun connectGattCompat(device: android.bluetooth.BluetoothDevice): BluetoothGatt {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                try {
                    gatt.requestMtu(517)
                } catch (_: Exception) {
                }
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                imageChar = null
                cmdChar = null
                sending = false
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // ATT payload is MTU-3
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuPayload = (mtu - 3).coerceAtLeast(20)
                Log.d(TAG, "MTU changed: mtu=$mtu payload=$mtuPayload")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onServicesDiscovered status=$status")
                return
            }

            val svc: BluetoothGattService = gatt.getService(UUID_IMAGE_SERVICE) ?: run {
                Log.w(TAG, "Image service not found")
                return
            }
            imageChar = svc.getCharacteristic(UUID_IMAGE_CHAR)
            cmdChar = svc.getCharacteristic(UUID_CMD_CHAR)

            val cc = cmdChar
            if (cc != null) {
                enableNotifications(gatt, cc)
            } else {
                Log.w(TAG, "CMD characteristic not found")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != UUID_CMD_CHAR) return
            val value = characteristic.value ?: return
            val cmd = try {
                value.toString(Charsets.UTF_8).trim()
            } catch (_: Exception) {
                return
            }
            if (cmd.isBlank()) return

            scope.launch(Dispatchers.Main) {
                onCommand(cmd)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != UUID_IMAGE_CHAR) return
            onPacketWritten(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val ok = gatt.setCharacteristicNotification(characteristic, true)
        if (!ok) {
            Log.w(TAG, "setCharacteristicNotification failed")
        }
        val cccd = characteristic.getDescriptor(UUID_CCCD) ?: run {
            Log.w(TAG, "CCCD not found")
            return
        }

        @Suppress("DEPRECATION")
        run {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }
    }

    private fun buildFrameHeader(payloadLen: Int): ByteArray {
        val header = ByteArray(12)
        header[0] = 'X'.code.toByte()
        header[1] = '4'.code.toByte()
        header[2] = 'I'.code.toByte()
        header[3] = 'M'.code.toByte()
        header[4] = 1
        header[5] = 1
        header[6] = 0
        header[7] = 0
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        bb.putInt(payloadLen)
        return header
    }

    private fun convertBitmapToRgb565Le(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val out = ByteArray(w * h * 2)
        var oi = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val rgb565 = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
            out[oi++] = (rgb565 and 0xFF).toByte() // little-endian
            out[oi++] = ((rgb565 shr 8) and 0xFF).toByte()
        }
        return out
    }

    private companion object {
        private const val TAG = "BleEspClient"

        private val UUID_IMAGE_SERVICE: UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
        private val UUID_IMAGE_CHAR: UUID = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb")
        private val UUID_CMD_CHAR: UUID = UUID.fromString("00005679-0000-1000-8000-00805f9b34fb")
        private val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    }
}
