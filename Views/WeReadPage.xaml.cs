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
    private string _currentCookie = string.Empty;

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
        _currentUrl = e.Url ?? string.Empty;
        CheckFloatingButtonVisibility(e.Url);
        CheckLoginButtonVisibility(e.Url);

        // 获取 Cookie
        _ = GetCookieAsync();
    }

    private async Task GetCookieAsync()
    {
        try
        {
            var cookie = await WebView.EvaluateJavaScriptAsync("document.cookie");
            _currentCookie = cookie ?? string.Empty;
            System.Diagnostics.Debug.WriteLine($"WeRead: 获取到 Cookie (长度: {_currentCookie.Length})");
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"WeRead: 获取 Cookie 失败 - {ex.Message}");
        }
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
        await GetCookieAsync();

        // 跳转到 EPD 阅读页面，传递 URL 和 Cookie
        var encodedUrl = Uri.EscapeDataString(_currentUrl);
        var encodedCookie = Uri.EscapeDataString(_currentCookie);

        await Shell.Current.GoToAsync($"EPDReadingPage?url={encodedUrl}&cookie={encodedCookie}");
    }

    private async void CheckLoginButtonVisibility(string? url)
    {
        if (string.IsNullOrEmpty(url))
        {
            LoginButton.IsVisible = false;
            return;
        }

        // 检查是否是微信读书首页
        bool isHomePage = url == "https://weread.qq.com" || url == "https://weread.qq.com/";

        if (!isHomePage)
        {
            LoginButton.IsVisible = false;
            return;
        }

        // 在首页时，检测页面上是否有"登录"按钮
        try
        {
            var result = await WebView.EvaluateJavaScriptAsync("""
                (function() {
                    var candidates = document.querySelectorAll('a.wr_index_page_top_section_header_action_link');
                    for (var i = 0; i < candidates.length; i++) {
                        if (candidates[i].innerText.indexOf('登录') !== -1) {
                            return 'has_login';
                        }
                    }

                    var anchors = document.getElementsByTagName('a');
                    for (var j = 0; j < anchors.length; j++) {
                        if (anchors[j].innerText.indexOf('登录') !== -1) {
                            return 'has_login';
                        }
                    }

                    return 'no_login';
                })();
            """);

            // 如果页面上有"登录"按钮，显示浮动登录按钮；否则不显示
            LoginButton.IsVisible = result == "has_login";
        }
        catch
        {
            // 出错时默认不显示登录按钮
            LoginButton.IsVisible = false;
        }
    }

    private async void LoginButton_Clicked(object? sender, EventArgs e)
    {
        // 执行登录点击操作
        await PerformLoginClick();
    }

    private async Task PerformLoginClick()
    {
        try
        {
            await WebView.EvaluateJavaScriptAsync("""
                (function() {
                    if (window.__weReadLoginDetectionStarted) {
                        console.log('登录检测已启动，跳过重复执行');
                        return;
                    }
                    window.__weReadLoginDetectionStarted = true;

                    console.log('开始检测登录按钮...');
                    var count = 0;
                    var timer = setInterval(function() {
                        count++;

                        // 先检查是否已经登录（检测退出登录按钮）
                        var logoutLink = document.querySelector('.wr_index_page_top_section_header_action_avatar_dropdown_item_lang');
                        if (logoutLink && logoutLink.innerText.indexOf('退出登录') !== -1) {
                            console.log('已检测到退出登录，用户已登录');
                            clearInterval(timer);
                            return;
                        }

                        if (window.__weReadLoginClicked) {
                            clearInterval(timer);
                            return;
                        }

                        var loginLink = null;
                        var candidates = document.querySelectorAll('a.wr_index_page_top_section_header_action_link');
                        for (var i = 0; i < candidates.length; i++) {
                            if (candidates[i].innerText.indexOf('登录') !== -1) {
                                loginLink = candidates[i];
                                break;
                            }
                        }

                        if (!loginLink) {
                            var anchors = document.getElementsByTagName('a');
                            for (var j = 0; j < anchors.length; j++) {
                                if (anchors[j].innerText.indexOf('登录') !== -1) {
                                    loginLink = anchors[j];
                                    break;
                                }
                            }
                        }

                        if (loginLink) {
                            console.log('执行点击: ' + loginLink.innerText);
                            window.__weReadLoginClicked = true;

                            // 只调用一次 click，不派发额外的事件
                            loginLink.click();

                            clearInterval(timer);
                        }

                        if (count > 30) {
                            clearInterval(timer);
                        }
                    }, 500);
                })();
            """);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"PerformLoginClick error: {ex.Message}");
        }
    }
}


