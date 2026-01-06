# BLE 通信最佳实践集成指南

## 概述

本文档提供将改进的 BLE 实现集成到现有项目的完整指南。包括：

1. **Android 端集成**：从旧的 `BleEspClient` 迁移到 `BleEspClientOptimized`
2. **ESP32 端集成**：实现新的缓存管理器和预加载机制
3. **测试和性能验证**

---

## 第一部分：Android 端集成

### 1.1 迁移步骤

#### 步骤 1：并行运行两个客户端

保持原有 `BleEspClient` 不变，同时引入新的 `BleEspClientOptimized`：

```kotlin
// MainActivity.kt
class MainActivity : AppCompatActivity() {
    
    // 原有客户端（继续使用）
    private var bleClient: BleEspClient? = null
    
    // 新客户端（逐步迁移）
    private var bleClientOpt: BleEspClientOptimized? = null
    
    private fun initBle() {
        val deviceAddress = "XX:XX:XX:XX:XX:XX"
        
        // 同时初始化两个客户端（用于对比测试）
        bleClient = BleEspClient(
            context = this,
            deviceAddress = deviceAddress,
            scope = lifecycleScope,
            onCommand = ::handleCommand
        )
        
        bleClientOpt = BleEspClientOptimized(
            context = this,
            deviceAddress = deviceAddress,
            scope = lifecycleScope,
            onCommand = ::handleCommand,
            onStatusChanged = ::updateConnectionStatus,
            onMetrics = ::updateMetrics
        )
        
        // 优先使用新客户端
        bleClientOpt!!.connect()
    }
    
    private fun handleCommand(cmd: String) {
        Log.d("BLE", "Command received: $cmd")
    }
    
    private fun updateConnectionStatus(status: BleEspClientOptimized.ConnectionStatus) {
        Log.d("BLE", "Connection status: $status")
        // 更新 UI
        runOnUiThread {
            connectionStatusView.text = status.name
        }
    }
    
    private fun updateMetrics(metrics: BleEspClientOptimized.BleMetrics) {
        Log.d("BLE", "Throughput: ${metrics.throughputKbps.toInt()} Kbps")
        // 更新性能监控面板
    }
}
```

#### 步骤 2：逐步替换调用

在渲染和发送页面时使用新客户端：

```kotlin
// ❌ 旧方式（停用）
// bleClient?.sendBitmap(bitmap)

// ✅ 新方式
bleClientOpt?.sendBitmap(bitmap)

// 或使用零拷贝渲染
bleClientOpt?.renderAndSendPage(1680, 2240) { pixels ->
    // 直接在像素数组上绘制
    drawTextToPixels(pixels, textContent)
    drawImagesToPixels(pixels, imageContent)
}
```

#### 步骤 3：完整替换（可选）

一旦新客户端在生产环境稳定运行，删除旧客户端：

```kotlin
class MainActivity : AppCompatActivity() {
    private var bleClient: BleEspClientOptimized? = null
    
    private fun initBle() {
        val deviceAddress = "XX:XX:XX:XX:XX:XX"
        bleClient = BleEspClientOptimized(
            context = this,
            deviceAddress = deviceAddress,
            scope = lifecycleScope,
            onCommand = ::handleCommand,
            onStatusChanged = ::updateConnectionStatus,
            onMetrics = ::updateMetrics
        )
        bleClient!!.connect()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bleClient?.close()
    }
}
```

### 1.2 集成零拷贝渲染

如果项目使用 EPUB 阅读器，集成新的渲染流程：

```kotlin
// EpubReaderActivity.kt
class EpubReaderActivity : AppCompatActivity() {
    
    private var bleClient: BleEspClientOptimized? = null
    private var epubReader: EpubReader? = null
    
    private fun displayPage(pageNum: Int) {
        val startTime = System.currentTimeMillis()
        
        // 使用零拷贝渲染
        bleClient?.renderAndSendPage(1680, 2240) { pixels ->
            // 从 EPUB 读取内容
            val content = epubReader!!.readPage(currentBookPath, pageNum)
            
            // 1. 清空像素为白色
            for (i in pixels.indices) {
                pixels[i] = 0xFFFFFFFF.toInt()
            }
            
            // 2. 栅格化文本
            for (textBlock in content.textBlocks) {
                drawText(
                    pixels = pixels,
                    width = 1680,
                    text = textBlock.content,
                    x = textBlock.x,
                    y = textBlock.y,
                    fontSize = textBlock.fontSize,
                    color = textBlock.color
                )
            }
            
            // 3. 栅格化图像
            for (imageBlock in content.imageBlocks) {
                drawImage(
                    pixels = pixels,
                    width = 1680,
                    imagePath = imageBlock.path,
                    x = imageBlock.x,
                    y = imageBlock.y,
                    width = imageBlock.width,
                    height = imageBlock.height
                )
            }
        }
        
        val renderTime = System.currentTimeMillis() - startTime
        Log.d("Render", "Page $pageNum rendered in ${renderTime}ms")
    }
    
    private fun drawText(
        pixels: IntArray,
        width: Int,
        text: String,
        x: Int,
        y: Int,
        fontSize: Int,
        color: Int
    ) {
        // 使用字体缓存获取每个字符的字形
        for ((i, char) in text.withIndex()) {
            val glyphBitmap = fontCache.getGlyph(char, fontSize)
            blendBitmap(pixels, width, glyphBitmap, x + i * fontSize / 2, y, color)
        }
    }
    
    private fun drawImage(
        pixels: IntArray,
        width: Int,
        imagePath: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: return
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        blendBitmap(pixels, width, scaled, x, y, 0xFF000000.toInt())
        bitmap.recycle()
        scaled.recycle()
    }
    
    private fun blendBitmap(
        pixels: IntArray,
        destWidth: Int,
        srcBitmap: Bitmap,
        destX: Int,
        destY: Int,
        color: Int
    ) {
        val srcPixels = IntArray(srcBitmap.width * srcBitmap.height)
        srcBitmap.getPixels(srcPixels, 0, srcBitmap.width, 0, 0, srcBitmap.width, srcBitmap.height)
        
        for (sy in 0 until srcBitmap.height) {
            for (sx in 0 until srcBitmap.width) {
                val dx = destX + sx
                val dy = destY + sy
                
                if (dx >= 0 && dx < destWidth && dy >= 0 && dy < 2240) {
                    val destIndex = dy * destWidth + dx
                    if (destIndex in pixels.indices) {
                        val srcPixel = srcPixels[sy * srcBitmap.width + sx]
                        pixels[destIndex] = alphaBlend(srcPixel, pixels[destIndex])
                    }
                }
            }
        }
    }
    
    private fun alphaBlend(src: Int, dest: Int): Int {
        val srcA = (src shr 24) and 0xFF
        val srcR = (src shr 16) and 0xFF
        val srcG = (src shr 8) and 0xFF
        val srcB = src and 0xFF
        
        val destR = (dest shr 16) and 0xFF
        val destG = (dest shr 8) and 0xFF
        val destB = dest and 0xFF
        
        val outR = ((srcR * srcA) + (destR * (255 - srcA))) / 255
        val outG = ((srcG * srcA) + (destG * (255 - srcA))) / 255
        val outB = ((srcB * srcA) + (destB * (255 - srcA))) / 255
        
        return 0xFF000000.toInt() or (outR shl 16) or (outG shl 8) or outB
    }
}
```

### 1.3 UI 集成 - 连接状态和性能监控

```kotlin
// 连接状态指示器
private fun updateConnectionStatusUI(status: BleEspClientOptimized.ConnectionStatus) {
    val (icon, text, color) = when (status) {
        BleEspClientOptimized.ConnectionStatus.DISCONNECTED -> 
            Triple(R.drawable.ic_disconnected, "未连接", Color.RED)
        BleEspClientOptimized.ConnectionStatus.CONNECTING -> 
            Triple(R.drawable.ic_connecting, "连接中...", Color.YELLOW)
        BleEspClientOptimized.ConnectionStatus.DISCOVERING -> 
            Triple(R.drawable.ic_discovering, "发现服务中...", Color.YELLOW)
        BleEspClientOptimized.ConnectionStatus.READY -> 
            Triple(R.drawable.ic_connected, "就绪", Color.GREEN)
        BleEspClientOptimized.ConnectionStatus.RECONNECTING -> 
            Triple(R.drawable.ic_reconnecting, "重新连接中...", Color.ORANGE)
        else -> 
            Triple(R.drawable.ic_error, "错误", Color.RED)
    }
    
    connectionStatusView.apply {
        setImageResource(icon)
        text = text
        setTextColor(color)
    }
}

// 性能监控面板
private fun updateMetricsUI(metrics: BleEspClientOptimized.BleMetrics) {
    throughputView.text = "吞吐量: ${metrics.throughputKbps.toInt()} Kbps"
    bytesTransferredView.text = "传输: ${metrics.totalBytesTransferred / 1024}KB"
    packetLossView.text = "丢包率: ${(metrics.packetLossRate * 100).toInt()}%"
    
    // 更新进度条
    if (metrics.totalBytesTransferred > 0) {
        progressBar.progress = (metrics.totalBytesTransferred * 100 / (48 * 1024)).toInt()
    }
}
```

---

## 第二部分：ESP32 端集成

### 2.1 实现缓存管理器

创建 `ble_cache_manager_optimized.c`：

```c
// ble_cache_manager_optimized.c

#include "ble_cache_manager_optimized.h"
#include <string.h>
#include <time.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "littlefs.h"

static const char* TAG = "BleCacheMgr";

// 全局状态
static ble_cache_config_t g_config;
static ble_cached_page_t g_pages[MAX_CACHED_PAGES];
static ble_sliding_window_t g_window;
static ble_rx_state_t g_rx_state;
static uint8_t* g_rx_buffer = NULL;

// 初始化
bool ble_cache_manager_init(const ble_cache_config_t* config) {
    if (!config) return false;
    
    memcpy(&g_config, config, sizeof(ble_cache_config_t));
    
    // 分配接收缓冲
    g_rx_buffer = malloc(RECEIVE_BUFFER_SIZE);
    if (!g_rx_buffer) {
        ESP_LOGE(TAG, "Failed to allocate RX buffer");
        return false;
    }
    
    // 初始化缓存页
    for (int i = 0; i < g_config.max_cached_pages; i++) {
        g_pages[i].valid = false;
        g_pages[i].timestamp = 0;
        g_pages[i].access_count = 0;
    }
    
    // 初始化窗口
    g_window.window_start = 0;
    g_window.window_end = g_config.window_size - 1;
    g_window.current_page = 0;
    g_window.cache_hits = 0;
    g_window.cache_misses = 0;
    g_window.pending_requests = 0;
    
    // 初始化接收状态
    memset(&g_rx_state, 0, sizeof(ble_rx_state_t));
    
    ESP_LOGI(TAG, "Cache manager initialized: window_size=%d, cache_pages=%d",
             g_config.window_size, g_config.max_cached_pages);
    
    return true;
}

// 更新滑动窗口
void ble_cache_update_window(uint16_t current_page) {
    if (current_page < g_window.window_start) {
        // 向后翻页
        uint16_t old_end = g_window.window_end;
        g_window.window_start = current_page;
        g_window.window_end = current_page + g_config.window_size - 1;
        
        // 清理窗口外的页
        for (int i = 0; i < g_config.max_cached_pages; i++) {
            if (g_pages[i].valid && 
                (g_pages[i].page_num < g_window.window_start ||
                 g_pages[i].page_num > g_window.window_end)) {
                ble_cache_evict_page(i);
            }
        }
        
        // 触发预加载
        ble_cache_trigger_prefetch();
        
    } else if (current_page >= g_window.window_start + g_config.prefetch_threshold) {
        // 接近窗口末尾，预加载后续页
        ble_cache_trigger_prefetch();
    }
    
    g_window.current_page = current_page;
    g_window.last_update_time = time(NULL);
}

// 检查页面是否缓存
bool ble_cache_page_exists(uint16_t book_id, uint16_t page_num) {
    for (int i = 0; i < g_config.max_cached_pages; i++) {
        if (g_pages[i].valid &&
            g_pages[i].book_id == book_id &&
            g_pages[i].page_num == page_num) {
            
            g_pages[i].access_count++;
            g_pages[i].last_access_time = time(NULL);
            g_window.cache_hits++;
            return true;
        }
    }
    
    g_window.cache_misses++;
    return false;
}

// 从缓存读取页面
int32_t ble_cache_read_page(uint16_t book_id, uint16_t page_num,
                            uint8_t* buffer, uint32_t max_len) {
    // 查找缓存页
    int page_idx = -1;
    for (int i = 0; i < g_config.max_cached_pages; i++) {
        if (g_pages[i].valid &&
            g_pages[i].book_id == book_id &&
            g_pages[i].page_num == page_num) {
            page_idx = i;
            break;
        }
    }
    
    if (page_idx < 0) {
        ESP_LOGW(TAG, "Page not in cache: book=%d, page=%d", book_id, page_num);
        return -1;
    }
    
    // 从 LittleFS 读取
    FILE* f = fopen(g_pages[page_idx].filename, "rb");
    if (!f) {
        ESP_LOGE(TAG, "Failed to open cache file: %s", g_pages[page_idx].filename);
        g_pages[page_idx].valid = false;  // 标记为无效
        return -1;
    }
    
    uint32_t to_read = (g_pages[page_idx].size_bytes > max_len) ? 
                       max_len : g_pages[page_idx].size_bytes;
    size_t bytes_read = fread(buffer, 1, to_read, f);
    fclose(f);
    
    if (bytes_read != to_read) {
        ESP_LOGE(TAG, "Incomplete read: requested=%lu, got=%lu", to_read, bytes_read);
        return -1;
    }
    
    // 更新访问信息
    g_pages[page_idx].access_count++;
    g_pages[page_idx].last_access_time = time(NULL);
    
    return bytes_read;
}

// 分片写入页面
bool ble_cache_write_page_chunk(uint16_t book_id, uint16_t page_num,
                                uint32_t offset, const uint8_t* data,
                                uint32_t len, uint32_t total_size) {
    // 查找缓存槽位
    int page_idx = -1;
    
    // 优先查找已有的页
    for (int i = 0; i < g_config.max_cached_pages; i++) {
        if (g_pages[i].valid &&
            g_pages[i].book_id == book_id &&
            g_pages[i].page_num == page_num) {
            page_idx = i;
            break;
        }
    }
    
    // 如果没有，找空槽位
    if (page_idx < 0) {
        for (int i = 0; i < g_config.max_cached_pages; i++) {
            if (!g_pages[i].valid) {
                page_idx = i;
                break;
            }
        }
    }
    
    // 如果还是没有，使用 LRU 淘汰
    if (page_idx < 0) {
        uint32_t min_access = UINT32_MAX;
        for (int i = 0; i < g_config.max_cached_pages; i++) {
            if (g_pages[i].access_count < min_access) {
                min_access = g_pages[i].access_count;
                page_idx = i;
            }
        }
        ble_cache_evict_page(page_idx);
    }
    
    // 初始化页元数据
    if (offset == 0) {
        g_pages[page_idx].book_id = book_id;
        g_pages[page_idx].page_num = page_num;
        g_pages[page_idx].size_bytes = total_size;
        g_pages[page_idx].timestamp = time(NULL);
        g_pages[page_idx].access_count = 0;
        
        snprintf(g_pages[page_idx].filename, sizeof(g_pages[page_idx].filename),
                 "/littlefs/book_%d_page_%d.dat", book_id, page_num);
    }
    
    // 写入分片到 LittleFS
    FILE* f = fopen(g_pages[page_idx].filename, offset == 0 ? "wb" : "r+b");
    if (!f) {
        ESP_LOGE(TAG, "Failed to open file for writing");
        return false;
    }
    
    fseek(f, offset, SEEK_SET);
    size_t bytes_written = fwrite(data, 1, len, f);
    fclose(f);
    
    if (bytes_written != len) {
        ESP_LOGE(TAG, "Incomplete write");
        return false;
    }
    
    // 检查是否完成
    if (offset + len >= total_size) {
        g_pages[page_idx].valid = true;
        ESP_LOGI(TAG, "Page write complete: book=%d, page=%d, size=%lu",
                 book_id, page_num, total_size);
        return true;
    }
    
    return true;
}

// 获取预加载列表
uint16_t ble_cache_get_prefetch_list(uint16_t* pages, uint16_t max_count) {
    uint16_t count = 0;
    
    for (uint16_t page = g_window.window_end + 1;
         page <= g_window.window_end + g_config.prefetch_threshold &&
         count < max_count;
         page++) {
        
        if (!ble_cache_page_exists(0, page)) {  // 假设 book_id = 0
            pages[count++] = page;
        }
    }
    
    return count;
}

// 后台预加载任务
void ble_cache_prefetch_task(void* arg) {
    while (1) {
        if (g_window.prefetch_active) {
            uint16_t prefetch_pages[10];
            uint16_t count = ble_cache_get_prefetch_list(prefetch_pages, 10);
            
            // 发送预加载请求到 Android
            for (uint16_t i = 0; i < count; i++) {
                // ble_send_prefetch_request(prefetch_pages[i]);
            }
            
            vTaskDelay(pdMS_TO_TICKS(g_config.prefetch_delay_ms));
        } else {
            vTaskDelay(pdMS_TO_TICKS(500));
        }
    }
}

// 获取缓存统计
void ble_cache_get_stats(uint32_t* hits, uint32_t* misses, uint32_t* pages_cached) {
    *hits = g_window.cache_hits;
    *misses = g_window.cache_misses;
    
    uint32_t count = 0;
    for (int i = 0; i < g_config.max_cached_pages; i++) {
        if (g_pages[i].valid) count++;
    }
    *pages_cached = count;
}
```

### 2.2 集成到显示引擎

```c
// 在 display_engine.c 中集成缓存和预加载

void display_engine_render_page(uint16_t page_num) {
    // 1. 更新缓存窗口
    ble_cache_update_window(page_num);
    
    // 2. 检查缓存
    uint8_t* page_data = malloc(48 * 1024);
    if (!page_data) {
        ESP_LOGE(TAG, "Failed to allocate page buffer");
        return;
    }
    
    if (ble_cache_read_page(0, page_num, page_data, 48 * 1024) > 0) {
        // 缓存命中，直接渲染
        display_engine_render_bitmap(page_data, 1680, 2240);
        ESP_LOGI(TAG, "Page %d rendered from cache", page_num);
    } else {
        // 缓存未命中
        ESP_LOGW(TAG, "Page %d not in cache, requesting from Android", page_num);
        ble_request_page(page_num);
        
        // 显示加载中提示
        display_engine_show_loading();
    }
    
    free(page_data);
}
```

---

## 第三部分：性能测试和验证

### 3.1 性能测试工具

创建 `BlePerformanceTest.kt`：

```kotlin
class BlePerformanceTest {
    
    private lateinit var bleClient: BleEspClientOptimized
    private val results = mutableListOf<TestResult>()
    
    data class TestResult(
        val testName: String,
        val duration: Long,
        val bytesTransferred: Long,
        val throughputKbps: Double,
        val success: Boolean,
        val error: String? = null
    )
    
    fun runFullTest() {
        testConnectionAndDiscovery()
        testSinglePageTransfer()
        testMultiplePageTransfers()
        testLargeTransfer()
        testErrorRecovery()
        printResults()
    }
    
    private fun testConnectionAndDiscovery() {
        Log.d(TAG, "Testing connection and discovery...")
        val startTime = System.currentTimeMillis()
        
        bleClient.connect()
        
        // 等待就绪
        val maxWait = 30000L
        val startWait = System.currentTimeMillis()
        while (System.currentTimeMillis() - startWait < maxWait) {
            if (bleClient.currentStatus == BleEspClientOptimized.ConnectionStatus.READY) {
                break
            }
            Thread.sleep(100)
        }
        
        val duration = System.currentTimeMillis() - startTime
        val success = bleClient.currentStatus == BleEspClientOptimized.ConnectionStatus.READY
        
        results.add(TestResult(
            testName = "Connection & Discovery",
            duration = duration,
            bytesTransferred = 0,
            throughputKbps = 0.0,
            success = success,
            error = if (success) null else "Failed to reach READY state in ${duration}ms"
        ))
    }
    
    private fun testSinglePageTransfer() {
        Log.d(TAG, "Testing single page transfer...")
        
        val bitmap = generateTestBitmap(1680, 2240)
        val startTime = System.currentTimeMillis()
        val startMetrics = bleClient.metrics.totalBytesTransferred
        
        bleClient.sendBitmap(bitmap)
        
        // 等待完成
        val maxWait = 10000L
        val startWait = System.currentTimeMillis()
        while (System.currentTimeMillis() - startWait < maxWait) {
            if (bleClient.sending == false && 
                bleClient.metrics.totalBytesTransferred > startMetrics) {
                break
            }
            Thread.sleep(100)
        }
        
        val duration = System.currentTimeMillis() - startTime
        val bytesTransferred = bleClient.metrics.totalBytesTransferred - startMetrics
        val throughputKbps = (bytesTransferred * 8) / (duration.toDouble() / 1000) / 1024
        
        results.add(TestResult(
            testName = "Single Page Transfer (48KB)",
            duration = duration,
            bytesTransferred = bytesTransferred,
            throughputKbps = throughputKbps,
            success = bytesTransferred > 40000  // 允许一些压缩或优化
        ))
    }
    
    private fun testMultiplePageTransfers() {
        Log.d(TAG, "Testing multiple page transfers...")
        
        val startTime = System.currentTimeMillis()
        val startMetrics = bleClient.metrics.totalBytesTransferred
        
        for (i in 0 until 5) {
            val bitmap = generateTestBitmap(1680, 2240)
            bleClient.sendBitmap(bitmap)
            
            // 等待每个页面完成
            while (bleClient.sending) {
                Thread.sleep(100)
            }
        }
        
        val duration = System.currentTimeMillis() - startTime
        val bytesTransferred = bleClient.metrics.totalBytesTransferred - startMetrics
        val throughputKbps = (bytesTransferred * 8) / (duration.toDouble() / 1000) / 1024
        
        results.add(TestResult(
            testName = "Multiple Page Transfers (5×48KB)",
            duration = duration,
            bytesTransferred = bytesTransferred,
            throughputKbps = throughputKbps,
            success = throughputKbps > 50.0  // 期望 > 50 Kbps
        ))
    }
    
    private fun testErrorRecovery() {
        Log.d(TAG, "Testing error recovery...")
        // 模拟设备断开
        bleClient.close()
        
        val startTime = System.currentTimeMillis()
        bleClient.connect()
        
        // 等待重新连接
        val maxWait = 30000L
        while (System.currentTimeMillis() - startTime < maxWait) {
            if (bleClient.currentStatus == BleEspClientOptimized.ConnectionStatus.READY) {
                break
            }
            Thread.sleep(100)
        }
        
        val duration = System.currentTimeMillis() - startTime
        val success = bleClient.currentStatus == BleEspClientOptimized.ConnectionStatus.READY
        
        results.add(TestResult(
            testName = "Error Recovery (Reconnect)",
            duration = duration,
            bytesTransferred = 0,
            throughputKbps = 0.0,
            success = success && duration < 15000
        ))
    }
    
    private fun generateTestBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
    
    private fun printResults() {
        Log.d(TAG, "===== TEST RESULTS =====")
        for (result in results) {
            Log.d(TAG, """
                Test: ${result.testName}
                - Duration: ${result.duration}ms
                - Bytes: ${result.bytesTransferred}
                - Throughput: ${result.throughputKbps.toInt()} Kbps
                - Status: ${if (result.success) "✓ PASS" else "✗ FAIL"}
                ${result.error?.let { "- Error: $it" } ?: ""}
            """.trimIndent())
        }
        
        val totalPassed = results.count { it.success }
        val totalTests = results.size
        Log.d(TAG, "Total: $totalPassed/$totalTests tests passed")
    }
}
```

### 3.2 运行测试

```kotlin
// 在应用中运行测试
class TestActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 运行性能测试
        Thread {
            val tester = BlePerformanceTest()
            tester.runFullTest()
        }.start()
    }
}
```

---

## 第四部分：检查清单

### 集成检查清单

- [ ] 编译无误，无 ProGuard 警告
- [ ] `BleEspClientOptimized` 成功初始化
- [ ] 连接建立（5-10 秒内）
- [ ] MTU 协商成功（517 字节）
- [ ] 单页传输完成（2 秒内）
- [ ] 吞吐量 > 50 Kbps
- [ ] 丢包率 < 1%
- [ ] 断线自动重连
- [ ] 页面渲染 < 30ms
- [ ] 内存占用稳定（无泄漏）

### 性能指标目标

| 指标 | 目标 | 实际 |
|-----|------|------|
| 连接时间 | < 10s | ____ |
| 页面传输 | 1-2s | ____ |
| 吞吐量 | > 100 Kbps | ____ |
| 丢包率 | < 1% | ____ |
| 渲染时间 | < 30ms | ____ |
| 内存占用 | < 30MB | ____ |

---

## 故障排查

### 连接问题

**问题**：无法连接或连接超时

**排查步骤**：
1. 检查蓝牙权限是否已授予
2. 验证设备地址是否正确
3. 检查 ESP32 是否在广告
4. 查看日志中的 `onConnectionStateChange` 事件

### 低吞吐量

**问题**：吞吐量 < 50 Kbps

**排查步骤**：
1. 检查 MTU 协商结果（应 > 100）
2. 验证连接间隔（应 < 20ms）
3. 关闭 WiFi 测试蓝牙干扰
4. 检查手机是否有背景限制（Settings > Battery)

### 页面加载卡顿

**问题**：翻页时延迟明显

**排查步骤**：
1. 验证缓存是否启用
2. 检查 LittleFS 读写速度
3. 确认预加载机制工作
4. 使用 Android Profiler 检查 CPU/内存

---

## 相关文档

- [BLE 最佳实践](./BLE_BEST_PRACTICES.md)
- [位图处理指南](./BITMAP_PROCESSING_GUIDE.md)
- [ESP32 缓存 API](./ble_cache_manager_optimized.h)

---

**更新日期**：2026-01-06  
**版本**：1.0
