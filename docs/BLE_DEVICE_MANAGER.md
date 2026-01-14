# BLE 设备管理功能说明

## 功能概览

已将 BLE 硬件扫描和连接功能集成到阅读界面，采用**浮动按钮 + 展开菜单 + 底表选择**的交互模式。

### 核心特性

1. **左侧浮动按钮**
   - 位置：阅读 View 左侧
   - 颜色：绿色（已连接）/ 蓝色（未连接）
   - 图标：📡（卫星信号）
   - 点击展开菜单

2. **展开菜单选项**
   - ✓ 显示连接状态（已连接/未连接）
   - 🔍 **选择设备** - 触发扫描底表
   - 🗑️ **忘记设备** - 删除已保存的 MAC 地址（仅在已连接时显示）
   - ✕ **关闭** - 折叠菜单

3. **MAC 地址持久化**
   - 首次选中设备后自动保存到 SharedPreferences
   - 下次启动自动连接到上次保存的设备
   - 连接失败不影响 OCR 和图片获取

4. **设备扫描底表**
   - 自动扫描周围 BLE 设备
   - 显示设备名称、MAC 地址和 RSSI 信号强度
   - 点击设备自动连接并保存
   - 10 秒后自动停止扫描（节省电量）

5. **关键特性**
   - ✅ BLE 连接完全可选（不影响 OCR）
   - ✅ 连接失败时仍可正常获取图片和进行 OCR
   - ✅ 只有在 BLE 连接成功时才发送数据到硬件

---

## 文件清单

### 新增文件

1. **BleDeviceManager.kt** - BLE 设备持久化管理
   - 保存/读取 MAC 地址
   - 保存/读取设备名称
   - 忘记设备功能

2. **BleConnectionManager.kt** - BLE 连接管理器
   - 设备扫描逻辑
   - 自动连接已保存设备
   - 权限检查
   - 连接状态管理

3. **BleComponents.kt** - UI 组件库
   - `BleFloatingButton()` - 左侧浮动按钮
   - `BleDeviceScanSheet()` - 设备选择底表
   - `BleDeviceItem` - 设备数据类

### 修改文件

1. **MainActivity.kt**
    - 添加 `BleConnectionManager` 实例
    - 集成浮动按钮

---

## 使用流程

### 初次连接

1. 启动应用
2. 左侧出现蓝色浮动按钮（📡 未连接状态）
3. 点击按钮展开菜单
4. 选择"🔍 选择设备"
5. 在底表中选择目标 BLE 设备
6. 设备连接成功，按钮变绿色
7. MAC 地址自动保存

### 后续使用

1. 启动应用时自动连接到上次保存的设备
2. 连接成功后按钮显示为绿色
3. 数据同步时自动发送图片数据到 BLE 设备

### 切换设备

1. 点击浮动按钮展开菜单
2. 选择"🔍 选择设备"
3. 在底表中选择新设备
4. 新 MAC 地址自动覆盖旧地址

### 忘记设备

1. 点击浮动按钮展开菜单
2. 选择"🗑️ 忘记设备"
3. 删除保存的 MAC 地址
4. 下次需重新选择设备

---

## 技术实现细节

### SharedPreferences 存储

```
ble_device_manager:
├── saved_device_address    (String) - 设备 MAC 地址
├── saved_device_name       (String) - 设备显示名称
└── last_connected_time     (Long)   - 上次连接时间戳
```

### 状态管理

```kotlin
// BleConnectionManager 状态
isConnected              // 连接状态
connectedDeviceName      // 当前设备名
connectedDeviceAddress   // 当前设备 MAC
isScanning               // 扫描中标志
showScanSheet            // 底表显示状态
scannedDevices          // 扫描结果列表
```

### BLE 连接流程

```
用户选择设备
    ↓
connectToDevice()
    ↓
保存到 SharedPreferences
    ↓
创建 BleEspClientOptimized 实例
    ↓
调用 connect()
    ↓
监听连接状态回调
    ↓
更新 UI（按钮颜色、菜单显示）
```

### 页面同步流程

```
performSync(pageNum)
    ├─ 获取 Canvas 图片
    ├─ 渲染为 1-bit 图像
    ├─ [可选] 发送到 BLE 设备（如果已连接）
    └─ OCR 识别（独立进行）
```

---

## 权限声明

```xml
<!-- AndroidManifest.xml 已有的权限 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" /> (API 31+)
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" /> (API 31+)
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

---

## 测试建议

1. **基础连接测试**
   - [ ] 浮动按钮显示正确
   - [ ] 扫描能发现 BLE 设备
   - [ ] 点击设备能成功连接
   - [ ] 连接状态正确更新

2. **持久化测试**
   - [ ] 杀死 App 后重启自动连接
   - [ ] MAC 地址正确保存
   - [ ] 忘记设备后不自动连接

3. **独立性测试**
   - [ ] BLE 未连接时 OCR 能正常工作
   - [ ] BLE 连接失败不影响图片获取
   - [ ] OCR 识别独立于 BLE 状态

4. **性能测试**
   - [ ] 扫描不卡顿 UI
   - [ ] 连接不影响页面翻动
   - [ ] 底表动画流畅

---

## 已知限制

1. **权限处理**
   - Android 12+：需要 BLUETOOTH_SCAN 和 BLUETOOTH_CONNECT 权限
   - 权限请求在调用方处理（不在 BleConnectionManager 中）

2. **设备支持**
   - 只支持 BLE 设备
   - 经典蓝牙不支持

3. **扫描机制**
   - 扫描持续 10 秒自动停止
   - 不支持后台扫描

---

## 故障排除

### 无法扫描到设备
- [ ] 检查 Bluetooth 是否启用
- [ ] 检查 BLE 设备是否开启
- [ ] 检查应用权限（设置 → 应用权限）
- [ ] 尝试手动停止扫描后重试

### 连接失败
- [ ] 检查 BLE 设备是否在范围内
- [ ] 尝试忘记设备并重新连接
- [ ] 检查日志：`BleConnectionManager`, `BleEspClientOptimized`

### 页面不同步
- [ ] 检查 BLE 连接状态（浮动按钮颜色）
- [ ] BLE 连接失败不影响 OCR（请查看 OCR 结果）
- [ ] 检查日志中的"发送 BLE 数据"消息

---

## 日志标签

| 标签 | 功能 |
|------|------|
| `BleConnectionManager` | 连接管理 |
| `BleDeviceManager` | 持久化存储 |
| `BleEspClientOptimized` | BLE 通信 |
| `SYNC` | 页面同步 |
| `MainActivity` | 主 Activity |

---

## 下一步优化方向

1. **功能优化**
   - [ ] 支持后台扫描
   - [ ] 支持多设备切换历史记录
   - [ ] 设备信号强度提示

2. **UI 优化**
   - [ ] 扫描进度条动画
   - [ ] 设备连接失败提示
   - [ ] 底表滑动预览

3. **性能优化**
   - [ ] 缓存扫描结果
   - [ ] 优化 BLE 吞吐量
   - [ ] 减少内存占用

4. **用户体验**
   - [ ] 添加连接超时提示
   - [ ] 支持快速重连按钮
   - [ ] 连接成功/失败声音提示
