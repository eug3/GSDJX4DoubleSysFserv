# BLE 设备管理 - 快速参考

## 🎯 核心改动总结

| 项目 | 描述 |
|------|------|
| **UI 位置** | 阅读界面左侧浮动按钮（📡） |
| **颜色** | 🟢 绿色=已连接 \| 🔵 蓝色=未连接 |
| **交互** | 点击按钮展开菜单 → 选择设备或管理连接 |
| **存储** | SharedPreferences（自动保存 MAC 地址） |
| **自动连接** | 启动时自动连接上次保存的设备 |
| **关键特性** | ✅ OCR 独立于 BLE，断连不影响功能 |

---

## 📱 用户操作流程

### 场景 1: 首次使用
```
启动App → 进入Ebook模式 → 看到蓝色浮动按钮(📡)
  ↓
点击按钮 → 展开菜单 → 点击"🔍 选择设备"
  ↓
在底表中选择目标设备 → 自动连接 → 按钮变绿色(✓)
  ↓
MAC地址自动保存 → 完成
```

### 场景 2: 后续使用（有保存的设备）
```
启动App → 进入Ebook模式 → 自动连接 → 按钮绿色(✓)
  ↓
页面同步时自动发送数据到硬件 → 正常工作
```

### 场景 3: 切换设备
```
点击按钮 → 展开菜单 → "🔍 选择设备"
  ↓
选择新设备 → 自动断开旧连接 → 连接新设备
  ↓
新MAC自动覆盖旧MAC → 完成
```

### 场景 4: 忘记设备
```
点击按钮 → 展开菜单 → "🗑️ 忘记设备"
  ↓
删除保存的MAC → 按钮变蓝色
  ↓
下次需重新手动选择设备
```

---

## 🔌 BLE 连接不影响核心功能

```
页面同步流程（performSync）
├─ 获取Canvas图片      ✅ 总是可用
├─ 渲染为1-bit        ✅ 总是可用
├─ 📡 发送BLE数据     ⚠️ BLE连接失败时跳过
└─ OCR文字识别        ✅ 总是可用（独立）
```

**即使 BLE 连接失败，用户仍可：**
- ✅ 获取页面截图
- ✅ 进行 OCR 识别
- ❌ 无法发送数据到硬件（预期行为）

---

## 📂 新增文件

```
app/src/main/java/com/guaishoudejia/x4doublesysfserv/
├── ble/
│   └── BleDeviceManager.kt           ← MAC地址存储管理
├── BleConnectionManager.kt           ← 扫描/连接管理
└── ui/components/
    └── BleComponents.kt              ← UI组件（按钮/底表）
```

---

## 🔧 代码集成点

### 1. 应用启动时的浮动按钮
```kotlin
BleFloatingButton(
    isConnected = bleConnectionManager.isConnected,
    deviceName = bleConnectionManager.connectedDeviceName,
    onScan = { bleConnectionManager.showScanSheet = true },
    onForget = { bleConnectionManager.forgetDevice() }
)
```

### 2. 页面同步中的可选 BLE 发送
```kotlin
val bleClient = bleConnectionManager.getBleClient()
if (bleClient != null && bleConnectionManager.isConnected) {
    try {
        // 发送数据（可选，失败不影响主流程）
    } catch (e: Exception) {
        Log.w("SYNC", "发送 BLE 数据失败", e)
    }
}
```

### 3. 初始化和自动连接
```kotlin
// onCreate 中
bleConnectionManager = BleConnectionManager(this, this, lifecycleScope)

// 应用启动时
lifecycleScope.launch {
    if (bleConnectionManager.hasRequiredPermissions()) {
        bleConnectionManager.tryAutoConnect()
    }
}
```

---

## 💾 SharedPreferences 数据结构

```
SharedPreferences("ble_device_manager")
{
    "saved_device_address": "AA:BB:CC:DD:EE:FF",  // MAC地址
    "saved_device_name": "设备名称",              // 显示名
    "last_connected_time": 1673001234567         // 时间戳
}
```

**API:**
```kotlin
val manager = BleDeviceManager(context)
manager.saveDevice("AA:BB:CC:DD:EE:FF", "MyDevice")
val mac = manager.getSavedDeviceAddress()
manager.forgetDevice()
```

---

## 🧪 测试命令

### 查看日志
```bash
adb logcat | grep -E "BleConnection|BleDevice"
```

### 检查 SharedPreferences
```bash
adb shell dumpsys dbinfo com.guaishoudejia.x4doublesysfserv
```

### 清空保存的设备
```bash
adb shell run-as com.guaishoudejia.x4doublesysfserv \
  rm /data/data/com.guaishoudejia.x4doublesysfserv/shared_prefs/ble_device_manager.xml
```

---

## ⚡ 关键区别（vs 旧版本）

| 特性 | 旧版本 | 新版本 |
|------|--------|--------|
| BLE按钮位置 | 底部中央 | 左侧浮动 |
| 设备选择 | Activity页面 | 底部Sheet |
| MAC保存 | 需手动传递 | 自动保存 |
| 自动连接 | 无 | ✓ 支持 |
| BLE可选 | 连接失败阻断 | ✓ OCR独立 |
| UI状态反馈 | 基础 | ✓ 颜色/菜单 |

---

## 📊 状态流转图

```
未连接(🔵)
  ↓
点击按钮 → 展开菜单
  ↓
选择设备 → 连接中
  ↓
连接成功 ──→ 已连接(🟢)
  ↑           ↓
  └── 自动重连 ─ 页面同步时自动发送BLE数据
               ↓
           连接断开 → 回到未连接状态
```

---

## 🐛 常见问题

**Q: 为什么BLE连接失败不显示错误？**  
A: 连接失败被视为"可忽略"，UI仍可正常工作。检查日志获取详情。

**Q: 如何确认 MAC 地址已保存？**  
A: 进入 Ebook 模式后关闭 App，重新启动进入 Ebook 模式，应自动连接。

**Q: 多个设备如何切换？**  
A: 只支持单个设备保存。选择新设备会自动覆盖旧 MAC 地址。

**Q: OCR 是否依赖 BLE 连接？**  
A: 完全独立。BLE 连接失败不影响 OCR 识别。

---

## 🚀 后续优化

- [ ] 支持多设备快速切换历史
- [ ] 设备信号强度实时显示
- [ ] 连接失败自动重试机制
- [ ] 扫描超时警告提示
