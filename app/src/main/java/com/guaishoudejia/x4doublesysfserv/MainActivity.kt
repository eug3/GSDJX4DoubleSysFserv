package com.guaishoudejia.x4doublesysfserv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.guaishoudejia.x4doublesysfserv.ui.theme.GSDJX4DoubleSysFservTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"

        private const val EMULATOR_UPLOAD_URL = "http://10.0.2.2:18080/image"
        private const val DEVICE_UPLOAD_URL = "http://192.168.31.105:18080/image"

        private fun isEmulator(): Boolean {
            val fp = Build.FINGERPRINT
            return fp.contains("generic") || fp.contains("emulator") || fp.contains("sdk_gphone")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            // Permissions granted, ensure battery optimization exemption then ask for screen capture
            Log.i(TAG, "Runtime permissions granted: ${permissions.keys.joinToString()}")
            ensureBatteryOptimizationExemptThenCapture()
        } else {
            // Handle the case where the user denies the permissions
            // You might want to show a message to the user
            val denied = permissions.filterValues { !it }.keys
            Log.w(TAG, "Runtime permissions denied: ${denied.joinToString()}")
        }
    }

    private val batteryOptLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Regardless of user choice, proceed to start service.
        Log.i(TAG, "Returned from battery optimization settings; starting service")
        startBleService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GSDJX4DoubleSysFservTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding),
                        onStartClick = { requestBlePermissions() }
                    )
                }
            }
        }
    }

    private fun requestBlePermissions() {
        Log.i(TAG, "Start button clicked; requesting BLE/runtime permissions")
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsNotGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNotGranted.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: ${permissionsNotGranted.joinToString()}")
            requestPermissionLauncher.launch(permissionsNotGranted.toTypedArray())
        } else {
            Log.i(TAG, "All runtime permissions already granted")
            ensureBatteryOptimizationExemptThenCapture()
        }
    }

    private fun ensureBatteryOptimizationExemptThenCapture() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        if (ignoring) {
            Log.i(TAG, "Battery optimizations already ignored; starting service")
            startBleService()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        if (intent.resolveActivity(packageManager) == null) {
            Log.w(TAG, "Battery optimization request activity not found; starting service directly")
            startBleService()
            return
        }
        batteryOptLauncher.launch(intent)
    }

    private fun startBleService() {
        val uploadUrl = if (isEmulator()) EMULATOR_UPLOAD_URL else DEVICE_UPLOAD_URL
        Log.i(TAG, "Starting X4Service with uploadUrl=$uploadUrl")
        val intent = Intent(this, X4Service::class.java).apply {
            putExtra(X4Service.EXTRA_UPLOAD_URL, uploadUrl)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier, onStartClick: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Hello $name!")
        Button(onClick = onStartClick) {
            Text("Start Service")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    GSDJX4DoubleSysFservTheme {
        Greeting("Android", onStartClick = {})
    }
}