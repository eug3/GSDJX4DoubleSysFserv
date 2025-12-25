package com.guaishoudejia.x4doublesysfserv

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * WebSocket client to connect device to remote control server.
 * Receives commands (prev/next/capture) and sends back status/images.
 */
class RemoteControlClient(
    private val deviceId: String,
    private val serverUrl: String,
    private val onCommand: (action: String) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var shouldReconnect = false
    private var reconnectDelayMs = 5_000L
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun connect() {
        shouldReconnect = true
        val url = "$serverUrl/ws?role=device&id=$deviceId"
        val request = Request.Builder().url(url).build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("RemoteControl", "WebSocket connected")
                sendStatus("connected", "device ready")
                reconnectDelayMs = 5_000L
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    if (type == "command") {
                        val action = json.optString("action")
                        Log.d("RemoteControl", "received command: $action")
                        onCommand(action)
                    }
                } catch (e: Exception) {
                    Log.e("RemoteControl", "parse message error", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("RemoteControl", "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("RemoteControl", "WebSocket closed")
                if (shouldReconnect) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("RemoteControl", "WebSocket error", t)
                if (shouldReconnect) scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun scheduleReconnect() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (shouldReconnect) {
                Log.d("RemoteControl", "Reconnecting WS...")
                connect()
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
            }
        }, reconnectDelayMs)
    }

    fun sendStatus(status: String, detail: String = "") {
        val json = JSONObject().apply {
            put("type", "status")
            put("deviceId", deviceId)
            put("status", status)
            if (detail.isNotEmpty()) put("detail", detail)
        }
        webSocket?.send(json.toString())
    }

    fun rawSend(payload: String) {
        webSocket?.send(payload)
    }

    fun sendImage(bitmap: Bitmap) {
        try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            
            val json = JSONObject().apply {
                put("type", "image")
                put("deviceId", deviceId)
                put("data", base64)
                put("mime", "image/png")
            }
            webSocket?.send(json.toString())
            Log.d("RemoteControl", "image sent: ${bytes.size} bytes")
        } catch (e: Exception) {
            Log.e("RemoteControl", "send image error", e)
        }
    }

    companion object {
        const val EMULATOR_HOST = "10.0.2.2:18081"
        const val DEFAULT_DEVICE_ID = "x4-001"
        
        fun getServerUrl(useEmulator: Boolean, customHost: String = ""): String {
            val host = if (useEmulator) {
                EMULATOR_HOST
            } else {
                customHost.ifEmpty { EMULATOR_HOST }
            }
            return "ws://$host"
        }
    }
}
