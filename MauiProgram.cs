using Microsoft.Extensions.Logging;
using Shiny;
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

        // 注册 Shiny.NET BluetoothLE (3.x API)
        builder.Services.AddBluetoothLE<ShinyBleDelegate>();

        // 注册自定义服务
        builder.Services.AddSingleton<IStorageService, StorageService>();

        // 注册蓝牙服务（统一使用 Shiny 实现）
        builder.Services.AddSingleton<IBleService, ShinyBleService>();

        // 注册页面
        builder.Services.AddTransient<Views.WeReadPage>();
        builder.Services.AddTransient<Views.SettingsPage>();
        builder.Services.AddTransient<Views.BleDevicesPage>();

#if DEBUG
        builder.Logging.AddDebug();
#endif

        return builder.Build();
    }
}
