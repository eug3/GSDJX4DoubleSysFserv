package com.guaishoudejia.x4doublesysfserv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class EInkAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val flipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_NEXT_PAGE) {
                performNextPageGesture()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(flipReceiver, IntentFilter(ACTION_REQUEST_NEXT_PAGE))
        Log.i(TAG, "Accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                FrameSyncState.markDirty()
            }
        }
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(flipReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun performNextPageGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        // Swipe up: from ~80% height to ~20% height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.8f)
            lineTo(w * 0.5f, h * 0.2f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()

        mainHandler.post {
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    FrameSyncState.markDirty()
                }
            }, null)
            Log.d(TAG, "dispatchGesture swipeUp ok=$ok")
        }
    }

    companion object {
        private const val TAG = "EInkA11y"
        const val ACTION_REQUEST_NEXT_PAGE = "com.guaishoudejia.x4doublesysfserv.ACTION_REQUEST_NEXT_PAGE"
    }
}
