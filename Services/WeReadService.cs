using System.Text.Json;
using System.Text.Json.Serialization;
#if IOS
using Foundation;
#endif

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 微信读书 API 响应
/// </summary>
public class WeReadResponse
{
    [JsonPropertyName("success")]
    public bool Success { get; set; }

    [JsonPropertyName("content")]
    public string? Content { get; set; }

    [JsonPropertyName("text")]
    public string? Text { get; set; }

    [JsonPropertyName("url")]
    public string? Url { get; set; }

    [JsonPropertyName("cookie")]
    public string? Cookie { get; set; }

    [JsonPropertyName("error")]
    public string? Error { get; set; }

    /// <summary>
    /// 获取文本内容（优先 content，其次 text）
    /// </summary>
    public string GetText() => Content ?? Text ?? string.Empty;
}

/// <summary>
/// 微信读书阅读状态
/// </summary>
public class WeReadState
{
    public int Page { get; set; }
    public string CurrentUrl { get; set; } = string.Empty;
    public string Cookie { get; set; } = string.Empty;
    public string LastText { get; set; } = string.Empty;
}

/// <summary>
/// 微信读书服务接口
/// </summary>
public interface IWeReadService
{
    /// <summary>
    /// Cookie 容器，用于管理 Cookies
    /// </summary>
    System.Net.CookieContainer Cookies { get; set; }

    /// <summary>
    /// RemoteServe 服务器地址
    /// </summary>
    string ServerUrl { get; set; }

    /// <summary>
    /// 当前阅读状态
    /// </summary>
    WeReadState State { get; }

    /// <summary>
    /// 获取当前页面内容
    /// </summary>
    Task<string> GetCurrentPageAsync(string url, string cookie);

    /// <summary>
    /// 获取下一页内容
    /// </summary>
    Task<string> GetNextPageAsync();

    /// <summary>
    /// 获取上一页内容
    /// </summary>
    Task<string> GetPrevPageAsync();

    /// <summary>
    /// 设置当前 URL 和 Cookie
    /// </summary>
    void SetReadingContext(string url, string cookie);

    /// <summary>
    /// 保存阅读状态到本地存储
    /// </summary>
    Task SaveStateAsync();

    /// <summary>
    /// 从本地存储加载阅读状态
    /// </summary>
    Task LoadStateAsync();

    /// <summary>
    /// 发送当前文本内容到 BLE 设备
    /// </summary>
    Task<bool> SendToDeviceAsync(IBleService bleService);

    /// <summary>
    /// 根据 URL 获取缓存的文本内容
    /// </summary>
    Task<string?> GetCachedContentAsync(string url);

    /// <summary>
    /// 缓存文本内容，使用 URL 作为 key
    /// </summary>
    Task CacheContentAsync(string url, string content);
}

/// <summary>
/// 微信读书服务实现
/// </summary>
public class WeReadService : IWeReadService
{
    private readonly HttpClient _httpClient;
    private readonly IStorageService _storageService;
    private readonly JsonSerializerOptions _jsonOptions;

    private const string StateKey = "WeRead_State";
    private const string CacheKeyPrefix = "WeReadCache_";

    /// <summary>
    /// Cookie 容器，用于管理 Cookies
    /// </summary>
    public System.Net.CookieContainer Cookies { get; set; } = new();

    public string ServerUrl { get; set; } = "https://3gx043ki8112.vicp.fun";
    public WeReadState State { get; private set; } = new();

    public WeReadService(IStorageService storageService)
    {
        _storageService = storageService;
        
        // 创建支持后台的 HttpClient
        _httpClient = CreateBackgroundHttpClient();
        
        _jsonOptions = new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true
        };
    }

    /// <summary>
    /// 创建支持后台运行的 HttpClient
    /// </summary>
    private static HttpClient CreateBackgroundHttpClient()
    {
#if IOS
        // iOS: 使用 NSUrlSession 后台配置
        var config = Foundation.NSUrlSessionConfiguration.DefaultSessionConfiguration;
        config.TimeoutIntervalForRequest = 60;
        config.TimeoutIntervalForResource = 120;
        config.WaitsForConnectivity = true; // 等待网络连接可用
        config.AllowsCellularAccess = true;
        
        var handler = new NSUrlSessionHandler(config);
        return new HttpClient(handler)
        {
            Timeout = TimeSpan.FromSeconds(60)
        };
#else
        // Android/其他平台: 使用默认配置
        var handler = new HttpClientHandler
        {
            // 允许自动重定向
            AllowAutoRedirect = true,
            MaxAutomaticRedirections = 5
        };
        return new HttpClient(handler)
        {
            Timeout = TimeSpan.FromSeconds(60)
        };
#endif
    }

    public void SetReadingContext(string url, string cookie)
    {
        State.CurrentUrl = url;
        State.Cookie = cookie;

        // 将 cookie 字符串解析并添加到 CookieContainer
        if (!string.IsNullOrEmpty(cookie) && !string.IsNullOrEmpty(url))
        {
            try
            {
                var uri = new Uri(url);
                var pairs = cookie.Split(';', StringSplitOptions.RemoveEmptyEntries);
                foreach (var pair in pairs)
                {
                    var trimmed = pair.Trim();
                    var equalIndex = trimmed.IndexOf('=');
                    if (equalIndex > 0)
                    {
                        var name = trimmed[..equalIndex].Trim();
                        var value = trimmed[(equalIndex + 1)..].Trim();
                        var cookieObj = new System.Net.Cookie(name, value, "/", uri.Host);
                        Cookies.Add(cookieObj);
                    }
                }
                System.Diagnostics.Debug.WriteLine($"WeRead: 已将 {pairs.Length} 个 cookies 添加到 CookieContainer");
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"WeRead: 解析 cookie 失败 - {ex.Message}");
            }
        }
    }

    public async Task<string> GetCurrentPageAsync(string url, string cookie)
    {
        SetReadingContext(url, cookie);
        State.Page = 0;

        var content = await CallWeReadApiAsync("page_current");
        State.LastText = content;

        await SaveStateAsync();
        // 缓存内容
        await CacheContentAsync(url, content);
        return content;
    }

    public async Task<string> GetNextPageAsync()
    {
        State.Page++;
        var content = await CallWeReadApiAsync("page_next");
        State.LastText = content;

        await SaveStateAsync();
        // 缓存内容
        await CacheContentAsync(State.CurrentUrl, content);
        return content;
    }

    public async Task<string> GetPrevPageAsync()
    {
        State.Page = Math.Max(0, State.Page - 1);
        var content = await CallWeReadApiAsync("page_prev");
        State.LastText = content;

        await SaveStateAsync();
        // 缓存内容
        await CacheContentAsync(State.CurrentUrl, content);
        return content;
    }

    private async Task<string> CallWeReadApiAsync(string action)
    {
        var apiUrl = ServerUrl.TrimEnd('/') + "/api/weread/reader";

        var requestBody = new
        {
            id = "maui-client",
            cookie = State.Cookie,
            url = State.CurrentUrl,
            action = action
        };

        var jsonContent = JsonSerializer.Serialize(requestBody, _jsonOptions);
        
        System.Diagnostics.Debug.WriteLine($"WeRead API: {action} -> {apiUrl}");
        System.Diagnostics.Debug.WriteLine($"WeRead Request: {jsonContent}");

        // 重试机制：最多重试 3 次
        const int maxRetries = 3;
        Exception? lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++)
        {
            try
            {
                var httpContent = new StringContent(jsonContent, System.Text.Encoding.UTF8, "application/json");

                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
                var response = await _httpClient.PostAsync(apiUrl, httpContent, cts.Token);
                var responseContent = await response.Content.ReadAsStringAsync(cts.Token);

                System.Diagnostics.Debug.WriteLine($"WeRead Response ({response.StatusCode}): {responseContent.Substring(0, Math.Min(200, responseContent.Length))}...");

                if (!response.IsSuccessStatusCode)
                {
                    throw new Exception($"API 请求失败: {response.StatusCode} - {responseContent}");
                }

                // 检查响应是否是 JSON 格式
                if (string.IsNullOrWhiteSpace(responseContent))
                {
                    throw new Exception("服务器返回空响应");
                }

                // 如果响应看起来是 HTML（例如 <!doctype html>），认为是会话初始化/错误页，需重试
                var trimmed = responseContent.TrimStart();
                if (trimmed.StartsWith("<!doctype", StringComparison.OrdinalIgnoreCase) ||
                    trimmed.StartsWith("<html", StringComparison.OrdinalIgnoreCase) ||
                    trimmed.StartsWith("<HTML", StringComparison.Ordinal))
                {
                    var preview = responseContent.Length > 300 ? responseContent.Substring(0, 300) + "..." : responseContent;
                    System.Diagnostics.Debug.WriteLine($"WeRead: 收到 HTML 响应，准备重试 (尝试 {attempt}/{maxRetries})。预览: {preview}");
                    if (attempt < maxRetries)
                    {
                        await Task.Delay(1000 * attempt); // 指数退避
                        continue; // 直接重试，不走异常路径
                    }
                    throw new Exception("服务器多次返回 HTML 页面，已达到最大重试次数");
                }

                WeReadResponse? result = null;
                try
                {
                    result = JsonSerializer.Deserialize<WeReadResponse>(responseContent, _jsonOptions);
                }
                catch (JsonException ex)
                {
                    var preview = responseContent.Length > 300 ? responseContent.Substring(0, 300) + "..." : responseContent;
                    throw new Exception($"JSON 解析失败: {ex.Message}。服务器响应: {preview}");
                }

                if (result == null)
                {
                    throw new Exception("API 响应解析失败");
                }

                if (!result.Success)
                {
                    throw new Exception(result.Error ?? "RemoteServe 返回失败");
                }

                // 更新 Cookie 和 URL（如果服务器返回了新的）
                if (!string.IsNullOrEmpty(result.Cookie))
                {
                    State.Cookie = result.Cookie;
                }

                if (!string.IsNullOrEmpty(result.Url))
                {
                    State.CurrentUrl = result.Url;
                }

                return result.GetText();
            }
            catch (HttpRequestException ex) when (attempt < maxRetries)
            {
                // 网络错误，等待后重试
                lastException = ex;
                System.Diagnostics.Debug.WriteLine($"WeRead: 网络请求失败 (尝试 {attempt}/{maxRetries}): {ex.Message}");
                await Task.Delay(1000 * attempt); // 指数退避
            }
            catch (TaskCanceledException ex) when (attempt < maxRetries)
            {
                // 超时，等待后重试
                lastException = ex;
                System.Diagnostics.Debug.WriteLine($"WeRead: 请求超时 (尝试 {attempt}/{maxRetries})");
                await Task.Delay(1000 * attempt);
            }
            catch (Exception ex) when ((ex.Message.Contains("'<'") || ex.Message.Contains("JSON") || ex.Message.Contains("HTML")) && attempt < maxRetries)
            {
                // JSON/HTML 解析错误，等待后重试（第一次请求可能返回 HTML 用于会话初始化）
                lastException = ex;
                System.Diagnostics.Debug.WriteLine($"WeRead: 响应格式错误，重试中 (尝试 {attempt}/{maxRetries}): {ex.Message}");
                await Task.Delay(1000 * attempt);
            }
            catch (Exception ex) when (ex.Message.Contains("network connection was lost") && attempt < maxRetries)
            {
                // iOS 后台网络断开，等待后重试
                lastException = ex;
                System.Diagnostics.Debug.WriteLine($"WeRead: 网络连接丢失 (尝试 {attempt}/{maxRetries}): {ex.Message}");
                await Task.Delay(2000 * attempt);
            }
        }

        // 所有重试都失败
        throw new Exception($"网络请求失败 (已重试 {maxRetries} 次): {lastException?.Message ?? "未知错误"}");
    }

    public async Task SaveStateAsync()
    {
        try
        {
            var json = JsonSerializer.Serialize(State, _jsonOptions);
            await _storageService.SetAsync(StateKey, json);
            System.Diagnostics.Debug.WriteLine($"WeRead: 状态已保存 - Page={State.Page}");
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"WeRead: 保存状态失败 - {ex.Message}");
        }
    }

    public async Task LoadStateAsync()
    {
        try
        {
            var json = await _storageService.GetAsync<string>(StateKey);
            if (!string.IsNullOrEmpty(json))
            {
                var state = JsonSerializer.Deserialize<WeReadState>(json, _jsonOptions);
                if (state != null)
                {
                    State = state;
                    System.Diagnostics.Debug.WriteLine($"WeRead: 状态已加载 - Page={State.Page}, URL={State.CurrentUrl}");
                }
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"WeRead: 加载状态失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 发送当前文本内容到 BLE 设备
    /// 使用 X4IM v2 协议，自动添加 EOF 标记
    /// </summary>
    /// <param name="bleService">BLE 服务</param>
    /// <returns>是否发送成功</returns>
    public async Task<bool> SendToDeviceAsync(IBleService bleService)
    {
        try
        {
            if (string.IsNullOrEmpty(State.LastText))
            {
                System.Diagnostics.Debug.WriteLine("WeRead: 没有要发送的文本内容");
                return false;
            }

            System.Diagnostics.Debug.WriteLine($"WeRead: 开始发送文本到设备 (页数: {State.Page}, 字符数: {State.LastText.Length})");

            var result = await bleService.SendTextToDeviceAsync(State.LastText, State.Page);

            if (result)
            {
                System.Diagnostics.Debug.WriteLine($"WeRead: 文本发送成功");
            }
            else
            {
                System.Diagnostics.Debug.WriteLine($"WeRead: 文本发送失败");
            }

            return result;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"WeRead: 发送文本异常 - {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 根据 URL 获取缓存的文本内容
    /// </summary>
    public async Task<string?> GetCachedContentAsync(string url)
    {
        try
        {
            var cacheKey = CacheKeyPrefix + Convert.ToHexString(System.Text.Encoding.UTF8.GetBytes(url)).ToLowerInvariant();
            var content = await _storageService.GetAsync<string>(cacheKey);

            if (!string.IsNullOrEmpty(content))
            {
                System.Diagnostics.Debug.WriteLine($"WeRead: 从缓存加载内容 - URL={url}");
                return content;
            }

            System.Diagnostics.Debug.WriteLine($"WeRead: 缓存未命中 - URL={url}");
            return null;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"WeRead: 读取缓存失败 - {ex.Message}");
            return null;
        }
    }

    /// <summary>
    /// 缓存文本内容，使用 URL 作为 key
    /// </summary>
    public async Task CacheContentAsync(string url, string content)
    {
        try
        {
            var cacheKey = CacheKeyPrefix + Convert.ToHexString(System.Text.Encoding.UTF8.GetBytes(url)).ToLowerInvariant();
            await _storageService.SetAsync(cacheKey, content);
            System.Diagnostics.Debug.WriteLine($"WeRead: 内容已缓存 - URL={url}, 长度={content.Length}");
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"WeRead: 缓存内容失败 - {ex.Message}");
        }
    }
}
