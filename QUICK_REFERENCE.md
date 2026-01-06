# BLE 通信快速参考卡

## 📋 性能指标速查表

### 连接性能
```
连接时间：       5-10 秒
重连时间：       2-15 秒（指数退避）
MTU 大小：       517 字节（协商）
有效载荷：       514 字节（MTU - 3）
包数据：         227 字节（预留头部）
```

### 传输性能
```
理论吞吐量：     22.7 KB/s
实际吞吐量：     100-150 Kbps（优化后）
48KB 页面：      1-2 秒
丢包率：         < 1%
重试次数：       < 3
```

### 渲染性能
```
页面渲染：       10-20 ms（零拷贝）
1 位转换：       5-10 ms
总延迟：         20-35 ms
Canvas 方式：    50-100 ms（不推荐）
```

### 内存占用
```
Android：        25-30 MB
  - 像素数组：   15 MB
  - 缓冲：       7.5 MB
  - 队列：       2 MB

ESP32：          ~750 KB
  - RX 缓冲：    64 KB
  - 缓存页：     480 KB（10×48KB）
  - 系统：       200 KB
```

---

## 🔧 常见配置

### Android 端 - 优化建议

```kotlin
// 连接参数
val params = BleConnectionParams(
    minIntervalMs = 7.5f,     // 最小间隔
    maxIntervalMs = 15f,       // 最大间隔
    slaveLatency = 0,          // 无延迟
    supervisionTimeout = 6000  // 6 秒超时
)

// MTU 目标
val targetMtu = 517

// 重连策略
val maxReconnectAttempts = 3
val baseBackoffMs = 1000L
val maxBackoffMs = 30000L
```

### ESP32 端 - 缓存配置

```c
ble_cache_config_t config = {
    .max_cached_pages = 10,           // 10 页缓存
    .cache_size_bytes = 480 * 1024,   // 480 KB 总大小
    .page_size_bytes = 48 * 1024,     // 48 KB/页
    
    .window_size = 8,                 // 8 页窗口
    .prefetch_threshold = 3,          // 预加载阈值
    .prefetch_delay_ms = 50,          // 预加载延迟
    
    .page_ttl_seconds = 3600,         // 1 小时过期
    .use_psram = true                 // 使用 PSRAM
};
```

---

## 📊 决策树

### 吞吐量低 (< 50 Kbps)

```
是否成功协商 MTU?
├─ 否 → 检查 Android 蓝牙设置
├─ 是 → 检查 WiFi 干扰
    ├─ 关闭 WiFi 测试
    └─ 如果改善 → 增大连接间隔
```

### 页面加载慢 (> 3s)

```
是否在缓存中?
├─ 是 → 检查 LittleFS 速度
│   └─ 使用 Android Profiler
└─ 否 → 检查预加载
    └─ 验证请求是否发送
```

### 连接不稳定

```
频繁断开?
├─ 是 → 增大超时时间
│   └─ supervisionTimeout = 10000
└─ 否 → 检查信号强度
    └─ RSSI > -75 dBm
```

---

## 🧪 快速测试

### 连接测试
```
预期：5-10 秒内连接成功
验证：日志显示 "Connection status: READY"
```

### 传输测试
```
发送 1×48KB 位图
预期：1-2 秒完成
验证：日志显示吞吐量 > 100 Kbps
```

### 缓存测试
```
连续翻页 10 次
预期：第 2-10 次瞬时响应（无加载延迟）
验证：缓存命中率 > 95%
```

---

## 🐛 常见问题速解

| 问题 | 原因 | 解决方案 |
|-----|------|---------|
| 连接超时 | 设备未在广告 | 重启 ESP32 |
| MTU 失败 | 设备不支持 | 使用默认 MTU (23) |
| 吞吐低 | WiFi 干扰 | 关闭 WiFi 或改变位置 |
| 缓存未命中 | 预加载延迟 | 增大窗口或预加载阈值 |
| 内存溢出 | 缓存过多 | 减少 max_cached_pages |

---

## 📱 API 速查

### Android - 主要方法

```kotlin
// 连接
bleClient.connect()

// 发送位图
bleClient.sendBitmap(bitmap)

// 零拷贝渲染
bleClient.renderAndSendPage(width, height) { pixels ->
    // 直接操作像素
}

// 发送 JSON 命令
bleClient.sendJson(jsonString)

// 关闭连接
bleClient.close()

// 获取状态
val status = bleClient.currentStatus
val metrics = bleClient.metrics
```

### ESP32 - 主要 API

```c
// 初始化
ble_cache_manager_init(&config);

// 更新窗口
ble_cache_update_window(current_page);

// 检查缓存
if (ble_cache_page_exists(book_id, page)) {
    ble_cache_read_page(book_id, page, buffer, size);
}

// 写入分片
ble_cache_write_page_chunk(book_id, page, offset, data, len, total_size);

// 获取统计
ble_cache_get_stats(&hits, &misses, &cached_pages);
```

---

## 🎯 检查清单

### 集成前
- [ ] 阅读 BLE_BEST_PRACTICES.md
- [ ] 审查 BleEspClientOptimized.kt 代码
- [ ] 准备测试设备

### 集成中
- [ ] 复制新文件到项目
- [ ] 更新导入和初始化代码
- [ ] 编译并测试连接
- [ ] 运行性能测试

### 集成后
- [ ] 验证所有性能指标
- [ ] 测试错误恢复
- [ ] 检查内存占用
- [ ] 部署到生产环境

---

## 📞 调试技巧

### 启用详细日志
```kotlin
// Android
Log.setLevel(Log.DEBUG)

// ESP32
esp_log_level_set("BleCacheMgr", ESP_LOG_DEBUG);
esp_log_level_set("BleManager", ESP_LOG_DEBUG);
```

### 性能分析
```kotlin
// 使用 Android Profiler
// - Memory：检查内存泄漏
// - CPU：确认 UI 线程未阻塞
// - Network：监控蓝牙流量
```

### 包嗅探
```bash
# 使用 nRF Connect 查看 GATT 特征
# 使用 Wireshark 捕获 BLE 包
```

---

## 🔗 快速链接

- 📖 完整指南：[BLE_BEST_PRACTICES.md](./BLE_BEST_PRACTICES.md)
- 🔧 集成指南：[BLE_INTEGRATION_GUIDE.md](./BLE_INTEGRATION_GUIDE.md)
- 🎨 位图处理：[BITMAP_PROCESSING_GUIDE.md](./BITMAP_PROCESSING_GUIDE.md)
- 📊 项目总结：[PROJECT_SUMMARY.md](./PROJECT_SUMMARY.md)
- 💻 源代码：[BleEspClientOptimized.kt](./app/src/main/java/com/guaishoudejia/x4doublesysfserv/BleEspClientOptimized.kt)

---

**版本**：1.0  
**更新**：2026-01-06  
**对象**：开发者、测试工程师、产品经理
