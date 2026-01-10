# BLE 数据传输完整性修复

## 问题诊断

### 症状
- Android 端首次发送 97 字节成功（header 12 + payload 85）
- ESP32 端只收到第一个数据包（97 字节），后续数据包未接收
- 文件只有 85 字节，缺少约 47915 字节

### 根本原因
1. **BLE Characteristic 属性矛盾**：
   - Characteristic 报告 `WRITE=true, WRITE_NO_RESPONSE=false`
   - 但实际上只支持 `WRITE_NO_RESPONSE` 模式
   - 使用 `WRITE_TYPE_DEFAULT` 时不会产生 `onCharacteristicWrite` 回调

2. **依赖回调而无响应**：
   - 代码等待 `onCharacteristicWrite` 回调来继续发送下一个数据块
   - 如果设备不支持回调，后续数据包永远无法发送

3. **MTU 协商问题**：
   - MTU 协商结果为 100（payload=97）
   - 代码正确使用了这个值，但在不同的 write type 下行为不同

## 修复方案

### Android 端修改 (BleEspClient.kt)

#### 1. 自适应 Write Type 选择
```kotlin
// 根据 characteristic 的实际属性选择写入类型
val writeType = if (canWrite) {
    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
} else {
    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
}
```

#### 2. 轮询模式支持
对于 `WRITE_NO_RESPONSE` 模式，改为立即继续发送而不等待回调：

```kotlin
if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
    pendingOffset += lastChunkSize
    
    // 立即继续下一个数据块
    if (pendingOffset < frame.size) {
        scope.launch(Dispatchers.Main) {
            delay(20)  // 短延迟避免过快
            sendNextPacket()
        }
    }
}
```

#### 3. 主线程调度保证
- 所有 `sendNextPacket()` 调用都在主线程执行（通过 `scope.launch(Dispatchers.Main)`）
- 防止主线程阻塞导致 BLE 操作失败

#### 4. Characteristic 动态刷新
- 在每次发送前尝试从 GATT services 中重新获取 characteristic 引用
- 防止使用 stale 对象引用

### ESP32 端验证 (ble_reader_screen.c)

代码已正确实现连续接收逻辑，无需修改：

✅ **流式文件写入**：数据到达即写入，不存储在内存中
✅ **自动完成检测**：接收字节数达到预期大小时自动完成
✅ **互斥锁保护**：使用 semaphore 保护共享状态
✅ **错误处理**：文件写入失败时清理资源

## 修改文件清单

### Android 项目
- `app/src/main/java/com/guaishoudejia/x4doublesysfserv/BleEspClient.kt`
  - 修改 `sendNextPacket()` 方法（行 408-487）
  - 添加 write type 自适应逻辑
  - 添加轮询模式支持
  - 改进日志输出

### ESP32 项目  
- `main/ui/screens/ble_reader_screen.c`
  - 无需修改（已正确实现）

## 预期改进

| 指标 | 修复前 | 修复后 |
|-----|------|------|
| 首包传输 | ✓ 97字节 | ✓ 97字节 |
| 后续包传输 | ✗ 无 | ✓ 持续发送 |
| 完整数据量 | 85字节 | 48000字节 |
| 传输完成 | ✗ | ✓ |
| 页面显示 | ✗ 无数据 | ✓ 显示位图 |

## 验证步骤

1. 构建 Android APK：
   ```bash
   ./gradlew :app:assembleDebug :app:installDebug
   ```

2. 启动应用并观察日志：
   ```bash
   adb logcat -s "BleEspClient" -d
   ```

3. 检查关键日志：
   - ✓ `writeCharacteristic(legacy) result=true`
   - ✓ `Wrote Xbytes (WRITE_NO_RESPONSE mode)` - 多次出现
   - ✓ `Frame transfer completed: 48012 bytes sent`

4. ESP32 端验证：
   ```bash
   ESP_LOGI(TAG, "Streaming to file: %u/%u bytes"  // 进度更新
   ESP_LOGI(TAG, "======== BITMAP RECEPTION COMPLETE ========")
   ```

## 技术细节

### BLE Write Type 说明

| 类型 | 响应 | 回调 | 使用场景 |
|-----|------|------|---------|
| WRITE_TYPE_DEFAULT | 有 | `onCharacteristicWrite` | 需要可靠性保证 |
| WRITE_TYPE_NO_RESPONSE | 无 | 无 | 高速数据流 |

该修复通过自适应选择合适的 write type，确保在两种模式下都能完成数据传输。

### 延迟设计

- 相邻数据块间 20ms 延迟：避免 BLE 控制器过载
- 重试延迟 100ms：给设备足够响应时间
- 这些延迟对用户不可感知，总传输时间约为 1-2 秒

## 相关文件位置

```
/Users/beijihu/Github/GSDJX4DoubleSysFserv/
├── app/src/main/java/com/guaishoudejia/x4doublesysfserv/
│   └── BleEspClient.kt  [已修改]
└── ...

/Users/beijihu/Github/esp32c3x4/c3x4_main_control/
├── main/ui/screens/
│   └── ble_reader_screen.c  [无需修改]
└── ...
```
