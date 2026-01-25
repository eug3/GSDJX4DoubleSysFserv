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
#if ANDROID
using Android.App;
using Android.Content;
using Android.OS;
using GSDJX4DoubleSysFserv.Platforms.Android;
#endif

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 基于 Shiny.NET 3.x 的蓝牙服务 - 统一跨平台 BLE 通信
 /// </summary>
public class ShinyBleService : IBleService
{
    private readonly IBleManager _bleManager;
    private readonly IStorageService _storageService;
    private readonly ILogger<ShinyBleService> _logger;
    private readonly IWeReadService _weReadService;
    private const string SavedMacKey = "Ble_SavedMacAddress";

    private IPeripheral? _connectedPeripheral;
    private string? _writeServiceUuid;
    private string? _writeCharacteristicUuid;
    private int _negotiatedMtu = 23; // BLE 默认值是 23 字节（20 + 3 ATT header）
    private ObservableCollection<BleDeviceInfo>? _scannedDevices;
#if IOS
    private nint _bgTaskId = 0; // iOS 后台任务 ID，对应 Android 前台服务
#endif
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
    
    // 防重复处理
    private string? _lastProcessedKey;
    private DateTime _lastProcessedTime = DateTime.MinValue;
    private readonly TimeSpan _debounceInterval = TimeSpan.FromMilliseconds(500);
    
    public event EventHandler<ButtonEventArgs>? ButtonPressed;
    public event EventHandler<ConnectionStateChangedEventArgs>? ConnectionStateChanged;

    public bool IsConnected { get; private set; }
    public string? ConnectedDeviceName { get; private set; }

    public ShinyBleService(IBleManager bleManager, IStorageService storageService, ILogger<ShinyBleService> logger, IWeReadService weReadService)
    {
        _bleManager = bleManager;
        _storageService = storageService;
        _logger = logger;
        _weReadService = weReadService;
        
        SubscribeToBackgroundDelegateEvents();
        _ = _weReadService.LoadStateAsync();
    }

    private void SubscribeToBackgroundDelegateEvents()
    {
        ShinyBleDelegate.PeripheralConnectedInBackground += OnPeripheralConnectedInBackground;
        ShinyBleDelegate.PeripheralDisconnectedInBackground += OnPeripheralDisconnectedInBackground;
        ShinyBleDelegate.NotificationReceivedInBackground += OnNotificationReceivedInBackground;
        
        _logger.LogInformation("BLE Service: 已订阅后台委托事件");
    }

    private async void OnPeripheralConnectedInBackground(object? sender, BlePeripheralEventArgs e)
    {
        _logger.LogInformation($"BLE Service: 收到后台连接事件 - {e.Peripheral.Name}");
        
        var savedDeviceId = await GetSavedMacAddress();
        if (savedDeviceId == e.Peripheral.Uuid)
        {
            _connectedPeripheral = e.Peripheral;
            IsConnected = true;
            ConnectedDeviceName = e.Peripheral.Name ?? "未知设备";
            
#if IOS
            // 重新启动后台任务，防止系统已关闭
            StartIosBackgroundTask();
#elif ANDROID
            // 重新启动前台服务，防止系统已关闭
            StartBleForegroundService();
#endif
            
            await CacheWriteCharacteristicAsync();
            await SubscribeToNotificationsAsync();
            
            // 协商 MTU
            NegotiateMtuAsync();
            
            NotifyConnectionStateChanged(true, ConnectedDeviceName, ConnectionChangeReason.AutoReconnect);
            
            _logger.LogInformation($"BLE Service: 后台重连初始化完成 - {ConnectedDeviceName}");
        }
    }

    private async void OnPeripheralDisconnectedInBackground(object? sender, BlePeripheralEventArgs e)
    {
        _logger.LogInformation($"BLE Service: 收到后台断开事件 - {e.Peripheral.Name}");
        
        var savedDeviceId = await GetSavedMacAddress();
        if (savedDeviceId == e.Peripheral.Uuid && IsConnected)
        {
            var previousDeviceName = ConnectedDeviceName;
            IsConnected = false;
            _writeServiceUuid = null;
            _writeCharacteristicUuid = null;
            _negotiatedMtu = 23; // BLE 默认值，系统会自行协商
            
            NotifyConnectionStateChanged(false, previousDeviceName, ConnectionChangeReason.DeviceDisconnected);
            
            _logger.LogWarning($"BLE Service: 后台设备断开 - {previousDeviceName}，保持服务运行等待重连");
        }
    }

    private void OnNotificationReceivedInBackground(object? sender, BleNotificationEventArgs e)
    {
        HandleNotification(e.Data, e.Message, "后台");
    }

    /// <summary>
    /// MTU 协商
    /// Android 使用 TryRequestMtuAsync 请求更大 MTU，iOS 系统会自动协商
    /// </summary>
    private async void NegotiateMtuAsync()
    {
        if (_connectedPeripheral == null)
        {
            _logger.LogWarning("BLE: 设备未连接，无法请求 MTU");
            return;
        }

#if ANDROID
        try
        {
            _logger.LogInformation("BLE: Android 请求 MTU 517...");
            var result = await _connectedPeripheral.TryRequestMtuAsync(517);
            _logger.LogInformation($"BLE: Android MTU 请求结果 = {result}");
        }
        catch (Exception ex)
        {
            _logger.LogWarning($"BLE: Android MTU 请求失败 - {ex.Message}");
        }
#else
        _logger.LogInformation($"BLE: iOS MTU 使用系统协商值（默认 {_negotiatedMtu} 字节）");
#endif
    }

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
        
#if IOS
        // 停止后台任务
        StopIosBackgroundTask();
#elif ANDROID
        // 停止前台服务
        StopBleForegroundService();
#endif
        
        _notifySubscription?.Dispose();
        _notifySubscription = null;
        _writeServiceUuid = null;
        _writeCharacteristicUuid = null;
        _negotiatedMtu = 23; // BLE 默认值，系统会自行协商

        if (_connectedPeripheral != null)
        {
            _connectedPeripheral.CancelConnection();
            _connectedPeripheral = null;
        }
        
        IsConnected = false;
        ConnectedDeviceName = null;
        _logger.LogInformation("BLE: 已删除保存的设备、断开连接并停止服务");
        
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
                _logger.LogWarning($"BLE: 未在缓存中找到设备 {deviceId}，开始扫描...");
                
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

    private async Task<bool> ConnectToPeripheralAsync(IPeripheral peripheral, string deviceId)
    {
        try
        {
            _connectedPeripheral = peripheral;
            _writeServiceUuid = null;
            _writeCharacteristicUuid = null;
            _negotiatedMtu = 23; // BLE 默认值，系统会自行协商

            _logger.LogInformation($"BLE: 开始连接到 {peripheral.Name ?? "未知设备"}...");

            var connectTask = peripheral
                .WhenStatusChanged()
                .Where(x => x == ConnectionState.Connected)
                .Take(1)
                .Timeout(TimeSpan.FromSeconds(15))
                .FirstAsync();

            peripheral.Connect(new ConnectionConfig
            {
                AutoConnect = true
            });
            await connectTask;

            IsConnected = true;
            ConnectedDeviceName = peripheral.Name ?? "未知设备";
            _logger.LogInformation($"BLE: 已连接到 {ConnectedDeviceName}");

            NotifyConnectionStateChanged(true, ConnectedDeviceName, ConnectionChangeReason.UserInitiated);

#if IOS
            // 启动后台任务，对应 Android 前台服务
            StartIosBackgroundTask();
#elif ANDROID
            // 启动前台服务，对应 iOS 的 BeginBackgroundTask
            StartBleForegroundService();
#endif

            await CacheWriteCharacteristicAsync();
            await SubscribeToNotificationsAsync();
            SetupDisconnectionHandler();

            // 协商 MTU
            NegotiateMtuAsync();

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

    private void SetupDisconnectionHandler()
    {
        if (_connectedPeripheral == null) return;

        _connectedPeripheral
            .WhenStatusChanged()
            .Subscribe(async state =>
            {
                if (state == ConnectionState.Disconnected)
                {
                    var previousDeviceName = ConnectedDeviceName;
                    _logger.LogWarning($"BLE: 设备 {previousDeviceName} 已断开，保持服务运行等待重连");
                    IsConnected = false;
                    _writeServiceUuid = null;
                    _writeCharacteristicUuid = null;
                    _negotiatedMtu = 23; // BLE 默认值，系统会自行协商
                    _notifySubscription?.Dispose();
                    _notifySubscription = null;
                    
                    NotifyConnectionStateChanged(false, previousDeviceName, ConnectionChangeReason.DeviceDisconnected);
                    
                    MainThread.BeginInvokeOnMainThread(async () =>
                    {
                        await Task.Delay(2000);
                        if (!IsConnected && _connectedPeripheral != null)
                        {
                            _logger.LogInformation("BLE: 尝试自动重连...");
                            _connectedPeripheral.Connect(new ConnectionConfig { AutoConnect = true });
                        }
                    });
                }
                else if (state == ConnectionState.Connected && !IsConnected)
                {
                    // 自动重连成功，重新初始化
                    _logger.LogInformation("BLE: 自动重连成功，重新初始化...");
                    IsConnected = true;
                    
#if IOS
                    // 重新启动后台任务，防止系统已关闭
                    StartIosBackgroundTask();
#elif ANDROID
                    // 重新启动前台服务，防止系统已关闭
                    StartBleForegroundService();
#endif
                    
                    await CacheWriteCharacteristicAsync();
                    await SubscribeToNotificationsAsync();
                    
                    // 协商 MTU
                    NegotiateMtuAsync();
                    
                    NotifyConnectionStateChanged(true, ConnectedDeviceName, ConnectionChangeReason.AutoReconnect);
                    _logger.LogInformation($"BLE: 自动重连初始化完成 - {ConnectedDeviceName}");
                }
            });
    }

    private async Task SubscribeToNotificationsAsync()
    {
        if (_connectedPeripheral == null) return;

        try
        {
            _notifySubscription?.Dispose();

            var allCharacteristics = await _connectedPeripheral
                .GetAllCharacteristics()
                .FirstAsync();

            _logger.LogInformation($"BLE: 搜索可通知特征，共 {allCharacteristics.Count} 个特征");

            static bool IsExcludedService(string uuid)
            {
                var u = uuid.ToLowerInvariant();
                return u == "00001800-0000-1000-8000-00805f9b34fb"
                    || u == "00001801-0000-1000-8000-00805f9b34fb";
            }

            var notifyChar = allCharacteristics.FirstOrDefault(ch =>
                !IsExcludedService(ch.Service.Uuid) &&
                (ch.Properties.HasFlag(CharacteristicProperties.Notify) ||
                 ch.Properties.HasFlag(CharacteristicProperties.Indicate)));

            if (notifyChar != null)
            {
                _logger.LogInformation($"BLE: ✅ 找到可通知特征: {notifyChar.Uuid} @ 服务 {notifyChar.Service.Uuid}");

                _notifySubscription = _connectedPeripheral
                    .NotifyCharacteristic(
                        notifyChar.Service.Uuid,
                        notifyChar.Uuid,
                        useIndicationsIfAvailable: true
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

    private void ProcessNotification(byte[] data)
    {
        HandleNotification(data, null, "前台");
    }

    public async Task ProcessButtonAsync(string key)
    {
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
            if ((key != "RIGHT" && key != "LEFT" && key != "OK" && key != "ENTER") ||
                !IsConnected ||
                string.IsNullOrEmpty(_weReadService.State.CurrentUrl))
            {
                return;
            }

            string content = string.Empty;
            if (key == "OK" || key == "ENTER")
            {
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
            else
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
        }
        catch (Exception ex)
        {
            _logger.LogError($"❌ 处理按键失败: {ex.Message}");
        }
    }

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

                ButtonPressed?.Invoke(this, new ButtonEventArgs(key));

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

        if (!string.IsNullOrWhiteSpace(message))
        {
            var normalized = message.Trim().ToUpperInvariant();

            if (normalized.StartsWith("BTN:"))
            {
                key = normalized.Substring(4);
                return true;
            }

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

#if IOS
        // 停止后台任务，对应 Android 前台服务
        StopIosBackgroundTask();
#elif ANDROID
        // 停止前台服务，对应 iOS 的 EndBackgroundTask
        StopBleForegroundService();
#endif

        _notifySubscription?.Dispose();
        _notifySubscription = null;
        _writeServiceUuid = null;
        _writeCharacteristicUuid = null;
        _negotiatedMtu = 23; // BLE 默认值，系统会自行协商

        if (_connectedPeripheral != null)
        {
            _connectedPeripheral.CancelConnection();
            _connectedPeripheral = null;
        }

        IsConnected = false;
        ConnectedDeviceName = null;
        _logger.LogInformation("BLE: 已断开连接");

        NotifyConnectionStateChanged(false, previousDeviceName, ConnectionChangeReason.UserDisconnected);
    }

    private void NotifyConnectionStateChanged(bool isConnected, string? deviceName, ConnectionChangeReason reason)
    {
        MainThread.BeginInvokeOnMainThread(() =>
        {
            ConnectionStateChanged?.Invoke(this, new ConnectionStateChangedEventArgs(isConnected, deviceName, reason));
        });
    }

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

            static bool IsStandardBase(string uuid)
                => uuid.EndsWith("-0000-1000-8000-00805f9b34fb", StringComparison.OrdinalIgnoreCase);

            static bool IsExcludedService(string uuid)
            {
                var u = uuid.ToLowerInvariant();
                return u == "00001800-0000-1000-8000-00805f9b34fb"
                    || u == "00001801-0000-1000-8000-00805f9b34fb";
            }

            var knownServicePref = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                "0000ffe0-0000-1000-8000-00805f9b34fb",
                "0000abf0-0000-1000-8000-00805f9b34fb",
                "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
            };

            var knownCharPref = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
                "0000ffe1-0000-1000-8000-00805f9b34fb",
                "beb5483e-36e1-4688-b7f5-ea07361b26a8"
            };

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
                    _logger.LogDebug($"BLE: 排除系统服务可写特征 {ch.Uuid} @ {ch.Service.Uuid}");
                    continue;
                }

                var score = 0;
                if (props.HasFlag(CharacteristicProperties.WriteWithoutResponse)) score += 120;
                if (props.HasFlag(CharacteristicProperties.Write)) score += 80;

                if (!IsStandardBase(ch.Service.Uuid)) score += 60;
                if (!IsStandardBase(ch.Uuid)) score += 20;

                if (knownServicePref.Contains(ch.Service.Uuid)) score += 100;
                if (knownCharPref.Contains(ch.Uuid)) score += 100;

                var chLower = ch.Uuid.ToLowerInvariant();
                if (chLower.StartsWith("00002b") && IsStandardBase(ch.Uuid)) score -= 200;

                candidates.Add((ch, score));
            }

            if (candidates.Count == 0)
            {
                _logger.LogWarning("BLE: 未找到任何可写特征值!");
                return;
            }

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
                var header = X4IMProtocol.CreateHeader((uint)data.Length, bookId, 0, X4IMProtocol.FLAG_TYPE_TXT);
                _logger.LogInformation($"BLE: 发送 TXT bookId=\"{bookId}\", size={data.Length} 字节");

                // 按原应用行为：数据传完后再单独发送 EOF
                var sent = await SendFrameAsync(header, data, appendEof: false);
                if (!sent)
                {
                    return false;
                }

                await Task.Delay(50);
                await SendEofAsync();
                _logger.LogInformation($"BLE: TXT 传输完成，已发送 EOF 标记");
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
            await _connectedPeripheral
                .WriteCharacteristic(_writeServiceUuid, _writeCharacteristicUuid, X4IMProtocol.EOF_MARKER)
                .FirstOrDefaultAsync();

            _logger.LogInformation($"BLE: EOF 发送完成 ({X4IMProtocol.EOF_MARKER.Length} 字节)");
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 发送 EOF 失败 - {ex.Message}");
            return false;
        }
    }

    public async Task<bool> SendImageToDeviceAsync(byte[] imageData, string fileName = "page_0.bmp", ushort flags = X4IMProtocol.FLAG_TYPE_BMP, bool sendShowPage = true, byte pageIndex = 0)
    {
        // 所有平台禁用图片发送，仅发送文字
        _logger.LogInformation("BLE: 图片发送已禁用，仅支持文字传输");
        await Task.CompletedTask;
        return false;
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

        const int HEADER_SIZE = 32;
        const int MTU = 512;
        const int FIRST_CHUNK_DATA_SIZE = MTU - HEADER_SIZE; // 480 字节

        _logger.LogInformation($"BLE: X4IM v2 帧传输开始 (header[5]=0x{header[5]:X2}, payload={payload.Length}B, appendEof={appendEof})");

        // ========== 策略与 main.js 对齐 ==========
        // 第一个包：帧头(32) + 部分数据(480) = 512 字节
        // 后续包：纯数据(最多 512 字节)
        // 最后：可选 EOF 标记

        try
        {
            // 第一个包：帧头 + 部分数据
            int firstDataSize = Math.Min(FIRST_CHUNK_DATA_SIZE, payload.Length);
            var firstPacket = new byte[HEADER_SIZE + firstDataSize];
            Array.Copy(header, 0, firstPacket, 0, HEADER_SIZE);
            Array.Copy(payload, 0, firstPacket, HEADER_SIZE, firstDataSize);

            using (var firstMs = new MemoryStream(firstPacket))
            {
                await _connectedPeripheral
                    .WriteCharacteristicBlob(_writeServiceUuid, _writeCharacteristicUuid, firstMs)
                    .LastOrDefaultAsync();
            }
            _logger.LogInformation($"BLE: 已发送第一包 (32B 帧头 + {firstDataSize}B 数据 = {firstPacket.Length}B)");

            // 后续包：纯数据（每包最多 MTU 字节）
            int offset = firstDataSize;
            int chunkNum = 1;

            while (offset < payload.Length)
            {
                int remainingSize = payload.Length - offset;
                int chunkSize = Math.Min(MTU, remainingSize);
                var chunk = new byte[chunkSize];
                Array.Copy(payload, offset, chunk, 0, chunkSize);

                using (var chunkMs = new MemoryStream(chunk))
                {
                    await _connectedPeripheral
                        .WriteCharacteristicBlob(_writeServiceUuid, _writeCharacteristicUuid, chunkMs)
                        .LastOrDefaultAsync();
                }

                offset += chunkSize;
                chunkNum++;

                if (chunkNum % 5 == 0 || offset >= payload.Length)
                {
                    var percent = (int)((offset * 100) / payload.Length);
                    _logger.LogDebug($"BLE: 数据传输进度 {offset}/{payload.Length} 字节 ({percent}%)");
                }

                await Task.Delay(10); // 节流
            }

            _logger.LogInformation($"BLE: 数据传输完成，共 {chunkNum} 个包，{payload.Length} 字节");

            // 可选 EOF 标记
            if (appendEof)
            {
                await Task.Delay(50); // 短暂延迟确保数据被处理
                
                using (var eofMs = new MemoryStream(X4IMProtocol.EOF_MARKER))
                {
                    await _connectedPeripheral
                        .WriteCharacteristicBlob(_writeServiceUuid, _writeCharacteristicUuid, eofMs)
                        .LastOrDefaultAsync();
                }
                _logger.LogInformation($"BLE: 已发送 EOF 标记 ({X4IMProtocol.EOF_MARKER.Length}B)，触发 ESP32 处理");
            }

            _logger.LogInformation($"BLE: ✅ 帧传输完成 (总 {HEADER_SIZE + payload.Length + (appendEof ? X4IMProtocol.EOF_MARKER.Length : 0)} 字节)");
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError($"BLE: 帧传输失败 - {ex.Message}");
            return false;
        }
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

        _scanSubscription = _bleManager
            .Scan()
            .Subscribe(
                scanResult =>
                {
                    var peripheral = scanResult.Peripheral;
                    var deviceId = peripheral.Uuid;

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

#if IOS
    /// <summary>
    /// 启动 iOS 后台任务（iOS 专用）
    /// 对应 Android 前台服务
    /// </summary>
    private void StartIosBackgroundTask()
    {
        try
        {
            // 如果已有后台任务在运行，先结束它
            if (_bgTaskId != 0)
            {
                UIApplication.SharedApplication.EndBackgroundTask(_bgTaskId);
                _bgTaskId = 0;
            }

            // 启动新的后台任务
            _bgTaskId = UIApplication.SharedApplication.BeginBackgroundTask("BLEConnection", () =>
            {
                // 系统即将结束后台任务时的回调
                if (_bgTaskId != 0)
                {
                    UIApplication.SharedApplication.EndBackgroundTask(_bgTaskId);
                    _bgTaskId = 0;
                }
            });

            if (_bgTaskId != 0)
            {
                _logger.LogInformation("BLE: iOS 后台任务已启动");
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning($"BLE: 启动后台任务失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 停止 iOS 后台任务（iOS 专用）
    /// 对应 Android 前台服务
    /// </summary>
    private void StopIosBackgroundTask()
    {
        try
        {
            if (_bgTaskId != 0)
            {
                UIApplication.SharedApplication.EndBackgroundTask(_bgTaskId);
                _bgTaskId = 0;
                _logger.LogInformation("BLE: iOS 后台任务已停止");
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning($"BLE: 停止后台任务失败 - {ex.Message}");
        }
    }
#endif

#if ANDROID
    /// <summary>
    /// 启动 BLE 前台服务（Android 专用）
    /// 对应 iOS 的 BeginBackgroundTask
    /// </summary>
    private void StartBleForegroundService()
    {
        try
        {
            var context = Platform.AppContext;
            if (context != null)
            {
                BleForegroundService.StartService(context);
                _logger.LogInformation("BLE: Android 前台服务已启动");
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning($"BLE: 启动前台服务失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 停止 BLE 前台服务（Android 专用）
    /// 对应 iOS 的 EndBackgroundTask
    /// </summary>
    private void StopBleForegroundService()
    {
        try
        {
            var context = Platform.AppContext;
            if (context != null)
            {
                BleForegroundService.StopService(context);
                _logger.LogInformation("BLE: Android 前台服务已停止");
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning($"BLE: 停止前台服务失败 - {ex.Message}");
        }
    }
#endif
}
