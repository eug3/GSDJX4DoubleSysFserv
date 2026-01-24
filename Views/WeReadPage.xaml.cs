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
                "            var img = doc.querySelector('.wr_login_modal_qr_img');\n" +
                "            if (img && img.src) return img.src;\n" +
                "        } catch (_) {}\n" +
                "        return null;\n" +
                "    }\n" +
                "    var src = findSource(document);\n" +
                "    if (!src) {\n" +
                "        var frame = document.querySelector('iframe');\n" +
                "        if (frame && frame.contentWindow && frame.contentWindow.document) {\n" +
                "            src = findSource(frame.contentWindow.document);\n" +
                "        }\n" +
                "    }\n" +
                "    return src;\n" +
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

            byte[] bmpBytes;
            try
            {
                bmpBytes = Convert.FromBase64String(base64);
                System.Diagnostics.Debug.WriteLine($"WeRead: BMP 大小 {bmpBytes.Length} 字节 ({bmpBytes.Length / 1024.0:F1} KB)");
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"WeRead: BMP Base64 解析失败 - {ex.Message}");
                return;
            }

            var sent = await _bleService.SendImageToDeviceAsync(bmpBytes, "page_0.bmp", X4IMProtocol.FLAG_TYPE_BMP, true, 0);
            System.Diagnostics.Debug.WriteLine(sent
                ? $"WeRead: 已发送登录二维码到设备 ({bmpBytes.Length} 字节)"
                : "WeRead: 发送登录二维码失败");
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"WeRead: 推送二维码失败 - {ex.Message}");
        }
    }

    private byte[]? PngTo1BitBmp(byte[] pngBytes)
    {
        try
        {
            // 检查 PNG 签名（89 50 4E 47 0D 0A 1A 0A）
            ReadOnlySpan<byte> pngSignature = stackalloc byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
            if (pngBytes.Length < pngSignature.Length || !pngBytes.AsSpan(0, pngSignature.Length).SequenceEqual(pngSignature))
            {
                System.Diagnostics.Debug.WriteLine("WeRead: 无效的 PNG 签名");
                return null;
            }

            // 读取 PNG 信息
            int offset = 8;
            int width = 0, height = 0;
            int bitDepth = 0, colorType = 0;
            List<byte> idatData = new List<byte>();

            while (offset < pngBytes.Length - 8)
            {
                int length = ReadInt32BigEndian(pngBytes, offset);
                string chunkType = System.Text.Encoding.ASCII.GetString(pngBytes, offset + 4, 4);

                if (chunkType == "IHDR")
                {
                    width = ReadInt32BigEndian(pngBytes, offset + 8);
                    height = ReadInt32BigEndian(pngBytes, offset + 12);
                    bitDepth = pngBytes[offset + 16];
                    colorType = pngBytes[offset + 17];
                }
                else if (chunkType == "IDAT")
                {
                    for (int i = 0; i < length; i++)
                    {
                        idatData.Add(pngBytes[offset + 8 + i]);
                    }
                }
                else if (chunkType == "IEND")
                {
                    break;
                }

                offset += 12 + length; // 4(长度) + 4(类型) + 数据 + 4(CRC)
            }

            if (width == 0 || height == 0 || idatData.Count == 0)
            {
                System.Diagnostics.Debug.WriteLine("WeRead: PNG 信息不完整");
                return null;
            }

            // 解压 zlib 数据
            byte[] rawData;
            try
            {
                rawData = Inflate(idatData.ToArray());
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"WeRead: zlib 解压失败 - {ex.Message}");
                return null;
            }

            // 缩放到 240x240 以减小文件大小（目标 5-6KB）
            const int targetSize = 240;
            byte[] scaledData = ScaleImageData(rawData, width, height, targetSize, targetSize);
            width = targetSize;
            height = targetSize;

            System.Diagnostics.Debug.WriteLine($"WeRead: 已缩放图片到 {width}x{height}");

            // 计算 BMP 大小
            int rowSize = ((width + 31) / 32) * 4;
            int sizeImage = rowSize * height;
            int fileSize = 14 + 40 + 8 + sizeImage;

            System.Diagnostics.Debug.WriteLine($"WeRead: BMP 预估大小 {fileSize / 1024.0:F1} KB");

            byte[] bmpBytes = new byte[fileSize];
            using (var ms = new MemoryStream(bmpBytes))
            using (var bw = new BinaryWriter(ms))
            {
                // BMP 文件头
                bw.Write((byte)0x42); // 'B'
                bw.Write((byte)0x4D); // 'M'
                bw.Write(fileSize);
                bw.Write(new byte[4]); // 保留
                bw.Write(14 + 40 + 8); // 像素数据偏移

                // DIB 信息头 (BITMAPINFOHEADER)
                bw.Write(40); // 头大小
                bw.Write(width);
                bw.Write(-height); // 负数表示从上到下
                bw.Write((short)1); // 颜色平面数
                bw.Write((short)1); // 每像素位数
                bw.Write(0); // 压缩
                bw.Write(sizeImage);
                bw.Write(2835); // X 分辨率
                bw.Write(2835); // Y 分辨率
                bw.Write(2); // 调色板颜色数
                bw.Write(2); // 重要颜色数

                // 调色板 (黑白)
                bw.Write(new byte[] { 0x00, 0x00, 0x00, 0x00 }); // 黑色
                bw.Write(new byte[] { 0xFF, 0xFF, 0xFF, 0x00 }); // 白色

                // 像素数据
                int rawIdx = 0;
                for (int y = 0; y < height; y++)
                {
                    rawIdx++; // 跳过过滤字节
                    int rowStart = (int)ms.Position;
                    int bitPos = 7;
                    byte byteVal = 0;

                    for (int x = 0; x < width; x++)
                    {
                        byte r = scaledData[rawIdx++];
                        byte g = scaledData[rawIdx++];
                        byte b = scaledData[rawIdx++];
                        byte lum = (byte)((r + g + b) / 3);
                        byte bit = lum < 128 ? (byte)0 : (byte)1;

                        if (bit == 1)
                        {
                            byteVal |= (byte)(1 << bitPos);
                        }

                        bitPos--;
                        if (bitPos < 0)
                        {
                            bw.Write(byteVal);
                            byteVal = 0;
                            bitPos = 7;
                        }
                    }

                    if (bitPos != 7)
                    {
                        bw.Write(byteVal);
                    }

                    // 填充到行大小
                    while ((int)ms.Position < rowStart + rowSize)
                    {
                        bw.Write((byte)0);
                    }
                }
            }

            return bmpBytes;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"WeRead: PNG 转 BMP 异常 - {ex.Message}");
            return null;
        }
    }

    private int ReadInt32BigEndian(byte[] data, int offset)
    {
        return (data[offset] << 24) | (data[offset + 1] << 16) | (data[offset + 2] << 8) | data[offset + 3];
    }

    /// <summary>
    /// 缩放 PNG 原始数据（最近邻插值，适合二维码）
    /// </summary>
    private byte[] ScaleImageData(byte[] rawData, int srcWidth, int srcHeight, int dstWidth, int dstHeight)
    {
        // 目标数据：每行 1 字节过滤标记 + dstWidth * 3 字节 RGB
        int dstRowSize = 1 + dstWidth * 3;
        byte[] result = new byte[dstRowSize * dstHeight];

        float xRatio = (float)srcWidth / dstWidth;
        float yRatio = (float)srcHeight / dstHeight;

        for (int dstY = 0; dstY < dstHeight; dstY++)
        {
            int dstRowOffset = dstY * dstRowSize;
            result[dstRowOffset] = 0; // 过滤字节（无过滤）

            int srcY = (int)(dstY * yRatio);
            int srcRowOffset = srcY * (1 + srcWidth * 3); // 源行起始（包含过滤字节）

            for (int dstX = 0; dstX < dstWidth; dstX++)
            {
                int srcX = (int)(dstX * xRatio);
                int srcPixelOffset = srcRowOffset + 1 + srcX * 3; // 跳过过滤字节
                int dstPixelOffset = dstRowOffset + 1 + dstX * 3;

                // 复制 RGB
                result[dstPixelOffset] = rawData[srcPixelOffset];     // R
                result[dstPixelOffset + 1] = rawData[srcPixelOffset + 1]; // G
                result[dstPixelOffset + 2] = rawData[srcPixelOffset + 2]; // B
            }
        }

        return result;
    }

    private byte[] Inflate(byte[] compressed)
    {
        if (compressed.Length < 2)
        {
            throw new Exception("压缩数据太短");
        }

        byte[] data = new byte[compressed.Length - 2];
        Array.Copy(compressed, 2, data, 0, data.Length);

        using var compressedStream = new MemoryStream(data);
        using var deflateStream = new System.IO.Compression.DeflateStream(compressedStream, System.IO.Compression.CompressionMode.Decompress);
        using var resultStream = new MemoryStream();
        deflateStream.CopyTo(resultStream);
        return resultStream.ToArray();
    }
}


