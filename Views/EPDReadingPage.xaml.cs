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

        // 只使用后端服务中保存的 URL
        var urlFromBackend = _weReadService.State.CurrentUrl;
        
        if (string.IsNullOrEmpty(urlFromBackend))
        {
            SetStatus("⚠️ 后端未保存 URL，请先从微信读书页面发送内容", true);
            System.Diagnostics.Debug.WriteLine("EPDReading: 页面加载时后端无 URL");
            return;
        }

        // 尝试从本地缓存获取对应 URL 的内容
        var cachedContent = await _weReadService.GetCachedContentAsync(urlFromBackend);
        if (!string.IsNullOrEmpty(cachedContent))
        {
            // 缓存命中，使用缓存内容
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

        // 缓存不存在，从后端服务请求
        System.Diagnostics.Debug.WriteLine($"EPDReading: 页面加载时缓存不存在，从后端请求 URL: {urlFromBackend}");
        SetStatus("缓存不存在，正在从后端服务加载...");

        try
        {
            var content = await _weReadService.GetCurrentPageAsync(urlFromBackend, _cookie);
            if (!string.IsNullOrEmpty(content))
            {
                ContentEditor.Text = content;
                SetStatus($"✅ 已从后端加载内容 ({content.Length} 字符)");
                UpdatePageInfo();

                // 自动发送到设备
                _ = Task.Run(async () =>
                {
                    await Task.Delay(500);
                    await AutoSendCurrentContentAsync();
                });
            }
            else
            {
                SetStatus("⚠️ 后端服务返回空内容", true);
            }
        }
        catch (Exception ex)
        {
            SetStatus($"加载失败: {ex.Message}", true);
            System.Diagnostics.Debug.WriteLine($"EPDReading: 页面加载时请求错误 - {ex.Message}");
        }
    }

    /// <summary>
    /// 处理连接状态变化事件 - 仅更新 UI 状态，不自动发送
    /// </summary>
    private void OnConnectionStateChanged(object? sender, ConnectionStateChangedEventArgs e)
    {
        System.Diagnostics.Debug.WriteLine($"EPDReading: 连接状态变化 - {(e.IsConnected ? "已连接" : "已断开")} {e.DeviceName}");
        UpdateConnectionStatus();
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
        // 获取后端服务中保存的 URL 作为 key
        var urlKey = _weReadService.State.CurrentUrl;
        if (string.IsNullOrEmpty(urlKey))
        {
            await DisplayAlertAsync("错误", "未保存的内容 URL，请先从微信读书页面获取过一次内容", "确定");
            return;
        }

        SetLoading(true);
        SetStatus("正在加载内容...");

        try
        {
            // 先从缓存中获取对应 URL 的文本
            var cachedContent = await _weReadService.GetCachedContentAsync(urlKey);
            
            if (!string.IsNullOrEmpty(cachedContent))
            {
                // 缓存命中，直接使用缓存内容
                ContentEditor.Text = cachedContent;
                UpdatePageInfo();
                SetStatus($"✅ 已加载缓存文本，共 {cachedContent.Length} 字符");
                
                // 自动发送到设备
                await AutoSendToDeviceAsync(cachedContent);
                return;
            }

            // 缓存不存在，从后端服务请求
            System.Diagnostics.Debug.WriteLine($"EPDReading: 缓存不存在，从后端请求 URL: {urlKey}");
            SetStatus("缓存不存在，正在从后端服务请求...");

            var content = await _weReadService.GetCurrentPageAsync(urlKey, _cookie);
            
            if (string.IsNullOrEmpty(content))
            {
                SetStatus("后端服务返回空内容", true);
                return;
            }

            // 更新 UI 中的文本框
            ContentEditor.Text = content;
            UpdatePageInfo();
            SetStatus($"✅ 已从后端服务加载内容，共 {content.Length} 字符");
            
            // 自动发送到设备
            await AutoSendToDeviceAsync(content);
        }
        catch (Exception ex)
        {
            SetStatus($"加载失败: {ex.Message}", true);
            System.Diagnostics.Debug.WriteLine($"EPDReading: 加载错误 - {ex.Message}");
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
    /// 架构说明：
    /// - UI 层只负责更新状态显示
    /// - 实际的翻页、数据获取、蓝牙发送都在 Service 层处理
    /// - 这样确保息屏时也能正常工作
    /// </summary>
    private async void OnButtonPressed(object? sender, ButtonEventArgs e)
    {
        System.Diagnostics.Debug.WriteLine($"🎯 UI 收到按键: {e.Key}");

        // 通知 Service 层处理按键（后台自动翻页 + 发送到设备）
        await _bleService.ProcessButtonAsync(e.Key);

        // UI 只负责更新状态显示
        MainThread.BeginInvokeOnMainThread(() =>
        {
            switch (e.Key.ToUpper())
            {
                case "RIGHT":
                    SetStatus("📖 请求下一章...", false);
                    break;
                case "LEFT":
                    SetStatus("📖 请求上一章...", false);
                    break;
                case "UP":
                    SetStatus("设备本地翻页: 向上", false);
                    break;
                case "DOWN":
                    SetStatus("设备本地翻页: 向下", false);
                    break;
                case "OK":
                case "ENTER":
                    SetStatus("设备请求刷新屏幕", false);
                    break;
                default:
                    SetStatus($"收到按键: {e.Key}", false);
                    break;
            }
        });
    }
}
