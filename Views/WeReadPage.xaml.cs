using System.Text.RegularExpressions;
using GSDJX4DoubleSysFserv.Services;

namespace GSDJX4DoubleSysFserv.Views;

/// <summary>
/// 微信读书页面
/// </summary>
public partial class WeReadPage : ContentPage
{
    private readonly IBleService _bleService;

    // 正则匹配阅读器 URL: https://weread.qq.com/web/reader/xxx
    private static readonly Regex ReaderUrlRegex = new Regex(
        @"^https://weread\.qq\.com/web/reader/[a-zA-Z0-9]+$",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);

    public WeReadPage(IBleService bleService)
    {
        InitializeComponent();
        _bleService = bleService;
    }

    private void ContentPage_Loaded(object? sender, EventArgs e)
    {
    }

    private void ContentPage_Unloaded(object? sender, EventArgs e)
    {
    }

    private void WebView_Navigated(object? sender, WebNavigatedEventArgs e)
    {
        CheckFloatingButtonVisibility(e.Url);
    }

    private void CheckFloatingButtonVisibility(string? url)
    {
        if (string.IsNullOrEmpty(url))
        {
            FloatingButton.IsVisible = false;
            return;
        }

        // 检查是否是阅读器页面
        bool isReaderPage = ReaderUrlRegex.IsMatch(url);
        // 检查蓝牙是否已连接
        bool isBleConnected = _bleService.IsConnected;

        // 只有同时满足条件才显示浮动按钮
        FloatingButton.IsVisible = isReaderPage && isBleConnected;

        System.Diagnostics.Debug.WriteLine($"URL: {url}, IsReaderPage: {isReaderPage}, IsBleConnected: {isBleConnected}, ButtonVisible: {FloatingButton.IsVisible}");
    }

    private void FloatingButton_Clicked(object? sender, EventArgs e)
    {
        // 浮动按钮点击事件 - 用户说后续再实现
    }
}


