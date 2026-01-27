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
        
        // 订阅连接状态变化事件
        _bleService.ConnectionStateChanged += OnConnectionStateChanged;
        
        LoadSavedDevice();
    }

    protected override void OnAppearing()
    {
        base.OnAppearing();
        UpdateConnectionStatus();
        LoadSavedDevice();
    }
    
    protected override void OnDisappearing()
    {
        base.OnDisappearing();
        _bleService.ConnectionStateChanged -= OnConnectionStateChanged;
    }
    
    /// <summary>
    /// 处理连接状态变化事件
    /// </summary>
    private void OnConnectionStateChanged(object? sender, ConnectionStateChangedEventArgs e)
    {
        MainThread.BeginInvokeOnMainThread(() =>
        {
            try
            {
                if (this.Handler == null)
                    return;

                UpdateConnectionStatus();

                if (e.IsConnected)
                {
                    DeviceListView.IsVisible = false;
                    LoadSavedDevice();
                    System.Diagnostics.Debug.WriteLine($"BLE: 连接状态变化 - 已连接到 {e.DeviceName}，原因: {e.Reason}");
                }
                else
                {
                    LoadSavedDevice();
                    System.Diagnostics.Debug.WriteLine($"BLE: 连接状态变化 - 已断开 {e.DeviceName}，原因: {e.Reason}");
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"BLE: OnConnectionStateChanged 异常 - {ex.Message}");
            }
        });
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
        }
        else
        {
            SavedDeviceFrame.IsVisible = false;
        }
    }

    private async void ScanButton_Clicked(object? sender, EventArgs e)
    {
#if ANDROID
        // Android: 检查并请求蓝牙扫描权限 (Android 12+ 需要 BLUETOOTH_SCAN)
        if (Android.OS.Build.VERSION.SdkInt >= Android.OS.BuildVersionCodes.S)
        {
            // .NET MAUI Essentials 将 Android 12+ 的 BLUETOOTH_SCAN/CONNECT 映射到 Permissions.Bluetooth
            var scanStatus = await Permissions.CheckStatusAsync<Permissions.Bluetooth>();
            if (scanStatus != PermissionStatus.Granted)
            {
                scanStatus = await Permissions.RequestAsync<Permissions.Bluetooth>();
                if (scanStatus != PermissionStatus.Granted)
                {
                    await DisplayAlertAsync("权限被拒绝", "应用需要蓝牙扫描/连接权限才能查找设备。\n请在设置中开启蓝牙权限。", "确定");
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
            var enable = await DisplayAlertAsync("蓝牙未开启", "需要开启蓝牙才能扫描设备，是否现在开启？", "开启", "取消");
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
        if (sender is not Button button)
            return;

        if (button.CommandParameter is not BleDeviceInfo device)
        {
            await DisplayAlertAsync("错误", "无效的设备信息", "确定");
            return;
        }

        var originalText = button.Text;
        button.IsEnabled = false;
        button.Text = "连接中...";

        try
        {
            System.Diagnostics.Debug.WriteLine($"UI: 开始连接 {device.Name}");

#if ANDROID
            // Android 13+ 先确保通知已启用（前台服务需要）
            if (Android.OS.Build.VERSION.SdkInt >= Android.OS.BuildVersionCodes.Tiramisu)
            {
                try
                {
                    var notificationManager = (Android.App.NotificationManager?)
                        Android.App.Application.Context.GetSystemService(Android.Content.Context.NotificationService);
                    
                    if (notificationManager != null && !notificationManager.AreNotificationsEnabled())
                    {
                        var result = await DisplayAlertAsync(
                            "通知权限提示",
                            "前台蓝牙服务需要通知权限在后台保持连接。\n\n是否现在打开应用通知设置？",
                            "设置",
                            "继续"
                        );

                        if (result)
                        {
                            try
                            {
                                var intent = new Android.Content.Intent();
                                intent.SetAction(Android.Provider.Settings.ActionAppNotificationSettings);
                                intent.PutExtra(Android.Provider.Settings.ExtraAppPackage, 
                                    Android.App.Application.Context.PackageName);
                                Platform.CurrentActivity?.StartActivity(intent);
                            }
                            catch (Exception ex)
                            {
                                System.Diagnostics.Debug.WriteLine($"打开通知设置失败: {ex.Message}");
                            }
                        }
                    }
                }
                catch
                {
                    // 忽略异常，继续连接
                }
            }

            // Android 12+: 连接需要 BLUETOOTH_CONNECT（Essentials 映射为 Permissions.Bluetooth）
            if (Android.OS.Build.VERSION.SdkInt >= Android.OS.BuildVersionCodes.S)
            {
                var connectStatus = await Permissions.CheckStatusAsync<Permissions.Bluetooth>();
                if (connectStatus != PermissionStatus.Granted)
                {
                    connectStatus = await Permissions.RequestAsync<Permissions.Bluetooth>();
                    if (connectStatus != PermissionStatus.Granted)
                    {
                        await DisplayAlertAsync("权限被拒绝", "应用需要蓝牙连接权限才能连接设备。\n请在设置中开启蓝牙权限。", "确定");
                        return;
                    }
                }
            }
#endif

            var connected = await _bleService.ConnectAsync(device.Id, device.MacAddress);

            System.Diagnostics.Debug.WriteLine($"UI: 连接结果 = {connected}");

            if (connected)
            {
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

    private async void DeleteButton_Clicked(object? sender, EventArgs e)
    {
        var result = await DisplayAlertAsync("确认", "删除已保存的蓝牙连接？", "确定", "取消");
        if (result)
        {
            await _bleService.DeleteSavedMacAddress();
            // UI 更新由 ConnectionStateChanged 事件处理
            await DisplayAlertAsync("提示", "已删除", "确定");
        }
    }
}


