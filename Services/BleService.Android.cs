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

    private TaskCompletionSource<bool>? _connectTcs;

    public async Task<bool> ConnectAsync(string deviceId, string macAddress)
    {
        try
        {
            if (_adapter == null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 适配器未初始化");
                return false;
            }

            // 优先使用已发现的设备
            BluetoothDevice? device = null;
            if (_discoveredDevices.TryGetValue(deviceId, out var discovered))
            {
                device = discovered;
                System.Diagnostics.Debug.WriteLine($"BLE: 使用已发现的设备 - {device.Name}");
            }
            else
            {
                device = _adapter.GetRemoteDevice(macAddress);
                System.Diagnostics.Debug.WriteLine($"BLE: 使用 MAC 地址创建设备 - {macAddress}");
            }
            
            if (device == null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 设备为空");
                return false;
            }

            _connectedDevice = device;
            _connectTcs = new TaskCompletionSource<bool>();
            
            System.Diagnostics.Debug.WriteLine($"BLE: 开始连接到 {device.Name ?? macAddress}...");
            
            // 使用 autoConnect=false 立即连接，不等待后台
            _gatt = device.ConnectGatt(
                Android.App.Application.Context, 
                false,  // autoConnect: false 立即连接
                _gattCallback,
                BluetoothTransports.Le
            );

            if (_gatt == null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: ConnectGatt 返回 null");
                return false;
            }

            // 等待连接结果，最多 15 秒
            var timeoutTask = Task.Delay(15000);
            var completedTask = await Task.WhenAny(_connectTcs.Task, timeoutTask);
            
            if (completedTask == timeoutTask)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 连接超时");
                _gatt?.Disconnect();
                _gatt?.Close();
                _gatt = null;
                return false;
            }

            var result = await _connectTcs.Task;
            if (result)
            {
                await SaveMacAddress(macAddress);
                System.Diagnostics.Debug.WriteLine($"BLE: 连接成功 - {ConnectedDeviceName}");
            }
            else
            {
                System.Diagnostics.Debug.WriteLine("BLE: 连接失败");
            }
            
            return result;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 连接错误 - {ex.Message}");
            _gatt?.Disconnect();
            _gatt?.Close();
            _gatt = null;
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
        if (_adapter == null)
        {
            System.Diagnostics.Debug.WriteLine("BLE: BluetoothAdapter 未初始化");
            throw new InvalidOperationException("蓝牙适配器未初始化，请检查设备是否支持蓝牙");
        }

        if (!_adapter.IsEnabled)
        {
            System.Diagnostics.Debug.WriteLine("BLE: 蓝牙未启用");
            throw new InvalidOperationException("蓝牙未启用，请先开启蓝牙");
        }

        if (_scanner == null)
        {
            System.Diagnostics.Debug.WriteLine("BLE: BluetoothLeScanner 未初始化");
            throw new InvalidOperationException("蓝牙扫描器不可用");
        }

        _scannedDevices = new ObservableCollection<BleDeviceInfo>();
        _discoveredDevices.Clear();
        _scanTcs = new TaskCompletionSource<ObservableCollection<BleDeviceInfo>>();

        var scanCallback = new BleScanCallback(this);
        var settings = new ScanSettings.Builder()
            .SetScanMode(Android.Bluetooth.LE.ScanMode.LowLatency)
            .Build();

        System.Diagnostics.Debug.WriteLine("BLE: 开始扫描... (低延迟模式，扫描时长 8 秒)");
        
        try
        {
            _scanner.StartScan(null, settings, scanCallback);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 启动扫描失败 - {ex.Message}");
            throw new InvalidOperationException($"启动蓝牙扫描失败: {ex.Message}。可能缺少权限或蓝牙状态异常", ex);
        }

        // 扫描 8 秒后停止
        _ = Task.Run(async () =>
        {
            await Task.Delay(8000);
            try
            {
                _scanner?.StopScan(scanCallback);
                System.Diagnostics.Debug.WriteLine($"BLE: 扫描结束，发现 {_scannedDevices?.Count ?? 0} 个设备");
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"BLE: 停止扫描时出错 - {ex.Message}");
            }
            _scanTcs?.TrySetResult(_scannedDevices ?? new ObservableCollection<BleDeviceInfo>());
        });

        return await _scanTcs.Task;
    }

    private void OnDeviceFound(BluetoothDevice device, int rssi)
    {
        var deviceId = device.Address ?? "";
        var deviceName = device.Name;

        // 过滤条件：
        // 1. 必须有地址
        // 2. 有名字或者信号强度较强（可能是 ESP32 但名字未广播）
        if (string.IsNullOrEmpty(deviceId))
        {
            return;
        }

        // 过滤掉没有名字的设备（只显示有蓝牙名称的设备）
        if (string.IsNullOrWhiteSpace(deviceName))
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 跳过无名设备 - ({deviceId}) RSSI: {rssi}");
            return;
        }

        System.Diagnostics.Debug.WriteLine($"BLE: 发现设备 - {deviceName} ({deviceId}) RSSI: {rssi}");

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
            
            // 通知连接成功
            _connectTcs?.TrySetResult(true);
            
            // 发现服务
            gatt?.DiscoverServices();
        }
        else if (newState == ProfileState.Disconnected)
        {
            var wasConnected = IsConnected;
            IsConnected = false;
            ConnectedDeviceName = null;
            System.Diagnostics.Debug.WriteLine("BLE: 连接已断开");

            // 如果是连接过程中断开，通知连接失败
            if (_connectTcs != null && !_connectTcs.Task.IsCompleted)
            {
                _connectTcs.TrySetResult(false);
            }

            // 后台自动重连（仅当之前已连接时）
            if (_shouldAutoReconnect && wasConnected && _connectedDevice != null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 尝试自动重连...");
                Task.Delay(1000).ContinueWith(_ =>
                {
                    _gatt = _connectedDevice.ConnectGatt(
                        Android.App.Application.Context,
                        true,  // autoConnect: 后台自动重连
                        _gattCallback,
                        BluetoothTransports.Le
                    );
                });
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
    /// 启动时尝试自动连接已保存的设备
    /// </summary>
    public async Task TryAutoConnectOnStartupAsync()
    {
        try
        {
            var macAddress = await GetSavedMacAddress();
            if (!string.IsNullOrEmpty(macAddress))
            {
                System.Diagnostics.Debug.WriteLine($"BLE: 启动时尝试自动连接到 {macAddress}");
                await ConnectAsync(macAddress, macAddress);
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 启动时自动连接失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 发送文本到设备（X4IM v2 协议）
    /// </summary>
    /// <param name="text">文本内容</param>
    /// <param name="chapter">章节号</param>
    /// <returns>是否发送成功</returns>
    public async Task<bool> SendTextToDeviceAsync(string text, int chapter = 0)
    {
        try
        {
            if (!IsConnected || _gatt == null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 设备未连接");
                return false;
            }

            // TODO: 实现 X4IM v2 协议的文本发送
            // 这里需要根据协议将文本转换为位图并分片传输
            System.Diagnostics.Debug.WriteLine($"BLE: 发送文本到设备 - 章节: {chapter}, 长度: {text.Length}");
            return true;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 发送文本失败 - {ex.Message}");
            return false;
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
