#if ANDROID
using Android.Bluetooth;
using Android.Bluetooth.LE;
using Android.Content;
using Android.OS;
using System.Collections.ObjectModel;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 蓝牙服务 Android 实现 - 支持后台模式
/// </summary>
public class BleServiceAndroid : IBleService
{
    private const string SavedMacKey = "Ble_SavedMacAddress";
    private readonly IStorageService _storageService;
    private BluetoothAdapter? _adapter;
    private BluetoothLeScanner? _scanner;
    private BluetoothDevice? _connectedDevice;
    private BluetoothGatt? _gatt;
    private readonly Dictionary<string, BluetoothDevice> _discoveredDevices = new();
    private TaskCompletionSource<ObservableCollection<BleDeviceInfo>>? _scanTcs;
    private ObservableCollection<BleDeviceInfo>? _scannedDevices;
    private bool _shouldAutoReconnect = true;
    private GattCallback? _gattCallback;

    public bool IsConnected { get; private set; }
    public string? ConnectedDeviceName { get; private set; }

    public BleServiceAndroid(IStorageService storageService)
    {
        _storageService = storageService;
        InitializeAdapter();
    }

    private void InitializeAdapter()
    {
        var manager = (BluetoothManager?)Android.App.Application.Context.GetSystemService(Context.BluetoothService);
        _adapter = manager?.Adapter;
        _scanner = _adapter?.BluetoothLeScanner;
        _gattCallback = new GattCallback(this);
        System.Diagnostics.Debug.WriteLine($"BLE: Android 适配器初始化完成，状态: {_adapter?.IsEnabled}");
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
            if (_adapter == null) return false;

            // 优先使用已发现的设备
            BluetoothDevice? device = null;
            if (_discoveredDevices.TryGetValue(deviceId, out var discovered))
            {
                device = discovered;
            }
            else
            {
                device = _adapter.GetRemoteDevice(macAddress);
            }
            
            if (device == null) return false;

            _connectedDevice = device;
            
            // 使用 autoConnect=true 支持后台自动重连
            _gatt = device.ConnectGatt(
                Android.App.Application.Context, 
                true,  // autoConnect: 后台自动重连
                _gattCallback,
                BluetoothTransports.Le
            );

            await SaveMacAddress(macAddress);
            System.Diagnostics.Debug.WriteLine($"BLE: 正在连接到 {device.Name} ({macAddress})");

            return true;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 连接错误 - {ex.Message}");
            return false;
        }
    }

    public void Disconnect()
    {
        _shouldAutoReconnect = false;
        _gatt?.Disconnect();
        _gatt?.Close();
        _gatt = null;
        _connectedDevice = null;
        IsConnected = false;
        ConnectedDeviceName = null;
        System.Diagnostics.Debug.WriteLine("BLE: 已断开连接");
    }

    public async Task<ObservableCollection<BleDeviceInfo>> ScanDevicesAsync()
    {
        if (_adapter == null || !_adapter.IsEnabled || _scanner == null)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 蓝牙不可用，状态: {_adapter?.IsEnabled}");
            return new ObservableCollection<BleDeviceInfo>();
        }

        _scannedDevices = new ObservableCollection<BleDeviceInfo>();
        _discoveredDevices.Clear();
        _scanTcs = new TaskCompletionSource<ObservableCollection<BleDeviceInfo>>();

        var scanCallback = new BleScanCallback(this);
        var settings = new ScanSettings.Builder()
            .SetScanMode(Android.Bluetooth.LE.ScanMode.LowLatency)
            .Build();

        System.Diagnostics.Debug.WriteLine("BLE: 开始扫描...");
        _scanner.StartScan(null, settings, scanCallback);

        // 扫描 5 秒后停止
        _ = Task.Run(async () =>
        {
            await Task.Delay(5000);
            _scanner?.StopScan(scanCallback);
            System.Diagnostics.Debug.WriteLine($"BLE: 扫描结束，发现 {_scannedDevices?.Count ?? 0} 个设备");
            _scanTcs?.TrySetResult(_scannedDevices ?? new ObservableCollection<BleDeviceInfo>());
        });

        return await _scanTcs.Task;
    }

    private void OnDeviceFound(BluetoothDevice device, int rssi)
    {
        var deviceId = device.Address ?? "";
        var deviceName = device.Name;

        // 过滤掉没有名字的设备
        if (string.IsNullOrWhiteSpace(deviceName) || string.IsNullOrEmpty(deviceId))
        {
            return;
        }

        if (!_discoveredDevices.ContainsKey(deviceId))
        {
            _discoveredDevices[deviceId] = device;

            var deviceInfo = new BleDeviceInfo
            {
                Id = deviceId,
                Name = $"{deviceName} (RSSI: {rssi})",
                MacAddress = deviceId
            };

            MainThread.BeginInvokeOnMainThread(() =>
            {
                _scannedDevices?.Add(deviceInfo);
                System.Diagnostics.Debug.WriteLine($"BLE: 发现设备 - {deviceName} ({deviceId})");
            });
        }
    }

    private void OnConnectionStateChanged(BluetoothGatt? gatt, GattStatus status, ProfileState newState)
    {
        System.Diagnostics.Debug.WriteLine($"BLE: 连接状态变化 - {newState}, Status: {status}");

        if (newState == ProfileState.Connected)
        {
            IsConnected = true;
            ConnectedDeviceName = gatt?.Device?.Name ?? "未知设备";
            _shouldAutoReconnect = true;
            System.Diagnostics.Debug.WriteLine($"BLE: 已连接到 {ConnectedDeviceName}");
            
            // 发现服务
            gatt?.DiscoverServices();
        }
        else if (newState == ProfileState.Disconnected)
        {
            IsConnected = false;
            ConnectedDeviceName = null;
            System.Diagnostics.Debug.WriteLine("BLE: 连接已断开");

            // 后台自动重连
            if (_shouldAutoReconnect && _connectedDevice != null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 尝试自动重连...");
                _gatt = _connectedDevice.ConnectGatt(
                    Android.App.Application.Context,
                    true,
                    _gattCallback,
                    BluetoothTransports.Le
                );
            }
        }
    }

    private void OnServicesDiscovered(BluetoothGatt? gatt, GattStatus status)
    {
        System.Diagnostics.Debug.WriteLine($"BLE: 服务发现完成 - {status}");
        if (status == GattStatus.Success && gatt != null)
        {
            foreach (var service in gatt.Services ?? Enumerable.Empty<BluetoothGattService>())
            {
                System.Diagnostics.Debug.WriteLine($"BLE: 发现服务 - {service.Uuid}");
            }
        }
    }

    /// <summary>
    /// BLE 扫描回调
    /// </summary>
    private class BleScanCallback : ScanCallback
    {
        private readonly BleServiceAndroid _service;

        public BleScanCallback(BleServiceAndroid service)
        {
            _service = service;
        }

        public override void OnScanResult(ScanCallbackType callbackType, ScanResult? result)
        {
            base.OnScanResult(callbackType, result);
            if (result?.Device != null)
            {
                _service.OnDeviceFound(result.Device, result.Rssi);
            }
        }

        public override void OnScanFailed(ScanFailure errorCode)
        {
            base.OnScanFailed(errorCode);
            System.Diagnostics.Debug.WriteLine($"BLE: 扫描失败 - {errorCode}");
        }
    }

    /// <summary>
    /// 蓝牙 GATT 回调实现
    /// </summary>
    private class GattCallback : BluetoothGattCallback
    {
        private readonly BleServiceAndroid _service;

        public GattCallback(BleServiceAndroid service)
        {
            _service = service;
        }

        public override void OnConnectionStateChange(BluetoothGatt? gatt, GattStatus status, ProfileState newState)
        {
            base.OnConnectionStateChange(gatt, status, newState);
            _service.OnConnectionStateChanged(gatt, status, newState);
        }

        public override void OnServicesDiscovered(BluetoothGatt? gatt, GattStatus status)
        {
            base.OnServicesDiscovered(gatt, status);
            _service.OnServicesDiscovered(gatt, status);
        }
    }
}
#endif
