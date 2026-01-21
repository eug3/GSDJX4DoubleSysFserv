#if IOS || MACCATALYST
using CoreBluetooth;
using Foundation;
using System.Collections.ObjectModel;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 蓝牙服务 iOS/MacCatalyst 实现
/// </summary>
public class BleServiceApple : IBleService
{
    private const string SavedMacKey = "Ble_SavedMacAddress";
    private readonly IStorageService _storageService;
    private CBCentralManager? _centralManager;
    private CBPeripheral? _connectedPeripheral;
    private readonly Dictionary<string, CBPeripheral> _discoveredPeripherals = new();
    private TaskCompletionSource<ObservableCollection<BleDeviceInfo>>? _scanTcs;
    private ObservableCollection<BleDeviceInfo>? _scannedDevices;

    public bool IsConnected { get; private set; }
    public string? ConnectedDeviceName { get; private set; }

    public BleServiceApple(IStorageService storageService)
    {
        _storageService = storageService;
        InitializeCentralManager();
    }

    private void InitializeCentralManager()
    {
        _centralManager = new CBCentralManager(new CentralManagerDelegate(this), null);
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
    }

    private void OnDisconnectedPeripheral(CBPeripheral peripheral, NSError? error)
    {
        IsConnected = false;
        ConnectedDeviceName = null;
        System.Diagnostics.Debug.WriteLine($"BLE: 已断开连接 - {error?.LocalizedDescription}");
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
    }
}
#endif
