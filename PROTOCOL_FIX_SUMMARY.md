# 图片协议修复总结

## 问题描述
发送图片时，协议使用不正确：
1. **MTU 设置不清楚** - 需要明确使用 512 字节 MTU
2. **图片类型混淆** - 不应该使用 TXT 类型标志发送图片

## 修复内容

### 1. X4IMProtocol.cs - 添加图片专用方法

添加了两个新方法，专门用于处理图片：

#### `CreateImageHeader()`
- 创建图片帧头（BMP/PNG/JPG 等）
- **关键验证**：确保不使用 TXT 类型标志
- 抛出异常如果传入 `FLAG_TYPE_TXT`

```csharp
public static byte[] CreateImageHeader(
    int imageSize, 
    string fileName = "image.png", 
    ushort imageFlags = FLAG_TYPE_PNG, 
    uint sd = 0)
{
    // 确保不是 TXT 类型
    if (imageFlags == FLAG_TYPE_TXT)
    {
        throw new ArgumentException(
            "图片不应使用 TXT 类型标志！请使用 FLAG_TYPE_PNG/BMP/JPG 等");
    }
    return CreateHeader((uint)imageSize, fileName, sd, imageFlags);
}
```

#### `ChunkImageData()`
- 分片图片数据以支持 BLE 传输
- **MTU 512 字节策略**：
  - **第一片**：32 字节头 + 480 字节数据 = 512 字节
  - **后续片**：最多 512 字节纯 payload 数据
- 验证不使用 TXT 类型

```csharp
public static List<byte[]> ChunkImageData(
    byte[] imageData, 
    ushort imageFlags = FLAG_TYPE_PNG, 
    string fileName = "image.png", 
    uint sd = 0, 
    int mtu = 512)
{
    // 验证图片类型
    if (imageFlags == FLAG_TYPE_TXT)
    {
        throw new ArgumentException(
            "图片不应使用 TXT 类型标志！请使用 FLAG_TYPE_PNG/BMP/JPG 等");
    }
    
    // ... MTU 512 分片逻辑 ...
}
```

### 2. ShinyBleService.cs - 优化图片发送

#### `SendImageToDeviceAsync()` 改进
- 添加类型验证：检查是否使用了 TXT 标志
- 改进日志记录，包含 `type` 字段值（0x01-0x04）
- 优化注释文档说明 MTU 512 策略

```csharp
public async Task<bool> SendImageToDeviceAsync(
    byte[] imageData, 
    string fileName = "page_0.png", 
    ushort flags = X4IMProtocol.FLAG_TYPE_PNG, 
    bool sendShowPage = true, 
    byte pageIndex = 0)
{
    // 验证不是 TXT 类型
    if (flags == X4IMProtocol.FLAG_TYPE_TXT)
    {
        _logger.LogError("BLE: 发送图片时不应使用 TXT 类型标志！");
        return false;
    }
    
    var header = CreateX4IMv2Header(imageData.Length, 0, fileName, flags);
    _logger.LogInformation(
        $"BLE: 发送图片 file=\"{fileName}\" size={imageData.Length} 字节 " +
        $"type=0x{header[5]:X2} flags=0x{flags:X4}");
    
    // ... 发送逻辑 ...
}
```

#### `SendFrameAsync()` 改进
- **明确 MTU 512 策略**：
  - `HEADER_SIZE = 32` 字节
  - `MTU = 512` 字节总大小
  - 第一片 = 32 头 + 480 数据
  - 后续片 = 512 数据
- 改进日志显示分片大小

```csharp
private async Task<bool> SendFrameAsync(byte[] header, byte[] payload, bool appendEof)
{
    const int MTU = 512;
    const int HEADER_SIZE = 32;
    // 第一包数据部分大小 = MTU - HEADER_SIZE = 480 字节
    
    // 第一包：帧头 + 部分数据（最多 512 字节）
    var firstPacketSize = Math.Min(MTU, (int)ms.Length);
    // ...
    _logger.LogDebug(
        $"BLE: 已发送第一包 ({firstPacketSize} = {HEADER_SIZE} 头 + " +
        $"{firstPacketSize - HEADER_SIZE} 数据 字节)");
    
    // 发送后续分片（每片 512 字节）
    // ...
    _logger.LogInformation(
        $"BLE: 帧传输完成，共 {chunkNum} 个分片，总 {totalSize} 字节");
}
```

### 3. WeReadPage.xaml.cs - 改进二维码发送

- 使用命名参数调用 `SendImageToDeviceAsync()`，提高可读性
- 添加详细注释说明使用 BMP 类型，不使用 TXT
- 改进日志消息

```csharp
var sent = await _bleService.SendImageToDeviceAsync(
    bmpBytes, 
    "page_0.bmp", 
    X4IMProtocol.FLAG_TYPE_BMP,  // 确保使用 BMP 类型，不是 TXT
    sendShowPage: true, 
    pageIndex: 0
);
```

## 协议说明

### X4IM v2 帧头（32 字节）
```
[0-3]   magic       = "X4IM" (0x58 0x34 0x49 0x4D)
[4]     version     = 0x02
[5]     type        = 文件类型 (0x01=BMP, 0x02=PNG, 0x03=JPG, 0x10=TXT 等)
[6-7]   flags       = 文件标志位（小端序）
[8-11]  payload_sz  = 数据大小（小端序，不含 EOF）
[12-15] sd/seq      = 存储/序号（通常为 0）
[16-31] name        = 文件名（UTF-8, 最多 15 字符 + \0）
```

### 文件类型（type 字段）
- **0x01** = BMP (FLAG_TYPE_BMP = 0x0020)
- **0x02** = PNG (FLAG_TYPE_PNG = 0x0008)
- **0x03** = JPG (FLAG_TYPE_JPG = 0x0010)
- **0x04** = JPEG
- **0x10** = TXT (FLAG_TYPE_TXT = 0x0004) ← **不用于图片**
- **0x11** = EPUB
- **0x12** = PDF

### BLE 分片传输（MTU 512）
1. **第一片**：32 字节帧头 + 480 字节 payload = 512 字节
2. **后续片**：最多 512 字节纯 payload 数据
3. **文件完成**：
   - TXT：单独发送 EOF 标记 `\x00EOF\n`
   - BMP/PNG：发送 SHOW_PAGE 命令触发显示

## 验证清单

- ✅ 图片发送时确保使用图片类型（BMP/PNG/JPG），不使用 TXT
- ✅ MTU 512 字节分片策略清晰（32 头 + 480 数据 + 512 后续数据）
- ✅ 日志记录包含 type 字段值用于调试
- ✅ 添加了专用的图片处理方法 `CreateImageHeader()` 和 `ChunkImageData()`
- ✅ 编译通过（iOS），无编译错误或警告

## 相关文件修改

1. [Services/X4IMProtocol.cs](Services/X4IMProtocol.cs)
   - 添加 `CreateImageHeader()` 方法
   - 添加 `ChunkImageData()` 方法

2. [Services/ShinyBleService.cs](Services/ShinyBleService.cs)
   - 改进 `SendImageToDeviceAsync()` 方法
   - 改进 `SendFrameAsync()` 方法

3. [Views/WeReadPage.xaml.cs](Views/WeReadPage.xaml.cs)
   - 改进二维码发送调用

## 测试建议

1. 发送 BMP 二维码到设备，确保使用 type=0x01（BMP）
2. 检查日志输出是否显示正确的 type 值
3. 验证 MTU 512 分片是否正确（第一片应为 512 字节）
4. 确认设备能够正确显示接收的图片
