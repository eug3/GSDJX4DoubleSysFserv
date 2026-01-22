using Shiny.BluetoothLE;
using System.Collections.ObjectModel;
using Shiny;
using System.Reactive.Linq;
using System.Text;
using Microsoft.Extensions.Logging;
using System.IO;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 基于 Shiny.NET 3.x 的蓝牙服务 - 支持后台持久化
/// </summary>
public class ShinyBleService : IBleService
{
    private readonly IBleManager _bleManager;
    private readonly IStorageService _storageService;
    private readonly ILogger<ShinyBleService> _logger;
    private const string SavedMacKey = "Ble_SavedMacAddress";

    private IPeripheral? _connectedPeripheral;
    private string? _writeServiceUuid;          // 缓存写入服务 UUID
    private string? _writeCharacteristicUuid;   // 缓存写入特征 UUID
    private ObservableCollection<BleDeviceInfo>? _scannedDevices;
    private readonly Dictionary<string, IPeripheral> _discoveredPeripherals = new();
    private TaskCompletionSource<ObservableCollection<BleDeviceInfo>>? _scanTcs;
    private IDisposable? _scanSubscription;
    private IDisposable? _notifySubscription;
    
    // 按键事件
    public event EventHandler<ButtonEventArgs>? ButtonPressed;

    public bool IsConnected { get; private set; }
    public string? ConnectedDeviceName { get; private set; }

    public ShinyBleService(IBleManager bleManager, IStorageService storageService, ILogger<ShinyBleService> logger)
    {
        _bleManager = bleManager;
        _storageService = storageService;
        _logger = logger;
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
                        var deviceId = peripheral.Uuid;
                        deviceCount++;

                        if (deviceId == savedDeviceId)
                        {
                            found = true;
                            _bleManager.StopScan();
                            _scanSubscription?.Dispose();

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
                _logger.LogWarning($"BLE: 扫描了 {deviceCount} 个设备，未找到已保存的设备");
            }
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 扫描连接失败 - {ex.Message}");
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
                _writeServiceUuid = null;
                _writeCharacteristicUuid = null; // 清空旧缓存，确保后续重新发现

                // Shiny 3.x: 使用 WhenStatusChanged() 等待连接
                var connectTask = peripheral
                    .WhenStatusChanged()
                    .Where(x => x == ConnectionState.Connected)
                    .Take(1)
                    .Timeout(TimeSpan.FromSeconds(15))
                    .FirstAsync();

                // 连接配置：AutoConnect=true 支持后台自动重连
                peripheral.Connect(new ConnectionConfig
                {
                    AutoConnect = true
                });
                await connectTask;

                IsConnected = true;
                ConnectedDeviceName = peripheral.Name ?? "未知设备";
                _logger.LogInformation($"BLE: 已连接到 {ConnectedDeviceName}");

                // 缓存写入特征值以提升性能
                await CacheWriteCharacteristicAsync();

                // 订阅通知特征（当前固件暂无通知，保持兼容占位）
                await SubscribeToNotificationsAsync();

                // 监听断开事件
                SetupDisconnectionHandler();

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

    /// <summary>
    /// 设置断开连接处理器
    /// </summary>
    private void SetupDisconnectionHandler()
    {
        if (_connectedPeripheral == null) return;

        _connectedPeripheral
            .WhenStatusChanged()
            .Where(x => x == ConnectionState.Disconnected)
            .Subscribe(_ =>
            {
                _logger.LogWarning($"BLE: 设备 {ConnectedDeviceName} 已断开");
                IsConnected = false;
                _writeServiceUuid = null;
                _writeCharacteristicUuid = null; // 清空缓存的特征值，防止重连后使用旧句柄
                _notifySubscription?.Dispose();
                _notifySubscription = null;
                
                // 尝试自动重连
                MainThread.BeginInvokeOnMainThread(async () =>
                {
                    await Task.Delay(2000);
                    if (!IsConnected && _connectedPeripheral != null)
                    {
                        _logger.LogInformation("BLE: 尝试自动重连...");
                        _connectedPeripheral.Connect(new ConnectionConfig { AutoConnect = true });
                    }
                });
            });
    }

    /// <summary>
    /// 订阅通知特征（接收设备按键事件）
    /// </summary>
    private async Task SubscribeToNotificationsAsync()
    {
        if (_connectedPeripheral == null) return;

        try
        {
            _notifySubscription?.Dispose();

            // 当前 ESP32 固件只暴露 SPP 服务 (0xABF0) 且未提供通知特征，这里保持兼容：
            // 如果未来固件增加可通知特征，再在此处按需订阅。
            _logger.LogInformation("BLE: 当前固件未发现可通知特征，跳过订阅（按键事件将不可用）");
        }
        catch (Exception ex)
        {
            _logger.LogWarning($"BLE: 订阅通知失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 处理设备通知数据
    /// </summary>
    private void ProcessNotification(byte[] data)
    {
        try
        {
            var message = Encoding.UTF8.GetString(data).Trim();
            _logger.LogInformation($"BLE: 收到通知 - {message}");

            // 解析按键事件 (格式: "BTN:LEFT", "BTN:RIGHT", etc.)
            if (message.StartsWith("BTN:"))
            {
                var key = message.Substring(4);
                ButtonPressed?.Invoke(this, new ButtonEventArgs(key));
            }
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 处理通知失败 - {ex.Message}");
        }
    }

    public void Disconnect()
    {
        _notifySubscription?.Dispose();
        _notifySubscription = null;
        _writeServiceUuid = null;
        _writeCharacteristicUuid = null;

        if (_connectedPeripheral != null)
        {
            _connectedPeripheral.CancelConnection();
            _connectedPeripheral = null;
        }
        
        IsConnected = false;
        ConnectedDeviceName = null;
        _logger.LogInformation("BLE: 已断开连接");
    }

    /// <summary>
    /// 缓存写入特征值以提升性能
    /// </summary>
    private async Task CacheWriteCharacteristicAsync()
    {
        if (_connectedPeripheral == null)
        {
            _logger.LogWarning("BLE: 设备未连接");
            return;
        }
        
        try
        {
            _logger.LogInformation("BLE: 开始搜索可写特征值...");

            var allCharacteristics = await _connectedPeripheral
                .GetAllCharacteristics()
                .FirstAsync();

            _logger.LogInformation($"BLE: 发现 {allCharacteristics.Count} 个特征值");

            foreach (var ch in allCharacteristics)
            {
                var props = ch.Properties;
                var canWrite = props.HasFlag(CharacteristicProperties.Write) || props.HasFlag(CharacteristicProperties.WriteWithoutResponse);

                _logger.LogDebug($"BLE: 特征 {ch.Uuid} @ 服务 {ch.Service.Uuid} Props={props}");

                if (canWrite)
                {
                    _writeServiceUuid = ch.Service.Uuid;
                    _writeCharacteristicUuid = ch.Uuid;
                    _logger.LogInformation("BLE: ✅ 找到可写特征值!");
                    _logger.LogInformation($"BLE:    服务: {_writeServiceUuid}");
                    _logger.LogInformation($"BLE:    特征值: {_writeCharacteristicUuid}");
                    _logger.LogInformation($"BLE:    属性: {props}");
                    return;
                }
            }

            _logger.LogWarning("BLE: 未找到任何可写特征值!");
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 缓存特征值失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 发送文本到设备（X4IM v2 协议）
    /// </summary>
    public async Task<bool> SendTextToDeviceAsync(string text, int chapter = 0)
    {
        if (!IsConnected || _connectedPeripheral == null)
        {
            _logger.LogWarning("BLE: 设备未连接");
            return false;
        }

        if (string.IsNullOrEmpty(text))
        {
            _logger.LogWarning("BLE: 文本内容为空");
            return false;
        }

        var retried = false;

        while (true)
        {
            try
            {
                var data = Encoding.UTF8.GetBytes(text);
                var bookId = $"weread_{chapter}";
                var header = CreateX4IMv2Header(data.Length, 0, bookId);
                var eofMarker = new byte[] { 0x00, 0x45, 0x4F, 0x46, 0x0A }; // \x00EOF\n
                _logger.LogInformation($"BLE: 发送文件 bookId=\"{bookId}\", size={data.Length} 字节");

                if (_writeServiceUuid == null || _writeCharacteristicUuid == null)
                {
                    await CacheWriteCharacteristicAsync();
                }

                if (_writeServiceUuid == null || _writeCharacteristicUuid == null)
                {
                    _logger.LogError("BLE: 无法找到写入特征值");
                    return false;
                }

                using var ms = new MemoryStream();
                ms.Write(header, 0, header.Length);
                ms.Write(data, 0, data.Length);
                ms.Write(eofMarker, 0, eofMarker.Length);
                ms.Position = 0;

                // Shiny 内置分片写入，自动处理 MTU
                await _connectedPeripheral!
                    .WriteCharacteristicBlob(_writeServiceUuid, _writeCharacteristicUuid, ms)
                    .LastOrDefaultAsync();

                _logger.LogInformation($"BLE: 文件数据传输完成! 总字节 {data.Length + header.Length + eofMarker.Length}");

                return true;
            }
            catch (Exception ex)
            {
                _logger.LogError($"BLE: 发送失败 - {ex.Message}");

                if (retried)
                    return false;

                retried = true;
                _logger.LogInformation("BLE: 清空缓存的特征值后重试一次...");
                _writeServiceUuid = null;
                _writeCharacteristicUuid = null;
                await CacheWriteCharacteristicAsync();
                await Task.Delay(200);
            }
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
                _logger.LogWarning($"BLE: 权限请求失败 - {access}");
                return new ObservableCollection<BleDeviceInfo>();
            }
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 权限请求错误 - {ex.Message}");
            return new ObservableCollection<BleDeviceInfo>();
        }

        _scannedDevices = new ObservableCollection<BleDeviceInfo>();
        _discoveredPeripherals.Clear();
        _scanTcs = new TaskCompletionSource<ObservableCollection<BleDeviceInfo>>();

        _logger.LogInformation("BLE: 开始扫描...");

        // Shiny 3.x: 使用 Scan 方法
        _scanSubscription = _bleManager
            .Scan()
            .Subscribe(
                scanResult =>
                {
                    var peripheral = scanResult.Peripheral;
                    var deviceId = peripheral.Uuid;
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
                            _logger.LogDebug($"BLE: 发现设备 - {deviceName}");
                        });
                    }
                },
                error =>
                {
                    _logger.LogError($"BLE: 扫描错误 - {error.Message}");
                    _scanTcs?.TrySetResult(_scannedDevices ?? new ObservableCollection<BleDeviceInfo>());
                }
            );

        // 5秒后停止扫描
        _ = Task.Run(async () =>
        {
            await Task.Delay(5000);
            _bleManager.StopScan();
            _scanSubscription?.Dispose();
            _logger.LogInformation($"BLE: 扫描结束，发现 {_scannedDevices?.Count ?? 0} 个设备");
            _scanTcs?.TrySetResult(_scannedDevices ?? new ObservableCollection<BleDeviceInfo>());
        });

        return await _scanTcs.Task;
    }
}
