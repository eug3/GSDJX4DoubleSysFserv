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

    /// <summary>
    /// 按键事件委托
    /// </summary>
    event EventHandler<ButtonEventArgs>? ButtonPressed;

    /// <summary>
    /// 连接状态变化事件
    /// </summary>
    event EventHandler<ConnectionStateChangedEventArgs>? ConnectionStateChanged;
}

/// <summary>
/// 连接状态变化事件参数
/// </summary>
public class ConnectionStateChangedEventArgs : EventArgs
{
    /// <summary>
    /// 是否已连接
    /// </summary>
    public bool IsConnected { get; set; }

    /// <summary>
    /// 设备名称
    /// </summary>
    public string? DeviceName { get; set; }

    /// <summary>
    /// 状态变化原因
    /// </summary>
    public ConnectionChangeReason Reason { get; set; }

    public ConnectionStateChangedEventArgs() { }

    public ConnectionStateChangedEventArgs(bool isConnected, string? deviceName, ConnectionChangeReason reason)
    {
        IsConnected = isConnected;
        DeviceName = deviceName;
        Reason = reason;
    }
}

/// <summary>
/// 连接状态变化原因
/// </summary>
public enum ConnectionChangeReason
{
    /// <summary>
    /// 用户主动连接
    /// </summary>
    UserInitiated,

    /// <summary>
    /// 自动重连
    /// </summary>
    AutoReconnect,

    /// <summary>
    /// 用户主动断开
    /// </summary>
    UserDisconnected,

    /// <summary>
    /// 设备断开
    /// </summary>
    DeviceDisconnected,

    /// <summary>
    /// 删除已保存设备
    /// </summary>
    DeviceDeleted
}

/// <summary>
/// 按键事件参数
/// </summary>
public class ButtonEventArgs : EventArgs
{
    /// <summary>
    /// 按键类型: LEFT, RIGHT, UP, DOWN, OK
    /// </summary>
    public string Key { get; set; } = string.Empty;

    public ButtonEventArgs() { }

    public ButtonEventArgs(string key)
    {
        Key = key;
    }
}
