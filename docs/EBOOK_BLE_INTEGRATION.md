# 电子书 BLE 三页缓存同步实现指南

## 概述

本文档说明如何使用新实现的电子书 BLE 通信功能。该实现支持：

- ✅ 手机连接后等待用户确认
- ✅ 初始化时发送三页位图
- ✅ 电子书防抖翻页（快速翻页自动防抖）
- ✅ 电子书发送页码通知给手机
- ✅ 手机按需发送缺失的页面（三页窗口：prev, current, next）
- ✅ 自动清理过期页面

## 业务流程

```
1. 连接阶段
   手机 → 连接到 ble_spp_server
   ESP32 ← 接收连接，显示"点击确认，开始阅读"

2. 初始化阶段
   用户点击确认
   ESP32 → 发送 "PAGE:0" 通知手机
   手机 → 渲染页 0, 1, 2 并发送位图
   ESP32 ← 接收三页位图，保存到 littlefs，标记初始化完成

3. 翻页阶段（防抖）
   用户快速按下翻页按钮多次（如 3 次）
   ESP32 记录点击：page_change_count = 3
   等待 500ms 没有新按键
   防抖定时器触发 → 计算最终页码：0 + 3 = 3
   ESP32 → 发送 "PAGE:3" 通知手机
   手机 检查缓存：需要页 2, 3, 4
   手机 → 发送缺失页面（3, 4）
   ESP32 ← 接收并缓存，清理页 0, 1

4. 循环继续
   ...
```

## Android 端集成

### 1. 在 Activity 中初始化 BLE 客户端

```kotlin
import com.guaishoudejia.x4doublesysfserv.BleEspClient
import com.guaishoudejia.x4doublesysfserv.EpubPageRenderer
import com.guaishoudejia.x4doublesysfserv.PageRenderer

class BookReadingActivity : AppCompatActivity() {
    private lateinit var bleClient: BleEspClient
    private lateinit var pageRenderer: PageRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 创建页面渲染器（实际应该集成真实的 EPUB 引擎）
        pageRenderer = EpubPageRenderer(
            context = this,
            epubPath = "/path/to/book.epub",
            screenWidth = 1680,
            screenHeight = 2240
        )

        // 初始化 BLE 客户端
        bleClient = BleEspClient(
            context = this,
            deviceAddress = "XX:XX:XX:XX:XX:XX",  // 从扫描结果获得
            scope = lifecycleScope,
            onCommand = { command ->
                // 处理来自电子书的其他命令
                Log.d(TAG, "Command: $command")
            },
            pageRenderer = pageRenderer  // 注入渲染器
        )

        // 连接
        bleClient.connect()
    }

    override fun onDestroy() {
        bleClient.close()
        super.onDestroy()
    }
}
```

### 2. 处理连接和初始化

```kotlin
// 在连接成功回调中
if (bleClient.isConnected()) {
    // 初始化书籍阅读
    bleClient.initializeBookReading(
        bookId = 1,  // 书籍唯一 ID
        renderer = pageRenderer
    )
    
    // 等待电子书确认... 
    // 当用户点击电子书上的"确认"按钮时，
    // ESP32 会发送 "PAGE:0" 通知，手机自动发送初始三页
}
```

### 3. 页面同步自动处理

无需额外代码！当电子书发送 `PAGE:<num>` 通知时，`BleEspClient` 会自动：
1. 解析页码
2. 检查缓存状态
3. 发送缺失的页面

```kotlin
// BleEspClient.kt 内部已处理
override fun onCharacteristicChanged(gatt: BluetoothGatt, ...) {
    if (cmd.startsWith("PAGE:")) {
        val pageNum = cmd.substring(5).toInt()
        handlePageChangeNotification(pageNum)  // 自动处理
    }
}
```

## ESP32 端集成

### 1. 屏幕初始化

```c
// 在 ble_reader_screen.c 中已实现：
// - 显示"点击确认，开始阅读"提示
// - 等待用户点击确认按钮
// - 发送初始页码通知
```

### 2. 翻页防抖

```c
// 在 on_event 中自动实现：
case BTN_RIGHT:
case BTN_VOLUME_DOWN:
    s_ble_state.page_change_count++;  // 累加翻页
    if (s_ble_state.debounce_timer == NULL) {
        s_ble_state.debounce_timer = xTimerCreate(
            "page_debounce",
            pdMS_TO_TICKS(500),  // 500ms 防抖延迟
            pdFALSE,
            NULL,
            debounce_timer_callback
        );
    }
    xTimerReset(s_ble_state.debounce_timer, portMAX_DELAY);
```

### 3. 防抖完成后的处理

```c
static void debounce_timer_callback(TimerHandle_t xTimer)
{
    // 计算最终页码
    int new_page = s_ble_state.current_page + s_ble_state.page_change_count;
    s_ble_state.current_page = new_page;
    s_ble_state.page_change_count = 0;

    // 更新三页缓存窗口
    update_cached_window(new_page);

    // 发送页码同步通知
    send_page_sync_notification(new_page);

    // 尝试加载页面
    load_current_page();
}
```

### 4. 缓存管理

```c
// 三页窗口管理
typedef struct {
    uint16_t cached_pages[3];  // [prev, current, next]
} ble_reader_state_t;

// 在 update_cached_window() 中维护
cached_pages[0] = current - 1;  // 上一页
cached_pages[1] = current;       // 当前页
cached_pages[2] = current + 1;   // 下一页

// cleanup_old_pages() 删除超出范围的文件
```

## 关键参数

### 防抖延迟
```c
#define DEBOUNCE_DELAY_MS 500  // 用户停止翻页500ms后才发送页码
```

### 三页窗口
```c
cached_pages[0] = page - 1  // 上一页
cached_pages[1] = page      // 当前页
cached_pages[2] = page + 1  // 下一页
```

### 通知消息格式
```
格式：PAGE:<page_num>
示例：PAGE:5
```

## 调试和测试

### 1. 检查日志

**Android 端：**
```
D/BleEspClient: Received page notification: 3
D/BleEspClient: Queued page 2 for sending (current=3)
D/BleEspClient: Queued page 3 for sending (current=3)
D/BleEspClient: Queued page 4 for sending (current=3)
I/BleEspClient: Rendering and sending page 2...
I/BleEspClient: Rendering and sending page 3...
I/BleEspClient: Rendering and sending page 4...
```

**ESP32 端：**
```
I/BLE_READER: Debounce complete: page_change_count=3, new_page=3
I/BLE_READER: Updated cache window: prev=2, current=3, next=4
I/BLE_READER: Sending page notification: PAGE:3
```

### 2. 验证缓存状态

检查 ESP32 的 littlefs 中的文件：
```
/littlefs/ble_pages/book_0001_page_00002.bin
/littlefs/ble_pages/book_0001_page_00003.bin
/littlefs/ble_pages/book_0001_page_00004.bin
```

### 3. 性能指标

- **防抖延迟**: 500ms（可调整）
- **缓存命中率目标**: > 95%（用户几乎不会等待）
- **三页缓存占用**: ~144KB（3 × 48KB）
- **带宽优化**: 只发送缺失的页面，避免重复传输

## 常见问题

### Q: 如果用户翻页速度超过网络传输速度怎么办？

A: 这是设计的关键优化点：
- ESP32 防抖等待 500ms，用户通常停顿速度更快
- 手机在用户停止翻页后才发送页面
- 通常下一页会在用户停顿时已经缓存

### Q: 如何支持更多页面缓存？

A: 修改参数：
```c
#define MAX_CACHED_PAGES 5  // 从 3 改为 5
// 在 ble_reader_screen.c 中相应调整缓存窗口和清理逻辑
```

### Q: 旧页面如何删除？

A: 在 `cleanup_old_pages()` 中实现：
```c
// 清理页面 < (current - 2) 或 > (current + 2) 的文件
for (uint16_t p = 0; p < min_valid; p++) {
    char path[64];
    snprintf(path, sizeof(path), "/littlefs/ble_pages/book_%04x_page_%05u.bin", 
             book_id, p);
    remove(path);
}
```

## 集成检查清单

- [ ] Android: BleEspClient 中添加了页面管理代码
- [ ] Android: PageRenderer 接口实现（EpubPageRenderer）
- [ ] Android: onCharacteristicChanged 处理 PAGE: 消息
- [ ] ESP32: ble_reader_screen.c 添加防抖定时器
- [ ] ESP32: 添加初始化确认提示和逻辑
- [ ] ESP32: ble_manager.c 实现 ble_manager_send_notification
- [ ] 两端: 编译通过，无错误
- [ ] 测试: 手机连接 → 确认 → 初始三页 → 翻页防抖 → 页面同步

## 参考资源

- BleEspClient.kt: 手机端 BLE 通信和页面管理
- EpubPageRenderer.kt: 页面渲染接口实现示例
- ble_reader_screen.c: 电子书端屏幕和防抖逻辑
- ble_manager.c: BLE 底层通信和通知发送

---

**版本**: 1.0  
**最后更新**: 2026-01-08
