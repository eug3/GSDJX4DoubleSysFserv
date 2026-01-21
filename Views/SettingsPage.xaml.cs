using System.Collections.ObjectModel;
using GSDJX4DoubleSysFserv.Services;

namespace GSDJX4DoubleSysFserv.Views;

/// <summary>
/// 设置页面 - 蓝牙连接管理
/// </summary>
public partial class SettingsPage : ContentPage
{
    private readonly IBleService _bleService;
    private ObservableCollection<BleDeviceInfo> _devices = new();

    public SettingsPage(IBleService bleService)
    {
        InitializeComponent();
        _bleService = bleService;
        DeviceListView.ItemsSource = _devices;
        LoadSavedDevice();
    }

    protected override void OnAppearing()
    {
        base.OnAppearing();
        UpdateConnectionStatus();
        LoadSavedDevice();
    }

    private void UpdateConnectionStatus()
    {
        if (_bleService.IsConnected)
        {
            StatusIndicator.BackgroundColor = Colors.Green;
            StatusLabel.Text = $"已连接: {_bleService.ConnectedDeviceName}";
            ScanButton.IsEnabled = false;
        }
        else
        {
            StatusIndicator.BackgroundColor = Colors.Gray;
            StatusLabel.Text = "未连接";
            ScanButton.IsEnabled = true;
        }
    }

    private async void LoadSavedDevice()
    {
        var macAddress = await _bleService.GetSavedMacAddress();
        if (!string.IsNullOrEmpty(macAddress))
        {
            SavedDeviceFrame.IsVisible = true;
            SavedDeviceMac.Text = $"MAC: {macAddress}";
            SavedDeviceName.Text = "已保存的设备";

            // 自动连接已保存的设备
            await ConnectToSavedDevice(macAddress);
        }
        else
        {
            SavedDeviceFrame.IsVisible = false;
        }
    }

    private async Task ConnectToSavedDevice(string macAddress)
    {
        try
        {
            var connected = await _bleService.ConnectAsync(macAddress, macAddress);
            if (connected)
            {
                UpdateConnectionStatus();
                await DisplayAlertAsync("提示", "自动连接成功", "确定");
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Auto connect failed: {ex.Message}");
        }
    }

    private async void ScanButton_Clicked(object? sender, EventArgs e)
    {
#if ANDROID
        // Android: 检查并请求蓝牙权限 (Android 12+)
        if (Android.OS.Build.VERSION.SdkInt >= Android.OS.BuildVersionCodes.S)
        {
            var scanStatus = await Permissions.CheckStatusAsync<Permissions.Bluetooth>();
            if (scanStatus != PermissionStatus.Granted)
            {
                scanStatus = await Permissions.RequestAsync<Permissions.Bluetooth>();
                if (scanStatus != PermissionStatus.Granted)
                {
                    await DisplayAlertAsync("权限被拒绝", "应用需要蓝牙扫描权限才能查找设备。\n请在设置中开启蓝牙权限。", "确定");
                    return;
                }
            }
        }
        else
        {
            // Android 11 及以下: 需要位置权限才能扫描蓝牙
            var locationStatus = await Permissions.CheckStatusAsync<Permissions.LocationWhenInUse>();
            if (locationStatus != PermissionStatus.Granted)
            {
                locationStatus = await Permissions.RequestAsync<Permissions.LocationWhenInUse>();
                if (locationStatus != PermissionStatus.Granted)
                {
                    await DisplayAlertAsync("权限被拒绝", "应用需要位置权限才能扫描蓝牙设备。\n请在设置中开启位置权限。", "确定");
                    return;
                }
            }
        }

        // 检查蓝牙是否启用
        if (!await CheckBluetoothEnabled())
        {
            return;
        }
#elif IOS
        // iOS: 检查并请求蓝牙权限
        var status = await Permissions.CheckStatusAsync<Permissions.Bluetooth>();
        if (status != PermissionStatus.Granted)
        {
            status = await Permissions.RequestAsync<Permissions.Bluetooth>();
            if (status != PermissionStatus.Granted)
            {
                await DisplayAlertAsync("权限被拒绝", "应用需要蓝牙权限才能扫描设备", "确定");
                return;
            }
        }
#endif

        _devices.Clear();
        DeviceListView.IsVisible = true;
        ScanningIndicator.IsVisible = true;
        ScanningIndicator.IsRunning = true;
        ScanButton.IsEnabled = false;

        try
        {
            var devices = await _bleService.ScanDevicesAsync();
            foreach (var device in devices)
            {
                _devices.Add(device);
            }

            if (_devices.Count == 0)
            {
                await DisplayAlertAsync("提示", "未扫描到蓝牙设备，请确保：\n1. ESP32 设备已开机\n2. 蓝牙已启用\n3. 设备距离较近", "确定");
            }
        }
        catch (Exception ex)
        {
            await DisplayAlertAsync("错误", $"扫描失败: {ex.Message}\n\n请检查蓝牙权限和设置", "确定");
            System.Diagnostics.Debug.WriteLine($"BLE Scan Error: {ex}");
        }
        finally
        {
            ScanningIndicator.IsVisible = false;
            ScanningIndicator.IsRunning = false;
            ScanButton.IsEnabled = true;
        }
    }

    /// <summary>
    /// 检查蓝牙是否启用，如果未启用则提示用户
    /// </summary>
    private async Task<bool> CheckBluetoothEnabled()
    {
#if ANDROID
        var manager = (Android.Bluetooth.BluetoothManager?)Android.App.Application.Context.GetSystemService(Android.Content.Context.BluetoothService);
        var adapter = manager?.Adapter;
        
        if (adapter == null)
        {
            await DisplayAlertAsync("蓝牙不可用", "您的设备不支持蓝牙功能", "确定");
            return false;
        }
        
        if (!adapter.IsEnabled)
        {
            var enable = await DisplayAlert("蓝牙未开启", "需要开启蓝牙才能扫描设备，是否现在开启？", "开启", "取消");
            if (enable)
            {
                try
                {
                    var enableIntent = new Android.Content.Intent(Android.Bluetooth.BluetoothAdapter.ActionRequestEnable);
                    Platform.CurrentActivity?.StartActivityForResult(enableIntent, 1);
                    await Task.Delay(2000); // 等待用户操作
                    return adapter.IsEnabled;
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to enable Bluetooth: {ex.Message}");
                    return false;
                }
            }
            return false;
        }
#endif
        return true;
    }

    private async void ConnectButton_Clicked(object? sender, EventArgs e)
    {
        if (sender is Button button && button.CommandParameter is BleDeviceInfo device)
        {
            var originalText = button.Text;
            button.IsEnabled = false;
            button.Text = "连接中...";

            try
            {
                System.Diagnostics.Debug.WriteLine($"UI: 开始连接 {device.Name}");
                var connected = await _bleService.ConnectAsync(device.Id, device.MacAddress);
                
                System.Diagnostics.Debug.WriteLine($"UI: 连接结果 = {connected}");
                
                if (connected)
                {
                    // 等待一下确保状态已更新
                    await Task.Delay(500);
                    
                    DeviceListView.IsVisible = false;
                    UpdateConnectionStatus();
                    LoadSavedDevice();
                    await DisplayAlertAsync("成功", $"已连接到 {device.Name}", "确定");
                }
                else
                {
                    await DisplayAlertAsync("失败", "连接失败，请检查：\n1. 设备是否开机\n2. 设备是否在范围内\n3. 蓝牙权限是否允许", "确定");
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"UI: 连接异常 - {ex}");
                await DisplayAlertAsync("错误", $"连接错误: {ex.Message}", "确定");
            }
            finally
            {
                button.IsEnabled = true;
                button.Text = originalText;
            }
        }
    }

    private async void DeleteButton_Clicked(object? sender, EventArgs e)
    {
        var result = await DisplayAlertAsync("确认", "删除已保存的蓝牙连接？", "确定", "取消");
        if (result)
        {
            await _bleService.DeleteSavedMacAddress();
            SavedDeviceFrame.IsVisible = false;
            UpdateConnectionStatus();
            await DisplayAlertAsync("提示", "已删除", "确定");
        }
    }
}


