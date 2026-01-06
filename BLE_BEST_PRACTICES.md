# 安卓与电子书 BLE 连接最佳实践指南

## 一、系统架构概览

```
安卓手机 (Android App)
    ↓ BLE GATT Client
    ├─ Image Write Characteristic (48KB 位图)
    └─ Command Notify Characteristic (反向控制)
    
    ↓ 蓝牙连接通道 (BLE Link)
    
ESP32C3 电子书阅读器 (BLE GATT Server)
    ↓ NimBLE Stack
    ├─ Image Characteristic Service (接收位图)
    ├─ Cache Manager (LittleFS 缓存)
    ├─ Sliding Window (预加载策略)
    └─ Display Engine (渲染到 E-ink)
```

## 二、BLE 连接管理最佳实践

### 2.1 连接建立流程

```
Phase 1: 发现 (Discovery)
├─ 蓝牙扫描（低功耗模式，间隔 1.28s，窗口 11.25ms）
├─ 信号过滤（RSSI > -75dBm，排除弱信号）
└─ 服务 UUID 预过滤（快速识别设备）

Phase 2: 连接 (Connection)
├─ 发起 GAP 连接（首选间隔 7.5-15ms，最大跳跃 80）
├─ 协商 MTU（目标 517 字节）
└─ 自动重连机制（3 次重试，指数退避）

Phase 3: 服务发现 (Service Discovery)
├─ 动态特征匹配（PROPERTY_WRITE + PROPERTY_NOTIFY）
├─ 优先级评分（自定义 UUID > 标准 UUID）
└─ CCCD 启用（命令通道通知）

Phase 4: 数据传输 (Data Transfer)
├─ 初始化发送队列
├─ 设置吞吐优化
└─ 启用错误重试
```

### 2.2 MTU 协商策略

**推荐配置**：
- **目标 MTU**：517 字节（Android 设备通常支持）
- **ATT 有效载荷**：514 字节（MTU - 3）
- **实际数据包**：227 字节（预留头部空间）

**计算公式**：
```
ATT Payload = MTU - 3
Usable Data = ATT Payload - Header(17) = MTU - 20
```

### 2.3 断线重连机制

```kotlin
// 连接状态管理
enum class BleConnectionState {
    DISCONNECTED,      // 未连接
    CONNECTING,        // 连接中
    CONNECTED,         // 已连接
    DISCOVERING,       // 发现服务中
    READY,             // 可传输数据
    RECONNECTING,      // 自动重连中
    ERROR              // 错误状态
}

// 重连策略
- 首次立即重连
- 2-5 秒后第二次（指数退避）
- 10-15 秒后第三次
- 提示用户手动重连
```

### 2.4 权限和错误处理

**关键权限**（Android 12+）：
```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
```

**运行时权限检查**：
- 编译时权限声明
- 运行时 request 动态权限
- 捕获 SecurityException

## 三、图像传输优化

### 3.1 位图数据格式

**规格**：
- **分辨率**：1680×2240 像素（4.26" E-ink）
- **颜色深度**：1 位（黑白，48KB）
- **格式**：行主序（Row-Major），MSB 优先

**计算**：
```
宽度 = 1680 像素 / 8 = 210 字节
高度 = 2240 像素
总大小 = 210 × 2240 = 470,400 字节 = ~460KB

1 位压缩：
压缩率 = 1/8（相对 RGB565）
每页 = 48KB（来自 460KB/~9.6 压缩）
```

### 3.2 零拷贝位图处理

**Android 端实现原则**：

```kotlin
// ❌ 错误：Canvas 绘制（慢，占用 GPU 内存）
val canvas = Canvas(targetBitmap)
canvas.drawBitmap(sourceBitmap, ...)

// ✅ 正确：内存直接绘制（快，只用 CPU 内存）
fun renderPageToBitmap(
    text: List<TextLayout>,
    images: List<ImageLayout>,
    width: Int,
    height: Int
): Bitmap {
    // 1. 创建位图（RGB565 在内存中，传输前转换）
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    
    // 2. 获取像素数组
    val pixels = IntArray(width * height)
    
    // 3. 直接操作像素
    for (textLayout in text) {
        rasterizeText(pixels, width, textLayout)
    }
    for (imageLayout in images) {
        rasterizeImage(pixels, width, imageLayout)
    }
    
    // 4. 写回位图
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    
    // 5. 转换为 1 位格式（仅传输时）
    return bitmap
}

// 像素栅格化示例
private fun rasterizeText(
    pixels: IntArray,
    width: Int,
    textLayout: TextLayout
) {
    val fontBitmap = loadGlyph(textLayout.char, textLayout.fontSize)
    val x = textLayout.x
    val y = textLayout.y
    
    // 直接混合到像素数组
    for (dy in 0 until fontBitmap.height) {
        for (dx in 0 until fontBitmap.width) {
            val px = x + dx
            val py = y + dy
            if (px >= 0 && px < width && py >= 0 && py < width) {
                val pixelIndex = py * width + px
                val glyphPixel = fontBitmap.getPixel(dx, dy)
                pixels[pixelIndex] = blendPixel(pixels[pixelIndex], glyphPixel)
            }
        }
    }
}
```

### 3.3 传输协议优化

**流式传输架构**：

```
安卓端：
┌─────────────────────────┐
│ Bitmap (内存)           │
│ 1680×2240 RGB565        │
└────────┬────────────────┘
         │ 转换为 1 位 (BLE 传输格式)
         ↓
┌─────────────────────────┐
│ BitBitmap (48KB)        │
│ 1 位打包格式            │
└────────┬────────────────┘
         │ 分块 (227 字节/包)
         ↓
┌─────────────────────────┐
│ BLE Packets             │
│ 17 字节头 + 227 字节数据│
└────────┬────────────────┘
         │ BLE 链路层
         ↓
┌─────────────────────────┐
│ ESP32C3 接收缓冲        │
│ 环形缓冲区              │
└────────┬────────────────┘
         │ 逐包重组
         ↓
┌─────────────────────────┐
│ 完整页面 (48KB)         │
│ LittleFS 缓存           │
└────────┬────────────────┘
         │ 滑动窗口预加载
         ↓
┌─────────────────────────┐
│ E-ink 显示引擎          │
│ 硬件渲染                │
└─────────────────────────┘
```

### 3.4 1 位位图格式转换

```kotlin
fun convertRgb565To1Bit(
    bitmap: Bitmap  // RGB565 格式
): ByteArray {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    
    // 1 位二值化 + 打包
    val bytesPerRow = (w + 7) / 8
    val data = ByteArray(bytesPerRow * h)
    var dataIndex = 0
    
    for (y in 0 until h) {
        var bitBuffer = 0
        var bitCount = 0
        
        for (x in 0 until w) {
            val pixelIndex = y * w + x
            val rgb = pixels[pixelIndex]
            
            // RGB565 -> 灰度 -> 二值
            val r = (rgb shr 11) and 0x1F
            val g = (rgb shr 5) and 0x3F
            val b = rgb and 0x1F
            
            val gray = (r * 76 + g * 150 + b * 30) shr 8
            val bit = if (gray > 127) 1 else 0
            
            // MSB 优先打包
            bitBuffer = (bitBuffer shl 1) or bit
            bitCount++
            
            if (bitCount == 8) {
                data[dataIndex++] = bitBuffer.toByte()
                bitBuffer = 0
                bitCount = 0
            }
        }
        
        // 处理不足 8 位的余数
        if (bitCount > 0) {
            bitBuffer = bitBuffer shl (8 - bitCount)
            data[dataIndex++] = bitBuffer.toByte()
        }
    }
    
    return data
}
```

## 四、缓存策略（滑动窗口）

### 4.1 缓存架构

```c
// ESP32 端缓存管理
typedef struct {
    uint16_t window_start;      // 窗口起始页
    uint16_t window_size;       // 窗口大小 (5-10 页)
    uint16_t prefetch_threshold; // 预加载阈值
    
    // LittleFS 缓存槽位
    ble_cached_page_t pages[MAX_CACHED_PAGES];  // 10 页 × 48KB = 480KB
    uint32_t cache_size_bytes;
    
    // 接收状态
    struct {
        uint32_t bytes_received;  // 已接收字节数
        uint32_t last_packet_time; // 最后包接收时间
        bool transfer_active;      // 传输进行中
    } rx_state;
} ble_cache_manager_t;
```

### 4.2 滑动窗口预加载

```c
// ESP32 端窗口管理逻辑
void ble_cache_update_window(uint16_t current_page) {
    if (current_page < window_start) {
        // 向后翻页（清理前面页，预加载后面页）
        window_start = current_page;
        evict_old_pages(window_start + window_size);
        prefetch_pages(current_page, current_page + window_size);
    } else if (current_page >= window_start + prefetch_threshold) {
        // 接近窗口末尾，预加载后续页
        prefetch_pages(current_page + 1, current_page + prefetch_threshold);
    }
}

// 预加载流程
void prefetch_pages(uint16_t start, uint16_t end) {
    for (uint16_t page = start; page <= end; page++) {
        if (!page_cached(page)) {
            // 发送请求到 Android
            ble_request_page(page);
        }
    }
}
```

### 4.3 内存管理

**可用内存分配**：
- **PSRAM**（若可用）：优先用于缓存（480KB × 2 个缓冲）
- **内部 SRAM**：关键路径（接收缓冲、队列）
- **IRAM**：中断处理程序

```c
#define CACHE_BUFFER_SIZE       (48 * 1024)      // 48KB/页
#define MAX_CACHED_PAGES        10               // 10 页
#define TOTAL_CACHE_BYTES       (480 * 1024)     // ~480KB

// 内存布局
typedef struct {
    uint8_t* rx_buffer;        // 环形接收缓冲 (64KB)
    uint8_t* cache_pages[10];  // 页缓冲指针
    uint32_t total_allocated;
} ble_memory_layout_t;
```

## 五、传输吞吐优化

### 5.1 传输速率计算

```
理论吞吐量 = (MTU - 20) × 数据包率
           = 247 字节 × 100 包/秒
           = 24.7 KB/s

48KB 页面传输时间：
T = 48KB / 24.7 KB/s ≈ 1.94 秒

实际考虑：
- BLE 事件间隔（间隔越小越快）
- 确认等待时间
- 处理延迟
- 典型值：800 ms - 2s/页
```

### 5.2 吞吐优化策略

```kotlin
// Android 端优化
class BleTransferOptimizer {
    
    // 1. 连接间隔优化
    fun requestPreferredConnectionParams(gatt: BluetoothGatt) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            gatt.connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_HIGH
        }
    }
    
    // 2. 批量写入
    fun sendBitmapOptimized(bitmap: Bitmap) {
        val data = convertBitmapToRgb565Le(bitmap)
        val frame = buildFrameHeader(data.size) + data
        
        // 分块处理，最小化内存拷贝
        val chunks = frame.chunked(mtuPayload) { chunk ->
            ByteArray(chunk.size) { chunk[it] }
        }
        
        // 背景线程发送
        scope.launch(Dispatchers.Default) {
            for (chunk in chunks) {
                writeCharacteristicOptimized(chunk)
                // 等待确认（不阻塞主线程）
                waitForWriteCallback()
            }
        }
    }
    
    // 3. 连接参数调优
    data class BleConnectionParams(
        val minIntervalMs: Int = 7.5f,      // 最小 7.5ms
        val maxIntervalMs: Int = 15f,       // 最大 15ms  
        val slaveLatency: Int = 0,          // 无延迟
        val supervisionTimeout: Int = 6000   // 6 秒超时
    )
}
```

### 5.3 流控机制

```c
// ESP32 端 - 背景预加载任务
void ble_cache_prefetch_task(void* arg) {
    while (1) {
        // 1. 检查当前显示页
        uint16_t current_page = display_engine_get_current_page();
        
        // 2. 计算需要的页面范围
        uint16_t prefetch_start = current_page + 1;
        uint16_t prefetch_end = current_page + PREFETCH_WINDOW;
        
        // 3. 发送请求（流控：最多 5 个待处理请求）
        if (pending_requests < 5) {
            for (uint16_t page = prefetch_start; page <= prefetch_end; page++) {
                if (!page_cached(page)) {
                    ble_send_request(page);
                    pending_requests++;
                    vTaskDelay(pdMS_TO_TICKS(50));  // 避免突发
                }
            }
        }
        
        vTaskDelay(pdMS_TO_TICKS(500));  // 每 500ms 检查一次
    }
}
```

## 六、错误处理和恢复

### 6.1 错误类型分类

| 错误类型 | 原因 | 处理方式 |
|---------|------|---------|
| GATT_ERROR | 蓝牙链路错误 | 立即断线，触发重连 |
| TIMEOUT | 设备无响应 | 等待 3 秒后重连 |
| MTU_FAILED | 协商失败 | 使用默认 MTU(23) |
| WRITE_FAILED | 写入失败 | 重试 3 次，间隔 100ms |
| CHARACTERISTIC_NOT_FOUND | 服务发现失败 | 重新发现或使用备用 UUID |

### 6.2 恢复流程

```kotlin
sealed class BleError(val message: String, val recoverable: Boolean) {
    class ConnectionLost(msg: String) : BleError(msg, true)
    class WriteTimeout(msg: String) : BleError(msg, true)
    class CharacteristicNotFound(msg: String) : BleError(msg, false)
    class MtuNegotiationFailed(msg: String) : BleError(msg, true)
}

// 错误处理中枢
fun handleBleError(error: BleError) {
    when (error) {
        is BleError.ConnectionLost -> {
            Log.w(TAG, error.message)
            reconnectWithBackoff()
        }
        is BleError.WriteTimeout -> {
            Log.w(TAG, error.message)
            retryWrite(maxRetries = 3, delayMs = 100)
        }
        is BleError.CharacteristicNotFound -> {
            Log.e(TAG, error.message)
            showErrorToUser("服务发现失败，请检查设备")
        }
        is BleError.MtuNegotiationFailed -> {
            Log.w(TAG, error.message)
            // 降级到默认 MTU 继续
            mtuPayload = 20
        }
    }
}
```

## 七、性能监控和调试

### 7.1 关键指标

```kotlin
// 性能计数器
data class BleMetrics(
    var totalBytesTransferred: Long = 0,
    var totalPacketsSent: Int = 0,
    var totalPacketsLost: Int = 0,
    var averageLatencyMs: Long = 0,
    var connectionUptime: Long = 0,
    var lastErrorTime: Long = 0,
    
    // 吞吐量 (更新每秒)
    val throughputKbps: Double
        get() = (totalBytesTransferred * 8) / (connectionUptime / 1000.0) / 1024,
    
    // 可靠性
    val packetLossRate: Double
        get() = if (totalPacketsSent == 0) 0.0 
                else totalPacketsLost.toDouble() / totalPacketsSent
)
```

### 7.2 日志策略

```kotlin
// 日志级别管理
object BleLogger {
    var logLevel = LogLevel.INFO
    
    fun debug(tag: String, msg: String, ex: Exception? = null) {
        if (logLevel <= LogLevel.DEBUG) {
            Log.d(tag, msg, ex)
            writeToFile(msg)  // 持久化关键日志
        }
    }
    
    fun logTransfer(
        pageNum: Int,
        bytesReceived: Int,
        totalBytes: Int,
        elapsedMs: Long
    ) {
        val progress = (bytesReceived * 100) / totalBytes
        val speedKbps = (bytesReceived * 8) / (elapsedMs.toDouble() / 1000) / 1024
        debug("Transfer", "Page $pageNum: $progress% complete, ${speedKbps.toInt()} Kbps")
    }
}
```

## 八、典型场景处理

### 8.1 翻页场景（快速）

```
用户快速翻页（0.5 秒/页）：
1. 当前页加载到 RAM
2. 后续 3 页预加载到 LittleFS
3. 超过范围的页面清理

缓存命中率目标：> 95%
用户体验：无感知延迟
```

### 8.2 双向通信

```
Android → ESP32：页面请求 (Request)
ESP32 → Android：页面数据 (Data) + 进度反馈

Android ← ESP32：用户操作反馈 (Command)
        - 翻页完成确认
        - 书签同步
        - 搜索结果
```

### 8.3 弱网恢复

```
场景：WiFi 干扰导致 BLE 吞吐下降
处理：
1. 降低连接间隔（增加重试频率）
2. 增加超时时间
3. 启用分片重组
4. 触发完整重连
```

## 九、安全性考虑

### 9.1 数据验证

```kotlin
// 包完整性检查
fun validatePacket(packet: ByteArray): Boolean {
    if (packet.size < MIN_PACKET_SIZE) return false
    
    val type = packet[0].toInt() and 0xFF
    val size = packet.size
    
    return when (type) {
        TYPE_REQUEST -> size == REQUEST_PKT_SIZE
        TYPE_DATA -> size in MIN_DATA_SIZE..MAX_DATA_SIZE
        TYPE_END -> size == END_PKT_SIZE
        else -> false
    }
}
```

### 9.2 配对和加密

```kotlin
// 配对流程
fun initiateSecureConnection(device: BluetoothDevice) {
    // 1. 创建可信设备列表
    val bondedDevices = bluetoothAdapter.bondedDevices
    
    // 2. 检查是否已配对
    if (device !in bondedDevices) {
        // 3. 触发配对
        device.createBond()
    }
    
    // 4. 使用安全连接
    gatt = device.connectGatt(
        context,
        false,
        gattCallback,
        TRANSPORT_LE,
        PHY_LE_1M_MASK,  // 1Mbps PHY
        null  // 将使用 BonInfo
    )
}
```

## 十、参考规范

### BLE 规范
- **Bluetooth 5.2 Core**
- **Generic Attribute Profile (GATT)**
- **Generic Access Profile (GAP)**

### Android 版本兼容性
- **最低**：Android 8.0 (API 26)
- **建议**：Android 10+ (更好的后台限制处理)
- **优化**：Android 12+ (改进的扫描和连接)

### 推荐库
- **Android Coroutines**：异步管理
- **NimBLE**（ESP32）：轻量级蓝牙栈
- **Protobuf**（可选）：消息序列化

---

**文档版本**：1.0  
**最后更新**：2026-01-06  
**维护者**：开发团队
