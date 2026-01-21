using System.Text.Json;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 存储服务接口
/// </summary>
public interface IStorageService
{
    Task<T?> GetAsync<T>(string key);
    Task SetAsync<T>(string key, T value);
    Task RemoveAsync(string key);
}

/// <summary>
/// Preferences 存储实现
/// </summary>
public class StorageService : IStorageService
{
    public Task<T?> GetAsync<T>(string key)
    {
        try
        {
            var value = Preferences.Get(key, string.Empty);
            if (string.IsNullOrEmpty(value))
                return Task.FromResult<T?>(default);

            var result = JsonSerializer.Deserialize<T>(value);
            return Task.FromResult(result);
        }
        catch
        {
            return Task.FromResult<T?>(default);
        }
    }

    public Task SetAsync<T>(string key, T value)
    {
        var json = JsonSerializer.Serialize(value);
        Preferences.Set(key, json);
        return Task.CompletedTask;
    }

    public Task RemoveAsync(string key)
    {
        Preferences.Remove(key);
        return Task.CompletedTask;
    }
}
