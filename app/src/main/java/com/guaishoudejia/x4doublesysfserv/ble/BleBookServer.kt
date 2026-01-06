package com.guaishoudejia.x4doublesysfserv.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Android acts as BLE peripheral (GATT server).
 * - Uses dynamic service UUID (advertised)
 * - Exposes a single IO characteristic with WRITE(+noRsp) and NOTIFY
 * Device (ESP32) can scan, connect, discover advertised service UUID, then pick the IO characteristic.
 */
class BleBookServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onRequest: suspend (BleBookProtocol.Request) -> Unit,
) {
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private val serviceUuid: UUID = UUID.randomUUID()
    private val ioCharUuid: UUID = UUID.randomUUID()

    private var ioChar: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null

    private val notifyAcks = ConcurrentHashMap<BluetoothDevice, Channel<Unit>>()

    fun getServiceUuid(): UUID = serviceUuid
    fun getIoCharacteristicUuid(): UUID = ioCharUuid
    fun getConnectedDevice(): BluetoothDevice? = connectedDevice

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btMgr?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter unavailable")
            return false
        }

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BLE advertiser not available")
            return false
        }

        gattServer = btMgr.openGattServer(context, gattCallback)
        if (gattServer == null) {
            Log.w(TAG, "Failed to open GATT server")
            return false
        }

        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val c = BluetoothGattCharacteristic(
            ioCharUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val cccd = BluetoothGattDescriptor(
            UUID_CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        c.addDescriptor(cccd)

        service.addCharacteristic(c)
        ioChar = c

        val added = gattServer!!.addService(service)
        Log.i(TAG, "GATT service added=$added serviceUuid=$serviceUuid ioCharUuid=$ioCharUuid")

        startAdvertising()
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        advertiser = null

        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        ioChar = null
        connectedDevice = null
        notifyAcks.values.forEach { it.close() }
        notifyAcks.clear()
    }

    @SuppressLint("MissingPermission")
    suspend fun notify(device: BluetoothDevice, value: ByteArray): Boolean {
        val gs = gattServer ?: return false
        val ch = ioChar ?: return false

        val ackCh = notifyAcks.getOrPut(device) { Channel(capacity = Channel.RENDEZVOUS) }

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gs.notifyCharacteristicChanged(device, ch, false, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.value = value
                gs.notifyCharacteristicChanged(device, ch, false)
            }
        }

        if (!ok) return false

        // Throttle on notification callback to avoid overrunning the link.
        // Some stacks may not reliably invoke onNotificationSent; use a timeout fail-safe.
        return withContext(Dispatchers.IO) {
            try {
                withTimeout(2000) {
                    ackCh.receive()
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun startAdvertising() {
        val adv = advertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()

        try {
            adv.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "startAdvertising failed", e)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started serviceUuid=$serviceUuid")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising failed error=$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "GATT conn change status=$status state=$newState dev=${device.address}")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectedDevice = device
                notifyAcks.putIfAbsent(device, Channel(Channel.RENDEZVOUS))
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connectedDevice = null
                notifyAcks.remove(device)?.close()
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val gs = gattServer ?: return
            if (responseNeeded) {
                gs.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            Log.i(TAG, "CCCD write ${descriptor.uuid} value=${value.joinToString(",") { (it.toInt() and 0xFF).toString() }}")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val gs = gattServer ?: return
            if (responseNeeded) {
                gs.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            val req = BleBookProtocol.parseRequest(value)
            if (req == null) {
                Log.w(TAG, "Unknown write (len=${value.size})")
                return
            }

            Log.i(TAG, "Request book=${req.bookId} start=${req.startPage} count=${req.pageCount}")
            scope.launch(Dispatchers.Default) {
                try {
                    onRequest(req)
                } catch (e: Exception) {
                    Log.w(TAG, "onRequest failed", e)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            // Unblock sender.
            val ch = notifyAcks[device] ?: return
            ch.trySend(Unit)
        }
    }

    companion object {
        private const val TAG = "BleBookServer"
        private val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
