package com.guaishoudejia.x4doublesysfserv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.guaishoudejia.x4doublesysfserv.ble.BleDeviceManager
import com.guaishoudejia.x4doublesysfserv.ui.components.BleDeviceItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BLE 连接管理器 - 处理设备扫描、连接和状态管理
 */
class BleConnectionManager(
    private val context: Context,
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private val deviceManager = BleDeviceManager(context)
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    // 状态变量
    var isConnected by mutableStateOf(false)
    var connectedDeviceName by mutableStateOf("")
    var connectedDeviceAddress by mutableStateOf<String?>(null)
    var isScanning by mutableStateOf(false)
    var showScanSheet by mutableStateOf(false)
    val scannedDevices = mutableStateListOf<BleDeviceItem>()

    private var bleClient: BleEspClientOptimized? = null
    private var scanCallback: ScanCallback? = null

    companion object {
        private const val TAG = "BleConnectionManager"
    }

    /**
     * 尝试连接到已保存的设备
     */
    fun tryAutoConnect(onConnected: (BleEspClientOptimized) -> Unit = {}) {
        val savedAddress = deviceManager.getSavedDeviceAddress()
        if (savedAddress != null) {
            Log.d(TAG, "尝试自动连接到已保存设备: $savedAddress")
            connectToDevice(
                savedAddress,
                deviceManager.getSavedDeviceName(),
                onConnected = onConnected
            )
        }
    }

    /**
     * 连接到指定设备
     */
    fun connectToDevice(
        address: String,
        name: String,
        onConnected: (BleEspClientOptimized) -> Unit = {}
    ) {
        Log.d(TAG, "连接到设备: $address ($name)")
        
        // 保存设备信息
        deviceManager.saveDevice(address, name)
        connectedDeviceAddress = address
        connectedDeviceName = name

        // 创建 BLE 客户端
        bleClient = BleEspClientOptimized(
            context = context,
            deviceAddress = address,
            scope = lifecycleScope,
            onCommand = { cmd ->
                Log.d(TAG, "收到命令: $cmd")
            },
            onStatusChanged = { status ->
                Log.d(TAG, "BLE 连接状态: $status")
                when (status) {
                    BleEspClientOptimized.ConnectionStatus.CONNECTED,
                    BleEspClientOptimized.ConnectionStatus.READY -> {
                        isConnected = true
                        onConnected(bleClient!!)
                    }
                    BleEspClientOptimized.ConnectionStatus.DISCONNECTED,
                    BleEspClientOptimized.ConnectionStatus.ERROR -> {
                        isConnected = false
                    }
                    else -> {}
                }
            }
        ).apply { 
            connect() 
        }
    }

    /**
     * 开始扫描设备
     */
    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (isScanning) return
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth 未启用")
            return
        }

        Log.d(TAG, "开始扫描 BLE 设备")
        isScanning = true
        scannedDevices.clear()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val deviceName = result.device.name
                val deviceAddress = result.device.address
                
                if (!deviceName.isNullOrBlank() && deviceAddress != null) {
                    // 检查是否已存在
                    if (scannedDevices.none { it.address == deviceAddress }) {
                        scannedDevices.add(
                            BleDeviceItem(
                                name = deviceName,
                                address = deviceAddress,
                                rssi = result.rssi
                            )
                        )
                        Log.d(TAG, "扫描到设备: $deviceName ($deviceAddress) RSSI: ${result.rssi}")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "扫描失败，错误码: $errorCode")
                isScanning = false
            }
        }

        bleScanner?.startScan(scanCallback)

        // 10秒后自动停止扫描
        lifecycleScope.launch {
            delay(10000)
            stopScanning()
        }
    }

    /**
     * 停止扫描
     */
    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) return
        Log.d(TAG, "停止扫描，扫描到 ${scannedDevices.size} 个设备")
        scanCallback?.let { bleScanner?.stopScan(it) }
        isScanning = false
    }

    /**
     * 忘记已保存的设备
     */
    fun forgetDevice() {
        Log.d(TAG, "忘记设备: $connectedDeviceAddress")
        disconnect()
        deviceManager.forgetDevice()
        isConnected = false
        connectedDeviceAddress = null
        connectedDeviceName = ""
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        Log.d(TAG, "断开连接")
        bleClient?.close()
        bleClient = null
        isConnected = false
    }

    /**
     * 获取 BLE 客户端（用于发送数据）
     */
    fun getBleClient(): BleEspClientOptimized? {
        return bleClient
    }

    /**
     * 检查权限
     */
    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 请求权限
     */
    fun requestPermissions(callback: (Boolean) -> Unit) {
        // 由于 registerForActivityResult 只能在 Activity/Fragment 中调用，
        // 权限请求应该在调用处理
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 需要请求权限，由调用方处理
            callback(false)
        } else {
            // Android 11 及更早版本不需要运行时权限
            callback(true)
        }
    }
}
