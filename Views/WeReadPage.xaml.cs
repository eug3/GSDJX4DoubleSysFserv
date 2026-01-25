using System.Text.RegularExpressions;
using GSDJX4DoubleSysFserv.Services;

namespace GSDJX4DoubleSysFserv.Views;

/// <summary>
/// 微信读书页面
/// </summary>
public partial class WeReadPage : ContentPage
{
    private readonly IBleService _bleService;
    private string _currentUrl = string.Empty;
    private readonly System.Net.CookieContainer _cookies = new();

    // 正则匹配阅读器 URL: https://weread.qq.com/web/reader/xxx
    private static readonly Regex ReaderUrlRegex = new Regex(
        @"^https://weread\.qq\.com/web/reader/[a-zA-Z0-9]+$",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);

    public WeReadPage(IBleService bleService)
    {
        InitializeComponent();
        _bleService = bleService;
        
        // 订阅连接状态变化事件
        _bleService.ConnectionStateChanged += OnConnectionStateChanged;
    }

    private void ContentPage_Loaded(object? sender, EventArgs e)
    {
    }

    private void ContentPage_Unloaded(object? sender, EventArgs e)
    {
    }
    
    /// <summary>
    /// 处理蓝牙连接状态变化
    /// </summary>
    private void OnConnectionStateChanged(object? sender, ConnectionStateChangedEventArgs e)
    {
        // 当连接状态变化时，重新检查浮动按钮可见性
        CheckFloatingButtonVisibility(_currentUrl);
        
        System.Diagnostics.Debug.WriteLine($"WeRead: 蓝牙连接状态变化 - IsConnected: {e.IsConnected}, Device: {e.DeviceName}, Reason: {e.Reason}");
    }

  

    private void WebView_Navigated(object? sender, WebNavigatedEventArgs e)
    {
        // 导航完成时再次更新 URL（作为备用机制）
        _currentUrl = e.Url ?? string.Empty;
        CheckFloatingButtonVisibility(e.Url);
        // 获取 Cookie
        _ = GetCookiesAsync();
    }

    private async Task GetCookiesAsync()
    {
        try
        {
            var cookieString = await WebView.EvaluateJavaScriptAsync("document.cookie");
            if (!string.IsNullOrEmpty(cookieString))
            {
                ParseAndStoreCookies(cookieString, _currentUrl);
                System.Diagnostics.Debug.WriteLine($"WeRead: 获取到 Cookie (长度: {cookieString.Length})");
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"WeRead: 获取 Cookie 失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 解析 cookie 字符串并存储到 CookieContainer
    /// </summary>
    private void ParseAndStoreCookies(string cookieString, string url)
    {
        if (string.IsNullOrEmpty(url))
            return;

        var uri = new Uri(url);

        // 解析格式: "name1=value1; name2=value2; ..."
        var pairs = cookieString.Split(';', StringSplitOptions.RemoveEmptyEntries);
        foreach (var pair in pairs)
        {
            var trimmed = pair.Trim();
            var equalIndex = trimmed.IndexOf('=');
            if (equalIndex > 0)
            {
                var name = trimmed[..equalIndex].Trim();
                var value = trimmed[(equalIndex + 1)..].Trim();
                var cookie = new System.Net.Cookie(name, value, "/", uri.Host);
                _cookies.Add(cookie);
            }
        }
    }

    /// <summary>
    /// 从 CookieContainer 获取 cookie 字符串
    /// </summary>
    private string GetCookieString()
    {
        if (string.IsNullOrEmpty(_currentUrl))
            return string.Empty;

        var uri = new Uri(_currentUrl);
        var cookies = _cookies.GetCookies(uri);
        return string.Join("; ", cookies.Cast<System.Net.Cookie>().Select(c => $"{c.Name}={c.Value}"));
    }

    private void CheckFloatingButtonVisibility(string? url)
    {
        if (string.IsNullOrEmpty(url))
        {
            FloatingButton.IsVisible = false;
            return;
        }

        // 检查是否是阅读器页面
        bool isReaderPage = ReaderUrlRegex.IsMatch(url)  ; 
        // 检查蓝牙是否已连接
        bool isBleConnected = _bleService.IsConnected;

        // 只有同时满足条件才显示浮动按钮
        FloatingButton.IsVisible = isReaderPage && isBleConnected;

        System.Diagnostics.Debug.WriteLine($"URL: {url}, IsReaderPage: {isReaderPage}, IsBleConnected: {isBleConnected}, ButtonVisible: {FloatingButton.IsVisible}");
    }

    private async void FloatingButton_Clicked(object? sender, EventArgs e)
    {
        // 确保有最新的 Cookie
        await GetCookiesAsync();

        var cookieString = GetCookieString();

        // 在 UI 跳转前同步更新后台阅读上下文（URL & Cookie）
        if (!string.IsNullOrEmpty(_currentUrl))
        {
            System.Diagnostics.Debug.WriteLine($"WeRead: 浮动按钮点击 - CurrentUrl: {_currentUrl}");
            await _bleService.UpdateReadingContextAsync(_currentUrl, cookieString);
        }

        // 跳转到 EPD 阅读页面，传递 URL 和 Cookie
        var encodedUrl = Uri.EscapeDataString(_currentUrl);
        var encodedCookie = Uri.EscapeDataString(cookieString);

        await Shell.Current.GoToAsync($"EPDReadingPage?url={encodedUrl}&cookie={encodedCookie}");
    }

  
 
}


