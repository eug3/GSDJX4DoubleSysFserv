package com.guaishoudejia.x4doublesysfserv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, WeReadActivity::class.java)
        startActivity(intent)
        finish()
    }
}
