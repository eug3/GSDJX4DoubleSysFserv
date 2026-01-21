using GSDJX4DoubleSysFserv.Services;

namespace GSDJX4DoubleSysFserv.Views;

/// <summary>
/// 蓝牙设备扫描页面
/// </summary>
public partial class BleDevicesPage : ContentPage
{
    private readonly IBleService _bleService;
    private BleDeviceInfo? _selectedDevice;

    public BleDevicesPage(IBleService bleService)
    {
        InitializeComponent();
        _bleService = bleService;
    }

    protected override async void OnAppearing()
    {
        base.OnAppearing();
        await CheckAndRequestPermissions();
    }

    private async Task CheckAndRequestPermissions()
    {
#if ANDROID
        var status = await Permissions.CheckStatusAsync<Permissions.Bluetooth>();
        if (status != PermissionStatus.Granted)
        {
            status = await Permissions.RequestAsync<Permissions.Bluetooth>();
        }

        // Android 11 及以下需要位置权限
        if (Android.OS.Build.VERSION.SdkInt < Android.OS.BuildVersionCodes.S)
        {
            var locationStatus = await Permissions.CheckStatusAsync<Permissions.LocationWhenInUse>();
            if (locationStatus != PermissionStatus.Granted)
            {
                locationStatus = await Permissions.RequestAsync<Permissions.LocationWhenInUse>();
            }
        }
#endif
    }

    private async void ScanButton_Clicked(object? sender, EventArgs e)
    {
        ScanningIndicator.IsRunning = true;
        StatusLabel.Text = "正在扫描附近的蓝牙设备...";
        DevicesCollectionView.ItemsSource = null;

        try
        {
            var devices = await _bleService.ScanDevicesAsync();
            DevicesCollectionView.ItemsSource = devices;
            
            StatusLabel.Text = devices.Count > 0 
                ? $"发现 {devices.Count} 个设备" 
                : "未发现设备，请确保设备已开启并靠近";
        }
        catch (Exception ex)
        {
            StatusLabel.Text = $"扫描失败: {ex.Message}";
            await DisplayAlertAsync("错误", $"蓝牙扫描失败：{ex.Message}", "确定");
        }
        finally
        {
            ScanningIndicator.IsRunning = false;
        }
    }

    private async void DevicesCollectionView_SelectionChanged(object? sender, SelectionChangedEventArgs e)
    {
        if (e.CurrentSelection.FirstOrDefault() is BleDeviceInfo device)
        {
            _selectedDevice = device;
            
                var result = await DisplayAlertAsync(
                "连接设备", 
                $"是否连接到设备 {device.Name}？", 
                "连接", 
                "取消");

            if (result)
            {
                await ConnectToDevice(device);
            }

            // 清除选择
            DevicesCollectionView.SelectedItem = null;
        }
    }

    private async Task ConnectToDevice(BleDeviceInfo device)
    {
        try
        {
            StatusLabel.Text = $"正在连接到 {device.Name}...";
            
            var success = await _bleService.ConnectAsync(device.Id, device.MacAddress);
            
            if (success)
            {
                await _bleService.SaveMacAddress(device.MacAddress);
                await DisplayAlertAsync("成功", $"已连接到 {device.Name}", "确定");
                await Navigation.PopAsync();
            }
            else
            {
                StatusLabel.Text = "连接失败";
                await DisplayAlertAsync("失败", "无法连接到设备，请重试", "确定");
            }
        }
        catch (Exception ex)
        {
            StatusLabel.Text = "连接出错";
            await DisplayAlertAsync("错误", $"连接失败：{ex.Message}", "确定");
        }
    }

    private async void CancelButton_Clicked(object? sender, EventArgs e)
    {
        await Navigation.PopAsync();
    }
}
