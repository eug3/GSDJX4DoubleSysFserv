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
    private var pageRenderer: PageRenderer? = null,
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

    // ========== 电子书读写管理 ==========
    // 当前书籍ID和已初始化标志
    private var currentBookId: Int = 0
    private var bookInitialized: Boolean = false

    // 待发送页码队列（手机根据电子书发来的页码决定发送哪些页）
    private val pagesToSendQueue = mutableSetOf<Int>()

    // 已发送的页码缓存（记录已经发送过的页面，避免重复发送）
    private val sentPages = mutableSetOf<Int>()

    // 页面发送同步锁
    private val pageSyncLock = Any()

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
        
        // 重置电子书状态
        synchronized(pageSyncLock) {
            currentBookId = 0
            bookInitialized = false
            pagesToSendQueue.clear()
            sentPages.clear()
        }
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

    /**
     * 发送原始1bit位图（直接从浏览器截图转换）
     * @param bitmapData 48000字节的1bit位图数据（480x800）
     */
    fun sendRawBitmap(bitmapData: ByteArray) {
        Log.d(TAG, "sendRawBitmap called: ${bitmapData.size} bytes")
        
        if (bitmapData.size != 48000) {
            Log.w(TAG, "Invalid bitmap size: ${bitmapData.size}, expected 48000")
            return
        }

        // 使用 X4IM 协议：
        // 0..3  : ASCII 'X4IM'
        // 4     : version = 1
        // 5..7  : reserved
        // 8..11 : payload length (uint32 LE)
        // 12+   : payload (1bit bitmap data)
        val header = ByteArray(12)
        header[0] = 'X'.code.toByte()
        header[1] = '4'.code.toByte()
        header[2] = 'I'.code.toByte()
        header[3] = 'M'.code.toByte()
        header[4] = 1  // version
        header[5] = 0
        header[6] = 0
        header[7] = 0
        
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        bb.putInt(bitmapData.size)
        
        val frame = ByteArray(header.size + bitmapData.size)
        System.arraycopy(header, 0, frame, 0, header.size)
        System.arraycopy(bitmapData, 0, frame, header.size, bitmapData.size)
        
        Log.d(TAG, "Prepared 1bit bitmap frame: header=${header.size} payload=${bitmapData.size} total=${frame.size}")

        pendingFrame = frame
        pendingOffset = 0
        if (!sending) {
            sending = true
            sendNextPacket()
        } else {
            Log.w(TAG, "Already sending, replaced pending bitmap frame")
        }
    }

    // ========== 电子书阅读功能 ==========

    /**
     * 初始化电子书阅读会话
     * @param bookId 书籍ID
     * @param pageRenderer 页面渲染器，用于生成页面位图
     */
    fun initializeBookReading(bookId: Int, renderer: PageRenderer) {
        pageRenderer = renderer
        synchronized(pageSyncLock) {
            currentBookId = bookId
            bookInitialized = false
            pagesToSendQueue.clear()
            sentPages.clear()
        }

        Log.i(TAG, "Book reading initialized: bookId=$bookId")
    }

    /**
     * 设置页面渲染器
     */
    fun setPageRenderer(renderer: PageRenderer) {
        pageRenderer = renderer
    }

    /**
     * 发送初始三页（在用户确认后调用）
     */
    fun sendInitialThreePages() {
        val bookId = synchronized(pageSyncLock) { currentBookId }
        if (bookId == 0 || pageRenderer == null) {
            Log.w(TAG, "Cannot send initial pages: book not initialized or renderer null")
            return
        }

        Log.i(TAG, "Sending initial three pages for book $bookId")

        // 发送第1、2、3页
        scope.launch(Dispatchers.Default) {
            for (pageNum in 1..3) {
                try {
                    val bitmap = pageRenderer!!.renderPage(pageNum)
                    if (bitmap != null) {
                        sendBitmap(bitmap)
                        synchronized(pageSyncLock) {
                            sentPages.add(pageNum)
                        }
                        // 等待前一页发送完成
                        delay(100)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to render page $pageNum", e)
                }
            }

            synchronized(pageSyncLock) {
                bookInitialized = true
            }
            Log.i(TAG, "Initial three pages sent successfully")
        }
    }

    /**
     * 处理电子书发来的页面变化通知
     * 格式: "PAGE:<page_num>" 表示电子书当前显示第几页
     * @param pageNum 当前显示的页码
     */
    fun handlePageChangeNotification(pageNum: Int) {
        Log.d(TAG, "Received page notification: $pageNum (triggering GeckoActivity to render and send)")
        
        // 直接通知调用者（通常是 GeckoActivity）发送该页面
        // GeckoActivity 会处理：页面跳转 -> DOM 提取 -> 渲染 -> 发送
        onCommand("PAGE:$pageNum")
    }

    /**
     * 发送队列中的页面
     */
    private fun sendQueuedPages() {
        val bookId = synchronized(pageSyncLock) { currentBookId }
        if (bookId == 0 || pageRenderer == null) return

        scope.launch(Dispatchers.Default) {
            while (true) {
                val pageToSend = synchronized(pageSyncLock) {
                    pagesToSendQueue.firstOrNull()?.also { pagesToSendQueue.remove(it) }
                }

                if (pageToSend == null) {
                    Log.d(TAG, "No more pages to send")
                    break
                }

                try {
                    Log.i(TAG, "Rendering and sending page $pageToSend...")
                    val bitmap = pageRenderer!!.renderPage(pageToSend)
                    if (bitmap != null) {
                        sendBitmap(bitmap)
                        synchronized(pageSyncLock) {
                            sentPages.add(pageToSend)
                        }
                        // 等待前一页发送完成
                        delay(100)
                    } else {
                        Log.w(TAG, "Failed to render page $pageToSend")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending page $pageToSend", e)
                }
            }
        }
    }

    /**
     * 清理过期的缓存页面记录
     * @param startPage 滑动窗口的起始页
     * @param endPage 滑动窗口的结束页
     */
    fun cleanupOldPages(startPage: Int, endPage: Int) {
        synchronized(pageSyncLock) {
            val pagesToRemove = sentPages.filter { it < startPage || it > endPage }
            sentPages.removeAll(pagesToRemove)
            Log.d(TAG, "Cleaned up pages: removed ${pagesToRemove.size} old pages, kept ${sentPages.size}")
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
            
            // Also try SPP UUIDs
            val sppSvc = gatt.getService(UUID_SPP_SERVICE)
            val sppChar = sppSvc?.getCharacteristic(UUID_SPP_CHAR)

            val matched = findBestChannels(gatt)
            imageChar = matched?.first ?: legacyImage ?: sppChar
            cmdChar = matched?.second ?: legacyCmd ?: sppChar

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
                // 处理页码同步消息
                if (cmd.startsWith("PAGE:")) {
                    try {
                        val pageNum = cmd.substring(5).toInt()
                        handlePageChangeNotification(pageNum)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse page number from: $cmd", e)
                    }
                } else {
                    // 处理其他命令
                    onCommand(cmd)
                }
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
                // Add support for ESP32 SPP service
                if (svc.uuid == UUID_SPP_SERVICE) score += 10

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
        // 对于电子书，转换为1bit黑白格式
        // 480x800像素 = 48000字节 (每字节8个像素)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 计算1bit格式所需的字节数
        val bytesPerRow = (w + 7) / 8  // 向上取整
        val totalBytes = bytesPerRow * h
        val out = ByteArray(totalBytes)
        
        Log.d(TAG, "Converting bitmap to 1bit: ${w}x${h} -> $totalBytes bytes")

        var byteIndex = 0
        var bitIndex = 0
        var currentByte: Byte = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = pixels[y * w + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // 转换为灰度值
                val gray = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                
                // 阈值化：灰度值 > 127 为白色(1)，否则为黑色(0)
                val bit = if (gray > 127) 1 else 0
                
                // 设置对应的位（MSB优先）
                currentByte = (currentByte.toInt() or (bit shl (7 - bitIndex))).toByte()
                
                bitIndex++
                if (bitIndex == 8) {
                    out[byteIndex++] = currentByte
                    currentByte = 0
                    bitIndex = 0
                }
            }
            
            // 每行结束后，如果有未完成的字节，写入并重置
            if (bitIndex != 0) {
                out[byteIndex++] = currentByte
                currentByte = 0
                bitIndex = 0
            }
        }
        
        Log.d(TAG, "Conversion complete: $byteIndex bytes written")
        return out
    }

    private companion object {
        private const val TAG = "BleEspClient"

        // Legacy custom UUIDs
        private val UUID_IMAGE_SERVICE: UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
        private val UUID_IMAGE_CHAR: UUID = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb")
        private val UUID_CMD_CHAR: UUID = UUID.fromString("00005679-0000-1000-8000-00805f9b34fb")
        
        // SPP (Serial Port Profile) UUIDs - used by ESP32 BLE server
        // Service: 0xABF0, Characteristic: 0xABF1
        private val UUID_SPP_SERVICE: UUID = UUID.fromString("0000ABF0-0000-1000-8000-00805f9b34fb")
        private val UUID_SPP_CHAR: UUID = UUID.fromString("0000ABF1-0000-1000-8000-00805f9b34fb")
        
        private val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    }
}
/**
 * 页面渲染器接口
 * 由电子书应用实现，用于按需渲染指定页面为位图
 */
interface PageRenderer {
    /**
     * 渲染指定页面为位图
     * @param pageNum 页码（1-based）
     * @return 渲染后的位图，失败返回null
     */
    suspend fun renderPage(pageNum: Int): Bitmap?
}