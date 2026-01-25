# 手机 App 滚动同步功能实现

## 概述

在 .NET MAUI App 中添加了对 ESP32 设备滚动位置的响应功能，实现三端（ESP32 ↔ App ↔ RemoteServe）的滚动同步。

## 实现细节

### 1. 协议支持（X4IMProtocol.cs）

已定义 `CMD_POSITION_REPORT = 0x96` 命令常量，用于接收位置报告。

### 2. BLE 服务处理（ShinyBleService.cs）

在 `TryMapButtonKey` 方法中添加了位置报告处理逻辑：

```csharp
// 处理位置报告 (0x96 + 8字节: charPosition(4B) + totalChars(4B))
if (data.Length == 9 && data[0] == X4IMProtocol.CMD_POSITION_REPORT)
{
    var charPosition = BitConverter.ToUInt32(data, 1);
    var totalChars = BitConverter.ToUInt32(data, 5);
    var progress = totalChars > 0 ? (charPosition * 100.0 / totalChars) : 0;
    
    _logger.LogInformation($"📍 位置报告: {charPosition}/{totalChars} ({progress:F1}%)");
    
    // 异步同步滚动到 RemoteServe
    _ = _weReadService.SyncScrollPositionAsync(charPosition, totalChars);
    
    return false; // 不触发按键事件
}
```

**数据格式**：
- 字节 0: `0x96` (CMD_POSITION_REPORT)
- 字节 1-4: `charPosition` (uint32, 小端序)
- 字节 5-8: `totalChars` (uint32, 小端序)

### 3. 微信读书服务（WeReadService.cs）

添加了 `SyncScrollPositionAsync` 方法，向 RemoteServe 发送滚动同步请求：

```csharp
public async Task SyncScrollPositionAsync(uint charPosition, uint totalChars)
{
    var readerUrl = $"{ServerUrl.TrimEnd('/')}/api/weread/reader";
    var payload = new
    {
        id = "maui-client",
        cookie = State.Cookie,
        url = State.CurrentUrl,
        action = "scroll",
        charPosition,
        metadata = new
        {
            totalChars,
            progress = charPosition / (double)totalChars
        }
    };
    
    // POST 到 RemoteServe
    await _httpClient.PostAsync(readerUrl, jsonContent);
}
```

## 数据流

```
ESP32 设备滚动检测
    ↓ BLE 发送 [0x96, charPos(4B), totalChars(4B)]
MAUI App 接收
    ├─ 解析位置数据
    ├─ 计算进度百分比
    └─ 调用 WeReadService.SyncScrollPositionAsync()
        ↓ POST /api/weread/reader
        {action: "scroll", charPosition, metadata}
        ↓
RemoteServe 处理
    └─ 执行 performScrollAction()
        └─ 浏览器页面滚动 (500±100px)
```

## 与 BleClient 的对比

| 功能 | BleClient (Node.js) | MAUI App (C#) |
|------|---------------------|---------------|
| 协议解析 | ✅ `data[0] === 0x96` | ✅ `data[0] == CMD_POSITION_REPORT` |
| 数据提取 | ✅ 位运算 | ✅ `BitConverter.ToUInt32()` |
| API 调用 | ✅ `fetch(readerUrl)` | ✅ `HttpClient.PostAsync()` |
| 日志输出 | ✅ `log()` | ✅ `_logger.LogInformation()` |

## 测试方法

1. 连接 ESP32 设备到 App
2. 打开微信读书页面并点击浮动按钮
3. 在 ESP32 上触发滚动操作
4. 观察日志输出：
   - 📍 位置报告: XXX/YYY (Z.Z%)
   - 🔄 同步滚动到 RemoteServe
   - ✅ 滚动同步成功

## 相关文件

- [Services/X4IMProtocol.cs](Services/X4IMProtocol.cs#L39) - 命令常量定义
- [Services/ShinyBleService.cs](Services/ShinyBleService.cs#L750) - BLE 通知处理
- [Services/WeReadService.cs](Services/WeReadService.cs#L475) - 滚动同步实现

## 注意事项

1. **字节序**: ESP32 和 C# 都使用小端序 (Little Endian)
2. **异步处理**: 滚动同步不阻塞 BLE 通知处理
3. **错误容错**: 网络错误只记录日志，不影响 BLE 通信
4. **进度计算**: 使用浮点数计算百分比，避免整数溢出
