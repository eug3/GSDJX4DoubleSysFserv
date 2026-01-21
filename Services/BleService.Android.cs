#if ANDROID
using Android.Bluetooth;
using Android.Bluetooth.LE;
using Android.Content;
using System.Collections.ObjectModel;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 蓝牙服务 Android 实现
/// </summary>
public class BleServiceAndroid : IBleService
{
    private const string SavedMacKey = "Ble_SavedMacAddress";
    private readonly IStorageService _storageService;
    private BluetoothAdapter? _adapter;
    private BluetoothDevice? _connectedDevice;
    private BluetoothGatt? _gatt;

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

            var device = _adapter.GetRemoteDevice(macAddress);
            if (device == null) return false;

            _connectedDevice = device;
            _gatt = device.ConnectGatt(Android.App.Application.Context, false, _gattCallback);

            IsConnected = true;
            ConnectedDeviceName = device.Name ?? "Unknown Device";
            await SaveMacAddress(macAddress);

            return true;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE Connect Error: {ex.Message}");
            return false;
        }
    }

    public void Disconnect()
    {
        _gatt?.Close();
        _gatt = null;
        _connectedDevice = null;
        IsConnected = false;
        ConnectedDeviceName = null;
    }

    public async Task<ObservableCollection<BleDeviceInfo>> ScanDevicesAsync()
    {
        var devices = new ObservableCollection<BleDeviceInfo>();

        if (_adapter == null || !_adapter.IsEnabled)
            return devices;

        var pairedDevices = _adapter.BondedDevices;
        foreach (var device in pairedDevices)
        {
            devices.Add(new BleDeviceInfo
            {
                Id = device.Address,
                Name = device.Name ?? "Unknown Device",
                MacAddress = device.Address
            });
        }

        return devices;
    }

    private readonly BluetoothGattCallback _gattCallback = new();
}
#endif
