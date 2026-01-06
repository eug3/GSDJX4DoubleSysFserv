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
import kotlinx.coroutines.delay
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

    private var isConnected: Boolean = false
    private var cmdNotificationsEnabled: Boolean = false

    private var pendingFrame: ByteArray? = null
    private var pendingOffset: Int = 0
    private var sending: Boolean = false
    private var lastChunkSize: Int = 0

    private var cccdRetryAttempts: Int = 0

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
        isConnected = false
        cmdNotificationsEnabled = false
        sending = false
        pendingFrame = null
        pendingOffset = 0
    }

    fun sendJson(json: String) {
        Log.d(TAG, "sendJson called: ${json.length} chars")
        // 使用新的 JSON 协议：
        // 0..3  : ASCII 'X4JS'
        // 4     : version = 1
        // 5..7  : reserved
        // 8..11 : payload length (uint32 LE)
        val payload = json.toByteArray(Charsets.UTF_8)
        val header = ByteArray(12)
        header[0] = 'X'.code.toByte()
        header[1] = '4'.code.toByte()
        header[2] = 'J'.code.toByte()
        header[3] = 'S'.code.toByte()
        header[4] = 1
        header[5] = 0
        header[6] = 0
        header[7] = 0
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        bb.putInt(payload.size)
        
        val frame = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, frame, 0, header.size)
        System.arraycopy(payload, 0, frame, header.size, payload.size)
        Log.d(TAG, "Prepared JSON frame: header=${header.size} payload=${payload.size} total=${frame.size}")

        pendingFrame = frame
        pendingOffset = 0
        if (!sending) {
            sending = true
            sendNextPacket()
        } else {
            Log.w(TAG, "Already sending, replaced pending frame")
        }
    }

    fun sendBitmap(bitmap: Bitmap) {
        Log.d(TAG, "sendBitmap called: ${bitmap.width}x${bitmap.height}")
        val payload = convertBitmapToRgb565Le(bitmap)
        val header = buildFrameHeader(payload.size)
        val frame = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, frame, 0, header.size)
        System.arraycopy(payload, 0, frame, header.size, payload.size)
        Log.d(TAG, "Prepared frame: header=${header.size} payload=${payload.size} total=${frame.size}")

        pendingFrame = frame
        pendingOffset = 0
        if (!sending) {
            sending = true
            sendNextPacket()
        } else {
            Log.w(TAG, "Already sending, replaced pending frame")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNextPacket() {
        val g = gatt ?: run {
            Log.w(TAG, "sendNextPacket: gatt is null")
            sending = false
            return
        }
        val ch = imageChar ?: run {
            // Common race: UI triggers send before services are discovered.
            // Keep pendingFrame and let onServicesDiscovered resume sending.
            if (pendingFrame != null) {
                Log.d(TAG, "sendNextPacket: imageChar not ready yet; will retry after services discovered")
            }
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
        if (pendingOffset == 0) {
            Log.d(TAG, "Starting frame transfer: total=${frame.size} bytes, MTU payload=$mtuPayload")
        } else if ((pendingOffset % 10000) < lastChunkSize) {
            Log.d(TAG, "Transfer progress: $pendingOffset / ${frame.size} bytes")
        }

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
            Log.w(TAG, "Chunk write failed; aborting transfer and cleaning state")
            sending = false
            pendingFrame = null
            pendingOffset = 0
            return
        }
        if (pendingOffset >= (pendingFrame?.size ?: 0)) {
            Log.i(TAG, "Frame transfer completed: $pendingOffset bytes sent")
        }
        val frame = pendingFrame ?: run {
            sending = false
            return
        }
        pendingOffset = (pendingOffset + lastChunkSize).coerceAtMost(frame.size)
        if (pendingOffset >= frame.size) {
            // Done; clear pending frame.
            pendingFrame = null
            pendingOffset = 0
            sending = false
            return
        }
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
                isConnected = true
                cmdNotificationsEnabled = false
                cccdRetryAttempts = 0
                try {
                    gatt.requestMtu(517)
                } catch (_: Exception) {
                }
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                isConnected = false
                imageChar = null
                cmdChar = null
                cmdNotificationsEnabled = false
                cccdRetryAttempts = 0
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

            // Prefer dynamic matching: find a service containing both a writable char and a notify char.
            // Fallback to legacy fixed UUIDs if present.
            val legacySvc = gatt.getService(UUID_IMAGE_SERVICE)
            val legacyImage = legacySvc?.getCharacteristic(UUID_IMAGE_CHAR)
            val legacyCmd = legacySvc?.getCharacteristic(UUID_CMD_CHAR)

            val matched = findBestChannels(gatt)
            imageChar = matched?.first ?: legacyImage
            cmdChar = matched?.second ?: legacyCmd

            if (imageChar == null || cmdChar == null) {
                Log.w(TAG, "No suitable GATT channels found (imageChar=${imageChar != null}, cmdChar=${cmdChar != null})")
            }

            Log.i(
                TAG,
                "Services ready: imageChar=${imageChar != null} cmdChar=${cmdChar != null} connected=$isConnected"
            )

            val cc = cmdChar
            if (cc != null) {
                enableNotifications(gatt, cc)
                // Schedule a check to ensure CCCD took effect; retry if needed.
                scope.launch(Dispatchers.Main) {
                    delay(1000)
                    ensureCmdNotifications(gatt)
                }
            } else {
                Log.w(TAG, "CMD characteristic not found")
            }

            // If a frame was queued before discovery completed, resume sending now.
            if (pendingFrame != null && !sending && imageChar != null) {
                Log.i(TAG, "Resuming queued frame send after services discovered")
                sending = true
                sendNextPacket()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            // CCCD write result tells us whether notifications are really enabled.
            if (descriptor.uuid == UUID_CCCD) {
                val ok = status == BluetoothGatt.GATT_SUCCESS
                cmdNotificationsEnabled = ok
                Log.i(TAG, "CCCD write status=$status ok=$ok")
                if (!ok) {
                    ensureCmdNotifications(gatt)
                }
            } else {
                Log.d(TAG, "Descriptor write uuid=${descriptor.uuid} status=$status")
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
            Log.i(TAG, "Received BLE command from ESP32: '$cmd'")

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

        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(cccd, value)
            Log.i(TAG, "writeDescriptor(T) returned status=$status")
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                val started = gatt.writeDescriptor(cccd)
                Log.i(TAG, "writeDescriptor(legacy) started=$started")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureCmdNotifications(gatt: BluetoothGatt) {
        if (cmdNotificationsEnabled) return
        val cc = cmdChar ?: run {
            Log.w(TAG, "ensureCmdNotifications: cmdChar null")
            return
        }
        if (cccdRetryAttempts >= 3) {
            Log.w(TAG, "ensureCmdNotifications: max attempts reached")
            return
        }
        cccdRetryAttempts += 1
        Log.w(TAG, "Retry enabling CMD notifications attempt=$cccdRetryAttempts")
        enableNotifications(gatt, cc)
    }

    private fun findBestChannels(gatt: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val services = try {
            gatt.services
        } catch (_: Exception) {
            null
        } ?: return null

        var bestScore = Int.MIN_VALUE
        var best: Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? = null

        for (svc in services) {
            val chars = svc.characteristics ?: continue
            var bestWrite: BluetoothGattCharacteristic? = null
            var bestNotify: BluetoothGattCharacteristic? = null

            for (ch in chars) {
                val p = ch.properties
                val canWrite = (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                val canNotify = (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                    (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

                if (canWrite) {
                    // Prefer write-with-response for reliability
                    val score = if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) 2 else 1
                    val curScore = if (bestWrite == null) -1 else {
                        val bp = bestWrite!!.properties
                        if ((bp and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) 2 else 1
                    }
                    if (score > curScore) bestWrite = ch
                }

                if (canNotify) {
                    // Prefer notify over indicate (lower latency)
                    val score = if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) 2 else 1
                    val curScore = if (bestNotify == null) -1 else {
                        val bp = bestNotify!!.properties
                        if ((bp and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) 2 else 1
                    }
                    if (score > curScore) bestNotify = ch
                }
            }

            if (bestWrite != null && bestNotify != null) {
                var score = 0
                // Prefer custom 128-bit UUID service (usually random) over standard SIG services.
                if (svc.uuid.toString().length > 8) score += 5
                // Prefer having CCCD present on notify char.
                val hasCccd = bestNotify.getDescriptor(UUID_CCCD) != null
                if (hasCccd) score += 3
                // Prefer service that also matches legacy UUID (keeps compatibility).
                if (svc.uuid == UUID_IMAGE_SERVICE) score += 10

                if (score > bestScore) {
                    bestScore = score
                    best = bestWrite to bestNotify
                }
            }
        }

        if (best != null) {
            Log.i(TAG, "Dynamic channels selected: write=${best.first.uuid} notify=${best.second.uuid} score=$bestScore")
        }
        return best
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
