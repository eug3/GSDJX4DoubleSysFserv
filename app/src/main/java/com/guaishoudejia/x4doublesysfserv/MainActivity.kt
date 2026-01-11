package com.guaishoudejia.x4doublesysfserv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 直接进入阅读视图，设备选择改为在阅读界面的左侧浮动按钮手动触发
        GeckoActivity.launch(this, "https://weread.qq.com/", null)
        // 关闭当前启动页，避免返回时再看到它
        finish()
    }
}
