using Microsoft.Extensions.Logging;
using GSDJX4DoubleSysFserv.Services;

namespace GSDJX4DoubleSysFserv;

public static class MauiProgram
{
    public static MauiApp CreateMauiApp()
    {
        var builder = MauiApp.CreateBuilder();
        builder
            .UseMauiApp<App>()
            .ConfigureFonts(fonts =>
            {
                fonts.AddFont("OpenSans-Regular.ttf", "OpenSansRegular");
                fonts.AddFont("OpenSans-Semibold.ttf", "OpenSansSemibold");
            });

        // 注册服务
        builder.Services.AddSingleton<IStorageService, StorageService>();

        // 根据平台注册蓝牙服务
#if ANDROID
        builder.Services.AddSingleton<IBleService, BleServiceAndroid>();
#elif IOS || MACCATALYST
        builder.Services.AddSingleton<IBleService, BleServiceApple>();
#else
        builder.Services.AddSingleton<IBleService, BleServiceAndroid>();
#endif

        // 注册页面
        builder.Services.AddTransient<Views.WeReadPage>();
        builder.Services.AddTransient<Views.SettingsPage>();

#if DEBUG
        builder.Logging.AddDebug();
#endif

        return builder.Build();
    }
}
