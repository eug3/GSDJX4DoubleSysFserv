package com.guaishoudejia.x4doublesysfserv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

class GeckoActivity : ComponentActivity() {
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank {
            DEFAULT_URL
        }

        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .build()

        runtime = GeckoRuntime.create(this)
        session = GeckoSession(settings).apply {
            open(runtime!!)
        }

        setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    org.mozilla.geckoview.GeckoView(context).apply {
                        this@GeckoActivity.session?.let { setSession(it) }
                    }
                },
                update = {
                    // no-op
                }
            )
        }

        session?.loadUri(targetUrl)
    }

    override fun onStart() {
        super.onStart()
        session?.setActive(true)
    }

    override fun onStop() {
        session?.setActive(false)
        super.onStop()
    }

    override fun onDestroy() {
        try {
            session?.close()
            runtime?.shutdown()
        } catch (e: Exception) {
            Log.w("Gecko", "Error shutting down", e)
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val DEFAULT_URL = "https://weread.qq.com/"

        fun launch(context: Context, url: String) {
            val intent = Intent(context, GeckoActivity::class.java)
            intent.putExtra(EXTRA_URL, url)
            context.startActivity(intent)
        }
    }
}
