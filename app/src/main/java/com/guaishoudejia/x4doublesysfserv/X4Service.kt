
package com.guaishoudejia.x4doublesysfserv

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.LinkedList
import java.util.Queue
import java.util.UUID

@SuppressLint("MissingPermission")
class X4Service : Service() {

    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var imageQueue: Queue<ByteArray> = LinkedList()
    private var isSending = false
    private var currentMtu = 20 // Default GATT MTU payload size (23-3)

    private var webView: WebView? = null
    private var webViewLoaded = false
    private var lastLoadedUrl: String? = null
    private var webViewLoadFinishedAtMs: Long = 0L

    @Volatile private var targetUrl: String = DEFAULT_TARGET_URL

    private fun loadTargetUrlFromPrefs(): String? {
        return try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY_TARGET_URL, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun persistTargetUrl(url: String) {
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_TARGET_URL, url)
                .apply()
        } catch (_: Throwable) {
        }
    }

        // 默认做一点放大（字更大），更适合 480x800 的电子墨水屏。
        // 可通过 WebSocket 命令 ZOOM 调整。
        @Volatile private var contentScale: Float = 1.2f

        private fun scaleToPercent(scale: Float): Int = (scale * 100f).toInt().coerceIn(20, 300)

        private fun applyScaleToWebView(wv: WebView) {
                val p = scaleToPercent(contentScale)
                try {
                        wv.settings.textZoom = p
                } catch (_: Throwable) {
                }
                try {
                        wv.setInitialScale(p)
                } catch (_: Throwable) {
                }

                // Also enforce via viewport + CSS zoom (some sites ignore one of these).
                try {
                        val scaleStr = String.format(java.util.Locale.US, "%.2f", contentScale)
                        val js = """
                                (function() {
                                    try {
                                        var head = document.head || document.getElementsByTagName('head')[0];
                                        if (!head) return 'nohead';
                                        var meta = document.querySelector('meta[name=viewport]');
                                        if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; head.appendChild(meta); }
                                        meta.content = 'width=${TARGET_WIDTH}, height=${TARGET_HEIGHT}, initial-scale=${scaleStr}, maximum-scale=${scaleStr}, user-scalable=no, viewport-fit=cover';
                                        document.documentElement.style.margin = '0';
                                        if (document.body) document.body.style.margin = '0';
                                        document.documentElement.style.zoom = '${scaleStr}';
                                        if (document.body) document.body.style.zoom = '${scaleStr}';
                                        return 'ok';
                                    } catch (e) {
                                        return 'err:' + e;
                                    }
                                })();
                        """.trimIndent()
                        wv.evaluateJavascript(js, null)
                } catch (_: Throwable) {
                }
        }

        private fun cleanupWeReadUi(wv: WebView) {
                // Best-effort cleanup: remove fixed/sticky bars and the "open app" promo that steals vertical space.
                try {
                        val js = """
                                (function(){
                                    try {
                                        function hide(el){ try{ el.style.setProperty('display','none','important'); el.style.setProperty('visibility','hidden','important'); }catch(e){} }
                                        // Remove obvious promo text blocks
                                        var walkers = document.querySelectorAll('body *');
                                        for (var i=0;i<walkers.length;i++){
                                            var el = walkers[i];
                                            if (!el || !el.innerText) continue;
                                            var t = el.innerText;
                                            if (t.indexOf('微信读书App')>=0 || t.indexOf('打开微信读书')>=0 || t.indexOf('添加微信读书')>=0){
                                                hide(el);
                                            }
                                        }
                                        // Hide fixed/sticky overlays near top/bottom
                                        var all = document.querySelectorAll('body *');
                                        var vh = Math.max(document.documentElement.clientHeight || 0, window.innerHeight || 0);
                                        for (var j=0;j<all.length;j++){
                                            var e = all[j];
                                            if (!e) continue;
                                            var cs = window.getComputedStyle(e);
                                            if (!cs) continue;
                                            if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
                                            var r = e.getBoundingClientRect();
                                            if (!r) continue;
                                            // only large overlays
                                            if (r.height < 36) continue;
                                            // near top or bottom
                                            if (r.top < 120 || (vh - r.bottom) < 120) {
                                                hide(e);
                                            }
                                        }
                                        // Try to remove extra paddings
                                        document.documentElement.style.padding = '0';
                                        if (document.body) document.body.style.padding = '0';
                                        return 'ok';
                                    }catch(e){return 'err:'+e;}
                                })();
                        """.trimIndent()
                        wv.evaluateJavascript(js, null)
                } catch (_: Throwable) {
                }
        }

    private val httpClient: OkHttpClient by lazy { OkHttpClient() }
    private var webSocket: WebSocket? = null
    private var wsUrl: String? = null
    private var wsReconnectScheduled = false

    private var enableBle: Boolean = false
    private val wsPingRunnable = object : Runnable {
        override fun run() {
            val ws = webSocket
            if (ws == null) return
            try {
                ws.send("PING")
            } catch (_: Throwable) {
            }
            handler.postDelayed(this, 10_000)
        }
    }

        private fun isWeReadBookLandingUrl(url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                // Book landing page: /web/reader/<bookId> (no chapter suffix starting with 'k')
                val prefix = "https://weread.qq.com/web/reader/"
                if (!url.startsWith(prefix)) return false
                val tail = url.removePrefix(prefix)
                return tail.isNotBlank() && !tail.contains("k") && tail.length >= 20
        }

        private fun tryEnterWeReadReading(wv: WebView) {
                // Best-effort: click "阅读" or "下一页" on landing page to enter the real reader view.
                try {
                        val js = """
                                (function(){
                                    try {
                                        function clickByText(txt){
                                            var els = document.querySelectorAll('button,a,div,span');
                                            for (var i=0;i<els.length;i++){
                                                var el = els[i];
                                                if (!el) continue;
                                                var t = (el.innerText||'').trim();
                                                if (t === txt){
                                                    try { el.click(); return 'clicked:'+txt; } catch(e) {}
                                                }
                                            }
                                            return 'no:'+txt;
                                        }
                                        // prefer explicit "阅读" (book landing), fallback to next page.
                                        var r = clickByText('阅读');
                                        if (r.indexOf('clicked') === 0) return r;
                                        r = clickByText('开始阅读');
                                        if (r.indexOf('clicked') === 0) return r;
                                        r = clickByText('下一页');
                                        return r;
                                    }catch(e){return 'err:'+e;}
                                })();
                        """.trimIndent()
                        wv.evaluateJavascript(js, null)
                } catch (_: Throwable) {
                }
        }

        private fun clickWeReadNav(wv: WebView, label: String) {
                // Click the in-page navigation buttons "上一页" / "下一页".
                try {
                        val js = """
                                (function(){
                                    try {
                                        var btns = document.querySelectorAll('button,a');
                                        for (var i=0;i<btns.length;i++){
                                            var b = btns[i];
                                            if (!b) continue;
                                            var t = (b.innerText||'').trim();
                                            if (t === '${label}') { b.click(); return 'clicked:${label}'; }
                                        }
                                        // fallback: text search
                                        var els = document.querySelectorAll('body *');
                                        for (var j=0;j<els.length;j++){
                                            var e = els[j];
                                            var tt = (e && e.innerText) ? e.innerText.trim() : '';
                                            if (tt === '${label}') { try { e.click(); return 'clicked2:${label}'; } catch(ex) {} }
                                        }
                                        return 'notfound:${label}';
                                    }catch(e){return 'err:'+e;}
                                })();
                        """.trimIndent()
                        wv.evaluateJavascript(js, null)
                } catch (_: Throwable) {
                }
        }

    private var isBleReady = false

    private val queueLock = Any()
    private var lastSentAtMs: Long = 0
    private var lastFrameCrc: Long = -1

    @Volatile
    private var uploadUrl: String? = null
    private val uploadExecutor = Executors.newSingleThreadExecutor()

    private val bluetoothAdapter: BluetoothAdapter? by lazy { (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    private val bluetoothLeScanner: BluetoothLeScanner? by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let {
                if (it.name == PERIPHERAL_NAME) {
                    Log.d(TAG, "Found target peripheral: ${it.address}")
                    bluetoothLeScanner?.stopScan(this)
                    isScanning = false
                    connectToDevice(it)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan Failed: $errorCode")
            isScanning = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server.")
                    isBleReady = false
                    gatt.requestMtu(512) // Request larger MTU for faster transfer
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server.")
                    isBleReady = false
                    currentMtu = 20 // Reset MTU on disconnect
                    handler.post { startBleScan() } // Restart scan on disconnect
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU changed to $mtu, discovering services...")
                currentMtu = mtu - 3 // Storing the new payload size
                gatt.discoverServices()
            } else {
                Log.w(TAG, "MTU change failed, discovering services with default MTU...")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered.")
                isBleReady = true
                enableCommandNotifications(gatt)
                // If frames were already queued before BLE became ready, start flushing now.
                sendNextPacket()
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristicChanged(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(characteristic.uuid, value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                sendNextPacket()
            } else {
                Log.e(TAG, "Characteristic write failed: $status")
                isSending = false
            }
        }
    }

    private fun enableCommandNotifications(gatt: BluetoothGatt) {
        try {
            val svc = gatt.getService(SERVICE_UUID)
            val ch = svc?.getCharacteristic(CHARACTERISTIC_UUID_CMD)
            if (ch == null) {
                Log.w(TAG, "Command characteristic not found: $CHARACTERISTIC_UUID_CMD")
                return
            }

            val ok = gatt.setCharacteristicNotification(ch, true)
            if (!ok) {
                Log.w(TAG, "setCharacteristicNotification returned false")
            }

            val cccd = ch.getDescriptor(UUID_CCCD)
            if (cccd == null) {
                Log.w(TAG, "CCCD descriptor not found on command characteristic")
                return
            }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val wrote = gatt.writeDescriptor(cccd)
            Log.i(TAG, "Enable CMD notify: setNotification=$ok writeCccd=$wrote")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enable command notifications", t)
        }
    }

    private fun handleCharacteristicChanged(uuid: UUID, value: ByteArray?) {
        if (uuid != CHARACTERISTIC_UUID_CMD) return
        val bytes = value ?: return
        if (bytes.isEmpty()) return

        val b = bytes[0].toInt() and 0xFF
        val cmd = when (b) {
            // also accept ASCII '1'..'4'
            0x31 -> 1
            0x32 -> 2
            0x33 -> 3
            0x34 -> 4
            else -> b
        }

        Log.i(TAG, "CMD notify value=0x${b.toString(16)} cmd=$cmd")
        handler.post {
            applyWebCommand(cmd)
        }
    }

    private fun applyWebCommand(cmd: Int) {
        ensureWebViewStarted()
        val wv = webView
        if (wv == null) {
            Log.w(TAG, "applyWebCommand: WebView is null")
            return
        }

        when (cmd) {
            CMD_OPEN -> {
                ensureWebViewUrlLoaded(targetUrl)
            }
            CMD_NEXT -> {
                // 向下翻/滚动
                wv.evaluateJavascript(
                    "(function(){var el=document.scrollingElement||document.documentElement||document.body; if(el){el.scrollBy(0, 640);} return el?el.scrollTop:0;})()",
                    null
                )
            }
            CMD_PREV -> {
                // 向上翻/滚动
                wv.evaluateJavascript(
                    "(function(){var el=document.scrollingElement||document.documentElement||document.body; if(el){el.scrollBy(0, -640);} return el?el.scrollTop:0;})()",
                    null
                )
            }
            CMD_RELOAD -> {
                wv.reload()
            }
            else -> {
                Log.w(TAG, "Unknown cmd=$cmd (expected 1..4)")
            }
        }

        // 以后如果你恢复成“按需抓图/发送”，这里可用 dirty 来触发立即刷新。
        FrameSyncState.markDirty()
    }

    private val renderRunnable = object : Runnable {
        override fun run() {
            // Keep the render pipeline alive; captures are done here (1Hz watchdog).
            ensureWebViewStarted()
            ensureWebViewUrlLoaded(targetUrl)
            maybeCaptureAndSend()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()

        Log.i(TAG, "Default contentScale=$contentScale")

        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.w(TAG, "Service restarted with null intent")
            return START_STICKY
        }

        Log.i(TAG, "onStartCommand action=${intent.action} startId=$startId")

        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        uploadUrl = intent.getStringExtra(EXTRA_UPLOAD_URL)
        if (uploadUrl.isNullOrBlank()) {
            uploadUrl = if (isEmulator()) EMULATOR_UPLOAD_URL else DEVICE_UPLOAD_URL
            Log.i(TAG, "uploadUrl missing; defaulting to $uploadUrl")
        }

        wsUrl = intent.getStringExtra(EXTRA_WS_URL)
        if (wsUrl.isNullOrBlank()) {
            wsUrl = if (isEmulator()) EMULATOR_WS_URL else DEVICE_WS_URL
            Log.i(TAG, "wsUrl missing; defaulting to $wsUrl")
        }
        connectWebSocketIfNeeded()

        // Pick target URL from intent first, fallback to persisted preference.
        val fromIntent = intent.getStringExtra(EXTRA_TARGET_URL)?.trim()
        val persisted = loadTargetUrlFromPrefs()
        val chosen = when {
            !fromIntent.isNullOrBlank() -> fromIntent
            !persisted.isNullOrBlank() -> persisted
            else -> DEFAULT_TARGET_URL
        }
        if (chosen != targetUrl) {
            Log.i(TAG, "Target URL set: $chosen")
            targetUrl = chosen
        }
        if (!fromIntent.isNullOrBlank()) {
            persistTargetUrl(fromIntent)
        }

        enableBle = intent.getBooleanExtra(EXTRA_ENABLE_BLE, false)

        // Clear previous debug frame so we can tell if a new one was generated.
        try {
            val outFile = File(cacheDir, "bleper_test.png")
            if (outFile.exists()) {
                outFile.delete()
            }
        } catch (_: Exception) {
        }

        // Start rendering immediately even if BLE isn't ready yet.
        handler.removeCallbacks(renderRunnable)
        handler.post(renderRunnable)

        if (enableBle && canUseBleScan()) {
            startBleScan()
        } else {
            Log.i(TAG, "BLE scan disabled; keep rendering/uploading")
        }

        return START_STICKY
    }

    private fun canUseBleScan(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-S scanning typically requires location permission.
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun connectWebSocketIfNeeded() {
        val url = wsUrl
        if (url.isNullOrBlank()) return
        if (webSocket != null) return

        Log.i(TAG, "Connecting WebSocket: $url")
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                wsReconnectScheduled = false
                handler.removeCallbacks(wsPingRunnable)
                handler.postDelayed(wsPingRunnable, 10_000)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRemoteCommand(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed code=$code reason=$reason")
                this@X4Service.webSocket = null
                handler.removeCallbacks(wsPingRunnable)
                scheduleWsReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure", t)
                this@X4Service.webSocket = null
                handler.removeCallbacks(wsPingRunnable)
                scheduleWsReconnect()
            }
        })
    }

    private fun scheduleWsReconnect() {
        if (wsReconnectScheduled) return
        wsReconnectScheduled = true
        handler.postDelayed({
            wsReconnectScheduled = false
            connectWebSocketIfNeeded()
        }, 2000)
    }

    private fun handleRemoteCommand(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        Log.i(TAG, "WS cmd: $text")

        // Supported formats:
        // - Plain: UP/DOWN/LEFT/RIGHT, BTN1..BTN4, RELOAD
        // - URL:<https://...>
        // - JSON: {"cmd":"UP"} or {"cmd":"URL","url":"https://..."}

        var parsedCmd: String
        var parsedUrl: String?
        var parsedZoom: Float? = null
        if (text.startsWith("{")) {
            try {
                val obj = org.json.JSONObject(text)
                parsedCmd = (obj.optString("cmd") ?: "").trim().uppercase()
                parsedUrl = obj.optString("url")?.takeIf { it.isNotBlank() }
                val z = obj.optString("zoom")?.trim()
                if (!z.isNullOrBlank()) {
                    parsedZoom = z.toFloatOrNull()
                }
            } catch (_: Throwable) {
                parsedCmd = text.uppercase()
                parsedUrl = null
            }
        } else if (text.startsWith("URL:", ignoreCase = true)) {
            parsedCmd = "URL"
            parsedUrl = text.substringAfter(":").trim()
        } else if (text.startsWith("ZOOM", ignoreCase = true)) {
            parsedCmd = "ZOOM"
            parsedUrl = null
            val tail = text.substringAfter(":", missingDelimiterValue = "").trim()
            val u = if (tail.isNotBlank()) tail else text.substringAfter("_", missingDelimiterValue = "").trim()
            val rawNum = u
                .removeSuffix("%")
                .trim()
            val f = rawNum.toFloatOrNull()
            if (f != null) {
                parsedZoom = if (f > 3f) (f / 100f) else f
            }
        } else {
            parsedCmd = text.uppercase()
            parsedUrl = null
        }

        handler.post {
            ensureWebViewStarted()
            val wv = webView ?: return@post

            fun sendKey(code: Int) {
                wv.requestFocus()
                wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }

            when (parsedCmd) {
                "BTN1", "UP" -> {
                    wv.evaluateJavascript("window.scrollBy(0, -600)", null)
                }
                "BTN2", "DOWN" -> {
                    wv.evaluateJavascript("window.scrollBy(0, 600)", null)
                }
                "BTN3", "LEFT", "BACK" -> {
                    if (wv.canGoBack()) wv.goBack() else wv.evaluateJavascript("window.scrollTo(0, 0)", null)
                }
                "BTN4", "RIGHT", "FORWARD" -> {
                    if (wv.canGoForward()) wv.goForward() else wv.reload()
                }
                "TAB" -> {
                    sendKey(KeyEvent.KEYCODE_TAB)
                }
                "ENTER" -> {
                    sendKey(KeyEvent.KEYCODE_ENTER)
                }
                "NEXT", "PAGEDOWN" -> {
                    clickWeReadNav(wv, "下一页")
                }
                "PREV", "PAGEUP" -> {
                    clickWeReadNav(wv, "上一页")
                }
                "RELOAD", "REFRESH" -> {
                    wv.reload()
                }
                "URL" -> {
                    val u = parsedUrl
                    if (!u.isNullOrBlank()) {
                        targetUrl = u
                        persistTargetUrl(u)
                        ensureWebViewUrlLoaded(u)
                    }
                }
                "ZOOM" -> {
                    val z = parsedZoom
                    if (z != null) {
                        contentScale = z.coerceIn(0.20f, 2.50f)
                        applyScaleToWebView(wv)
                        // re-clean after scale change
                        cleanupWeReadUi(wv)
                    }
                }
                else -> {
                    // No-op for unknown command
                }
            }
        }
    }

    private fun startBleScan() {
        if (bluetoothAdapter?.isEnabled == true) {
            if (isScanning) {
                Log.d(TAG, "BLE scan already running; skip start")
                return
            }
            Log.d(TAG, "Starting BLE Scan...")
            isScanning = true
            bluetoothLeScanner?.startScan(scanCallback)
        } else {
            // Rendering pipeline should still work even without BLE.
            Log.w(TAG, "Bluetooth is not enabled; skip BLE scan but keep rendering")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.address}")
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private fun ensureWebViewStarted() {
        if (webView != null) return

        // WebView must be created/used on the main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { ensureWebViewStarted() }
            return
        }

        val wv = WebView(applicationContext)
        wv.setBackgroundColor(Color.WHITE)
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        // Offscreen rendering is more reliable with software layer.
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadsImagesAutomatically = true

        // Share cookies with the interactive WebView in MainActivity.
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            try {
                cm.setAcceptThirdPartyCookies(wv, true)
            } catch (_: Throwable) {
            }
            cm.flush()
        } catch (_: Throwable) {
        }
        // Use Android Chrome UA (system default). This improves WeRead compatibility and typically
        // reduces GPU tile memory pressure compared to desktop UA.
        try {
            s.userAgentString = WebSettings.getDefaultUserAgent(applicationContext)
        } catch (_: Throwable) {
        }
        // Avoid implicit auto-scaling; we control viewport explicitly for 480x800.
        s.useWideViewPort = false
        s.loadWithOverviewMode = false
        // Try to keep sizing stable across devices/emulators.
        s.textZoom = scaleToPercent(contentScale)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "WebView onPageFinished url=$url")

                                // Apply scale + cleanup. Do it multiple times because WeRead may inject UI after load.
                                val v = view
                                if (v != null) {
                                        applyScaleToWebView(v)
                                        cleanupWeReadUi(v)
                                    if (isWeReadBookLandingUrl(url)) {
                                        // On landing pages, try to enter real reading mode.
                                        tryEnterWeReadReading(v)
                                    }
                                        handler.postDelayed({ applyScaleToWebView(v); cleanupWeReadUi(v) }, 800)
                                    handler.postDelayed({
                                        applyScaleToWebView(v)
                                        cleanupWeReadUi(v)
                                        if (isWeReadBookLandingUrl(url)) {
                                            tryEnterWeReadReading(v)
                                        }
                                    }, 2000)
                                }

                webViewLoaded = true
                webViewLoadFinishedAtMs = android.os.SystemClock.elapsedRealtime()
                FrameSyncState.markDirty()
            }
        }

        // Ensure it has the right virtual size for rendering.
        wv.setInitialScale(scaleToPercent(contentScale))
        val widthSpec = View.MeasureSpec.makeMeasureSpec(TARGET_WIDTH, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(TARGET_HEIGHT, View.MeasureSpec.EXACTLY)
        wv.measure(widthSpec, heightSpec)
        wv.layout(0, 0, TARGET_WIDTH, TARGET_HEIGHT)

        webView = wv
        webViewLoaded = false
        lastLoadedUrl = null
        webViewLoadFinishedAtMs = 0L
        Log.i(TAG, "WebView render pipeline started ${TARGET_WIDTH}x${TARGET_HEIGHT}")
    }

    private fun ensureWebViewUrlLoaded(url: String) {
        val wv = webView ?: return
        if (lastLoadedUrl == url) return
        lastLoadedUrl = url
        webViewLoaded = false
        webViewLoadFinishedAtMs = 0L
        Log.i(TAG, "Loading URL in headless WebView: $url")
        wv.loadUrl(url)
    }

    private fun maybeCaptureAndSend() {
        val now = android.os.SystemClock.elapsedRealtime()

        // 1fps throttle
        if (lastSentAtMs != 0L && now - lastSentAtMs < 1000L) {
            return
        }

        // Always capture at 1Hz (including in screen-off) per requirement.

        val wv = webView
        if (wv == null || !webViewLoaded) {
            if (wv == null) {
                Log.d(TAG, "WebView not ready yet: null")
            } else {
                Log.d(TAG, "WebView not ready yet: page not finished")
            }
            return
        }

        // WeRead is JS-heavy; give it a short grace period after onPageFinished.
        val finishedAt = webViewLoadFinishedAtMs
        if (finishedAt != 0L && now - finishedAt < 1200L) {
            Log.d(TAG, "WebView not ready yet: waiting for paint")
            return
        }

        try {
            // Re-measure/layout to guarantee size before draw.
            val widthSpec = View.MeasureSpec.makeMeasureSpec(TARGET_WIDTH, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(TARGET_HEIGHT, View.MeasureSpec.EXACTLY)
            wv.measure(widthSpec, heightSpec)
            wv.layout(0, 0, TARGET_WIDTH, TARGET_HEIGHT)

            // Give the page a brief moment after load to paint (weread is JS-heavy).
            // If it's already stable, this is a no-op delay across ticks because of 1Hz throttle.
            val bitmap = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Prevent unpainted regions (transparent/black) from becoming dither noise
            // after grayscale/threshold processing.
            canvas.drawColor(Color.WHITE)
            wv.draw(canvas)

            val processedBitmap = processImage(bitmap)

            val pngBytes = try {
                val baos = ByteArrayOutputStream()
                processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                baos.toByteArray()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to encode debug frame PNG", t)
                null
            }

            // Debug aid: persist latest processed frame for host-side inspection.
            // Pull it via: adb exec-out run-as com.guaishoudejia.x4doublesysfserv cat cache/bleper_test.png > /tmp/bleper_test.png
            if (pngBytes != null) {
                try {
                    val outFile = File(cacheDir, "bleper_test.png")
                    FileOutputStream(outFile).use { out ->
                        out.write(pngBytes)
                    }
                    Log.d(TAG, "Wrote debug frame to ${outFile.absolutePath}")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to write debug frame PNG", t)
                }

                val url = uploadUrl
                if (!url.isNullOrBlank()) {
                    uploadExecutor.execute {
                        Log.d(TAG, "Uploading debug frame to $url")
                        uploadImageMultipart(url, pngBytes)
                    }
                }
            }

            val imageData = convertBitmapTo1BitArray(processedBitmap)
            val crc = crc32(imageData)

            if (crc == lastFrameCrc) {
                return
            }
            lastFrameCrc = crc
            lastSentAtMs = now

            queueImageDataReplace(imageData)
        } catch (t: Throwable) {
            Log.e(TAG, "WebView capture failed", t)
        }
    }

    private fun uploadImageMultipart(urlString: String, pngBytes: ByteArray) {
        val boundary = "----BlePerBoundary${System.currentTimeMillis()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        try {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 15000
                setRequestProperty("Connection", "close")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            conn.outputStream.use { out ->
                out.write((twoHyphens + boundary + lineEnd).toByteArray())
                out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"bleper_test.png\"" + lineEnd).toByteArray())
                out.write(("Content-Type: image/png" + lineEnd).toByteArray())
                out.write(lineEnd.toByteArray())
                out.write(pngBytes)
                out.write(lineEnd.toByteArray())
                out.write((twoHyphens + boundary + twoHyphens + lineEnd).toByteArray())
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Upload failed HTTP $code url=$urlString")
            } else {
                Log.d(TAG, "Upload ok HTTP $code url=$urlString")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Upload failed url=$urlString", t)
        }
    }

    private fun processImage(bitmap: Bitmap): Bitmap {
        // 1. Scale
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, TARGET_HEIGHT, true)

        // 2. Grayscale
        val grayscaleBitmap = Bitmap.createBitmap(scaledBitmap.width, scaledBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscaleBitmap)
        val paint = Paint()
        val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

        // 3. Dither
        return floydSteinbergDither(grayscaleBitmap)
    }

    private fun floydSteinbergDither(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val oldPixel = pixels[y * width + x]
                val oldR = Color.red(oldPixel)
                val newPixel = if (oldR < 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                pixels[y * width + x] = newPixel

                val quantError = oldR - Color.red(newPixel)

                if (x + 1 < width) {
                    val pixel = pixels[y * width + x + 1]
                    val r = (Color.red(pixel) + quantError * 7 / 16).coerceIn(0, 255)
                    pixels[y * width + x + 1] = Color.rgb(r, r, r)
                }
                if (x - 1 >= 0 && y + 1 < height) {
                    val pixel = pixels[(y + 1) * width + x - 1]
                    val r = (Color.red(pixel) + quantError * 3 / 16).coerceIn(0, 255)
                    pixels[(y + 1) * width + x - 1] = Color.rgb(r, r, r)
                }
                if (y + 1 < height) {
                    val pixel = pixels[(y + 1) * width + x]
                    val r = (Color.red(pixel) + quantError * 5 / 16).coerceIn(0, 255)
                    pixels[(y + 1) * width + x] = Color.rgb(r, r, r)
                }
                if (x + 1 < width && y + 1 < height) {
                    val pixel = pixels[(y + 1) * width + x + 1]
                    val r = (Color.red(pixel) + quantError * 1 / 16).coerceIn(0, 255)
                    pixels[(y + 1) * width + x + 1] = Color.rgb(r, r, r)
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun convertBitmapTo1BitArray(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val byteArr = ByteArray(width * height / 8)
        var byteIndex = 0
        var bitIndex = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) < 128) {
                    byteArr[byteIndex] = (byteArr[byteIndex].toInt() or (1 shl (7 - bitIndex))).toByte()
                }
                bitIndex++
                if (bitIndex == 8) {
                    bitIndex = 0
                    byteIndex++
                }
            }
        }
        return byteArr
    }

    private fun queueImageData(data: ByteArray) {
        val chunkSize = currentMtu
        for (i in data.indices step chunkSize) {
            val end = (i + chunkSize).coerceAtMost(data.size)
            imageQueue.add(data.copyOfRange(i, end))
        }
        if (!isSending) {
            sendNextPacket()
        }
    }

    private fun queueImageDataReplace(data: ByteArray) {
        synchronized(queueLock) {
            imageQueue.clear()
            isSending = false
            queueImageData(data)
        }
    }

    private fun sendNextPacket() {
        val gatt = bluetoothGatt
        if (!isBleReady || gatt == null) {
            synchronized(queueLock) {
                isSending = false
            }
            return
        }

        val data: ByteArray?
        synchronized(queueLock) {
            if (imageQueue.isEmpty()) {
                isSending = false
                return
            }
            isSending = true
            data = imageQueue.peek()
        }

        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID_DATA)
        if (characteristic == null || data == null) {
            Log.w(TAG, "BLE not ready to write (service/characteristic missing); will retry")
            synchronized(queueLock) {
                isSending = false
            }
            return
        }

        characteristic.value = data
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val writeStarted = gatt.writeCharacteristic(characteristic)
        if (writeStarted) {
            synchronized(queueLock) {
                imageQueue.poll() // remove only after write request is accepted
            }
        } else {
            Log.w(TAG, "writeCharacteristic returned false; will retry")
            synchronized(queueLock) {
                isSending = false
            }
        }
    }

    private fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:EInkCapture")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
        Log.i(TAG, "WakeLock acquired")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Screen Capture Service"
            val descriptionText = "This service captures the screen to send to a peripheral."
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Screen Sharing Active")
            .setContentText("Sharing screen content with a peripheral device.")
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with a proper icon
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(renderRunnable)
        try {
            webSocket?.close(1000, "service destroy")
        } catch (_: Exception) {
        }
        webSocket = null
        try {
            webView?.stopLoading()
        } catch (_: Exception) {
        }
        try {
            webView?.destroy()
        } catch (_: Exception) {
        }
        webView = null
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        isScanning = false
        bluetoothGatt?.close()
        try {
            uploadExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        Log.d(TAG, "Service destroyed, all resources released.")
    }

    companion object {
        private const val TAG = "X4Service"
        private const val PERIPHERAL_NAME = "BlePer"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "X4_SERVICE_CHANNEL"
        // 需求：宽 480，高 800
        private const val TARGET_WIDTH = 480
        private const val TARGET_HEIGHT = 800
        private const val DEFAULT_TARGET_URL = "https://weread.qq.com/"

        private const val PREFS_NAME = "x4service"
        private const val PREF_KEY_TARGET_URL = "target_url"

        // 默认上传到 18080 服务（与 MainActivity 保持一致）
        private const val EMULATOR_UPLOAD_URL = "http://10.0.2.2:18080/image"
        private const val DEVICE_UPLOAD_URL = "http://192.168.31.105:18080/image"

        // WebSocket：Python BlePer 测试端（可通过 EXTRA_WS_URL 覆盖）
        // 默认复用 18080 端口：HTTP /image + WS /ws
        private const val EMULATOR_WS_URL = "ws://10.0.2.2:18080/ws"
        private const val DEVICE_WS_URL = "ws://192.168.31.105:18080/ws"

        private fun isEmulator(): Boolean {
            val fp = Build.FINGERPRINT
            return fp.contains("generic") || fp.contains("emulator") || fp.contains("sdk_gphone")
        }
        val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        // 发送显示数据：Android -> 外设
        val CHARACTERISTIC_UUID_DATA = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        // 接收按钮命令：外设 -> Android（notify）
        val CHARACTERISTIC_UUID_CMD = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val CMD_OPEN = 1
        private const val CMD_NEXT = 2
        private const val CMD_PREV = 3
        private const val CMD_RELOAD = 4
        const val EXTRA_UPLOAD_URL = "EXTRA_UPLOAD_URL"
        const val EXTRA_WS_URL = "EXTRA_WS_URL"
        const val EXTRA_ENABLE_BLE = "EXTRA_ENABLE_BLE"
        const val EXTRA_TARGET_URL = "EXTRA_TARGET_URL"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }
}
