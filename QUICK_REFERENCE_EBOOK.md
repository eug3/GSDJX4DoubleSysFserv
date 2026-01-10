# 电子书 BLE 三页缓存 - 快速参考卡

## 🎯 快速开始

### Android 端
```kotlin
// 1. 创建渲染器
val renderer = EpubPageRenderer(context, "/path/to/book.epub")

// 2. 创建 BLE 客户端
val bleClient = BleEspClient(
    context = this,
    deviceAddress = "XX:XX:XX:XX:XX:XX",
    scope = lifecycleScope,
    onCommand = { cmd -> Log.d(TAG, cmd) },
    pageRenderer = renderer
)

// 3. 连接
bleClient.connect()

// 4. 初始化
bleClient.initializeBookReading(bookId = 1, renderer = renderer)

// 5. 用户确认后发送初始三页
bleClient.sendInitialThreePages()

// ✅ 后续自动处理！页码通知会自动发送缺失页面
```

### ESP32 端
```c
// 在 on_event 中处理翻页（自动防抖）
case BTN_RIGHT:
case BTN_VOLUME_DOWN:
    s_ble_state.page_change_count++;
    // 防抖定时器自动启动/重启
    // → 500ms 后自动发送 PAGE:X 通知手机
```

---

## 📋 核心概念

### 三页缓存窗口
```c
cached_pages[0] = page - 1   // 上一页
cached_pages[1] = page       // 当前页
cached_pages[2] = page + 1   // 下一页
```

### 页码同步消息
```
格式: PAGE:<page_num>
示例: PAGE:5
用途: 电子书告诉手机"我现在显示第5页"
```

### 防抖流程
```
用户按键 → 累加计数 → 启动定时器
     ↓
用户再按键 → 加总计数 → 定时器重启
     ↓
500ms 无新按键 → 定时器触发 → 计算目标页 → 发送通知
```

---

## 🔧 关键代码片段

### Android: 处理页码通知
```kotlin
// 自动！在 BleEspClient.onCharacteristicChanged 中
if (cmd.startsWith("PAGE:")) {
    val pageNum = cmd.substring(5).toInt()
    handlePageChangeNotification(pageNum)  // 自动发送缺失页面
}
```

### ESP32: 防抖定时器回调
```c
void debounce_timer_callback(TimerHandle_t xTimer)
{
    // 计算最终页码
    int new_page = s_ble_state.current_page + s_ble_state.page_change_count;
    
    // 发送给手机
    send_page_sync_notification(new_page);
    
    // 重置
    s_ble_state.page_change_count = 0;
}
```

### Android: 发送初始三页
```kotlin
fun sendInitialThreePages() {
    scope.launch(Dispatchers.Default) {
        for (pageNum in 1..3) {
            val bitmap = pageRenderer?.renderPage(pageNum)
            if (bitmap != null) {
                sendBitmap(bitmap)
                sentPages.add(pageNum)
                delay(100)  // 等待发送完成
            }
        }
        bookInitialized = true
    }
}
```

---

## ✅ 工作流检查

- [ ] 手机连接电子书
- [ ] 电子书显示"点击确认"
- [ ] 用户点击确认
- [ ] 手机发送初始三页
- [ ] 电子书显示第一页
- [ ] 用户翻页一次
- [ ] 手机发送对应页面
- [ ] 用户快速翻页 3 次
- [ ] 防抖：只发送一次（最终页码）
- [ ] 三页缓存中有旧页时，清理旧页

---

## 🐛 调试技巧

### 查看日志
```
// Android
D/BleEspClient: Queued page X for sending
I/BleEspClient: Rendering and sending page X...

// ESP32
I/BLE_READER: Debounce complete: page_change_count=N, new_page=X
I/BLE_READER: Updated cache window: prev=X, current=Y, next=Z
I/BLE_READER: Sending page notification: PAGE:X
```

### 验证缓存
```bash
# ESP32 LittleFS
ls /littlefs/ble_pages/
# 应该看到：
# - book_0001_page_00000.bin
# - book_0001_page_00001.bin
# - book_0001_page_00002.bin
# (只有三页)
```

### 测试防抖
1. 按一下翻页键 → 检查 page_change_count=1
2. 立即再按一下 → 检查 page_change_count=2
3. 等待 500ms 不按 → 定时器触发，发送 PAGE:X

---

## 📦 文件清单

### Android
- [x] `BleEspClient.kt` - 核心通信类
- [x] `EpubPageRenderer.kt` - 页面渲染器
- [x] `BluetoothBookActivity.kt` - 集成示例

### ESP32
- [x] `ble_reader_screen.c` - 屏幕和防抖逻辑
- [x] `ble_manager.c` - BLE 通知发送
- [x] `ble_manager.h` - 函数声明

### 文档
- [x] `EBOOK_BLE_INTEGRATION.md` - 详细集成指南
- [x] `IMPLEMENTATION_SUMMARY.md` - 实现总结
- [x] `QUICK_REFERENCE.md` - 本文件

---

## ⚡ 性能指标

| 指标 | 值 | 说明 |
|------|-----|------|
| 防抖延迟 | 500ms | 用户停止翻页后延迟 |
| 三页缓存 | ~144KB | 3 × 48KB 位图 |
| 传输速率 | ~100KB/s* | BLE 3.0 |
| 单页加载 | ~500ms* | 从发送到显示 |
| 缓存命中 | >95% | 用户无感知延迟 |

*实际值取决于网络和系统负载

---

## 🎓 学习路径

1. **理解业务流程** → 读 IMPLEMENTATION_SUMMARY.md
2. **学习代码实现** → 查看 Android 和 ESP32 源码
3. **集成到项目** → 参考 BluetoothBookActivity.kt
4. **调试和优化** → 使用调试技巧

---

## 🚀 常见任务

### 增加缓存页数
```c
// 从 3 改为 5
uint16_t cached_pages[5];

// 在 update_cached_window 中更新逻辑
cached_pages[0] = page - 2;
cached_pages[1] = page - 1;
cached_pages[2] = page;
cached_pages[3] = page + 1;
cached_pages[4] = page + 2;
```

### 修改防抖延迟
```c
#define DEBOUNCE_DELAY_MS 300  // 改为 300ms
```

### 自定义命令处理
```kotlin
// Android 端
when {
    cmd.startsWith("BATTERY:") -> { /* 处理电池信息 */ }
    cmd.startsWith("BOOKMARK:") -> { /* 处理书签 */ }
    else -> { /* 其他命令 */ }
}
```

---

## ❓ FAQ

**Q: 如果用户翻页速度非常快会怎样？**
A: 防抖延迟 500ms，快速翻页会累加计数，最后只发送一次目标页码。

**Q: 缓存命中率是多少？**
A: 设计目标 >95%，因为防抖等待时间足够将页面预加载到缓存。

**Q: 如何支持彩色电子书？**
A: 将位图格式从 RGB565 改为其他格式（如 RGBA8888），相应调整传输大小。

**Q: 是否支持多本书同时阅读？**
A: 是，通过 bookId 区分。每本书有独立的缓存目录。

**Q: 离线模式怎么实现？**
A: 提前将整本书下载到电子书，按需读取本地缓存，无需 BLE 同步。

---

**最后更新**: 2026-01-08  
**版本**: 1.0
