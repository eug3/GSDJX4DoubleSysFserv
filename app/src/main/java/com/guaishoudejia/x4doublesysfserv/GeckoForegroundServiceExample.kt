package com.guaishoudejia.x4doublesysfserv

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.sp

/**
 * GeckoForegroundService 实现示例
 * 演示如何在 Compose 中使用前台服务进行离屏渲染
 */

/**
 * 使用前台服务的 Composable 组件示例
 */
@Composable
fun GeckoForegroundServiceScreenExample() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderState = remember { ForegroundServiceRenderState() }
    val serviceManager = remember { 
        GeckoForegroundServiceManager(context, lifecycleOwner)
    }

    // 初始化并启动服务
    LaunchedEffect(Unit) {
        Log.d("GeckoForegroundServiceExample", "Initializing service...")
        
        serviceManager.setServiceConnectionCallbacks(
            onConnected = {
                Log.d("GeckoForegroundServiceExample", "Service connected, starting render...")
                renderState.isRendering = true
                
                // 启动前台服务渲染
                serviceManager.startForegroundService(
                    uri = "https://weread.qq.com/web/reader/",
                    width = 480,
                    height = 800
                )

                // 注册渲染回调
                val callback = SimpleRenderCallback(
                    onFrame = { bitmap ->
                        renderState.renderedBitmap = bitmap
                        renderState.currentFrameCount++
                        Log.d(
                            "GeckoForegroundServiceExample",
                            "Frame ${renderState.currentFrameCount}: ${bitmap.width}x${bitmap.height}"
                        )
                    },
                    onError = { error ->
                        renderState.lastErrorMessage = error
                        Log.e("GeckoForegroundServiceExample", "Render error: $error")
                    }
                )
                serviceManager.registerRenderCallback(callback)
            },
            onDisconnected = {
                Log.d("GeckoForegroundServiceExample", "Service disconnected")
                renderState.isRendering = false
            }
        )
    }

    // UI 布局
    Column(modifier = Modifier.fillMaxSize()) {
        // 渲染内容显示
        if (renderState.isRendering && renderState.renderedBitmap != null) {
            Box(modifier = Modifier.weight(1f)) {
                Image(
                    bitmap = renderState.renderedBitmap!!.asImageBitmap(),
                    contentDescription = "Rendered content",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                Text("连接到渲染服务中...", fontSize = 16.sp)
            }
        }

        // 状态显示
        Column(modifier = Modifier.weight(0.2f)) {
            Text("渲染状态: ${if (renderState.isRendering) "活跃" else "停止"}", fontSize = 12.sp)
            Text("已渲染帧数: ${renderState.currentFrameCount}", fontSize = 12.sp)
            
            renderState.lastErrorMessage?.let { error ->
                Text("错误: $error", fontSize = 11.sp)
            }
        }
    }

    // 清理资源
    DisposableEffect(Unit) {
        onDispose {
            Log.d("GeckoForegroundServiceExample", "Cleaning up...")
            serviceManager.cleanup()
        }
    }
}

/**
 * 高级使用示例：使用自定义回调处理帧数据
 */
@Composable
fun GeckoForegroundServiceAdvancedExample() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderState = remember { ForegroundServiceRenderState() }
    val serviceManager = remember { 
        GeckoForegroundServiceManager(context, lifecycleOwner)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        serviceManager.setServiceConnectionCallbacks(
            onConnected = {
                serviceManager.startForegroundService(
                    uri = "https://example.com",
                    width = 1024,
                    height = 768
                )

                // 自定义回调：处理帧数据
                object : GeckoForegroundService.RenderCallback {
                    override fun onFrameRendered(bitmap: Bitmap) {
                        // 1. 更新 UI
                        renderState.renderedBitmap = bitmap
                        renderState.currentFrameCount++
                        
                        // 2. 可选：处理帧数据
                        processFrameData(bitmap)
                    }

                    override fun onError(error: String) {
                        renderState.lastErrorMessage = error
                        handleRenderError(error)
                    }
                }.let { callback ->
                    serviceManager.registerRenderCallback(callback)
                }
            },
            onDisconnected = {
                Log.d("GeckoForegroundServiceAdvanced", "Service disconnected")
            }
        )
    }

    // UI...
    Box(modifier = Modifier.fillMaxSize()) {
        renderState.renderedBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Advanced render",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            serviceManager.cleanup()
        }
    }
}

/**
 * 处理帧数据的示例函数
 */
private fun processFrameData(bitmap: Bitmap) {
    // 这里可以进行：
    // 1. OCR 识别
    // 2. 图像分析
    // 3. 帧保存
    // 4. 发送到其他服务
    
    Log.d(
        "FrameProcessing",
        "Processing frame: ${bitmap.width}x${bitmap.height}, size=${bitmap.byteCount / 1024}KB"
    )
}

/**
 * 错误处理示例
 */
private fun handleRenderError(error: String) {
    Log.e("RenderErrorHandler", error)
    // 这里可以进行：
    // 1. 显示用户通知
    // 2. 记录错误日志
    // 3. 尝试恢复
    // 4. 重新启动服务
}

/**
 * 集成到现有 GeckoActivity 的示例
 * 
 * 在 GeckoActivity 类中添加以下代码：
 * 
 * class GeckoActivity : ComponentActivity() {
 *     private var foregroundServiceManager: GeckoForegroundServiceManager? = null
 *     private var renderCallback: GeckoForegroundService.RenderCallback? = null
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         
 *         // 初始化前台服务管理器
 *         foregroundServiceManager = GeckoForegroundServiceManager(this, this)
 *         
 *         setContent {
 *             // 在适当的时候启动前台服务渲染
 *             if (shouldUseForegroundService) {
 *                 setupForegroundServiceRendering()
 *             }
 *             
 *             GeckoActivityContent()
 *         }
 *     }
 *
 *     private fun setupForegroundServiceRendering() {
 *         foregroundServiceManager?.setServiceConnectionCallbacks(
 *             onConnected = {
 *                 Log.d("GeckoActivity", "Foreground service connected")
 *                 startForegroundServiceRender()
 *             },
 *             onDisconnected = {
 *                 Log.d("GeckoActivity", "Foreground service disconnected")
 *             }
 *         )
 *     }
 *
 *     private fun startForegroundServiceRender() {
 *         val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
 *         foregroundServiceManager?.startForegroundService(
 *             uri = targetUrl,
 *             width = 480,
 *             height = 800
 *         )
 *
 *         // 创建并注册回调
 *         renderCallback = SimpleRenderCallback(
 *             onFrame = { bitmap ->
 *                 // 处理渲染的 Bitmap
 *                 onForegroundServiceFrameReceived(bitmap)
 *             },
 *             onError = { error ->
 *                 Log.e("GeckoActivity", "Foreground service error: $error")
 *             }
 *         )
 *         renderCallback?.let { 
 *             foregroundServiceManager?.registerRenderCallback(it) 
 *         }
 *     }
 *
 *     private fun onForegroundServiceFrameReceived(bitmap: Bitmap) {
 *         // 处理接收到的 Bitmap
 *         // 例如：用于 OCR、显示、保存等
 *     }
 *
 *     override fun onDestroy() {
 *         super.onDestroy()
 *         renderCallback?.let {
 *             foregroundServiceManager?.unregisterRenderCallback(it)
 *         }
 *         foregroundServiceManager?.cleanup()
 *     }
 * }
 */

/**
 * 配置和启动的完整流程：
 * 
 * 1. 创建管理器
 *    val manager = GeckoForegroundServiceManager(context, lifecycleOwner)
 * 
 * 2. 设置连接监听
 *    manager.setServiceConnectionCallbacks(onConnected, onDisconnected)
 * 
 * 3. 启动服务
 *    manager.startForegroundService(uri, width, height)
 * 
 * 4. 注册回调
 *    manager.registerRenderCallback(callback)
 * 
 * 5. 处理帧
 *    在 callback.onFrameRendered() 中处理 Bitmap
 * 
 * 6. 清理资源
 *    manager.cleanup()
 */
