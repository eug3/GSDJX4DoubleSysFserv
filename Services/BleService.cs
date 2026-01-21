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

    /// <summary>
    /// 启动时尝试自动连接已保存的设备
    /// </summary>
    Task TryAutoConnectOnStartupAsync();

    /// <summary>
    /// 发送文本到设备（X4IM v2 协议）
    /// </summary>
    /// <param name="text">文本内容</param>
    /// <param name="chapter">章节号</param>
    /// <returns>是否发送成功</returns>
    Task<bool> SendTextToDeviceAsync(string text, int chapter = 0);
}
