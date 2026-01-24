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
    /// 手动发送 EOF 标记到设备
    /// </summary>
    /// <returns>是否发送成功</returns>
    Task<bool> SendEofAsync();

    /// <summary>
    /// 发送图片到设备（X4IM v2）
    /// </summary>
    /// <param name="imageData">图片二进制数据（PNG/BMP 等）</param>
    /// <param name="fileName">文件名，例如 page_0.png</param>
    /// <param name="flags">X4IM 文件类型标志，如 X4IMProtocol.FLAG_TYPE_PNG</param>
    /// <param name="sendShowPage">是否发送 SHOW_PAGE 命令触发显示</param>
    /// <param name="pageIndex">SHOW_PAGE 页面索引</param>
    Task<bool> SendImageToDeviceAsync(byte[] imageData, string fileName = "page_0.png", ushort flags = X4IMProtocol.FLAG_TYPE_PNG, bool sendShowPage = true, byte pageIndex = 0);

    /// <summary>
    /// 按键事件委托
    /// </summary>
    event EventHandler<ButtonEventArgs>? ButtonPressed;

    /// <summary>
    /// 连接状态变化事件
    /// </summary>
    event EventHandler<ConnectionStateChangedEventArgs>? ConnectionStateChanged;

    /// <summary>
    /// 处理来自设备的按键事件（自动翻页并获取内容发送到设备）
    /// 此方法由 UI 或后台调用，统一在 Service 层处理
    /// </summary>
    /// <param name="key">按键类型: LEFT, RIGHT, UP, DOWN, OK</param>
    Task ProcessButtonAsync(string key);

    /// <summary>
    /// 更新阅读上下文（URL 与 Cookie），并持久化到本地存储
    /// UI 可调用此方法进行参数同步，后台无需依赖 UI 也能工作
    /// </summary>
    /// <param name="url">当前阅读器页面 URL</param>
    /// <param name="cookie">当前页面 Cookie</param>
    Task UpdateReadingContextAsync(string url, string cookie);
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
