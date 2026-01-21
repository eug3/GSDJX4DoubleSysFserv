#if IOS || MACCATALYST
using CoreBluetooth;
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

    public bool IsConnected { get; private set; }
    public string? ConnectedDeviceName { get; private set; }

    public BleServiceApple(IStorageService storageService)
    {
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

    public Task<bool> ConnectAsync(string deviceId, string macAddress)
    {
        // iOS BLE 需要完整的 CoreBluetooth 实现
        // 实际使用需要配置权限和完整的 CentralManager 流程
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

    public Task<ObservableCollection<BleDeviceInfo>> ScanDevicesAsync()
    {
        var devices = new ObservableCollection<BleDeviceInfo>();
        return Task.FromResult(devices);
    }
}
#endif
