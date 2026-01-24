using Shiny.BluetoothLE;
using System.Collections.ObjectModel;
using Shiny;
using System.Reactive.Linq;
using System.Text;
using Microsoft.Extensions.Logging;
using System.IO;
#if IOS
using UIKit;
#endif

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 基于 Shiny.NET 3.x 的蓝牙服务 - 支持后台持久化
/// 
/// 架构说明：
/// - ShinyBleService: 前台服务层，处理 UI 请求（扫描、连接、发送数据）
/// - ShinyBleDelegate: 后台委托，处理后台事件（设备断开重连、通知接收）
/// 
/// 两者通过静态事件通信，确保后台事件能够正确更新服务状态
/// </summary>
public class ShinyBleService : IBleService
{
    private readonly IBleManager _bleManager;
    private readonly IStorageService _storageService;
    private readonly ILogger<ShinyBleService> _logger;
    private readonly IWeReadService _weReadService;
    private const string SavedMacKey = "Ble_SavedMacAddress";

    private IPeripheral? _connectedPeripheral;
    private string? _writeServiceUuid;          // 缓存写入服务 UUID
    private string? _writeCharacteristicUuid;   // 缓存写入特征 UUID
    private ObservableCollection<BleDeviceInfo>? _scannedDevices;
    private readonly Dictionary<string, IPeripheral> _discoveredPeripherals = new();
    private TaskCompletionSource<ObservableCollection<BleDeviceInfo>>? _scanTcs;
    private IDisposable? _scanSubscription;
    private IDisposable? _notifySubscription;
    private static readonly Dictionary<byte, string> CommandButtonMap = new()
    {
        { X4IMProtocol.CMD_NEXT_PAGE, "RIGHT" },
        { X4IMProtocol.CMD_PREV_PAGE, "LEFT" },
        { X4IMProtocol.CMD_REFRESH, "OK" },
        { X4IMProtocol.CMD_SHOW_PAGE, "OK" }
    };
    
    // 防重复处理：记录最后处理的按键和时间戳
    private string? _lastProcessedKey;
    private DateTime _lastProcessedTime = DateTime.MinValue;
    private readonly TimeSpan _debounceInterval = TimeSpan.FromMilliseconds(500); // 500ms 防抖
    
    // 按键事件
    public event EventHandler<ButtonEventArgs>? ButtonPressed;
    
    // 连接状态变化事件
    public event EventHandler<ConnectionStateChangedEventArgs>? ConnectionStateChanged;

    public bool IsConnected { get; private set; }
    public string? ConnectedDeviceName { get; private set; }

    public ShinyBleService(IBleManager bleManager, IStorageService storageService, ILogger<ShinyBleService> logger, IWeReadService weReadService)
    {
        _bleManager = bleManager;
        _storageService = storageService;
        _logger = logger;
        _weReadService = weReadService;
        
        // 订阅后台委托的事件
        SubscribeToBackgroundDelegateEvents();

        // 后台初始化阅读状态（无需 UI 即可工作）
        _ = _weReadService.LoadStateAsync();
    }

    /// <summary>
    /// 订阅 ShinyBleDelegate 的后台事件
    /// </summary>
    private void SubscribeToBackgroundDelegateEvents()
    {
        // 后台连接成功事件
        ShinyBleDelegate.PeripheralConnectedInBackground += OnPeripheralConnectedInBackground;
        
        // 后台断开事件
        ShinyBleDelegate.PeripheralDisconnectedInBackground += OnPeripheralDisconnectedInBackground;
        
        // 后台通知事件
        ShinyBleDelegate.NotificationReceivedInBackground += OnNotificationReceivedInBackground;
        
        _logger.LogInformation("BLE Service: 已订阅后台委托事件");
    }

    /// <summary>
    /// 处理后台连接成功事件
    /// </summary>
    private async void OnPeripheralConnectedInBackground(object? sender, BlePeripheralEventArgs e)
    {
        _logger.LogInformation($"BLE Service: 收到后台连接事件 - {e.Peripheral.Name}");
        
        // 检查是否是我们保存的设备
        var savedDeviceId = await GetSavedMacAddress();
        if (savedDeviceId == e.Peripheral.Uuid)
        {
            _connectedPeripheral = e.Peripheral;
            IsConnected = true;
            ConnectedDeviceName = e.Peripheral.Name ?? "未知设备";
            
            // 缓存写入特征
            await CacheWriteCharacteristicAsync();
            
            // 通知 UI 层
            NotifyConnectionStateChanged(true, ConnectedDeviceName, ConnectionChangeReason.AutoReconnect);
            
            _logger.LogInformation($"BLE Service: 后台重连成功 - {ConnectedDeviceName}");
        }
    }

    /// <summary>
    /// 处理后台断开事件
    /// </summary>
    private async void OnPeripheralDisconnectedInBackground(object? sender, BlePeripheralEventArgs e)
    {
        _logger.LogInformation($"BLE Service: 收到后台断开事件 - {e.Peripheral.Name}");
        
        // 检查是否是当前连接的设备
        var savedDeviceId = await GetSavedMacAddress();
        if (savedDeviceId == e.Peripheral.Uuid && IsConnected)
        {
            var previousDeviceName = ConnectedDeviceName;
            IsConnected = false;
            _writeServiceUuid = null;
            _writeCharacteristicUuid = null;
            
            // 通知 UI 层
            NotifyConnectionStateChanged(false, previousDeviceName, ConnectionChangeReason.DeviceDisconnected);
            
            _logger.LogWarning($"BLE Service: 后台设备断开 - {previousDeviceName}");
        }
    }

    /// <summary>
    /// 处理后台通知事件
    /// </summary>
    private void OnNotificationReceivedInBackground(object? sender, BleNotificationEventArgs e)
    {
        HandleNotification(e.Data, e.Message, "后台");
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

            // 检查是否是旧版 MAC 地址格式（包含冒号），如果是则清除旧数据
            if (savedDeviceId.Contains(":"))
            {
                _logger.LogInformation($"BLE: 检测到旧版 MAC 地址格式 ({savedDeviceId})，将使用新 UUID 格式重新配对");
                await DeleteSavedMacAddress();
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
    /// 确保有蓝牙访问权限（先检查再请求，避免重复弹窗）
    /// </summary>
    private async Task<bool> EnsureBleAccessAsync()
    {
        try
        {
            var access = await _bleManager.RequestAccess().FirstAsync();
            return access == AccessState.Available;
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 权限检查错误 - {ex.Message}");
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

        _logger.LogInformation($"BLE: 扫描查找已保存的设备 {savedDeviceId}...");

        try
        {
            if (!await EnsureBleAccessAsync())
            {
                _logger.LogWarning($"BLE: 权限请求失败");
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

                        // 过滤掉没有名字的设备
                        if (string.IsNullOrWhiteSpace(peripheral.Name))
                        {
                            return;
                        }

                        deviceCount++;

                        _logger.LogDebug($"BLE: 发现设备 - {peripheral.Name} (UUID: {deviceId})");

                        if (deviceId == savedDeviceId)
                        {
                            found = true;
                            _bleManager.StopScan();
                            _scanSubscription?.Dispose();

                            _discoveredPeripherals[deviceId] = peripheral;

                            _logger.LogInformation($"BLE: 找到已保存的设备 {peripheral.Name}");
                            MainThread.BeginInvokeOnMainThread(async () =>
                            {
                                await ConnectAsync(deviceId, savedDeviceId);
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
                _logger.LogWarning($"BLE: 扫描了 {deviceCount} 个设备，未找到已保存的设备 (ID: {savedDeviceId})");
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
        var previousDeviceName = ConnectedDeviceName;
        var wasConnected = IsConnected;
        
        await _storageService.RemoveAsync(SavedMacKey);
        
        // 断开连接但不触发 UserDisconnected 事件
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
        _logger.LogInformation("BLE: 已删除保存的设备并断开连接");
        
        // 只有之前已连接时才触发事件
        if (wasConnected)
        {
            NotifyConnectionStateChanged(false, previousDeviceName, ConnectionChangeReason.DeviceDeleted);
        }
    }

    public async Task<bool> ConnectAsync(string deviceId, string macAddress)
    {
        try
        {
            if (_discoveredPeripherals.TryGetValue(deviceId, out var peripheral))
            {
                return await ConnectToPeripheralAsync(peripheral, deviceId);
            }
            else
            {
                // 设备不在已发现列表中，先扫描查找
                _logger.LogWarning($"BLE: 未在缓存中找到设备 {deviceId}，开始扫描...");
                
                // 扫描并查找目标设备
                var foundDevice = await ScanForDeviceAsync(deviceId);
                if (foundDevice != null)
                {
                    return await ConnectToPeripheralAsync(foundDevice, deviceId);
                }
                
                _logger.LogWarning($"BLE: 扫描后仍未找到设备 {deviceId}");
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
    /// 扫描查找指定设备
    /// </summary>
    private async Task<IPeripheral?> ScanForDeviceAsync(string targetDeviceId)
    {
        try
        {
            if (!await EnsureBleAccessAsync())
            {
                _logger.LogWarning($"BLE: 权限请求失败");
                return null;
            }

            var foundPeripheral = (IPeripheral?)null;
            var deviceCount = 0;

            var scanCompletion = new TaskCompletionSource<bool>();
            
            _scanSubscription = _bleManager
                .Scan()
                .Subscribe(
                    scanResult =>
                    {
                        var peripheral = scanResult.Peripheral;

                        // 过滤掉没有名字的设备
                        if (string.IsNullOrWhiteSpace(peripheral.Name))
                        {
                            return;
                        }

                        deviceCount++;

                        var peripheralUuid = peripheral.Uuid;
                        _discoveredPeripherals[peripheralUuid] = peripheral;

                        _logger.LogDebug($"BLE: 发现设备 - {peripheral.Name} (UUID: {peripheralUuid})");

                        if (peripheralUuid == targetDeviceId)
                        {
                            foundPeripheral = peripheral;
                            _logger.LogInformation($"BLE: 找到目标设备 {peripheral.Name}");
                            _bleManager.StopScan();
                            _scanSubscription?.Dispose();
                            scanCompletion.TrySetResult(true);
                        }
                    },
                    error =>
                    {
                        _logger.LogError($"BLE: 扫描错误 - {error.Message}");
                        scanCompletion.TrySetException(error);
                    }
                );

            // 最多扫描 10 秒
            var timeoutTask = Task.Delay(10000);
            var completedTask = await Task.WhenAny(scanCompletion.Task, timeoutTask);
            
            if (!scanCompletion.Task.IsCompleted)
            {
                _bleManager.StopScan();
                _scanSubscription?.Dispose();
                _logger.LogWarning($"BLE: 扫描超时，扫描了 {deviceCount} 个设备");
            }

            return foundPeripheral;
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 扫描设备失败 - {ex.Message}");
            return null;
        }
    }

    /// <summary>
    /// 连接到外设
    /// </summary>
    private async Task<bool> ConnectToPeripheralAsync(IPeripheral peripheral, string deviceId)
    {
        try
        {
            _connectedPeripheral = peripheral;
            _writeServiceUuid = null;
            _writeCharacteristicUuid = null;

            _logger.LogInformation($"BLE: 开始连接到 {peripheral.Name ?? "未知设备"}...");

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

            // 触发连接状态变化事件
            NotifyConnectionStateChanged(true, ConnectedDeviceName, ConnectionChangeReason.UserInitiated);

            // 缓存写入特征值以提升性能
            await CacheWriteCharacteristicAsync();

            // 订阅通知特征（当前固件暂无通知，保持兼容占位）
            await SubscribeToNotificationsAsync();

            // 监听断开事件
            SetupDisconnectionHandler();

            // 保存设备 UUID（用于后续自动连接）
            var peripheralUuid = peripheral.Uuid;
            _logger.LogInformation($"BLE: 保存设备 UUID: {peripheralUuid}");
            await SaveMacAddress(peripheralUuid);

            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 连接外设失败 - {ex.Message}");
            IsConnected = false;
            _connectedPeripheral = null;
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
                var previousDeviceName = ConnectedDeviceName;
                _logger.LogWarning($"BLE: 设备 {previousDeviceName} 已断开");
                IsConnected = false;
                _writeServiceUuid = null;
                _writeCharacteristicUuid = null; // 清空缓存的特征值，防止重连后使用旧句柄
                _notifySubscription?.Dispose();
                _notifySubscription = null;
                
                // 触发连接状态变化事件
                NotifyConnectionStateChanged(false, previousDeviceName, ConnectionChangeReason.DeviceDisconnected);
                
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
    /// 订阅通知特征（接收设备按键事件）- 动态发现方式
    /// </summary>
    private async Task SubscribeToNotificationsAsync()
    {
        if (_connectedPeripheral == null) return;

        try
        {
            _notifySubscription?.Dispose();

            // 获取所有特征并查找具有 Notify 属性的特征
            var allCharacteristics = await _connectedPeripheral
                .GetAllCharacteristics()
                .FirstAsync();

            _logger.LogInformation($"BLE: 搜索可通知特征，共 {allCharacteristics.Count} 个特征");

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
                _logger.LogInformation($"BLE: ✅ 找到可通知特征: {notifyChar.Uuid} @ 服务 {notifyChar.Service.Uuid}");

                // 订阅通知
                _notifySubscription = _connectedPeripheral
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
                            ProcessNotification(data);
                        }
                    });

                _logger.LogInformation("BLE: 已订阅通知，按键事件可用");
            }
            else
            {
                _logger.LogWarning("BLE: 未发现可通知特征，按键事件将不可用");
            }
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
        HandleNotification(data, null, "前台");
    }

    /// <summary>
    /// 统一处理按键事件（自动翻页并获取内容发送到设备）
    /// 此方法由 UI 或后台调用，确保不会重复执行
    /// </summary>
    public async Task ProcessButtonAsync(string key)
    {
        // 防重复检查：如果在防抖时间窗口内收到相同按键，直接忽略
        lock (this)
        {
            var now = DateTime.UtcNow;
            if (key == _lastProcessedKey && (now - _lastProcessedTime) < _debounceInterval)
            {
                _logger.LogDebug($"⚠️ 忽略重复按键事件: {key} (距上次 {(now - _lastProcessedTime).TotalMilliseconds:F0}ms)");
                return;
            }

            _lastProcessedKey = key;
            _lastProcessedTime = now;
            _logger.LogInformation($"✅ 处理按键事件: {key}");
        }

        try
        {
#if IOS
            // 在 iOS 上开启后台任务，确保网络请求与 BLE 写入可在后台完成
            nint bgTaskId = 0;
            try
            {
                bgTaskId = UIApplication.SharedApplication.BeginBackgroundTask("BLEPageTurn", () => { });
#endif
            // 处理 RIGHT/LEFT 翻页；OK/ENTER 刷新当前内容
            if ((key != "RIGHT" && key != "LEFT" && key != "OK" && key != "ENTER") ||
                !IsConnected ||
                string.IsNullOrEmpty(_weReadService.State.CurrentUrl))
            {
                return;
            }

            string content = string.Empty;
            if (key == "OK" || key == "ENTER")
            {
                // 设备请求刷新：直接发送最后一次成功内容（走缓存）
                content = _weReadService.State.LastText;
                if (string.IsNullOrEmpty(content))
                {
                    var cached = await _weReadService.GetCachedContentAsync(_weReadService.State.CurrentUrl);
                    content = cached ?? string.Empty;
                }
                _logger.LogInformation($"🔁 刷新当前页，使用已保存/缓存内容: {(string.IsNullOrEmpty(content) ? 0 : content.Length)} 字符");
            }
            else if (key == "RIGHT")
            {
                _logger.LogInformation($"🔄 处理按键：获取下一章");
                try
                {
                    content = await _weReadService.GetNextPageAsync();
                }
                catch (Exception ex)
                {
                    _logger.LogWarning($"获取下一章失败，尝试使用缓存: {ex.Message}");
                    var cached = await _weReadService.GetCachedContentAsync(_weReadService.State.CurrentUrl);
                    content = cached ?? string.Empty;
                }
            }
            else // LEFT
            {
                _logger.LogInformation($"🔄 处理按键：获取上一章");
                try
                {
                    content = await _weReadService.GetPrevPageAsync();
                }
                catch (Exception ex)
                {
                    _logger.LogWarning($"获取上一章失败，尝试使用缓存: {ex.Message}");
                    var cached = await _weReadService.GetCachedContentAsync(_weReadService.State.CurrentUrl);
                    content = cached ?? string.Empty;
                }
            }

            // 自动发送到设备
            if (!string.IsNullOrEmpty(content))
            {
                _logger.LogInformation($"📤 发送内容到 EPD ({content.Length} 字符)");
                var success = await SendTextToDeviceAsync(content, _weReadService.State.Page);
                if (success)
                {
                    _logger.LogInformation($"✅ 发送成功");
                }
                else
                {
                    _logger.LogWarning($"⚠️ 发送失败");
                }
            }
#if IOS
            }
            finally
            {
                if (bgTaskId != 0)
                    UIApplication.SharedApplication.EndBackgroundTask(bgTaskId);
            }
#endif
        }
        catch (Exception ex)
        {
            _logger.LogError($"❌ 处理按键失败: {ex.Message}");
        }
    }

    /// <summary>
    /// 更新阅读上下文（URL 与 Cookie），并持久化
    /// </summary>
    public async Task UpdateReadingContextAsync(string url, string cookie)
    {
        try
        {
            _weReadService.SetReadingContext(url, cookie);
            await _weReadService.SaveStateAsync();
            _logger.LogInformation($"WeRead: 阅读上下文已更新 URL={url} CookieLen={cookie?.Length ?? 0}");
        }
        catch (Exception ex)
        {
            _logger.LogError($"WeRead: 更新阅读上下文失败 - {ex.Message}");
        }
    }

    private void HandleNotification(byte[] data, string? messageFromDelegate, string sourceLabel)
    {
        try
        {
            var hex = BitConverter.ToString(data);
            var message = NormalizeNotificationText(data, messageFromDelegate);
            _logger.LogInformation($"🔔 BLE 收到{sourceLabel}通知 (长度: {data.Length}B)");
            _logger.LogInformation($"   文本: \"{message}\"");
            _logger.LogInformation($"   Hex:  {hex}");

            if (TryMapButtonKey(message, data, out var key))
            {
                _logger.LogInformation($"✅ 映射到按键事件: {key}");

                // 触发 UI 层的按键事件（仅用于更新状态显示）
                ButtonPressed?.Invoke(this, new ButtonEventArgs(key));

                // 调用统一的按键处理方法（后台翻页 + 发送到设备）
                _ = ProcessButtonAsync(key);

                return;
            }

            _logger.LogWarning($"⚠️  未识别的通知格式，忽略");
        }
        catch (Exception ex)
        {
            _logger.LogError($"❌ 处理通知失败: {ex.Message}");
        }
    }

    private static string NormalizeNotificationText(byte[] data, string? original)
    {
        if (!string.IsNullOrWhiteSpace(original))
        {
            var trimmed = original.Trim('\0', '\r', '\n', ' ');
            if (!string.IsNullOrEmpty(trimmed))
            {
                return trimmed;
            }
        }

        var printable = data.Where(b => b >= 0x20 && b <= 0x7E).ToArray();
        if (printable.Length > 0)
        {
            return Encoding.ASCII.GetString(printable);
        }

        return data.Length > 0 ? $"0x{data[0]:X2}" : string.Empty;
    }

    private bool TryMapButtonKey(string message, byte[] data, out string key)
    {
        key = string.Empty;

        if (!string.IsNullOrWhiteSpace(message))
        {
            var normalized = message.Trim().ToUpperInvariant();

            // 文本协议：BTN:LEFT, BTN:RIGHT 等
            if (normalized.StartsWith("BTN:"))
            {
                key = normalized.Substring(4);
                return true;
            }

            // 别名映射：NEXT_PAGE → RIGHT, PREV_PAGE → LEFT
            if (normalized is "NEXT_PAGE" or "NEXT" or "PAGE_NEXT" or "RIGHT")
            {
                key = "RIGHT";
                return true;
            }

            if (normalized is "PREV_PAGE" or "PREVIOUS" or "PAGE_PREV" or "LEFT")
            {
                key = "LEFT";
                return true;
            }

            if (normalized is "UP")
            {
                key = "UP";
                return true;
            }

            if (normalized is "DOWN")
            {
                key = "DOWN";
                return true;
            }

            if (normalized is "OK" or "ENTER")
            {
                key = "OK";
                return true;
            }
        }

        // 二进制协议：直接映射命令字节（如 0x81=NEXT_PAGE → RIGHT）
        if (data.Length > 0 && CommandButtonMap.TryGetValue(data[0], out var mapped))
        {
            _logger.LogInformation($"   命令字节映射: 0x{data[0]:X2} → {mapped}");
            key = mapped;
            return true;
        }

        return false;
    }

    public void Disconnect()
    {
        var previousDeviceName = ConnectedDeviceName;
        
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
        
        // 触发连接状态变化事件
        NotifyConnectionStateChanged(false, previousDeviceName, ConnectionChangeReason.UserDisconnected);
    }

    /// <summary>
    /// 通知连接状态变化
    /// </summary>
    private void NotifyConnectionStateChanged(bool isConnected, string? deviceName, ConnectionChangeReason reason)
    {
        MainThread.BeginInvokeOnMainThread(() =>
        {
            ConnectionStateChanged?.Invoke(this, new ConnectionStateChangedEventArgs(isConnected, deviceName, reason));
        });
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

            // 排除标准服务（0x1800/0x1801），避免误选 0x2B29 等系统特征
            static bool IsStandardBase(string uuid)
                => uuid.EndsWith("-0000-1000-8000-00805f9b34fb", StringComparison.OrdinalIgnoreCase);

            static bool IsExcludedService(string uuid)
            {
                var u = uuid.ToLowerInvariant();
                return u == "00001800-0000-1000-8000-00805f9b34fb" // Generic Access
                    || u == "00001801-0000-1000-8000-00805f9b34fb"; // Generic Attribute
            }

            var knownServicePref = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                // Nordic UART Service
                "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                // 常见透传服务
                "0000ffe0-0000-1000-8000-00805f9b34fb",
                "0000abf0-0000-1000-8000-00805f9b34fb",
                // ESP-IDF 示例服务
                "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
            };

            var knownCharPref = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                // NUS Write 特征
                "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
                // FFE1 常作透传特征
                "0000ffe1-0000-1000-8000-00805f9b34fb",
                // ESP-IDF 示例特征
                "beb5483e-36e1-4688-b7f5-ea07361b26a8"
            };

            // 计算候选评分并选择最佳
            var candidates = new List<(BleCharacteristicInfo ch, int score)>();
            foreach (var ch in allCharacteristics)
            {
                var props = ch.Properties;
                var canWrite = props.HasFlag(CharacteristicProperties.Write) || props.HasFlag(CharacteristicProperties.WriteWithoutResponse);
                _logger.LogDebug($"BLE: 特征 {ch.Uuid} @ 服务 {ch.Service.Uuid} Props={props}");
                if (!canWrite)
                    continue;

                if (IsExcludedService(ch.Service.Uuid))
                {
                    // 丢弃 GA/GAtt 服务下的写特征（如 0x2B29）
                    _logger.LogDebug($"BLE: 排除系统服务可写特征 {ch.Uuid} @ {ch.Service.Uuid}");
                    continue;
                }

                var score = 0;
                // 首选 Write Without Response（常见串口透传表现更稳定）
                if (props.HasFlag(CharacteristicProperties.WriteWithoutResponse)) score += 120;
                if (props.HasFlag(CharacteristicProperties.Write)) score += 80;

                // 自定义 128-bit UUID 优先
                if (!IsStandardBase(ch.Service.Uuid)) score += 60;
                if (!IsStandardBase(ch.Uuid)) score += 20;

                // 已知服务/特征额外加分
                if (knownServicePref.Contains(ch.Service.Uuid)) score += 100;
                if (knownCharPref.Contains(ch.Uuid)) score += 100;

                // 避免选择 0x2Bxx 类系统特征
                var chLower = ch.Uuid.ToLowerInvariant();
                if (chLower.StartsWith("00002b") && IsStandardBase(ch.Uuid)) score -= 200;

                candidates.Add((ch, score));
            }

            if (candidates.Count == 0)
            {
                _logger.LogWarning("BLE: 未找到任何可写特征值!");
                return;
            }

            // 调试输出候选排序
            foreach (var c in candidates.OrderByDescending(x => x.score))
            {
                _logger.LogInformation($"BLE: 候选写特征 score={c.score} svc={c.ch.Service.Uuid} ch={c.ch.Uuid} props={c.ch.Properties}");
            }

            var best = candidates.OrderByDescending(x => x.score).First().ch;
            _writeServiceUuid = best.Service.Uuid;
            _writeCharacteristicUuid = best.Uuid;
            _logger.LogInformation("BLE: ✅ 选定写特征值");
            _logger.LogInformation($"BLE:    服务: {_writeServiceUuid}");
            _logger.LogInformation($"BLE:    特征值: {_writeCharacteristicUuid}");
            _logger.LogInformation($"BLE:    属性: {best.Properties}");
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
                _logger.LogInformation($"BLE: 发送文件 bookId=\"{bookId}\", size={data.Length} 字节");

                var sent = await SendFrameAsync(header, data, appendEof: true);
                if (sent)
                {
                    _logger.LogInformation($"BLE: 文件传输完成（含 EOF）! {data.Length + X4IMProtocol.EOF_MARKER.Length} 字节");
                }

                return sent;
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
    /// 手动发送 EOF 标记到设备
    /// </summary>
    public async Task<bool> SendEofAsync()
    {
        if (!IsConnected || _connectedPeripheral == null)
        {
            _logger.LogWarning("BLE: 设备未连接，无法发送 EOF");
            return false;
        }

        if (_writeServiceUuid == null || _writeCharacteristicUuid == null)
        {
            await CacheWriteCharacteristicAsync();
        }

        if (_writeServiceUuid == null || _writeCharacteristicUuid == null || _connectedPeripheral == null)
        {
            _logger.LogError("BLE: 无法找到写入特征值");
            return false;
        }

        try
        {
            _logger.LogInformation("BLE: 手动发送 EOF 标记");
            
            using var ms = new MemoryStream();
            ms.Write(X4IMProtocol.EOF_MARKER, 0, X4IMProtocol.EOF_MARKER.Length);
            ms.Position = 0;

            await _connectedPeripheral
                .WriteCharacteristicBlob(_writeServiceUuid, _writeCharacteristicUuid, ms)
                .LastOrDefaultAsync();

            _logger.LogInformation($"BLE: EOF 发送完成 ({X4IMProtocol.EOF_MARKER.Length} 字节)");
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 发送 EOF 失败 - {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 发送图片到设备（X4IM v2 协议，默认 PNG，不追加 EOF）
    /// </summary>
    public async Task<bool> SendImageToDeviceAsync(byte[] imageData, string fileName = "page_0.png", ushort flags = X4IMProtocol.FLAG_TYPE_PNG, bool sendShowPage = true, byte pageIndex = 0)
    {
        if (!IsConnected || _connectedPeripheral == null)
        {
            _logger.LogWarning("BLE: 设备未连接，无法发送图片");
            return false;
        }

        if (imageData == null || imageData.Length == 0)
        {
            _logger.LogWarning("BLE: 图片数据为空");
            return false;
        }

        try
        {
            var header = CreateX4IMv2Header(imageData.Length, 0, fileName, flags);
            _logger.LogInformation($"BLE: 发送图片 file=\"{fileName}\" size={imageData.Length} 字节 flags=0x{flags:X4}");

            var sent = await SendFrameAsync(header, imageData, appendEof: false);
            if (!sent)
            {
                return false;
            }

            if (sendShowPage)
            {
                // 默认沿用 SHOW_PAGE 命令触发刷新
                await SendCommandAsync(X4IMProtocol.CMD_SHOW_PAGE, new byte[] { pageIndex });
            }

            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 发送图片失败 - {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 创建 X4IM v2 协议帧头
    /// </summary>
    private static byte[] CreateX4IMv2Header(int payloadSize, int sd, string name, ushort flags = X4IMProtocol.FLAG_TYPE_TXT)
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

        // Flags (小端序)
        header[6] = (byte)(flags & 0xFF);
        header[7] = (byte)((flags >> 8) & 0xFF);

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

    private async Task<bool> SendFrameAsync(byte[] header, byte[] payload, bool appendEof)
    {
        if (_writeServiceUuid == null || _writeCharacteristicUuid == null)
        {
            await CacheWriteCharacteristicAsync();
        }

        if (_writeServiceUuid == null || _writeCharacteristicUuid == null || _connectedPeripheral == null)
        {
            _logger.LogError("BLE: 无法找到写入特征值");
            return false;
        }

        using var ms = new MemoryStream();
        ms.Write(header, 0, header.Length);
        ms.Write(payload, 0, payload.Length);

        if (appendEof)
        {
            ms.Write(X4IMProtocol.EOF_MARKER, 0, X4IMProtocol.EOF_MARKER.Length);
        }

        ms.Position = 0;

        await _connectedPeripheral
            .WriteCharacteristicBlob(_writeServiceUuid, _writeCharacteristicUuid, ms)
            .LastOrDefaultAsync();

        return true;
    }

    private async Task<bool> SendCommandAsync(byte command, byte[]? payload = null)
    {
        if (!IsConnected || _connectedPeripheral == null)
        {
            return false;
        }

        if (_writeServiceUuid == null || _writeCharacteristicUuid == null)
        {
            await CacheWriteCharacteristicAsync();
        }

        if (_writeServiceUuid == null || _writeCharacteristicUuid == null)
        {
            _logger.LogError("BLE: 无法找到写入特征值");
            return false;
        }

        var length = 1 + (payload?.Length ?? 0);
        var buffer = new byte[length];
        buffer[0] = command;
        if (payload is { Length: > 0 })
        {
            Array.Copy(payload, 0, buffer, 1, payload.Length);
        }

        using var ms = new MemoryStream(buffer);
        await _connectedPeripheral
            .WriteCharacteristicBlob(_writeServiceUuid, _writeCharacteristicUuid, ms)
            .LastOrDefaultAsync();

        return true;
    }

    public async Task<ObservableCollection<BleDeviceInfo>> ScanDevicesAsync()
    {
        if (!await EnsureBleAccessAsync())
        {
            _logger.LogWarning($"BLE: 权限请求失败");
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

                    // 过滤掉没有名字的设备
                    if (string.IsNullOrWhiteSpace(peripheral.Name))
                    {
                        return;
                    }

                    if (!_discoveredPeripherals.ContainsKey(deviceId))
                    {
                        _discoveredPeripherals[deviceId] = peripheral;

                        var deviceInfo = new BleDeviceInfo
                        {
                            Id = deviceId,
                            Name = $"{peripheral.Name} ({deviceId.Substring(0, Math.Min(8, deviceId.Length))}...)",
                            MacAddress = deviceId
                        };

                        MainThread.BeginInvokeOnMainThread(() =>
                        {
                            _scannedDevices?.Add(deviceInfo);
                            _logger.LogDebug($"BLE: 发现设备 - {peripheral.Name}");
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
