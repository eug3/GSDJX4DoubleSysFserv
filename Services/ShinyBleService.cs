using Shiny.BluetoothLE;
using System.Collections.ObjectModel;
using Shiny;
using System.Reactive.Linq;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 基于 Shiny.NET 的蓝牙服务 - 支持后台持久化
/// </summary>
public class ShinyBleService : IBleService
{
    private readonly IBleManager _bleManager;
    private readonly IStorageService _storageService;
    private const string SavedMacKey = "Ble_SavedMacAddress";

    private IPeripheral? _connectedPeripheral;
    private ObservableCollection<BleDeviceInfo>? _scannedDevices;
    private readonly Dictionary<string, IPeripheral> _discoveredPeripherals = new();
    private TaskCompletionSource<ObservableCollection<BleDeviceInfo>>? _scanTcs;
    private IDisposable? _scanSubscription;

    public bool IsConnected { get; private set; }
    public string? ConnectedDeviceName { get; private set; }

    public ShinyBleService(IBleManager bleManager, IStorageService storageService)
    {
        _bleManager = bleManager;
        _storageService = storageService;
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
                    .Timeout(TimeSpan.FromSeconds(10))
                    .FirstAsync();
                
                peripheral.Connect(new ConnectionConfig { AutoConnect = true });
                await connectTask;
                
                IsConnected = true;
                ConnectedDeviceName = peripheral.Name ?? "未知设备";
                System.Diagnostics.Debug.WriteLine($"BLE: 已连接到 {ConnectedDeviceName}");
                
                // 保存 MAC 地址
                await SaveMacAddress(deviceId);
                
                return true;
            }
            else
            {
                System.Diagnostics.Debug.WriteLine($"BLE: 未找到设备 {deviceId}");
                return false;
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 连接错误 - {ex.Message}");
            return false;
        }
    }

    public void Disconnect()
    {
        if (_connectedPeripheral != null)
        {
            _connectedPeripheral.CancelConnection();
            _connectedPeripheral = null;
        }
        IsConnected = false;
        ConnectedDeviceName = null;
        System.Diagnostics.Debug.WriteLine("BLE: 已断开连接");
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
