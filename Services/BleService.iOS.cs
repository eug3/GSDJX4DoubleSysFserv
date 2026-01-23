#if IOS || MACCATALYST
using CoreBluetooth;
using Foundation;
using System.Collections.ObjectModel;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 蓝牙服务 iOS/MacCatalyst 实现 - 支持后台模式
/// </summary>
public class BleServiceApple : IBleService
{
    private const string SavedMacKey = "Ble_SavedMacAddress";
    private const string CentralManagerRestoreIdentifier = "com.guaishoudejia.gsdjx4doublesysfserv.ble";
    private readonly IStorageService _storageService;
    private CBCentralManager? _centralManager;
    private CBPeripheral? _connectedPeripheral;
    private readonly Dictionary<string, CBPeripheral> _discoveredPeripherals = new();
    private TaskCompletionSource<ObservableCollection<BleDeviceInfo>>? _scanTcs;
    private ObservableCollection<BleDeviceInfo>? _scannedDevices;
    private bool _shouldAutoReconnect = true;

#pragma warning disable CS0067
    public event EventHandler<ButtonEventArgs>? ButtonPressed;
    public event EventHandler<ConnectionStateChangedEventArgs>? ConnectionStateChanged;
#pragma warning restore CS0067

    public bool IsConnected { get; private set; }
    public string? ConnectedDeviceName { get; private set; }

    public BleServiceApple(IStorageService storageService)
    {
        _storageService = storageService;
        InitializeCentralManager();
    }

    private void InitializeCentralManager()
    {
        // 使用 RestoreIdentifier 启用后台状态恢复
        var options = new CBCentralInitOptions
        {
            RestoreIdentifier = CentralManagerRestoreIdentifier,
            ShowPowerAlert = true
        };
        _centralManager = new CBCentralManager(new CentralManagerDelegate(this), null, options);
        System.Diagnostics.Debug.WriteLine($"BLE: CentralManager 初始化完成，RestoreIdentifier: {CentralManagerRestoreIdentifier}");
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

    public Task<bool> ConnectAsync(string deviceId, string macAddress)
    {
        // 查找已发现的外设
        if (_discoveredPeripherals.TryGetValue(deviceId, out var peripheral))
        {
            _connectedPeripheral = peripheral;
            _centralManager?.ConnectPeripheral(peripheral, new PeripheralConnectionOptions());
            return Task.FromResult(true);
        }
        return Task.FromResult(false);
    }

    public void Disconnect()
    {
        if (_connectedPeripheral != null)
        {
            _centralManager?.CancelPeripheralConnection(_connectedPeripheral);
            _connectedPeripheral = null;
        }
        IsConnected = false;
        ConnectedDeviceName = null;
    }

    public async Task<ObservableCollection<BleDeviceInfo>> ScanDevicesAsync()
    {
           if (_centralManager == null || _centralManager.State != CBManagerState.PoweredOn)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 蓝牙状态不可用: {_centralManager?.State}");
            return new ObservableCollection<BleDeviceInfo>();
        }

        _scannedDevices = new ObservableCollection<BleDeviceInfo>();
        _discoveredPeripherals.Clear();
        _scanTcs = new TaskCompletionSource<ObservableCollection<BleDeviceInfo>>();

        System.Diagnostics.Debug.WriteLine("BLE: 开始扫描...");
        _centralManager.ScanForPeripherals((CBUUID[]?)null);

        // 扫描 5 秒后停止
        _ = Task.Run(async () =>
        {
            await Task.Delay(5000);
            _centralManager?.StopScan();
            System.Diagnostics.Debug.WriteLine($"BLE: 扫描结束，发现 {_scannedDevices?.Count ?? 0} 个设备");
            _scanTcs?.TrySetResult(_scannedDevices ?? new ObservableCollection<BleDeviceInfo>());
        });

        return await _scanTcs.Task;
    }

    private void OnDiscoveredPeripheral(CBPeripheral peripheral, NSDictionary advertisementData, NSNumber rssi)
    {
        var deviceId = peripheral.Identifier.ToString();
        var deviceName = peripheral.Name ?? "未知设备";

        if (!_discoveredPeripherals.ContainsKey(deviceId))
        {
            _discoveredPeripherals[deviceId] = peripheral;
            
            var deviceInfo = new BleDeviceInfo
            {
                Id = deviceId,
                Name = $"{deviceName} (RSSI: {rssi})",
                MacAddress = deviceId // iOS 不提供 MAC 地址，使用 UUID
            };

            MainThread.BeginInvokeOnMainThread(() =>
            {
                _scannedDevices?.Add(deviceInfo);
                System.Diagnostics.Debug.WriteLine($"BLE: 发现设备 - {deviceName} ({deviceId})");
            });
        }
    }

    private void OnConnectedPeripheral(CBPeripheral peripheral)
    {
        IsConnected = true;
        ConnectedDeviceName = peripheral.Name ?? "未知设备";
        System.Diagnostics.Debug.WriteLine($"BLE: 已连接到 {ConnectedDeviceName}");
        
        // 触发连接状态变化事件
        MainThread.BeginInvokeOnMainThread(() =>
        {
            ConnectionStateChanged?.Invoke(this, new ConnectionStateChangedEventArgs(true, ConnectedDeviceName, ConnectionChangeReason.UserInitiated));
        });
    }

    private void OnDisconnectedPeripheral(CBPeripheral peripheral, NSError? error)
    {
        var previousDeviceName = ConnectedDeviceName;
        IsConnected = false;
        ConnectedDeviceName = null;
        System.Diagnostics.Debug.WriteLine($"BLE: 已断开连接 - {error?.LocalizedDescription}");
        
        // 触发连接状态变化事件
        MainThread.BeginInvokeOnMainThread(() =>
        {
            ConnectionStateChanged?.Invoke(this, new ConnectionStateChangedEventArgs(false, previousDeviceName, ConnectionChangeReason.DeviceDisconnected));
        });
        
        // 后台自动重连
        if (_shouldAutoReconnect && _connectedPeripheral != null)
        {
            System.Diagnostics.Debug.WriteLine("BLE: 尝试自动重连...");
            _centralManager?.ConnectPeripheral(_connectedPeripheral, new PeripheralConnectionOptions
            {
                NotifyOnConnection = true,
                NotifyOnDisconnection = true,
                NotifyOnNotification = true
            });
        }
    }

    private void OnWillRestoreState(NSDictionary dict)
    {
        System.Diagnostics.Debug.WriteLine("BLE: 后台恢复状态...");
        
        // 恢复已连接的外设
        if (dict.ContainsKey(CBCentralManager.RestoredStatePeripheralsKey))
        {
            var peripherals = dict[CBCentralManager.RestoredStatePeripheralsKey] as NSArray;
            if (peripherals != null && peripherals.Count > 0)
            {
                for (nuint i = 0; i < peripherals.Count; i++)
                {
                    var peripheral = peripherals.GetItem<CBPeripheral>(i);
                    if (peripheral != null)
                    {
                        _connectedPeripheral = peripheral;
                        _discoveredPeripherals[peripheral.Identifier.ToString()] = peripheral;
                        System.Diagnostics.Debug.WriteLine($"BLE: 恢复外设 - {peripheral.Name}");
                        
                        // 重新连接
                        _centralManager?.ConnectPeripheral(peripheral, new PeripheralConnectionOptions
                        {
                            NotifyOnConnection = true,
                            NotifyOnDisconnection = true,
                            NotifyOnNotification = true
                        });
                    }
                }
            }
        }
    }

    private void OnStateUpdated(CBManagerState state)
    {
        System.Diagnostics.Debug.WriteLine($"BLE: 状态更新 - {state}");
    }

    // CBCentralManagerDelegate 实现
    private class CentralManagerDelegate : CBCentralManagerDelegate
    {
        private readonly BleServiceApple _service;

        public CentralManagerDelegate(BleServiceApple service)
        {
            _service = service;
        }

        public override void UpdatedState(CBCentralManager central)
        {
            _service.OnStateUpdated(central.State);
        }

        public override void DiscoveredPeripheral(CBCentralManager central, CBPeripheral peripheral, NSDictionary advertisementData, NSNumber rssi)
        {
            _service.OnDiscoveredPeripheral(peripheral, advertisementData, rssi);
        }

        public override void ConnectedPeripheral(CBCentralManager central, CBPeripheral peripheral)
        {
            _service.OnConnectedPeripheral(peripheral);
        }

        public override void DisconnectedPeripheral(CBCentralManager central, CBPeripheral peripheral, NSError? error)
        {
            _service.OnDisconnectedPeripheral(peripheral, error);
        }

        public override void WillRestoreState(CBCentralManager central, NSDictionary dict)
        {
            _service.OnWillRestoreState(dict);
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
                System.Diagnostics.Debug.WriteLine("BLE iOS: 没有保存的设备");
                return;
            }

            System.Diagnostics.Debug.WriteLine($"BLE iOS: 启动时尝试自动连接设备 {savedDeviceId}");

            // 等待蓝牙就绪
            while (_centralManager?.State != CBManagerState.PoweredOn)
            {
                await Task.Delay(100);
                System.Diagnostics.Debug.WriteLine($"BLE iOS: 等待蓝牙就绪... 状态: {_centralManager?.State}");
            }

            // 先尝试直接从已发现的设备中查找
            if (_discoveredPeripherals.TryGetValue(savedDeviceId, out var peripheral))
            {
                System.Diagnostics.Debug.WriteLine($"BLE iOS: 在已发现设备中找到目标设备");
                _connectedPeripheral = peripheral;
                _centralManager?.ConnectPeripheral(peripheral, new PeripheralConnectionOptions
                {
                    NotifyOnConnection = true,
                    NotifyOnDisconnection = true,
                    NotifyOnNotification = true
                });
                return;
            }

            // 如果没找到，扫描查找
            System.Diagnostics.Debug.WriteLine("BLE iOS: 未在缓存中找到设备，开始扫描...");
            
            var devices = await ScanDevicesAsync();
            var targetDevice = devices.FirstOrDefault(d => d.Id == savedDeviceId);

            if (targetDevice != null)
            {
                System.Diagnostics.Debug.WriteLine($"BLE iOS: 在扫描结果中找到目标设备 {targetDevice.Name}");
                await ConnectAsync(savedDeviceId, savedDeviceId);
            }
            else
            {
                System.Diagnostics.Debug.WriteLine($"BLE iOS: 未找到保存的设备 {savedDeviceId}");
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE iOS: 启动时自动连接失败 - {ex.Message}");
        }
    }

    public Task<bool> SendTextToDeviceAsync(string text, int chapter = 0)
    {
        System.Diagnostics.Debug.WriteLine("BLE iOS: SendTextToDeviceAsync not implemented");
        return Task.FromResult(false);
    }
}
#endif
