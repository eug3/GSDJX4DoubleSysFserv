# BLE 蓝牙逻辑详细分析

## 代码结构对比

### 调用流程一致性

```
连接流程（完全一致）
├─ ConnectAsync()
│  ├─ 连接外设
│  ├─ Task.Delay(500ms)          ← iOS和Android相同
│  ├─ StartIosBackgroundTask() 或 StartBleForegroundService()
│  ├─ CacheWriteCharacteristicAsync() ← 完全相同实现
│  ├─ SubscribeToNotificationsAsync() ← 完全相同实现
│  ├─ SetupDisconnectionHandler()     ← 完全相同实现
│  └─ NegotiateMtuAsync()
│     ├─ iOS: 读取系统值
│     └─ Android: 主动请求 517

后台重连（完全一致）
├─ OnPeripheralConnectedInBackground()
│  ├─ Task.Delay(1500ms)         ← iOS和Android相同
│  ├─ StartIosBackgroundTask() 或 StartBleForegroundService()
│  ├─ CacheWriteCharacteristicAsync() ← 完全相同实现
│  ├─ SubscribeToNotificationsAsync() ← 完全相同实现
│  └─ SetupDisconnectionHandler()

自动重连（完全一致）
├─ SetupDisconnectionHandler()
│  ├─ 监听状态变化
│  ├─ 断开: 清理资源 → Task.Delay(2000ms) → Connect
│  ├─ 重连成功: Task.Delay(1000ms) → 初始化
│  └─ 特征缓存和订阅 ← 完全相同
```

## 时间轴对比

### iOS 时间线

```
t=0ms    | 连接成功 (BeginBackgroundTask)
t=500ms  | 缓存写特征 + 订阅通知
         ├─ 重试1: 失败? → delay 500ms
         ├─ 重试2: 成功 ✅
         └─ 订阅建立
t=1000ms | MTU 协商 (读系统值)
t=1500ms | 事件监听就绪
```

### Android 时间线

```
t=0ms    | 连接成功 (StartForegroundService)
t=500ms  | 缓存写特征 + 订阅通知
         ├─ 重试1: 失败? → delay 500ms
         ├─ 重试2: 成功 ✅
         └─ 订阅建立
t=1000ms | MTU 协商 (请求 517)
t=1500ms | 事件监听就绪
```

**差异**: ⚠️ MTU 协商策略不同，但最终结果一致

---

## 特征发现算法对比

### 两个平台完全相同的算法

```csharp
// 1. 评分系统（完全相同）
score = 0;
if (WriteWithoutResponse)  score += 120;
if (Write)                 score += 80;
if (CustomService)         score += 60;
if (CustomCharacteristic)  score += 20;
if (KnownService)          score += 100;
if (KnownCharacteristic)   score += 100;

// 2. 排除标准（完全相同）
IsStandardBase() && startsWith("00002b") → score -= 200;
IsExcludedService() → 跳过;

// 3. 候选选择（完全相同）
best = candidates.OrderByDescending(x => x.score).First();
```

### 代码位置

| 项目 | 位置 | 一致性 |
|------|------|--------|
| iOS 特征缓存 | 第 906-1002 行 | ✅ 相同 |
| Android 特征缓存 | 第 906-1002 行 | ✅ 相同 |
| iOS 通知订阅 | 第 557-648 行 | ✅ 相同 |
| Android 通知订阅 | 第 557-648 行 | ✅ 相同 |

**结论**: ✅ **完全平台无关的实现**

---

## 平台特定代码分析

### iOS 特定部分

**文件**: `Services/ShinyBleService.cs` (第 1331-1365 行)

```csharp
#if IOS
private nint _bgTaskId = 0;

private void StartIosBackgroundTask()
{
    // 启动后台任务
    _bgTaskId = UIApplication.SharedApplication.BeginBackgroundTask("BLEConnection", () =>
    {
        // 任务过期回调
        if (_bgTaskId != 0)
        {
            UIApplication.SharedApplication.EndBackgroundTask(_bgTaskId);
            _bgTaskId = 0;
        }
    });
}

private void StopIosBackgroundTask()
{
    if (_bgTaskId != 0)
    {
        UIApplication.SharedApplication.EndBackgroundTask(_bgTaskId);
        _bgTaskId = 0;
    }
}
#endif
```

**特点**:
- ✅ 清晰的生命周期管理
- ⚠️ 没有定时刷新机制
- ⚠️ 后台任务可能在 10 分钟后过期

### Android 特定部分

**文件**: `Platforms/Android/BleForegroundService.cs` (完整文件)

```csharp
#if ANDROID
private void StartBleForegroundService()
{
    var context = Platform.AppContext;
    BleForegroundService.StartService(context);
    _logger.LogInformation("BLE: Android 前台服务已启动");
}

private void StopBleForegroundService()
{
    var context = Platform.AppContext;
    BleForegroundService.StopService(context);
    _logger.LogInformation("BLE: Android 前台服务已停止");
}
#endif
```

**服务实现**:
- ✅ 清晰的通知机制
- ✅ 不会被杀死 (StartCommandResult.Sticky)
- ⚠️ 需要通知权限

---

## 可能的不一致问题

### 🔴 高风险问题

**问题 1: iOS 后台任务过期**

```csharp
// 当前代码
_bgTaskId = UIApplication.SharedApplication.BeginBackgroundTask("BLEConnection", () =>
{
    // 回调: 后台任务快要过期时调用
    // 但这里只是简单地结束任务，没有重新启动
});
```

**风险**:
- iOS 后台任务默认最多 10 分钟
- 长时间使用可能导致后台任务自动过期
- 届时 BLE 连接可能中断

**改进建议**:
```csharp
private async Task RefreshIosBackgroundTask()
{
    if (_bgTaskId != 0 && (_bgTaskId % 60 == 0))
    {
        // 每 60 秒刷新一次后台任务
        StopIosBackgroundTask();
        StartIosBackgroundTask();
    }
}
```

### 🟡 中等风险问题

**问题 2: Android MTU 请求可能被忽略**

```csharp
// 当前代码
var result = await _connectedPeripheral.TryRequestMtuAsync(517);
```

**风险**:
- 不是所有 Android 设备都支持 MTU 517
- 某些低端设备可能只支持 MTU 250
- 如果特征不适应，发送可能失败

**改进建议**:
```csharp
var maxMtu = await _connectedPeripheral.TryRequestMtuAsync(517);
if (maxMtu < 100)
{
    _logger.LogWarning($"BLE: 设备 MTU 过小 ({maxMtu})，可能存在兼容性问题");
}
```

**问题 3: Android 权限检查不完整**

```csharp
// 当前代码
var context = Platform.AppContext;
if (context != null)
{
    BleForegroundService.StartService(context);
}
```

**风险**:
- 没有检查蓝牙权限是否已授予
- 前台服务需要通知权限（Android 13+）
- 没有权限检查可能导致应用崩溃

**改进建议**:
```csharp
var hasBlePermission = OperatingSystem.IsAndroidVersionAtLeast(31) 
    ? Platform.CurrentActivity?.CheckSelfPermission("android.permission.BLUETOOTH_CONNECT") == PermissionStatus.Granted
    : true;

if (!hasBlePermission)
{
    _logger.LogError("BLE: 蓝牙权限未授予");
    return;
}
```

### 🟢 低风险问题

**问题 4: iOS/Android MTU 差异未记录**

```csharp
// 当前代码
#if ANDROID
var result = await _connectedPeripheral.TryRequestMtuAsync(517);
#else
_negotiatedMtu = Math.Max(_connectedPeripheral.Mtu, 23);
#endif
```

**风险**:
- iOS 和 Android 可能协商出不同的 MTU 值
- 发送数据时大小不一致可能导致性能差异
- 代码未明确处理这个差异

**改进建议**:
```csharp
_logger.LogInformation($"BLE: 最终 MTU = {_negotiatedMtu} (平台特定协商)");

// 使用保守策略: 两平台都使用 20 字节 payload
var safePayloadSize = Math.Min(_negotiatedMtu - 3, 20);
```

---

## 一致性检查清单

```
✅ 初始化流程
  ├─ ConnectAsync() 流程           [✅] 完全一致
  ├─ 延迟时间 (500/1000/1500ms)    [✅] 完全一致
  ├─ 后台启动 (iOS/Android)        [✅] 对应相同
  └─ 事件处理顺序                  [✅] 完全一致

✅ 特征发现
  ├─ 评分算法                      [✅] 完全一致
  ├─ 排除逻辑                      [✅] 完全一致
  ├─ 候选选择                      [✅] 完全一致
  └─ 缓存策略                      [✅] 完全一致

✅ 通知订阅
  ├─ 重试机制                      [✅] 完全一致
  ├─ 系统服务排除                  [✅] 完全一致
  ├─ 错误处理                      [✅] 完全一致
  └─ 日志记录                      [✅] 完全一致

⚠️ MTU 协商
  ├─ iOS: 系统协商                 [⚠️] 被动
  ├─ Android: 主动请求             [⚠️] 主动
  ├─ 最小值保障                    [✅] 都有
  └─ 日志记录                      [⚠️] 不同

⚠️ 后台保活
  ├─ iOS: BeginBackgroundTask      [⚠️] 有时限
  ├─ Android: 前台服务             [✅] 可靠
  ├─ 刷新机制                      [❌] 缺失
  └─ 权限检查                      [⚠️] 不完整

✅ 状态机处理
  ├─ 断开逻辑                      [✅] 完全一致
  ├─ 重连逻辑                      [✅] 完全一致
  ├─ 延迟参数                      [✅] 完全一致
  └─ 错误处理                      [✅] 完全一致
```

---

## 性能对比

| 指标 | iOS | Android | 备注 |
|------|-----|---------|------|
| 连接初始化 | 500ms | 500ms | ✅ 相同 |
| 自动重连 | 1000ms | 1000ms | ✅ 相同 |
| 后台重连 | 1500ms | 1500ms | ✅ 相同 |
| 特征发现 | 重试3次 | 重试3次 | ✅ 相同 |
| 通知订阅 | 重试3次 | 重试3次 | ✅ 相同 |
| 数据发送 MTU | ≤ iOS系统值 | ≤ 517 | ⚠️ 可能差异 |
| 后台任务 | 10分钟上限 | 无上限 | ⚠️ iOS有时限 |

---

## 总结

### ✅ 优势

1. **核心逻辑完全一致** - 消除了跨平台 bug 的可能性
2. **平台差异最小化** - 只在必要处使用 #if 指令
3. **可维护性好** - 改动一处可同时修复两个平台
4. **代码复用率高** - 特征发现、重试机制等完全共享

### ⚠️ 改进机会

1. **iOS 后台任务需要定期刷新** 🔴
2. **Android 权限检查不完整** 🟡
3. **MTU 差异需要统一处理** 🟢

### 📊 最终评分

```
代码一致性:    9/10 ⭐⭐⭐⭐⭐
平台差异:      8/10 ⭐⭐⭐⭐
可维护性:      9/10 ⭐⭐⭐⭐⭐
风险评估:      7/10 ⭐⭐⭐⭐
整体质量:      8/10 ⭐⭐⭐⭐
```
