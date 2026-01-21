using Shiny.BluetoothLE;
using Microsoft.Extensions.Logging;
using Shiny;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// Shiny Bluetooth Delegate - 实现后台持久化蓝牙事件处理
/// 即使应用被系统杀死，特定的蓝牙事件也能重新触发应用逻辑
/// </summary>
public class ShinyBleDelegate : BleDelegate
{
    private readonly ILogger<ShinyBleDelegate> _logger;

    public ShinyBleDelegate(ILogger<ShinyBleDelegate> logger)
    {
        _logger = logger;
    }

    /// <summary>
    /// 当蓝牙适配器状态更改时调用
    /// </summary>
    public override Task OnAdapterStateChanged(AccessState state)
    {
        _logger.LogInformation($"BLE 状态更新: {state}");

        switch (state)
        {
            case AccessState.Available:
                _logger.LogInformation("BLE: 蓝牙已启用");
                break;
            case AccessState.Disabled:
                _logger.LogWarning("BLE: 蓝牙已关闭");
                break;
            case AccessState.Restricted:
                _logger.LogWarning("BLE: 蓝牙访问受限");
                break;
        }
        
        return Task.CompletedTask;
    }

    /// <summary>
    /// 当外围设备状态变化时调用（连接/断开）
    /// </summary>
    public override Task OnPeripheralStateChanged(IPeripheral peripheral)
    {
        _logger.LogInformation($"BLE: 设备状态变化 - {peripheral.Name} ({peripheral.Uuid}) - 状态: {peripheral.Status}");

        switch (peripheral.Status)
        {
            case ConnectionState.Connected:
                _logger.LogInformation($"BLE: 设备已连接 - {peripheral.Name}");
                break;
            case ConnectionState.Disconnected:
                _logger.LogInformation($"BLE: 设备已断开 - {peripheral.Name}");
                break;
            case ConnectionState.Connecting:
                _logger.LogInformation($"BLE: 正在连接设备 - {peripheral.Name}");
                break;
            case ConnectionState.Disconnecting:
                _logger.LogInformation($"BLE: 正在断开设备 - {peripheral.Name}");
                break;
        }

        return Task.CompletedTask;
    }
}
