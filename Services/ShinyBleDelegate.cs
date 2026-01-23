using Shiny.BluetoothLE;
using Microsoft.Extensions.Logging;
using Shiny;
using System.Reactive.Linq;
using System.Text;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// Shiny Bluetooth Delegate - 实现后台持久化蓝牙事件处理
/// 即使应用在后台或被系统杀死，也能处理蓝牙事件
/// 
/// 关键功能：
/// 1. 监听蓝牙适配器状态变化
/// 2. 监听外围设备连接/断开事件
/// 3. 在后台自动重连已保存的设备
/// 4. 处理来自设备的通知（如按键事件）
/// </summary>
public class ShinyBleDelegate : BleDelegate
{
    private readonly ILogger<ShinyBleDelegate> _logger;
    private readonly IStorageService _storageService;
    private readonly IBleManager _bleManager;
    private IDisposable? _notifySubscription;
    
    // 用于与 ShinyBleService 通信的静态事件
    public static event EventHandler<BlePeripheralEventArgs>? PeripheralConnectedInBackground;
    public static event EventHandler<BlePeripheralEventArgs>? PeripheralDisconnectedInBackground;
    public static event EventHandler<BleNotificationEventArgs>? NotificationReceivedInBackground;

    public ShinyBleDelegate(
        ILogger<ShinyBleDelegate> logger, 
        IStorageService storageService,
        IBleManager bleManager)
    {
        _logger = logger;
        _storageService = storageService;
        _bleManager = bleManager;
    }

    /// <summary>
    /// 当蓝牙适配器状态更改时调用（包括后台）
    /// </summary>
    public override async Task OnAdapterStateChanged(AccessState state)
    {
        _logger.LogInformation($"BLE Delegate: 蓝牙状态更新 - {state}");

        switch (state)
        {
            case AccessState.Available:
                _logger.LogInformation("BLE Delegate: 蓝牙已启用，尝试后台重连...");
                // 蓝牙可用时，尝试重连已保存的设备
                await TryReconnectSavedDeviceAsync();
                break;
                
            case AccessState.Disabled:
                _logger.LogWarning("BLE Delegate: 蓝牙已关闭");
                _notifySubscription?.Dispose();
                _notifySubscription = null;
                break;
                
            case AccessState.Restricted:
                _logger.LogWarning("BLE Delegate: 蓝牙访问受限");
                break;
        }
    }

    /// <summary>
    /// 当外围设备状态变化时调用（连接/断开）- 后台也会触发
    /// </summary>
    public override async Task OnPeripheralStateChanged(IPeripheral peripheral)
    {
        _logger.LogInformation($"BLE Delegate: 设备状态变化 - {peripheral.Name} ({peripheral.Uuid}) - 状态: {peripheral.Status}");

        switch (peripheral.Status)
        {
            case ConnectionState.Connected:
                _logger.LogInformation($"BLE Delegate: 设备已连接 - {peripheral.Name}");
                
                // 通知 ShinyBleService 后台连接成功
                PeripheralConnectedInBackground?.Invoke(this, new BlePeripheralEventArgs(peripheral));
                
                // 订阅通知特征（处理按键事件）
                await SubscribeToNotificationsAsync(peripheral);
                break;
                
            case ConnectionState.Disconnected:
                _logger.LogInformation($"BLE Delegate: 设备已断开 - {peripheral.Name}");
                
                // 清理通知订阅
                _notifySubscription?.Dispose();
                _notifySubscription = null;
                
                // 通知 ShinyBleService 后台断开
                PeripheralDisconnectedInBackground?.Invoke(this, new BlePeripheralEventArgs(peripheral));
                
                // 尝试自动重连
                _ = Task.Run(async () =>
                {
                    await Task.Delay(2000);
                    await TryReconnectPeripheralAsync(peripheral);
                });
                break;
                
            case ConnectionState.Connecting:
                _logger.LogInformation($"BLE Delegate: 正在连接设备 - {peripheral.Name}");
                break;
                
            case ConnectionState.Disconnecting:
                _logger.LogInformation($"BLE Delegate: 正在断开设备 - {peripheral.Name}");
                break;
        }
    }

    /// <summary>
    /// 尝试重连已保存的设备（后台使用）
    /// </summary>
    private async Task TryReconnectSavedDeviceAsync()
    {
        try
        {
            var savedDeviceId = await _storageService.GetAsync<string>("Ble_SavedMacAddress");
            if (string.IsNullOrEmpty(savedDeviceId))
            {
                _logger.LogDebug("BLE Delegate: 没有已保存的设备需要重连");
                return;
            }

            // 检查设备是否已连接
            var connectedPeripherals = _bleManager.GetConnectedPeripherals();
            if (connectedPeripherals.Any(p => p.Uuid == savedDeviceId))
            {
                _logger.LogInformation("BLE Delegate: 设备已连接，跳过重连");
                return;
            }

            _logger.LogInformation($"BLE Delegate: 后台扫描重连设备 {savedDeviceId}...");

            // 使用 Service UUID 进行后台扫描（iOS 后台扫描必须指定 Service UUID）
            var knownServiceUuids = new[]
            {
                "6e400001-b5a3-f393-e0a9-e50e24dcca9e", // Nordic UART Service
                "4fafc201-1fb5-459e-8fcc-c5c9c331914b", // ESP-IDF 示例服务
                "0000ffe0-0000-1000-8000-00805f9b34fb", // 常见透传服务
            };

            var scanConfig = new ScanConfig(knownServiceUuids);
            
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            
            await _bleManager
                .Scan(scanConfig)
                .Where(sr => sr.Peripheral.Uuid == savedDeviceId)
                .Take(1)
                .Timeout(TimeSpan.FromSeconds(30))
                .SelectMany(async sr =>
                {
                    _logger.LogInformation($"BLE Delegate: 找到设备 {sr.Peripheral.Name}，开始连接...");
                    sr.Peripheral.Connect(new ConnectionConfig { AutoConnect = true });
                    return sr;
                })
                .FirstOrDefaultAsync();
        }
        catch (TimeoutException)
        {
            _logger.LogWarning("BLE Delegate: 后台扫描超时，未找到设备");
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE Delegate: 后台重连失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 尝试重连指定外设
    /// </summary>
    private async Task TryReconnectPeripheralAsync(IPeripheral peripheral)
    {
        try
        {
            var savedDeviceId = await _storageService.GetAsync<string>("Ble_SavedMacAddress");
            if (savedDeviceId != peripheral.Uuid)
            {
                _logger.LogDebug("BLE Delegate: 断开的设备不是已保存的设备，跳过重连");
                return;
            }

            if (peripheral.Status == ConnectionState.Connected)
            {
                _logger.LogDebug("BLE Delegate: 设备已连接，跳过重连");
                return;
            }

            _logger.LogInformation($"BLE Delegate: 后台尝试重连 {peripheral.Name}...");
            peripheral.Connect(new ConnectionConfig { AutoConnect = true });
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE Delegate: 重连失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 订阅通知特征（处理按键事件等）
    /// </summary>
    private async Task SubscribeToNotificationsAsync(IPeripheral peripheral)
    {
        try
        {
            _notifySubscription?.Dispose();

            var allCharacteristics = await peripheral
                .GetAllCharacteristics()
                .FirstAsync();

            _logger.LogInformation($"BLE Delegate: 搜索可通知特征，共 {allCharacteristics.Count} 个特征");

            // 排除标准服务
            static bool IsExcludedService(string uuid)
            {
                var u = uuid.ToLowerInvariant();
                return u == "00001800-0000-1000-8000-00805f9b34fb" // Generic Access
                    || u == "00001801-0000-1000-8000-00805f9b34fb"; // Generic Attribute
            }

            // 查找具有 Notify 属性的特征
            var notifyChar = allCharacteristics.FirstOrDefault(ch =>
                !IsExcludedService(ch.Service.Uuid) &&
                (ch.Properties.HasFlag(CharacteristicProperties.Notify) ||
                 ch.Properties.HasFlag(CharacteristicProperties.Indicate)));

            if (notifyChar != null)
            {
                _logger.LogInformation($"BLE Delegate: ✅ 找到可通知特征: {notifyChar.Uuid}");

                _notifySubscription = peripheral
                    .NotifyCharacteristic(
                        notifyChar.Service.Uuid,
                        notifyChar.Uuid,
                        useIndicationsIfAvailable: false
                    )
                    .Subscribe(notificationResult =>
                    {
                        if (notificationResult.Data != null)
                        {
                            var data = notificationResult.Data.ToArray();
                            ProcessNotification(peripheral, data);
                        }
                    });

                _logger.LogInformation("BLE Delegate: 已订阅后台通知");
            }
            else
            {
                _logger.LogWarning("BLE Delegate: 未发现可通知特征");
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning($"BLE Delegate: 订阅通知失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 处理设备通知数据
    /// </summary>
    private void ProcessNotification(IPeripheral peripheral, byte[] data)
    {
        try
        {
            var message = Encoding.UTF8.GetString(data).Trim();
            _logger.LogInformation($"BLE Delegate: 后台收到通知 - {message}");

            // 触发通知事件，让 ShinyBleService 处理
            NotificationReceivedInBackground?.Invoke(this, new BleNotificationEventArgs(peripheral, data, message));
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE Delegate: 处理通知失败 - {ex.Message}");
        }
    }
}

/// <summary>
/// 外设事件参数
/// </summary>
public class BlePeripheralEventArgs : EventArgs
{
    public IPeripheral Peripheral { get; }

    public BlePeripheralEventArgs(IPeripheral peripheral)
    {
        Peripheral = peripheral;
    }
}

/// <summary>
/// 通知事件参数
/// </summary>
public class BleNotificationEventArgs : EventArgs
{
    public IPeripheral Peripheral { get; }
    public byte[] Data { get; }
    public string Message { get; }

    public BleNotificationEventArgs(IPeripheral peripheral, byte[] data, string message)
    {
        Peripheral = peripheral;
        Data = data;
        Message = message;
    }
}
