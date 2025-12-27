package com.guaishoudejia.x4doublesysfserv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import com.guaishoudejia.x4doublesysfserv.ui.theme.GSDJX4DoubleSysFservTheme

class MainActivity : ComponentActivity() {

    private val deviceScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(DeviceScanActivity.EXTRA_DEVICE_ADDRESS)?.let {
                GeckoActivity.launch(this, "https://weread.qq.com/", it)
            }
        }
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
                        onStartClick = { launchDeviceScan() }
                    )
                }
            }
        }
    }

    private fun launchDeviceScan() {
        val intent = Intent(this, DeviceScanActivity::class.java)
        deviceScanLauncher.launch(intent)
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
            Text("Select Device and Start")
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
