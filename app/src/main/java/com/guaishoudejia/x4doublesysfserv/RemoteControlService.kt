package com.guaishoudejia.x4doublesysfserv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class RemoteControlService : Service() {
    companion object {
        private const val CHANNEL_ID = "remote_control"
        private const val NOTIF_ID = 2001
        const val ACTION_START = "remote.start"
        const val ACTION_SEND_STATUS = "remote.send_status"
        const val ACTION_SEND_IMAGE = "remote.send_image"
        const val ACTION_COMMAND = "remote.command_broadcast"
        const val EXTRA_ACTION = "extra_action"
        const val EXTRA_DETAIL = "extra_detail"
        const val EXTRA_BASE64 = "extra_base64"
        const val EXTRA_MIME = "extra_mime"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_DEVICE_ID = "extra_device_id"

        fun start(context: Context, host: String, deviceId: String) {
            val i = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_DEVICE_ID, deviceId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteControlService::class.java))
        }

        fun sendStatus(context: Context, status: String, detail: String = "") {
            val i = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_SEND_STATUS
                putExtra(EXTRA_ACTION, status)
                putExtra(EXTRA_DETAIL, detail)
            }
            context.startService(i)
        }

        fun sendImage(context: Context, base64: String, mime: String = "image/png") {
            val i = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_SEND_IMAGE
                putExtra(EXTRA_BASE64, base64)
                putExtra(EXTRA_MIME, mime)
            }
            context.startService(i)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var client: RemoteControlClient? = null
    private var deviceId: String = RemoteControlClient.DEFAULT_DEVICE_ID

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        acquireWakeLock()
    }

    override fun onDestroy() {
        client?.disconnect()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { RemoteControlClient.EMULATOR_HOST }
                deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty().ifBlank { RemoteControlClient.DEFAULT_DEVICE_ID }
                val url = "ws://$host"
                Log.d("RemoteSvc", "start remote ws $url id=$deviceId")
                client?.disconnect()
                client = RemoteControlClient(deviceId, url) { action ->
                    val bc = Intent(ACTION_COMMAND)
                    bc.putExtra(EXTRA_ACTION, action)
                    sendBroadcast(bc)
                }
                client?.connect()
            }
            ACTION_SEND_STATUS -> {
                val status = intent.getStringExtra(EXTRA_ACTION).orEmpty()
                val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
                client?.sendStatus(status, detail)
            }
            ACTION_SEND_IMAGE -> {
                val b64 = intent.getStringExtra(EXTRA_BASE64)
                val mime = intent.getStringExtra(EXTRA_MIME) ?: "image/png"
                if (!b64.isNullOrEmpty()) {
                    try {
                        val json = JSONObject().apply {
                            put("type", "image")
                            put("deviceId", deviceId)
                            put("data", b64)
                            put("mime", mime)
                        }
                        client?.rawSend(json.toString())
                    } catch (e: Exception) {
                        Log.w("RemoteSvc", "send image json err", e)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "RemoteControl", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
            val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Remote control active")
                .setContentText("Listening for commands")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .build()
            startForeground(NOTIF_ID, n)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "x4:remoteService").apply {
            setReferenceCounted(false)
            try { acquire(60 * 60 * 1000L) } catch (_: Exception) {}
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }
}
