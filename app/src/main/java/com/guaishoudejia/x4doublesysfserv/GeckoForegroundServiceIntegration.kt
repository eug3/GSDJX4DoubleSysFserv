package com.guaishoudejia.x4doublesysfserv

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch

/**
 * GeckoActivity 与 GeckoForegroundService 集成扩展
 * 提供使用前台服务进行离屏渲染的集成示例
 */

/**
 * 在 GeckoActivity 中初始化前台服务管理器
 * 调用示例：
 *
 * class GeckoActivity : ComponentActivity() {
 *     private var foregroundServiceManager: GeckoForegroundServiceManager? = null
 *     
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         foregroundServiceManager = GeckoForegroundServiceManager(this, this)
 *         foregroundServiceManager?.setServiceConnectionCallbacks(
 *             onConnected = { setupForegroundServiceRendering() },
 *             onDisconnected = { Log.d("GeckoActivity", "Service disconnected") }
 *         )
 *     }
 *     
 *     private fun setupForegroundServiceRendering() {
 *         // 启动前台服务渲染
 *         foregroundServiceManager?.startForegroundService(
 *             uri = targetUrl,
 *             width = 480,
 *             height = 800
 *         )
 *         
 *         // 注册渲染回调
 *         val renderCallback = SimpleRenderCallback(
 *             onFrame = { bitmap ->
 *                 // 处理渲染的 Bitmap
 *                 Log.d("GeckoActivity", "Frame rendered: ${bitmap.width}x${bitmap.height}")
 *             },
 *             onError = { error ->
 *                 Log.e("GeckoActivity", "Render error: $error")
 *             }
 *         )
 *         foregroundServiceManager?.registerRenderCallback(renderCallback)
 *     }
 * }
 */

/**
 * 前台服务渲染模式配置
 */
data class ForegroundServiceRenderConfig(
    val uri: String = "about:blank",
    val width: Int = 480,
    val height: Int = 800,
    val fps: Int = 30,
    val enableOffscreenRendering: Boolean = true,
    val independentEglContext: Boolean = true
)

/**
 * 前台服务渲染状态
 */
class ForegroundServiceRenderState {
    private val _isRendering = mutableStateOf(false)
    private val _currentFrameCount = mutableStateOf(0)
    private val _lastErrorMessage = mutableStateOf<String?>(null)
    private val _renderedBitmap = mutableStateOf<Bitmap?>(null)
    
    var isRendering: Boolean
        get() = _isRendering.value
        set(value) { _isRendering.value = value }
    
    var currentFrameCount: Int
        get() = _currentFrameCount.value
        set(value) { _currentFrameCount.value = value }
    
    var lastErrorMessage: String?
        get() = _lastErrorMessage.value
        set(value) { _lastErrorMessage.value = value }
    
    var renderedBitmap: Bitmap?
        get() = _renderedBitmap.value
        set(value) { _renderedBitmap.value = value }
}

/**
 * 创建前台服务渲染配置建造器
 */
fun createForegroundServiceRenderConfig(builder: ForegroundServiceRenderConfig.() -> ForegroundServiceRenderConfig): ForegroundServiceRenderConfig {
    return ForegroundServiceRenderConfig().builder()
}

/**
 * 使用示例 - 在 Composable 中集成
 */
@Suppress("unused")
object GeckoForegroundServiceIntegration {
    /**
     * Composable 示例：使用前台服务渲染
     * 
     * @Composable
     * fun GeckoForegroundServiceScreen(
     *     manager: GeckoForegroundServiceManager,
     *     config: ForegroundServiceRenderConfig
     * ) {
     *     val renderState = remember { ForegroundServiceRenderState() }
     *     val scope = rememberCoroutineScope()
     *
     *     LaunchedEffect(Unit) {
     *         manager.setServiceConnectionCallbacks(
     *             onConnected = {
     *                 renderState.isRendering = true
     *                 manager.startForegroundService(
     *                     uri = config.uri,
     *                     width = config.width,
     *                     height = config.height
     *                 )
     *
     *                 // 注册渲染回调
     *                 val callback = SimpleRenderCallback(
     *                     onFrame = { bitmap ->
     *                         renderState.renderedBitmap = bitmap
     *                         renderState.currentFrameCount++
     *                     },
     *                     onError = { error ->
     *                         renderState.lastErrorMessage = error
     *                     }
     *                 )
     *                 manager.registerRenderCallback(callback)
     *             },
     *             onDisconnected = {
     *                 renderState.isRendering = false
     *             }
     *         )
     *     }
     *
     *     Column(modifier = Modifier.fillMaxSize()) {
     *         if (renderState.isRendering) {
     *             renderState.renderedBitmap?.let { bitmap ->
     *                 Image(
     *                     bitmap = bitmap.asImageBitmap(),
     *                     contentDescription = "Rendered content",
     *                     modifier = Modifier.fillMaxSize(),
     *                     contentScale = ContentScale.Fit
     *                 )
     *             }
     *         } else {
     *             Text("Connecting to rendering service...")
     *         }
     *
     *         renderState.lastErrorMessage?.let { error ->
     *             Text("Error: $error", color = Color.Red)
     *         }
     *
     *         Text("Frames: ${renderState.currentFrameCount}")
     *     }
     *
     *     DisposableEffect(Unit) {
     *         onDispose {
     *             manager.cleanup()
     *         }
     *     }
     * }
     */
    
    /**
     * 在 Activity 中使用前台服务
     * 
     * class MyActivity : ComponentActivity() {
     *     private lateinit var foregroundServiceManager: GeckoForegroundServiceManager
     *
     *     override fun onCreate(savedInstanceState: Bundle?) {
     *         super.onCreate(savedInstanceState)
     *         foregroundServiceManager = GeckoForegroundServiceManager(this, this)
     *
     *         val config = ForegroundServiceRenderConfig(
     *             uri = "https://weread.qq.com/web/reader/...",
     *             width = 480,
     *             height = 800
     *         )
     *
     *         foregroundServiceManager.startForegroundService(
     *             uri = config.uri,
     *             width = config.width,
     *             height = config.height
     *         )
     *     }
     *
     *     override fun onDestroy() {
     *         super.onDestroy()
     *         foregroundServiceManager.cleanup()
     *     }
     * }
     */
}

/**
 * 日志辅助函数
 */
object ForegroundServiceLogger {
    private const val TAG = "GeckoForegroundService"

    fun logStartRendering(uri: String, width: Int, height: Int) {
        Log.d(TAG, "Starting rendering - URI: $uri, Size: ${width}x${height}")
    }

    fun logFrameRendered(frameNumber: Int, bitmap: Bitmap?) {
        Log.d(TAG, "Frame $frameNumber rendered: ${bitmap?.width}x${bitmap?.height}")
    }

    fun logRenderingError(error: String) {
        Log.e(TAG, "Rendering error: $error")
    }

    fun logServiceStatus(isConnected: Boolean) {
        Log.i(TAG, "Service status: ${if (isConnected) "Connected" else "Disconnected"}")
    }
}
