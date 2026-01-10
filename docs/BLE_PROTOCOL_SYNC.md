# BLE 电子书阅读协议同步规范

## 双向通信协议

### 1. 从电子书到手机：页码同步通知
**格式**: `"PAGE:<page_num>"`  
**方向**: ESP32 → Android 手机  
**触发时机**: 用户按翻页键，防抖500ms后  
**处理流程**:
```
ESP32: 用户按下翻页键
  └─> 累加 page_change_count
  └─> 启动防抖定时器(500ms)
  
ESP32: 防抖完成
  └─> 计算最终页码
  └─> 调用 send_page_sync_notification(final_page)
  └─> 通过 BLE Notification 发送 "PAGE:123"
  
Android: BleEspClient.onCharacteristicChanged() 接收
  └─> 解析 "PAGE:123" 提取页码 = 123
  └─> 调用 handlePageChangeNotification(123)
  └─> 将 123-1, 123, 123+1 加入待发送队列
  └─> 调用 sendQueuedPages() 异步发送
  └─> 注意：不再调用 GeckoActivity.onCommand()
```

### 2. 从手机到电子书：位图数据
**协议**: X4IM  
**格式**:
```
Byte 0-3:   "X4IM" (ASCII)
Byte 4:     Version = 1
Byte 5-7:   Reserved = 0
Byte 8-11:  Payload size (uint32 LE) = 48000
Byte 12+:   1-bit 黑白位图数据 (480×800 = 48000字节)
```
**尺寸**: 12 + 48000 = 48012 字节  
**MTU分割**: BLE MTU ~100字节，自动分割成多个 ATT packet  
**处理流程**:
```
Android GeckoActivity: sendPageByNumber(pageNum)
  └─> 调用 gotoLogicalPage(pageNum) 跳转网页
  └─> 调用 extractDomLayoutJson() 获取DOM布局
  └─> 调用 captureViewportBitmap() 截图
  └─> 调用 DomLayoutRenderer.renderTo1bpp48k() 渲染为1bit
  └─> 调用 BleEspClient.sendRawBitmap(bitmap_data)
  
BleEspClient: sendRawBitmap()
  └─> 构造 X4IM 帧头（12字节）
  └─> 合并 [帧头 + 位图数据] = 48012字节
  └─> 分割为 MTU 大小的包
  └─> 逐个发送（监听 onCharacteristicWrite 确认）
  
ESP32: ble_data_received_callback()
  └─> 检测 X4IM 帧头 "X4IM"
  └─> 解析 payload_size = 48000
  └─> 分配 48000 字节缓冲区
  └─> 累积接收数据块
  └─> 显示进度: "Receiving bitmap: X/48000 bytes (Z%)"
  └─> 当 received >= expected 时，完成接收
  └─> 保存到 /littlefs/ble_pages/book_XXXX_page_YYYY.bin
  └─> 触发屏幕重绘
```

## 当前实现对比

### ✅ ESP32 端 (ble_reader_screen.c)
```c
send_page_sync_notification(page_num)
  └─> snprintf(msg, "PAGE:%u", page_num)
  └─> ble_manager_send_notification(msg, strlen(msg))  // ✓ 正确
```

### ✅ Android 端 - BleEspClient.kt 
```kotlin
onCharacteristicChanged(characteristic) {
  val cmd = value.toString(Charsets.UTF_8).trim()
  if (cmd.startsWith("PAGE:")) {
    val pageNum = cmd.substring(5).toInt()
    handlePageChangeNotification(pageNum)  // 处理并回传给调用者
  }
}

handlePageChangeNotification(pageNum) {
  Log.d("Received page notification: $pageNum")
  onCommand("PAGE:$pageNum")  // ✓ 通知 GeckoActivity 处理
}
```

### ✅ Android 端 - GeckoActivity.kt
```kotlin
onCommand = { cmd ->
  if (cmd.startsWith("PAGE:")) {
    val pageNum = cmd.substringAfter("PAGE:").toIntOrNull()
    if (pageNum != null) {
      scope.launch {
        Log.d("电子书请求页码: $pageNum")
        sendPageByNumber(pageNum)  // ✓ 在 Gecko 中跳转并发送
      }
    }
  }
}

suspend fun sendPageByNumber(pageNum: Int) {
  val layoutJson = gotoLogicalPage(pageNum)  // 通过 Gecko 跳转
  val screenshot = captureViewportBitmap()
  val render = DomLayoutRenderer.renderTo1bpp48k(layoutJson, screenshot)
  bleClient?.sendRawBitmap(render.pageBytes48k)  // ✓ X4IM 发送
}
```

## 三页缓存策略

当电子书请求 `PAGE:100` 时：
1. BleEspClient 自动请求页码: [99, 100, 101]
2. GeckoActivity.sendQueuedPages() 逐个渲染并发送
3. ESP32 保存到文件系统，维护三页窗口缓存
4. 用户继续翻页时，下一个请求的页可能已经在缓存中

## 数据流示意

```
用户在电子书上按"下一页"键
    ↓
ESP32 debounce_timer_callback
    ↓
send_page_sync_notification("PAGE:123")
    ↓
    └─→ BLE Notification → 手机
         ↓
      BleEspClient.onCharacteristicChanged()
      解析 "PAGE:123"
         ↓
      handlePageChangeNotification(123)
      队列: [122, 123, 124]
         ↓
      sendQueuedPages() 异步发送
         ↓
      GeckoActivity.sendPageByNumber(122)
      渲染 + 调用 sendRawBitmap()
         ↓
      BleEspClient.sendRawBitmap()
      构造 X4IM + 分割 + 发送
         ↓
         └─→ BLE WriteNoResponse → ESP32
             ↓
          ble_data_received_callback()
          累积 X4IM 数据块
             ↓
          保存到 /littlefs/ble_pages/book_XXXX_page_122.bin
             ↓
          显示在 e-ink 屏幕上
```

## 需要修正的地方

1. ✅ **BleEspClient.handlePageChangeNotification() 修正完成**
   - 改为：接收 "PAGE:XXX"，直接通过 onCommand() 回传给 GeckoActivity
   - 理由：GeckoActivity 是 Gecko 浏览器的宿主，只有它能进行页面跳转和DOM提取

2. ✅ **GeckoActivity 的 onCommand 回调修正完成**  
   - 改为：接收并处理 "PAGE:XXX" 消息
   - 流程：PAGE:123 → sendPageByNumber(123) → gotoLogicalPage() → renderTo1bpp48k() → sendRawBitmap()

3. ✅ **BleEspClient - send_page_sync_notification 格式正确**

4. ✅ **ESP32 - send_page_sync_notification 正确**

5. ✅ **位图 X4IM 格式和接收逻辑正确**
