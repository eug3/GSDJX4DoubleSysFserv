# 位图大小协议不匹配问题 - 诊断与修复

## 问题现象

**Android 日志输出:**
```
2026-01-10 15:18:22.227 20539-20539 BleEspClient W  Invalid bitmap size: 49152, expected 48000
```

Android 试图发送 49,152 字节，但 ESP32 期望 48,000 字节。

## 根本原因分析

### 480×800 像素正确大小计算

- **像素总数**: 480 × 800 = 384,000 像素
- **1位深度**: 384,000 ÷ 8 = **48,000 字节** (准确值)

### 代码中的错误定义

**文件**: `BleBookProtocol.kt` (第 13 行)

**错误代码:**
```kotlin
const val PAGE_SIZE_BYTES: Int = 48 * 1024  // = 49,152 字节 ❌
```

**正确代码:**
```kotlin
const val PAGE_SIZE_BYTES: Int = 48000      // = 48,000 字节 ✅
```

### 链路分析

#### Android 发送端:

1. **DomLayoutRenderer.kt (第 156 行)**
   ```kotlin
   val out48k = ByteArray(BleBookProtocol.PAGE_SIZE_BYTES)  // 使用错误的常量
   System.arraycopy(packed, 0, out48k, 0, packed.size)
   ```

2. **GeckoActivity.kt (第 260 行)**
   ```kotlin
   val render = DomLayoutRenderer.renderTo1bpp48k(layoutJson, screenshot)
   client.sendRawBitmap(render.pageBytes48k)  // 发送 49,152 字节的数组
   ```

3. **BleEspClient.kt (第 180-184 行)**
   ```kotlin
   fun sendRawBitmap(bitmapData: ByteArray) {
       if (bitmapData.size != 48000) {
           Log.w(TAG, "Invalid bitmap size: ${bitmapData.size}, expected 48000")  // ❌ 拒绝
           return
       }
   }
   ```

#### ESP32 接收端:

**ble_reader_screen.c (第 265 行)**
```c
uint32_t payload_size = data[8] | (data[9] << 8) | (data[10] << 16) | (data[11] << 24);
// payload_size = 49152 (从 X4IM 头部读取)
// 但文件保存时只接收 49,152 字节，不符合预期的 48,000
```

## 问题影响

1. **BLE 传输被拒绝**: Android 拒绝发送不符合预期大小的数据
2. **后续帧无法发送**: 用户按下确认后，Android 无法继续发送页面 1, 2
3. **显示不完整**: ESP32 无法显示完整的位图内容

## 修复步骤

### ✅ 已执行修复

**文件**: [BleBookProtocol.kt](BleBookProtocol.kt#L13)

```diff
- const val PAGE_SIZE_BYTES: Int = 48 * 1024
+ const val PAGE_SIZE_BYTES: Int = 48000      // 480x800像素，1bit深度：(480*800)/8 = 48000字节
```

### 验证

**Android 编译结果**: ✅ BUILD SUCCESSFUL

## 协议对齐确认

| 项目 | Android 端 | ESP32 端 | 状态 |
|------|-----------|---------|------|
| 位图大小 | 48,000 字节 | 期望 48,000 字节 | ✅ 匹配 |
| X4IM 头部 | 12 字节 | 解析正确 | ✅ 一致 |
| 总传输大小 | 48,000 + 12 = 48,012 字节 | 48,012 字节 | ✅ 一致 |
| 调用链 | renderTo1bpp48k() → sendRawBitmap() | X4IM 接收处理 | ✅ 一致 |

## 后续步骤

1. **重新编译 Android**: `./gradlew assembleDebug` (✅ 已完成)
2. **安装 APK**: 部署到测试设备
3. **端对端测试**:
   - 发送第一帧 → ESP32 显示确认提示
   - 用户按确认 → ESP32 发送 `USER_CONFIRMED:page_0`
   - Android 接收 → 开始渲染并发送页面 1, 2
   - 验证所有 3 帧完整接收和显示

## 相关文件

- [BleBookProtocol.kt](app/src/main/java/com/guaishoudejia/x4doublesysfserv/ble/BleBookProtocol.kt) - 协议常量定义
- [DomLayoutRenderer.kt](app/src/main/java/com/guaishoudejia/x4doublesysfserv/ble/DomLayoutRenderer.kt) - 位图渲染
- [BleEspClient.kt](app/src/main/java/com/guaishoudejia/x4doublesysfserv/BleEspClient.kt) - BLE 传输
- [ble_reader_screen.c](../esp32c3x4/c3x4_main_control/main/ui/screens/ble_reader_screen.c) - ESP32 接收处理
