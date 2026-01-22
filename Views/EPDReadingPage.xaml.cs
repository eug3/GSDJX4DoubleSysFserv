using GSDJX4DoubleSysFserv.Services;

namespace GSDJX4DoubleSysFserv.Views;

/// <summary>
/// EPD 阅读页面 - 显示微信读书章节内容并发送到设备
/// </summary>
[QueryProperty(nameof(BookUrl), "url")]
[QueryProperty(nameof(Cookie), "cookie")]
public partial class EPDReadingPage : ContentPage
{
    private readonly IBleService _bleService;
    private readonly IWeReadService _weReadService;

    private string _bookUrl = string.Empty;
    private string _cookie = string.Empty;

    public string BookUrl
    {
        get => _bookUrl;
        set
        {
            _bookUrl = Uri.UnescapeDataString(value ?? string.Empty);
            System.Diagnostics.Debug.WriteLine($"EPDReading: 收到 BookUrl = {_bookUrl}");
        }
    }

    public string Cookie
    {
        get => _cookie;
        set
        {
            _cookie = Uri.UnescapeDataString(value ?? string.Empty);
            System.Diagnostics.Debug.WriteLine($"EPDReading: 收到 Cookie (长度: {_cookie.Length})");
        }
    }

    public EPDReadingPage(IBleService bleService, IWeReadService weReadService)
    {
        InitializeComponent();
        _bleService = bleService;
        _weReadService = weReadService;

        // 页面加载时更新状态
        Loaded += OnPageLoaded;
        
        // 订阅按键事件
        _bleService.ButtonPressed += OnButtonPressed;
    }

    private void OnPageLoaded(object? sender, EventArgs e)
    {
        UpdateConnectionStatus();
        UpdatePageInfo();

        // 如果有已保存的内容，显示它
        if (!string.IsNullOrEmpty(_weReadService.State.LastText))
        {
            ContentEditor.Text = _weReadService.State.LastText;
        }
    }

    private void UpdateConnectionStatus()
    {
        if (_bleService.IsConnected)
        {
            ConnectionStatusLabel.Text = $"蓝牙状态: 已连接 ({_bleService.ConnectedDeviceName})";
            ConnectionStatusLabel.TextColor = Color.FromArgb("#00ff88");
            SendToDeviceBtn.IsEnabled = true;
        }
        else
        {
            ConnectionStatusLabel.Text = "蓝牙状态: 未连接";
            ConnectionStatusLabel.TextColor = Color.FromArgb("#ff6b6b");
            SendToDeviceBtn.IsEnabled = false;
        }
    }

    private void UpdatePageInfo()
    {
        var page = _weReadService.State.Page;
        var charCount = ContentEditor.Text?.Length ?? 0;
        PageInfoLabel.Text = $"页码: {page} | 字符: {charCount}";
    }

    private void SetStatus(string message, bool isError = false)
    {
        StatusLabel.Text = message;
        StatusLabel.TextColor = isError ? Color.FromArgb("#ff6b6b") : Color.FromArgb("#00ff88");
        System.Diagnostics.Debug.WriteLine($"EPDReading: {message}");
    }

    private void SetLoading(bool isLoading)
    {
        LoadingIndicator.IsRunning = isLoading;
        LoadingIndicator.IsVisible = isLoading;

        PrevChapterBtn.IsEnabled = !isLoading;
        CurrentPageBtn.IsEnabled = !isLoading;
        NextChapterBtn.IsEnabled = !isLoading;
        SendToDeviceBtn.IsEnabled = !isLoading && _bleService.IsConnected;
    }

    private async void OnGetCurrentPage(object? sender, EventArgs e)
    {
        if (string.IsNullOrEmpty(_bookUrl))
        {
            await DisplayAlertAsync("错误", "未获取到书籍 URL，请从微信读书页面进入", "确定");
            return;
        }

        SetLoading(true);
        SetStatus("正在获取当前页内容...");

        try
        {
            var content = await _weReadService.GetCurrentPageAsync(_bookUrl, _cookie);
            ContentEditor.Text = content;
            UpdatePageInfo();
            SetStatus($"获取成功，共 {content.Length} 字符");
            
            // 自动发送到设备
            await AutoSendToDeviceAsync(content);
        }
        catch (Exception ex)
        {
            SetStatus($"获取失败: {ex.Message}", true);
            await DisplayAlertAsync("错误", $"获取内容失败: {ex.Message}", "确定");
        }
        finally
        {
            SetLoading(false);
        }
    }

    private async void OnPrevChapter(object? sender, EventArgs e)
    {
        if (string.IsNullOrEmpty(_weReadService.State.CurrentUrl))
        {
            await DisplayAlertAsync("提示", "请先获取当前页内容", "确定");
            return;
        }

        SetLoading(true);
        SetStatus("正在获取上一章...");

        try
        {
            var content = await _weReadService.GetPrevPageAsync();
            ContentEditor.Text = content;
            UpdatePageInfo();
            SetStatus($"上一章获取成功，共 {content.Length} 字符");
            
            // 自动发送到设备
            await AutoSendToDeviceAsync(content);
        }
        catch (Exception ex)
        {
            SetStatus($"获取上一章失败: {ex.Message}", true);
        }
        finally
        {
            SetLoading(false);
        }
    }

    private async void OnNextChapter(object? sender, EventArgs e)
    {
        if (string.IsNullOrEmpty(_weReadService.State.CurrentUrl))
        {
            await DisplayAlertAsync("提示", "请先获取当前页内容", "确定");
            return;
        }

        SetLoading(true);
        SetStatus("正在获取下一章...");

        try
        {
            var content = await _weReadService.GetNextPageAsync();
            ContentEditor.Text = content;
            UpdatePageInfo();
            SetStatus($"下一章获取成功，共 {content.Length} 字符");
            
            // 自动发送到设备
            await AutoSendToDeviceAsync(content);
        }
        catch (Exception ex)
        {
            SetStatus($"获取下一章失败: {ex.Message}", true);
        }
        finally
        {
            SetLoading(false);
        }
    }

    private async void OnSendToDevice(object? sender, EventArgs e)
    {
        var content = ContentEditor.Text;
        if (string.IsNullOrEmpty(content))
        {
            await DisplayAlertAsync("提示", "没有内容可发送，请先获取章节内容", "确定");
            return;
        }

        if (!_bleService.IsConnected)
        {
            await DisplayAlertAsync("错误", "蓝牙未连接，请先连接设备", "确定");
            return;
        }

        SetLoading(true);
        SetStatus("正在发送到 EPD 设备...");

        try
        {
            var success = await _bleService.SendTextToDeviceAsync(content, _weReadService.State.Page);
            if (success)
            {
                SetStatus($"发送成功! 共 {content.Length} 字符");
            }
            else
            {
                SetStatus("发送失败", true);
            }
        }
        catch (Exception ex)
        {
            SetStatus($"发送失败: {ex.Message}", true);
        }
        finally
        {
            SetLoading(false);
        }
    }

    /// <summary>
    /// 自动发送内容到 EPD 设备（后台静默发送）
    /// </summary>
    private async Task AutoSendToDeviceAsync(string content)
    {
        if (string.IsNullOrEmpty(content))
        {
            System.Diagnostics.Debug.WriteLine("自动发送: 内容为空，跳过");
            return;
        }

        if (!_bleService.IsConnected)
        {
            System.Diagnostics.Debug.WriteLine("自动发送: 蓝牙未连接，跳过");
            SetStatus("提示: 蓝牙未连接，无法自动发送到设备", true);
            return;
        }

        SetStatus("正在自动发送到 EPD 设备...");

        try
        {
            var success = await _bleService.SendTextToDeviceAsync(content, _weReadService.State.Page);
            if (success)
            {
                SetStatus($"✅ 已自动发送到设备 ({content.Length} 字符)");
            }
            else
            {
                SetStatus("自动发送失败", true);
            }
        }
        catch (Exception ex)
        {
            SetStatus($"自动发送失败: {ex.Message}", true);
            System.Diagnostics.Debug.WriteLine($"自动发送异常: {ex}");
        }
    }

    /// <summary>
    /// 处理ESP32设备发送的按键事件（左、右、上、下）
    /// </summary>
    private async void OnButtonPressed(object? sender, ButtonEventArgs e)
    {
        System.Diagnostics.Debug.WriteLine($"📱 检测到设备按键: {e.Key}");

        switch (e.Key.ToUpper())
        {
            case "LEFT":
                System.Diagnostics.Debug.WriteLine("设备按键: LEFT - 执行上一章");
                MainThread.BeginInvokeOnMainThread(() => OnPrevChapter(this, EventArgs.Empty));
                break;

            case "RIGHT":
                System.Diagnostics.Debug.WriteLine("设备按键: RIGHT - 执行下一章");
                MainThread.BeginInvokeOnMainThread(() => OnNextChapter(this, EventArgs.Empty));
                break;

            case "UP":
                System.Diagnostics.Debug.WriteLine("设备按键: UP - 本地翻页");
                SetStatus("设备按键: 向上翻页");
                break;

            case "DOWN":
                System.Diagnostics.Debug.WriteLine("设备按键: DOWN - 本地翻页");
                SetStatus("设备按键: 向下翻页");
                break;

            case "OK":
            case "ENTER":
                System.Diagnostics.Debug.WriteLine("设备按键: OK/ENTER - 刷新屏幕");
                SetStatus("设备按键: 确认/刷新");
                break;

            default:
                System.Diagnostics.Debug.WriteLine($"未知的设备按键: {e.Key}");
                break;
        }
    }
}
