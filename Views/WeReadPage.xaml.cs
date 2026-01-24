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

        // 在 UI 跳转前同步更新后台阅读上下文（URL & Cookie）
        if (!string.IsNullOrEmpty(_currentUrl))
        {
            await _bleService.UpdateReadingContextAsync(_currentUrl, _currentCookie);
        }

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
                    function hasText(node, text) { return node && node.innerText && node.innerText.replace(/\s+/g, '').indexOf(text) !== -1; }

                    // 先检查是否有 "退出登录"（代表已登录）
                    var candidates = document.querySelectorAll('.wr_index_page_top_section_header_action_link');
                    for (var i = 0; i < candidates.length; i++) {
                        if (hasText(candidates[i], '退出登录')) {
                            return 'logged_in';
                        }
                    }
                    var anchors = document.getElementsByTagName('a');
                    for (var j = 0; j < anchors.length; j++) {
                        if (hasText(anchors[j], '退出登录')) {
                            return 'logged_in';
                        }
                    }

                    // 如果存在单独的 "登录" 文本（且不包含 "退出登录"），说明未登录
                    for (var i = 0; i < candidates.length; i++) {
                        if (hasText(candidates[i], '登录') && !hasText(candidates[i], '退出登录')) {
                            return 'has_login';
                        }
                    }
                    for (var j = 0; j < anchors.length; j++) {
                        if (hasText(anchors[j], '登录') && !hasText(anchors[j], '退出登录')) {
                            return 'has_login';
                        }
                    }

                    // 未发现 "登录"（且未发现 "退出登录"），默认认为已登录
                    return 'logged_in';
                })();
            """);

            // 如果页面上有"登录"按钮，显示浮动登录按钮；否则不显示
            LoginButton.IsVisible = result == "has_login";
        }
        catch
        {
            // 出错时默认不显示登录按钮
            LoginButton.IsVisible =  false;
        }
    }

    private async void LoginButton_Clicked(object? sender, EventArgs e)
    {
        // 执行登录点击操作
        await PerformLoginClick();

        // 弹出二维码后尝试推送到 EPD 设备
        await TrySendLoginQrAsync();
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
                        if (logoutLink && logoutLink.innerText.replace(/\s+/g, '').indexOf('退出登录') !== -1) {
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
                            if (candidates[i].innerText.replace(/\s+/g, '').indexOf('登录') !== -1) {
                                loginLink = candidates[i];
                                break;
                            }
                        }

                        if (!loginLink) {
                            var anchors = document.getElementsByTagName('a');
                            for (var j = 0; j < anchors.length; j++) {
                                if (anchors[j].innerText.replace(/\s+/g, '').indexOf('登录') !== -1) {
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

    private async Task TrySendLoginQrAsync()
    {
        try
        {
            if (!_bleService.IsConnected)
            {
                System.Diagnostics.Debug.WriteLine("WeRead: 蓝牙未连接，跳过二维码推送");
                return;
            }

            const string script = "(function() {\n" +
                "    function findSource(doc) {\n" +
                "        try {\n" +
                "            var img = doc.querySelector('img[alt=\"登录二维码\"]');\n" +
                "            if (img && img.src) return { node: img, src: img.src, w: img.naturalWidth || img.width || 0, h: img.naturalHeight || img.height || 0 };\n" +
                "            var canvas = doc.querySelector('canvas');\n" +
                "            if (canvas && canvas.toDataURL) return { node: canvas, src: canvas.toDataURL('image/png'), w: canvas.width, h: canvas.height };\n" +
                "        } catch (_) {}\n" +
                "        return null;\n" +
                "    }\n" +
                "    function toBmp1bit(srcInfo) {\n" +
                "        var dataUrl = srcInfo.src;\n" +
                "        var w = srcInfo.w || 0;\n" +
                "        var h = srcInfo.h || 0;\n" +
                "        if (!dataUrl || !w || !h) return null;\n" +
                "        var canvas = document.createElement('canvas');\n" +
                "        canvas.width = w;\n" +
                "        canvas.height = h;\n" +
                "        var ctx = canvas.getContext('2d');\n" +
                "        if (srcInfo.node && srcInfo.node.tagName === 'CANVAS') {\n" +
                "            ctx.drawImage(srcInfo.node, 0, 0);\n" +
                "        } else {\n" +
                "            var img = document.createElement('img');\n" +
                "            img.src = dataUrl;\n" +
                "            ctx.drawImage(img, 0, 0, w, h);\n" +
                "        }\n" +
                "        var imgData = ctx.getImageData(0, 0, w, h).data;\n" +
                "        var rowSize = Math.ceil(w / 32) * 4;\n" +
                "        var sizeImage = rowSize * h;\n" +
                "        var fileSize = 14 + 40 + 8 + sizeImage;\n" +
                "        var buffer = new Uint8Array(fileSize);\n" +
                "        var dv = new DataView(buffer.buffer);\n" +
                "        buffer[0] = 0x42;\n" +
                "        buffer[1] = 0x4D;\n" +
                "        dv.setUint32(2, fileSize, true);\n" +
                "        dv.setUint32(10, 14 + 40 + 8, true);\n" +
                "        dv.setUint32(14, 40, true);\n" +
                "        dv.setInt32(18, w, true);\n" +
                "        dv.setInt32(22, -h, true);\n" +
                "        dv.setUint16(26, 1, true);\n" +
                "        dv.setUint16(28, 1, true);\n" +
                "        dv.setUint32(30, 0, true);\n" +
                "        dv.setUint32(34, sizeImage, true);\n" +
                "        dv.setUint32(38, 2835, true);\n" +
                "        dv.setUint32(42, 2835, true);\n" +
                "        dv.setUint32(46, 2, true);\n" +
                "        dv.setUint32(50, 2, true);\n" +
                "        var paletteOffset = 14 + 40;\n" +
                "        buffer[paletteOffset + 0] = 0x00;\n" +
                "        buffer[paletteOffset + 1] = 0x00;\n" +
                "        buffer[paletteOffset + 2] = 0x00;\n" +
                "        buffer[paletteOffset + 3] = 0x00;\n" +
                "        buffer[paletteOffset + 4] = 0xFF;\n" +
                "        buffer[paletteOffset + 5] = 0xFF;\n" +
                "        buffer[paletteOffset + 6] = 0xFF;\n" +
                "        buffer[paletteOffset + 7] = 0x00;\n" +
                "        var dataOffset = paletteOffset + 8;\n" +
                "        var dst = buffer;\n" +
                "        for (var y = 0; y < h; y++) {\n" +
                "            var rowStart = dataOffset + y * rowSize;\n" +
                "            var bitPos = 7;\n" +
                "            var byteVal = 0;\n" +
                "            for (var x = 0; x < w; x++) {\n" +
                "                var idx = (y * w + x) * 4;\n" +
                "                var r = imgData[idx];\n" +
                "                var g = imgData[idx + 1];\n" +
                "                var b = imgData[idx + 2];\n" +
                "                var lum = (r + g + b) / 3;\n" +
                "                var bit = lum < 128 ? 0 : 1;\n" +
                "                byteVal |= (bit << bitPos);\n" +
                "                bitPos--;\n" +
                "                if (bitPos < 0) {\n" +
                "                    dst[rowStart++] = byteVal;\n" +
                "                    byteVal = 0;\n" +
                "                    bitPos = 7;\n" +
                "                }\n" +
                "            }\n" +
                "            if (bitPos !== 7) {\n" +
                "                dst[rowStart] = byteVal;\n" +
                "            }\n" +
                "        }\n" +
                "        var binary = '';\n" +
                "        for (var i = 0; i < buffer.length; i++) {\n" +
                "            binary += String.fromCharCode(buffer[i]);\n" +
                "        }\n" +
                "        return 'data:image/bmp;base64,' + btoa(binary);\n" +
                "    }\n" +
                "    var src = findSource(document);\n" +
                "    if (!src) {\n" +
                "        var frame = document.querySelector('iframe');\n" +
                "        if (frame && frame.contentWindow && frame.contentWindow.document) {\n" +
                "            src = findSource(frame.contentWindow.document);\n" +
                "        }\n" +
                "    }\n" +
                "    if (!src) return null;\n" +
                "    return toBmp1bit(src);\n" +
                "})();";

            string? dataUrl = null;
            for (var i = 0; i < 6 && string.IsNullOrEmpty(dataUrl); i++)
            {
                dataUrl = await WebView.EvaluateJavaScriptAsync(script);
                if (string.IsNullOrEmpty(dataUrl))
                {
                    await Task.Delay(500);
                }
            }

            if (string.IsNullOrEmpty(dataUrl))
            {
                System.Diagnostics.Debug.WriteLine("WeRead: 未获取到登录二维码");
                return;
            }

            var commaIndex = dataUrl.IndexOf(',');
            var base64 = commaIndex >= 0 ? dataUrl.Substring(commaIndex + 1) : dataUrl;

            byte[] imageBytes;
            try
            {
                imageBytes = Convert.FromBase64String(base64);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"WeRead: 二维码 Base64 解析失败 - {ex.Message}");
                return;
            }

            var sent = await _bleService.SendImageToDeviceAsync(imageBytes, "page_0.bmp", X4IMProtocol.FLAG_TYPE_BMP, true, 0);
            System.Diagnostics.Debug.WriteLine(sent
                ? $"WeRead: 已发送登录二维码到设备 ({imageBytes.Length} 字节)"
                : "WeRead: 发送登录二维码失败");
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"WeRead: 推送二维码失败 - {ex.Message}");
        }
    }
}


