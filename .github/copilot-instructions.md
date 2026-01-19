# 阅星瞳 X4 电子书阅读系统 - AI 编码指南

## 项目架构总览

这是一个三端协同的电子墨水屏阅读器系统：

```
┌─────────────────┐     BLE (X4IM v2)    ┌─────────────────┐
│  Android App    │ ◄──────────────────► │   ESP32-C3      │
│ (GSDJX4Double   │                      │ (esp32c3x4)     │
│  SysFserv)      │                      │ 电子墨水屏设备  │
└────────┬────────┘                      └─────────────────┘
         │ HTTP
         ▼
┌─────────────────┐
│  RemoteServe    │
│ (Go/OCR服务)    │
│  + BleClient    │
└─────────────────┘
```

## 关键协议：X4IM v2

三端通信统一使用 **X4IM v2 协议**（32字节帧头）：

```javascript
// 帧头结构 (main.js:1-32)
magic (4B) = "X4IM"
version (1B) = 0x02
type (1B)    // 0x01-0x04=图片, 0x10-0x12=文档, 0x80+=命令
flags (2B)   // STORAGE_SD=0x0100, TYPE_TXT=0x0004
payload_size (4B), sequence (2B), reserved (2B), filename (16B)
```

发送数据时必须先发送帧头，再分片发送 payload。

## Android 端 (Kotlin)

### 构建与调试

```bash
# 构建 (tasks.json 已配置)
./gradlew :app:assembleDebug

# 安装到设备
./gradlew :app:installDebug

# 查看日志 (X4Service 标签)
adb logcat -s X4Service EInkA11y
```

### 核心文件

- [BleEspClientOptimized.kt](app/src/main/java/com/guaishoudejia/x4doublesysfserv/BleEspClientOptimized.kt) - BLE 客户端，实现 MTU 协商、分片传输、错误恢复
- [EpubPageRenderer.kt](app/src/main/java/com/guaishoudejia/x4doublesysfserv/EpubPageRenderer.kt) - EPUB 页面渲染为 1-bit 位图
- [WeReadActivity.kt](app/src/main/java/com/guaishoudejia/x4doublesysfserv/WeReadActivity.kt) - 微信读书集成

### BLE 开发模式

```kotlin
// MTU 协商目标 517 字节，实际 payload = MTU - 3
// 连接参数：最小间隔 7.5ms，重连使用指数退避 (1s→2s→4s)
// 位图转换：RGB565 → 1-bit（零拷贝设计）
class BleEspClientOptimized(
    private val context: Context,
    private val deviceAddress: String,
    // ...
)
```

## ESP32-C3 端 (C/ESP-IDF)

### 构建与烧录

```bash
source $IDF_PATH/export.sh
cd esp32c3x4
idf.py build
idf.py -p /dev/ttyUSB0 flash monitor
```

### 内存约束（400KB RAM）

- 接收缓冲：64KB
- 页面缓存：10 页 × 48KB = 480KB（LittleFS）
- 使用 3 槽滑动窗口：`slot0/slot1/slot2` 存储当前页±1

### 关键目录结构

```
main/
├── ui/
│   ├── ble/           # BLE 协议实现
│   ├── epub/          # EPUB 解析器和预缓存
│   ├── txt/           # TXT 阅读器（GB18030/UTF-8）
│   ├── screens/       # 各功能屏幕
│   └── fonts/         # 字体管理
├── EPD_4in26.c/h      # 电子墨水屏驱动 (800×480)
└── power_manager.c/h  # 电源管理
```

### 页面预缓存模式

```c
// 滑动窗口：缓存 [N-2, N+7] 共 10 页
// 翻页时自动预加载下 3 页，淘汰最旧页面
// 文件名格式：page_{逻辑索引}，如 page_0, page_-1
```

## RemoteServe (Go) + BleClient (Node.js)

### RemoteServe 启动

```bash
cd BleReadBook/RemoteServe
go build -o remote_serve && ./remote_serve
# 或使用 Docker
docker-compose up -d
```

### API 端点

```
GET  /health                    # 健康检查
POST /api/weread/reader         # 微信读书 OCR 识别
GET  /api/device/actions        # 设备操作查询
```

### BleClient 运行

```bash
cd BleReadBook/BleClient
pnpm install && node bleClient.js
```

## 跨组件数据流

### 翻页流程

```
1. ESP32 按键 → BLE notify 发送 NEXT_PAGE 命令
2. Android 收到命令 → 渲染下一页位图
3. 位图 RGB565→1bit → X4IM v2 分片发送
4. ESP32 接收并写入 LittleFS → 刷新 E-ink 屏
```

### 章节传输 (3 槽滑动窗口)

```javascript
// BleClient 发送时指定逻辑索引
await sendPageToDevice({
  logicalIndex: 0,  // 当前页
  content: textContent
});
// ESP32 根据 logicalIndex mod 3 映射到物理槽位
```

## 编码规范

- **Kotlin**: 使用协程处理 BLE 操作，避免主线程阻塞
- **C (ESP-IDF)**: 使用 FreeRTOS 任务，注意栈大小限制（4KB 默认）
- **Go**: 标准 net/http 路由，handler 模式
- **协议兼容**: 修改帧格式时需同步更新三端代码

## 性能基准

| 指标     | 优化前  | 优化后    |
| -------- | ------- | --------- |
| 连接时间 | 13-25s  | 5-10s     |
| 页面传输 | 3-4s    | 1-2s      |
| BLE 吞吐 | 30 Kbps | 100+ Kbps |
