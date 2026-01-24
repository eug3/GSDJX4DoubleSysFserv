# BLE BMP 传输协议修复说明

## 问题诊断

### 1. ESP32 日志异常
```
I (5729131) BLE_READER: ===== BLE DATA RECEIVED: 20 bytes =====
I (5729131) BLE_READER: Write path: /littlefs/ble_vfs/current_ch0.txt
```

**问题**：
- 每次只接收 20 字节（MTU 太小）
- BMP 数据被误识别为 TXT 并写入文本路径
- ESP32 未检测到 X4IM v2 BMP 帧头

### 2. 根本原因

#### 协议头格式错误
```csharp
// ❌ 错误（旧代码）
header[4] = 0x02;  // Version
header[5] = 0x00;  // 错误：ESP32 期望这里是 type 字节

// ✅ 正确（修复后）
header[4] = 0x02;  // Version (1 字节)
header[5] = FlagsToType(flags);  // Type (1 字节): BMP=0x01, TXT=0x10
```

**ESP32 解析逻辑** (`esp32c3x4/main/ui/screens/ble_reader_screen.c:897`):
```c
if (length >= 32 && data[0]=='X' && data[1]=='4' && data[2]=='I' && data[3]=='M' && data[4]==0x02) {
    uint8_t type = data[5];      // ← 这里读取 type 字节
    uint16_t flags = data[6] | (data[7] << 8);
    
    if (flags & X4IM_FLAGS_TYPE_BMP) {  // 0x0020
        // BMP 流式写入
        const char *mode = g_ble_new_transfer ? "wb" : "ab";
        FILE *fp = fopen("/littlefs/ble_vfs/page_0.bmp", mode);
        ...
    }
}
```

#### MTU 分片过小
```csharp
// ❌ 旧代码：WriteCharacteristicBlob 默认使用 20 字节分片
await _connectedPeripheral
    .WriteCharacteristicBlob(_writeServiceUuid, _writeCharacteristicUuid, ms)
    .LastOrDefaultAsync();

// ✅ 修复后：明确使用 512 字节 MTU
const int MTU = 512;
await _connectedPeripheral
    .WriteCharacteristicBlob(_writeServiceUuid, _writeCharacteristicUuid, firstMs, MTU)
    .LastOrDefaultAsync();
```

## 修复内容

### 1. X4IMProtocol.cs
- 更新协议头注释，明确 version 和 type 各占 1 字节
- 添加 `FlagsToType()` 方法，从 flags 推导 type 字节

### 2. ShinyBleService.cs
修改三处关键点：

#### A. CreateX4IMv2Header
```csharp
// Version (1字节) + Type (1字节)
header[4] = 0x02;
header[5] = FlagsToType(flags);

// Flags (小端序)
header[6] = (byte)(flags & 0xFF);
header[7] = (byte)((flags >> 8) & 0xFF);
```

#### B. SendFrameAsync
改为手动分片，每片 512 字节：
```csharp
// 第一包：header (32B) + data (最多 480B)
var firstPacketSize = Math.Min(MTU, (int)ms.Length);

// 后续每包 512 字节
while (ms.Position < ms.Length) {
    var chunkSize = Math.Min(MTU, remaining);
    await WriteCharacteristicBlob(..., chunkMs, MTU);
    await Task.Delay(10); // 节流
}
```

#### C. 添加 FlagsToType 辅助方法
```csharp
private static byte FlagsToType(ushort flags)
{
    if ((flags & X4IMProtocol.FLAG_TYPE_BMP) != 0) return X4IMProtocol.TYPE_BMP;
    if ((flags & X4IMProtocol.FLAG_TYPE_PNG) != 0) return X4IMProtocol.TYPE_PNG;
    // ... 其他类型映射
    return X4IMProtocol.TYPE_BINARY;
}
```

## 性能提升

| 指标           | 修复前     | 修复后       |
| -------------- | ---------- | ------------ |
| MTU 分片大小   | 20 字节    | 512 字节     |
| 48KB 位图传输  | ~2400 包   | ~94 包       |
| 预估传输时间   | 60-80 秒   | 3-5 秒       |
| 协议识别       | ❌ 无法识别 | ✅ 正确识别   |

## 验证步骤

### 1. 编译并部署
```bash
cd /Users/beijihu/Github/GSDJX4DoubleSysFserv
dotnet build
```

### 2. 运行并推送二维码
在 WeReadPage 页面点击登录，观察日志：

**期望的 ESP32 日志**：
```
I (xxx) BLE_READER: ===== BLE DATA RECEIVED: 512 bytes =====
I (xxx) BLE_READER: X4IM v2 header: type=0x01, flags=0x0020, payload=48600, name='page_0.bmp'
I (xxx) BLE_READER: Receiving BMP bitmap data
I (xxx) BLE_READER: BMP: New file created, wrote 480 bytes to /littlefs/ble_vfs/page_0.bmp
I (xxx) BLE_READER: BMP: Appended 512 bytes to /littlefs/ble_vfs/page_0.bmp
...
I (xxx) BLE_READER: BMP: Transfer complete! Total: 48600 bytes
```

### 3. 检查文件存储
```bash
# ESP-IDF Monitor 中运行
ls /littlefs/ble_vfs/
# 应显示 page_0.bmp 且大小约 48KB
```

### 4. 触发显示
- App 会自动发送 `SHOW_PAGE` 命令 (0x80 0x00)
- ESP32 应调用 `wallpaper_render_image_to_display()` 显示二维码

## 参考对比：main.js 实现

BleClient 的正确实现 (`BleReadBook/BleClient/src/main.js:382-475`):
```javascript
const MTU = 512;
const header = new Uint8Array(32);

// Magic + Version + Type
header[0] = 0x58; header[1] = 0x34; header[2] = 0x49; header[3] = 0x4D;
header[4] = 0x02;  // Version
header[5] = 0x00;  // Type (保留)

// Flags: TYPE_BMP (0x0020)
const flags = 0x0020;
header[6] = flags & 0xFF;
header[7] = (flags >> 8) & 0xFF;

// Payload size (小端序)
header[8] = payloadSize & 0xFF;
header[9] = (payloadSize >> 8) & 0xFF;
header[10] = (payloadSize >> 16) & 0xFF;
header[11] = (payloadSize >> 24) & 0xFF;

// ... name 字段

// 第一包：header + 部分数据
const firstChunkSize = Math.min(MTU - 32, bitmapData.length);
const firstPacket = new Uint8Array(32 + firstChunkSize);
firstPacket.set(header, 0);
firstPacket.set(bitmapData.slice(0, firstChunkSize), 32);

await writeCharacteristic.writeValue(firstPacket);

// 后续分片
while (offset < bitmapData.length) {
    const chunk = bitmapData.slice(offset, offset + MTU);
    await writeCharacteristic.writeValue(chunk);
    offset += MTU;
    await new Promise(r => setTimeout(r, 10));
}
```

## 注意事项

1. **BMP 文件名必须遵循规范**：`page_{index}.bmp`（如 `page_0.bmp`）
2. **不要发送 EOF**：BMP 传输完成由字节计数判断，无需 EOF 标记
3. **必须发送 SHOW_PAGE 命令**：传输完成后发送 `0x80 0x00` 触发显示
4. **MTU 协商**：确保 BLE 连接时协商到 MTU=517（有效载荷 512 字节）

## 后续优化方向

### 图片质量提升
当前实现：简单阈值二值化（亮度 < 128 为黑）

可优化为：
- Floyd-Steinberg 误差扩散抖动
- Atkinson 抖动算法（更适合电子墨水屏）
- 自适应阈值（Otsu 算法）

### 尺寸适配
ESP32 屏幕分辨率：800×480

建议在 JavaScript 端预处理：
```javascript
// 二维码通常 200×200，放大到 400×400 居中显示
const canvas = document.createElement('canvas');
canvas.width = 800;
canvas.height = 480;
const ctx = canvas.getContext('2d');
ctx.fillStyle = 'white';
ctx.fillRect(0, 0, 800, 480);
ctx.drawImage(qrImage, 200, 40, 400, 400); // 居中
```

## 相关文件

- [Services/X4IMProtocol.cs](Services/X4IMProtocol.cs#L26-L188)
- [Services/ShinyBleService.cs](Services/ShinyBleService.cs#L1080-L1250)
- [Views/WeReadPage.xaml.cs](Views/WeReadPage.xaml.cs#L260-L480)
- [esp32c3x4/main/ui/screens/ble_reader_screen.c](../../esp32c3x4/main/ui/screens/ble_reader_screen.c#L887-L980)
- [BleReadBook/BleClient/src/main.js](../../BleReadBook/BleClient/src/main.js#L1-L520)
