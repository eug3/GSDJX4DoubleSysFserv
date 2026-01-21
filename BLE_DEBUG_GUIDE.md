# 蓝牙扫描问题修复指南

## ✅ 已修复的问题

### 1. **Android 运行时权限缺失**
- **问题**: 虽然 `AndroidManifest.xml` 声明了权限，但没有在运行时请求
- **修复**: 在 `SettingsPage.xaml.cs` 中添加了完整的权限请求逻辑
  - Android 12+ (API 31+): 请求 `BLUETOOTH_SCAN` 和 `BLUETOOTH_CONNECT`
  - Android 11 及以下: 请求 `ACCESS_FINE_LOCATION`

### 2. **蓝牙未启用检测**
- **问题**: 扫描前未检查蓝牙是否开启
- **修复**: 添加 `CheckBluetoothEnabled()` 方法，引导用户开启蓝牙

### 3. **错误提示不明确**
- **问题**: 扫描失败时只有 Debug 日志，用户不知道原因
- **修复**: 
  - 改进了错误提示，明确告知权限、蓝牙状态等问题
  - 增加了详细的调试日志
  - 扫描结果为空时提供排查建议

### 4. **设备过滤过于严格**
- **问题**: 只显示有名字的设备，可能漏掉 ESP32（有时不广播名字）
- **修复**: 
  - 显示所有有名字的设备
  - 对无名字但信号强 (RSSI > -70) 的设备也显示
  - 记录所有发现的设备到日志，方便调试

## 📋 测试步骤

### 1. **清理并重新构建**
```bash
# 清理旧的构建文件
dotnet clean

# 重新构建 Android 版本
dotnet build -p:Configuration=Debug -p:TargetFramework=net10.0-android
```

### 2. **安装到手机**
```bash
# 卸载旧版本（清除权限状态）
adb uninstall com.guaishoudejia.gsdjx4doublesysfserv

# 安装新版本
dotnet build -p:Configuration=Debug -p:TargetFramework=net10.0-android -t:Install
```

### 3. **运行时检查清单**
- [ ] 手机蓝牙已开启
- [ ] ESP32 设备已上电且正在广播
- [ ] 首次扫描时会弹出权限请求对话框，**必须点击"允许"**
- [ ] 如果蓝牙未开启，会提示开启蓝牙

### 4. **查看调试日志**
```bash
# 过滤 BLE 相关日志
adb logcat -s System.Diagnostics

# 或查看所有日志
adb logcat | grep -E "BLE:|Bluetooth"
```

**关键日志示例：**
```
BLE: Android 适配器初始化完成，状态: True
BLE: 开始扫描... (低延迟模式，扫描时长 8 秒)
BLE: 发现设备 - ESP32_X4 (XX:XX:XX:XX:XX:XX) RSSI: -45
BLE: 发现设备 - iPhone (YY:YY:YY:YY:YY:YY) RSSI: -60
BLE: 扫描结束，发现 2 个设备
```

## 🐛 常见问题排查

### 问题 1: 权限请求对话框未出现
**原因**: 可能之前拒绝了权限，系统记住了选择
**解决方案**:
```bash
# 重置应用权限
adb shell pm reset-permissions com.guaishoudejia.gsdjx4doublesysfserv

# 或手动在手机设置中重置应用
# 设置 → 应用 → GSDJX4DoubleSysFserv → 权限 → 删除数据
```

### 问题 2: 扫描时崩溃或抛出异常
**检查项**:
1. 查看 logcat 中的异常堆栈
2. 确认 `AndroidManifest.xml` 中的权限声明完整
3. 检查 Android 版本是否 ≥ Android 5.0 (API 21)

**日志关键词**:
```
SecurityException: Need BLUETOOTH_SCAN permission
SecurityException: Need ACCESS_FINE_LOCATION permission
```

### 问题 3: 扫描不到 ESP32 设备
**检查项**:
1. ESP32 是否在广播模式 (运行 `idf.py monitor` 查看日志)
2. 增加扫描时长（已从 5 秒改为 8 秒）
3. 查看所有发现的设备日志（包括无名设备）
4. 尝试靠近设备（信号强度 RSSI > -70）

**临时调试方法** - 修改过滤条件显示所有设备：
```csharp
// BleService.Android.cs OnDeviceFound() 方法
var shouldAdd = true; // 临时显示所有设备
```

### 问题 4: Android 12+ 特定问题
**Android 12 (API 31)+ 新的蓝牙权限模型：**
- 不再需要 `ACCESS_FINE_LOCATION` 用于蓝牙扫描
- 需要新的权限: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`
- `BLUETOOTH_SCAN` 可以声明 `neverForLocation` 避免位置权限

**当前配置已优化** ✅

## 📱 权限对话框参考

**Android 12+ 首次扫描时应显示:**
```
"GSDJX4DoubleSysFserv 想要查找、连接附近的设备并确定其相对位置"

[ 拒绝 ]  [ 允许 ]
```

**Android 11 及以下首次扫描时应显示:**
```
"GSDJX4DoubleSysFserv 想要访问此设备的位置信息"

[ 仅在使用该应用时允许 ]
[ 拒绝 ]
```

## 🔄 下一步优化建议

1. **添加权限状态检查页面**
   - 显示当前权限状态
   - 提供跳转到系统设置的按钮

2. **改进自动连接逻辑**
   - 启动时检查权限后再尝试自动连接
   - 连接失败时提供更明确的错误原因

3. **后台扫描支持**
   - 当前只支持前台扫描
   - 可以使用 Shiny.NET 的后台作业实现定期扫描

## 📞 联系与支持

如果问题仍未解决，请提供以下信息：
1. Android 系统版本
2. 完整的 logcat 日志（扫描过程）
3. ESP32 设备的蓝牙广播日志
4. 权限设置截图
