using System.Text.Json;
using System.Text.Json.Serialization;

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

    public string ServerUrl { get; set; } = "http://home.onino.xyz:18008";
    public WeReadState State { get; private set; } = new();

    public WeReadService(IStorageService storageService)
    {
        _storageService = storageService;
        _httpClient = new HttpClient
        {
            Timeout = TimeSpan.FromSeconds(60)
        };
        _jsonOptions = new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true
        };
    }

    public void SetReadingContext(string url, string cookie)
    {
        State.CurrentUrl = url;
        State.Cookie = cookie;
    }

    public async Task<string> GetCurrentPageAsync(string url, string cookie)
    {
        SetReadingContext(url, cookie);
        State.Page = 0;

        var content = await CallWeReadApiAsync("page_current");
        State.LastText = content;

        await SaveStateAsync();
        return content;
    }

    public async Task<string> GetNextPageAsync()
    {
        State.Page++;
        var content = await CallWeReadApiAsync("page_next");
        State.LastText = content;

        await SaveStateAsync();
        return content;
    }

    public async Task<string> GetPrevPageAsync()
    {
        State.Page = Math.Max(0, State.Page - 1);
        var content = await CallWeReadApiAsync("page_prev");
        State.LastText = content;

        await SaveStateAsync();
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
        var httpContent = new StringContent(jsonContent, System.Text.Encoding.UTF8, "application/json");

        System.Diagnostics.Debug.WriteLine($"WeRead API: {action} -> {apiUrl}");
        System.Diagnostics.Debug.WriteLine($"WeRead Request: {jsonContent}");

        var response = await _httpClient.PostAsync(apiUrl, httpContent);
        var responseContent = await response.Content.ReadAsStringAsync();

        System.Diagnostics.Debug.WriteLine($"WeRead Response ({response.StatusCode}): {responseContent.Substring(0, Math.Min(200, responseContent.Length))}...");

        if (!response.IsSuccessStatusCode)
        {
            throw new Exception($"API 请求失败: {response.StatusCode} - {responseContent}");
        }

        var result = JsonSerializer.Deserialize<WeReadResponse>(responseContent, _jsonOptions);

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
}
