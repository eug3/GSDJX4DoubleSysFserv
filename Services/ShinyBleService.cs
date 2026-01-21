#if !ANDROID
using Shiny.BluetoothLE;
using System.Collections.ObjectModel;
using Shiny;
using System.Reactive.Linq;
using System.Text;
using Microsoft.Extensions.Logging;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 基于 Shiny.NET 的蓝牙服务 - 支持后台持久化
/// </summary>
public class ShinyBleService : IBleService
{
    private readonly IBleManager _bleManager;
    private readonly IStorageService _storageService;
    private readonly ILogger<ShinyBleService> _logger;
    private const string SavedMacKey = "Ble_SavedMacAddress";

    // ESP32 X4IM 服务和特征 UUID
    private static readonly Guid X4IM_SERVICE_UUID = Guid.Parse("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    private static readonly Guid X4IM_WRITE_CHAR_UUID = Guid.Parse("beb5483e-36e1-4688-b7f5-ea07361b26a8");

    private IPeripheral? _connectedPeripheral;
    private object? _writeCharacteristic; // 由连接时动态设置
    private ObservableCollection<BleDeviceInfo>? _scannedDevices;
    private readonly Dictionary<string, IPeripheral> _discoveredPeripherals = new();
    private TaskCompletionSource<ObservableCollection<BleDeviceInfo>>? _scanTcs;
    private IDisposable? _scanSubscription;
    private IDisposable? _stateSubscription;

    public bool IsConnected { get; private set; }
    public string? ConnectedDeviceName { get; private set; }

    public ShinyBleService(IBleManager bleManager, IStorageService storageService, ILogger<ShinyBleService> logger)
    {
        _bleManager = bleManager;
        _storageService = storageService;
        _logger = logger;

        // 监听设备断开事件，自动重连
        SetupAutoReconnect();
    }

    /// <summary>
    /// 设置自动重连机制 - 当设备意外断开时自动重连
    /// </summary>
    private void SetupAutoReconnect()
    {
        _stateSubscription = _bleManager
            .WhenPeripheralStatusChanged()
            .Subscribe(peripheral =>
            {
                if (peripheral.Status == ConnectionState.Disconnected && _connectedPeripheral?.Uuid == peripheral.Uuid)
                {
                    _logger.LogWarning($"BLE: 设备 {_connectedPeripheral.Name} 已断开，尝试自动重连...");
                    MainThread.BeginInvokeOnMainThread(async () =>
                    {
                        await Task.Delay(1000); // 等待1秒后重连
                        await TryAutoReconnectAsync();
                    });
                }
            });
    }

    /// <summary>
    /// 尝试自动重连已保存的设备
    /// </summary>
    private async Task<bool> TryAutoReconnectAsync()
    {
        try
        {
            var savedDeviceId = await GetSavedMacAddress();
            if (string.IsNullOrEmpty(savedDeviceId))
            {
                _logger.LogInformation("BLE: 没有已保存的设备");
                return false;
            }

            _logger.LogInformation($"BLE: 尝试自动重连设备 {savedDeviceId}...");

            // 检查是否已在发现列表中
            if (_discoveredPeripherals.TryGetValue(savedDeviceId, out var peripheral))
            {
                return await ConnectAsync(savedDeviceId, savedDeviceId);
            }
            else
            {
                // 需要重新扫描
                _logger.LogInformation("BLE: 设备不在发现列表中，开始扫描...");
                await ScanAndConnectToSavedDeviceAsync();
                return true;
            }
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 自动重连失败 - {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 扫描并连接到已保存的设备
    /// </summary>
    private async Task ScanAndConnectToSavedDeviceAsync()
    {
        var savedDeviceId = await GetSavedMacAddress();
        if (string.IsNullOrEmpty(savedDeviceId))
            return;

        _logger.LogInformation($"BLE: 扫描查找设备 {savedDeviceId}...");

        try
        {
            var access = await _bleManager.RequestAccess().FirstAsync();
            if (access != AccessState.Available)
            {
                _logger.LogWarning($"BLE: 权限请求失败 - {access}");
                return;
            }

            var found = false;
            var deviceCount = 0;

            _scanSubscription = _bleManager
                .Scan()
                .Subscribe(
                    scanResult =>
                    {
                        var peripheral = scanResult.Peripheral;
                        var deviceId = peripheral.Uuid.ToString();
                        deviceCount++;

                        // 打印每个发现的设备用于调试
                        _logger.LogInformation($"BLE: 扫描发现 [{deviceCount}] - {peripheral.Name} ({deviceId.Substring(0, Math.Min(8, deviceId.Length))}...)");

                        if (deviceId == savedDeviceId)
                        {
                            found = true;
                            _bleManager.StopScan();
                            _scanSubscription?.Dispose();

                            // 将设备添加到发现列表
                            _discoveredPeripherals[deviceId] = peripheral;

                            _logger.LogInformation($"BLE: 找到已保存的设备 {peripheral.Name ?? "未知"}");
                            MainThread.BeginInvokeOnMainThread(async () =>
                            {
                                await ConnectAsync(savedDeviceId, savedDeviceId);
                            });
                        }
                    },
                    error =>
                    {
                        _logger.LogError($"BLE: 扫描错误 - {error.Message}");
                    }
                );

            // 15秒后停止扫描
            await Task.Delay(15000);
            if (!found)
            {
                _bleManager.StopScan();
                _scanSubscription?.Dispose();
                _logger.LogWarning($"BLE: 扫描了 {deviceCount} 个设备，未找到已保存的设备 {savedDeviceId.Substring(0, Math.Min(8, savedDeviceId.Length))}...");
            }
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 扫描连接失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 启动时尝试自动连接已保存的设备
    /// </summary>
    public async Task TryAutoConnectOnStartupAsync()
    {
        try
        {
            var savedDeviceId = await GetSavedMacAddress();
            if (string.IsNullOrEmpty(savedDeviceId))
            {
                _logger.LogInformation("BLE: 启动时没有已保存的设备");
                return;
            }

            _logger.LogInformation($"BLE: 启动时尝试自动连接设备 {savedDeviceId}...");
            await ScanAndConnectToSavedDeviceAsync();
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 启动时自动连接失败 - {ex.Message}");
        }
    }

    public async Task<string?> GetSavedMacAddress()
    {
        return await _storageService.GetAsync<string>(SavedMacKey);
    }

    public async Task SaveMacAddress(string macAddress)
    {
        await _storageService.SetAsync(SavedMacKey, macAddress);
    }

    public async Task DeleteSavedMacAddress()
    {
        await _storageService.RemoveAsync(SavedMacKey);
        Disconnect();
    }

    public async Task<bool> ConnectAsync(string deviceId, string macAddress)
    {
        try
        {
            if (_discoveredPeripherals.TryGetValue(deviceId, out var peripheral))
            {
                _connectedPeripheral = peripheral;

                // Shiny 3.x: 使用 WhenConnected() 等待连接
                var connectTask = peripheral
                    .WhenConnected()
                    .Take(1)
                    .Timeout(TimeSpan.FromSeconds(15))
                    .FirstAsync();

                // 连接配置：AutoConnect=true 支持后台自动重连
                peripheral.Connect(new ConnectionConfig
                {
                    AutoConnect = true,
                    // Android: 可以设置连接超时和重试参数
                    // Android 特定配置可以通过平台特定代码设置
                });
                await connectTask;

                IsConnected = true;
                ConnectedDeviceName = peripheral.Name ?? "未知设备";
                _logger.LogInformation($"BLE: 已连接到 {ConnectedDeviceName}");

                // 发现服务并获取写入特征
                await DiscoverWriteCharacteristicAsync();

                // 保存 MAC 地址
                await SaveMacAddress(deviceId);

                return true;
            }
            else
            {
                _logger.LogWarning($"BLE: 未找到设备 {deviceId}");
                return false;
            }
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 连接错误 - {ex.Message}");
            return false;
        }
    }

    private async Task DiscoverWriteCharacteristicAsync()
    {
        if (_connectedPeripheral == null) return;

        try
        {
            var service = await _connectedPeripheral.GetKnownService(X4IM_SERVICE_UUID);
            if (service != null)
            {
                _writeCharacteristic = await service.GetKnownCharacteristic(X4IM_WRITE_CHAR_UUID);
                System.Diagnostics.Debug.WriteLine($"BLE: 发现写入特征 - {X4IM_WRITE_CHAR_UUID}");
            }
            else
            {
                System.Diagnostics.Debug.WriteLine($"BLE: 未找到 X4IM 服务");
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 发现特征失败 - {ex.Message}");
        }
    }

    public void Disconnect()
    {
        if (_connectedPeripheral != null)
        {
            _connectedPeripheral.CancelConnection();
            _connectedPeripheral = null;
        }
        _writeCharacteristic = null;
        IsConnected = false;
        ConnectedDeviceName = null;
        System.Diagnostics.Debug.WriteLine("BLE: 已断开连接");
    }

    /// <summary>
    /// 发送文本到设备（X4IM v2 协议）
    /// </summary>
    public async Task<bool> SendTextToDeviceAsync(string text, int chapter = 0)
    {
        if (!IsConnected || _writeCharacteristic == null)
        {
            System.Diagnostics.Debug.WriteLine("BLE: 设备未连接或无可写特征值");
            return false;
        }

        if (string.IsNullOrEmpty(text))
        {
            System.Diagnostics.Debug.WriteLine("BLE: 文本内容为空");
            return false;
        }

        try
        {
            var data = Encoding.UTF8.GetBytes(text);
            var bookId = $"weread_{chapter}";
            
            // 创建 X4IM v2 帧头
            var header = CreateX4IMv2Header(data.Length, 0, bookId);
            
            const int MTU = 512;
            
            System.Diagnostics.Debug.WriteLine($"BLE: 发送文件 bookId=\"{bookId}\", size={data.Length} 字节");

            // 第一个包：帧头 + 部分数据
            var firstChunkSize = Math.Min(MTU - 32, data.Length);
            var firstPacket = new byte[32 + firstChunkSize];
            Array.Copy(header, 0, firstPacket, 0, 32);
            Array.Copy(data, 0, firstPacket, 32, firstChunkSize);

            await _writeCharacteristic.Write(firstPacket);
            System.Diagnostics.Debug.WriteLine($"BLE: 已发送帧头 + 第一块 ({firstPacket.Length} 字节)");

            // 发送剩余数据
            var offset = firstChunkSize;
            var chunkNum = 1;

            while (offset < data.Length)
            {
                var chunkSize = Math.Min(MTU, data.Length - offset);
                var chunk = new byte[chunkSize];
                Array.Copy(data, offset, chunk, 0, chunkSize);

                await _writeCharacteristic.Write(chunk);
                offset += chunkSize;
                chunkNum++;

                if (chunkNum % 5 == 0 || offset >= data.Length)
                {
                    var percent = (int)Math.Round((double)offset / data.Length * 100);
                    System.Diagnostics.Debug.WriteLine($"BLE: 进度 {offset}/{data.Length} 字节 ({percent}%)");
                }

                await Task.Delay(10); // 短暂延迟确保数据被处理
            }

            System.Diagnostics.Debug.WriteLine($"BLE: 文件数据传输完成! {chunkNum} 块，{data.Length} 字节");

            // 发送 EOF 标记
            await Task.Delay(50);
            var eofMarker = new byte[] { 0x00, 0x45, 0x4F, 0x46, 0x0A }; // \x00EOF\n
            await _writeCharacteristic.Write(eofMarker);
            System.Diagnostics.Debug.WriteLine("BLE: 已发送 EOF 标记");

            return true;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 发送失败 - {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 创建 X4IM v2 协议帧头
    /// </summary>
    private static byte[] CreateX4IMv2Header(int payloadSize, int sd, string name)
    {
        var header = new byte[32];

        // Magic: "X4IM"
        header[0] = 0x58; // 'X'
        header[1] = 0x34; // '4'
        header[2] = 0x49; // 'I'
        header[3] = 0x4D; // 'M'

        // Version: 0x0002 (小端序)
        header[4] = 0x02;
        header[5] = 0x00;

        // Flags: 0x0004 (TXT 标志位，小端序)
        header[6] = 0x04;
        header[7] = 0x00;

        // Payload size (小端序)
        header[8] = (byte)(payloadSize & 0xFF);
        header[9] = (byte)((payloadSize >> 8) & 0xFF);
        header[10] = (byte)((payloadSize >> 16) & 0xFF);
        header[11] = (byte)((payloadSize >> 24) & 0xFF);

        // SD (小端序)
        header[12] = (byte)(sd & 0xFF);
        header[13] = (byte)((sd >> 8) & 0xFF);
        header[14] = (byte)((sd >> 16) & 0xFF);
        header[15] = (byte)((sd >> 24) & 0xFF);

        // Name (最多 15 字节 + 1 字节结束符)
        if (!string.IsNullOrEmpty(name))
        {
            var nameBytes = Encoding.UTF8.GetBytes(name);
            var copyLen = Math.Min(nameBytes.Length, 15);
            Array.Copy(nameBytes, 0, header, 16, copyLen);
            header[16 + copyLen] = 0; // 结束符
        }

        return header;
    }

    public async Task<ObservableCollection<BleDeviceInfo>> ScanDevicesAsync()
    {
        // 请求蓝牙权限
        try
        {
            var access = await _bleManager.RequestAccess().FirstAsync();
            if (access != AccessState.Available)
            {
                System.Diagnostics.Debug.WriteLine($"BLE: 权限请求失败 - {access}");
                return new ObservableCollection<BleDeviceInfo>();
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 权限请求错误 - {ex.Message}");
            return new ObservableCollection<BleDeviceInfo>();
        }

        _scannedDevices = new ObservableCollection<BleDeviceInfo>();
        _discoveredPeripherals.Clear();
        _scanTcs = new TaskCompletionSource<ObservableCollection<BleDeviceInfo>>();

        System.Diagnostics.Debug.WriteLine("BLE: 开始扫描...");

        // Shiny 3.x: 使用 Scan 方法
        _scanSubscription = _bleManager
            .Scan()
            .Subscribe(
                scanResult =>
                {
                    var peripheral = scanResult.Peripheral;
                    var deviceId = peripheral.Uuid.ToString();
                    var deviceName = peripheral.Name ?? "未知设备";

                    if (!_discoveredPeripherals.ContainsKey(deviceId))
                    {
                        _discoveredPeripherals[deviceId] = peripheral;

                        var deviceInfo = new BleDeviceInfo
                        {
                            Id = deviceId,
                            Name = $"{deviceName} ({deviceId.Substring(0, Math.Min(8, deviceId.Length))}...)",
                            MacAddress = deviceId
                        };

                        MainThread.BeginInvokeOnMainThread(() =>
                        {
                            _scannedDevices?.Add(deviceInfo);
                            System.Diagnostics.Debug.WriteLine($"BLE: 发现设备 - {deviceName}");
                        });
                    }
                },
                error =>
                {
                    System.Diagnostics.Debug.WriteLine($"BLE: 扫描错误 - {error.Message}");
                    _scanTcs?.TrySetResult(_scannedDevices ?? new ObservableCollection<BleDeviceInfo>());
                }
            );

        // 5秒后停止扫描
        _ = Task.Run(async () =>
        {
            await Task.Delay(5000);
            _bleManager.StopScan();
            _scanSubscription?.Dispose();
            System.Diagnostics.Debug.WriteLine($"BLE: 扫描结束，发现 {_scannedDevices?.Count ?? 0} 个设备");
            _scanTcs?.TrySetResult(_scannedDevices ?? new ObservableCollection<BleDeviceInfo>());
        });

        return await _scanTcs.Task;
    }
}
#endif
