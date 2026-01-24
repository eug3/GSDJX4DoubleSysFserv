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

        // 订阅连接状态变化事件（用于连接时自动发送）
        _bleService.ConnectionStateChanged += OnConnectionStateChanged;
    }

    private async void OnPageLoaded(object? sender, EventArgs e)
    {
        UpdateConnectionStatus();
        UpdatePageInfo();

        // 优先检查 URL 缓存
        if (!string.IsNullOrEmpty(_bookUrl))
        {
            var cachedContent = await _weReadService.GetCachedContentAsync(_bookUrl);
            if (!string.IsNullOrEmpty(cachedContent))
            {
                // 使用缓存内容
                ContentEditor.Text = cachedContent;
                SetStatus($"✅ 已加载缓存内容 ({cachedContent.Length} 字符)");
                UpdatePageInfo();

                // 自动发送到设备
                _ = Task.Run(async () =>
                {
                    await Task.Delay(500);
                    await AutoSendCurrentContentAsync();
                });
                return;
            }
        }

        // 如果有已保存的内容，显示它
        if (!string.IsNullOrEmpty(_weReadService.State.LastText))
        {
            ContentEditor.Text = _weReadService.State.LastText;

            // 页面启动时自动发送到设备
            _ = Task.Run(async () =>
            {
                await Task.Delay(500); // 延迟以确保连接状态稳定
                await AutoSendCurrentContentAsync();
            });
        }
        // 如果没有已保存的内容，但提供了 URL，自动获取当前页
        else if (!string.IsNullOrEmpty(_bookUrl))
        {
            _ = Task.Run(async () =>
            {
                await Task.Delay(300); // 短暂延迟确保 UI 准备好
                MainThread.BeginInvokeOnMainThread(() =>
                {
                    OnGetCurrentPage(this, EventArgs.Empty);
                });
            });
        }
    }

    /// <summary>
    /// 处理连接状态变化事件 - 当设备连接时自动发送当前内容
    /// </summary>
    private async void OnConnectionStateChanged(object? sender, ConnectionStateChangedEventArgs e)
    {
        if (e.IsConnected)
        {
            System.Diagnostics.Debug.WriteLine($"EPDReading: 设备已连接 - {e.DeviceName}，尝试自动发送当前内容");
            UpdateConnectionStatus();
            await AutoSendCurrentContentAsync();
        }
        else
        {
            UpdateConnectionStatus();
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
    /// 自动发送当前编辑器内容到 EPD 设备
    /// 用于页面启动时和设备连接时自动发送
    /// </summary>
    private async Task AutoSendCurrentContentAsync()
    {
        var content = ContentEditor.Text;
        if (string.IsNullOrEmpty(content))
        {
            System.Diagnostics.Debug.WriteLine("自动发送当前内容: 编辑器内容为空，跳过");
            return;
        }

        if (!_bleService.IsConnected)
        {
            System.Diagnostics.Debug.WriteLine("自动发送当前内容: 蓝牙未连接，跳过");
            return;
        }

        MainThread.BeginInvokeOnMainThread(() =>
        {
            SetStatus("📤 正在自动发送当前页到 EPD 设备...");
        });

        try
        {
            var success = await _bleService.SendTextToDeviceAsync(content, _weReadService.State.Page);
            MainThread.BeginInvokeOnMainThread(() =>
            {
                if (success)
                {
                    SetStatus($"✅ 已自动发送当前页到设备 ({content.Length} 字符)");
                }
                else
                {
                    SetStatus("自动发送当前页失败", true);
                }
            });
        }
        catch (Exception ex)
        {
            MainThread.BeginInvokeOnMainThread(() =>
            {
                SetStatus($"自动发送当前页失败: {ex.Message}", true);
            });
            System.Diagnostics.Debug.WriteLine($"自动发送当前页异常: {ex}");
        }
    }

    /// <summary>
    /// 处理ESP32设备发送的按键事件
    /// 
    /// 关键逻辑（参考 BleReadBook/main.js）：
    /// - ESP32 只在 文本末尾 时发送 0x81 (NEXT_PAGE) / 0x82 (PREV_PAGE)
    /// - 其他时候是本地翻页，**不应该发送网络请求**
    /// 
    /// RIGHT/LEFT 的含义：
    ///   • 0x81 (RIGHT): 当前页是最后一页 → 请求下一章
    ///   • 0x82 (LEFT):  当前页是第一页 → 请求上一章
    ///   • UP/DOWN:      中间页的本地翻页 → 不发送网络请求
    /// </summary>
    private async void OnButtonPressed(object? sender, ButtonEventArgs e)
    {
        System.Diagnostics.Debug.WriteLine($"🎯 ESP32 按键: {e.Key}");

        switch (e.Key.ToUpper())
        {
            case "RIGHT":
                // ✅ RIGHT = 0x81 = 页面已到末尾，请求下一章
                System.Diagnostics.Debug.WriteLine("✅ RIGHT (0x81): 当前页是末尾，请求下一章");
                MainThread.BeginInvokeOnMainThread(() =>
                {
                    if (!string.IsNullOrEmpty(_weReadService.State.CurrentUrl))
                    {
                        OnNextChapter(this, EventArgs.Empty);
                    }
                    else
                    {
                        SetStatus("未设置阅读 URL，无法获取下一章", true);
                    }
                });
                break;

            case "LEFT":
                // ✅ LEFT = 0x82 = 页面已到开头，请求上一章
                System.Diagnostics.Debug.WriteLine("✅ LEFT (0x82): 当前页是开头，请求上一章");
                MainThread.BeginInvokeOnMainThread(() =>
                {
                    if (!string.IsNullOrEmpty(_weReadService.State.CurrentUrl))
                    {
                        OnPrevChapter(this, EventArgs.Empty);
                    }
                    else
                    {
                        SetStatus("未设置阅读 URL，无法获取上一章", true);
                    }
                });
                break;

            case "UP":
                // ⚠️ UP = 本地滚动页面向上（不发送网络请求）
                System.Diagnostics.Debug.WriteLine("⚠️  UP: 本地页面向上滚动");
                MainThread.BeginInvokeOnMainThread(() =>
                {
                    SetStatus("设备本地翻页: 向上", false);
                });
                break;

            case "DOWN":
                // ⚠️ DOWN = 本地滚动页面向下（不发送网络请求）
                System.Diagnostics.Debug.WriteLine("⚠️  DOWN: 本地页面向下滚动");
                MainThread.BeginInvokeOnMainThread(() =>
                {
                    SetStatus("设备本地翻页: 向下", false);
                });
                break;

            case "OK":
            case "ENTER":
                // ℹ️ OK = 确认/刷新
                System.Diagnostics.Debug.WriteLine("ℹ️  OK: 刷新屏幕");
                MainThread.BeginInvokeOnMainThread(() =>
                {
                    SetStatus("设备请求刷新屏幕", false);
                });
                break;

            default:
                System.Diagnostics.Debug.WriteLine($"❓ 未知按键: {e.Key}");
                break;
        }
    }
}
