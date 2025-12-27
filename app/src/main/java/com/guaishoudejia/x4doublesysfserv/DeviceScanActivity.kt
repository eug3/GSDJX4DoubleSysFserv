package com.guaishoudejia.x4doublesysfserv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@SuppressLint("MissingPermission") // Permissions are requested at runtime
class DeviceScanActivity : ComponentActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }
    private var isScanning by mutableStateOf(false)
    private val scanResults = mutableStateListOf<ScanResult>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Ensure devices are not duplicated in the list
            if (scanResults.none { it.device.address == result.device.address }) {
                scanResults.add(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("DeviceScanActivity", "Scan failed with error $errorCode")
        }
    }
    
    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            startBleScan()
        } else {
            // Handle permission denial by finishing the activity
            finish()
        }
    }
    
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            // User did not enable Bluetooth
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DeviceScanScreen(
                    isScanning = isScanning,
                    scanResults = scanResults,
                    onDeviceSelected = { deviceAddress ->
                        bleScanner?.stopScan(scanCallback)
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onScanClicked = {
                        if (isScanning) stopBleScan() else checkPermissionsAndScan()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            checkPermissionsAndScan()
        }
    }

    override fun onPause() {
        super.onPause()
        stopBleScan()
    }

    private fun checkPermissionsAndScan() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            startBleScan()
        }
    }

    private fun startBleScan() {
        scanResults.clear()
        isScanning = true
        bleScanner?.startScan(scanCallback)
    }

    private fun stopBleScan() {
        isScanning = false
        bleScanner?.stopScan(scanCallback)
    }

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScanScreen(
    isScanning: Boolean,
    scanResults: List<ScanResult>,
    onDeviceSelected: (String) -> Unit,
    onScanClicked: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan for Devices") },
                actions = {
                    Button(onClick = onScanClicked) {
                        Text(if (isScanning) "Stop Scan" else "Scan")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.padding(paddingValues)) {
            items(scanResults.sortedByDescending { it.rssi }) { result ->
                DeviceItem(result = result, onDeviceSelected = onDeviceSelected)
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceItem(result: ScanResult, onDeviceSelected: (String) -> Unit) {
    val context = LocalContext.current
    val hasConnectPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed for older versions
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDeviceSelected(result.device.address) }
            .padding(16.dp)
    ) {
        Text(
            text = if (hasConnectPermission) result.device.name ?: "Unnamed Device" else "Name hidden (permission)",
            style = MaterialTheme.typography.titleMedium
        )
        Text(text = result.device.address, style = MaterialTheme.typography.bodyMedium)
        Text(text = "RSSI: ${result.rssi}", style = MaterialTheme.typography.bodySmall)
    }
}
