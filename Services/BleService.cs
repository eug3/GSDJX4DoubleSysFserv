using System.Collections.ObjectModel;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 蓝牙设备信息
/// </summary>
public class BleDeviceInfo
{
    public string Id { get; set; } = string.Empty;
    public string Name { get; set; } = string.Empty;
    public string MacAddress { get; set; } = string.Empty;
}

/// <summary>
/// 蓝牙服务接口
/// </summary>
public interface IBleService
{
    bool IsConnected { get; }
    string? ConnectedDeviceName { get; }
    Task<bool> ConnectAsync(string deviceId, string macAddress);
    void Disconnect();
    Task<ObservableCollection<BleDeviceInfo>> ScanDevicesAsync();
    Task<string?> GetSavedMacAddress();
    Task SaveMacAddress(string macAddress);
    Task DeleteSavedMacAddress();
}
