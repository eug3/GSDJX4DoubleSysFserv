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
                await DisplayAlert("提示", "自动连接成功", "确定");
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Auto connect failed: {ex.Message}");
        }
    }

    private async void ScanButton_Clicked(object? sender, EventArgs e)
    {
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
                await DisplayAlert("提示", "未扫描到蓝牙设备", "确定");
            }
        }
        catch (Exception ex)
        {
            await DisplayAlert("错误", $"扫描失败: {ex.Message}", "确定");
        }
        finally
        {
            ScanningIndicator.IsVisible = false;
            ScanningIndicator.IsRunning = false;
            ScanButton.IsEnabled = true;
        }
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
                var connected = await _bleService.ConnectAsync(device.Id, device.MacAddress);
                if (connected)
                {
                    DeviceListView.IsVisible = false;
                    UpdateConnectionStatus();
                    LoadSavedDevice();
                    await DisplayAlert("成功", $"已连接到 {device.Name}", "确定");
                }
                else
                {
                    await DisplayAlert("失败", "连接失败", "确定");
                }
            }
            catch (Exception ex)
            {
                await DisplayAlert("错误", $"连接错误: {ex.Message}", "确定");
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
        var result = await DisplayAlert("确认", "删除已保存的蓝牙连接？", "确定", "取消");
        if (result)
        {
            await _bleService.DeleteSavedMacAddress();
            SavedDeviceFrame.IsVisible = false;
            UpdateConnectionStatus();
            await DisplayAlert("提示", "已删除", "确定");
        }
    }
}
