using Android.App;
using Android.Content;
using Android.Content.PM;
using Android.OS;
using AndroidX.Core.App;

namespace GSDJX4DoubleSysFserv.Platforms.Android;

/// <summary>
/// Android BLE 前台服务
/// 用于在后台保持 BLE 连接和按键翻页功能
/// 对应 iOS 的 BeginBackgroundTask/EndBackgroundTask 机制
/// </summary>
[Service(Exported = false, ForegroundServiceType = ForegroundService.TypeConnectedDevice)]
public class BleForegroundService : Service
{
    private const string ServiceChannelId = "BleForegroundServiceChannel";
    private const string ServiceChannelName = "BLE 翻页服务";
    private const int NotificationId = 10001;

    public override IBinder? OnBind(Intent? intent)
    {
        return null;
    }

    public override StartCommandResult OnStartCommand(Intent? intent, StartCommandFlags flags, int startId)
    {
        // 创建通知渠道（Android 8.0+ 需要）
        CreateNotificationChannel();

        // 创建前台服务通知
        var notification = new NotificationCompat.Builder(this, ServiceChannelId)
            .SetContentTitle("微信读书翻页服务")
            .SetContentText("正在运行后台翻页服务，保持连接...")
            .SetSmallIcon(17301574)  // Android 系统内置图标 (ic_dialog_info)
            .SetOngoing(true)
            .SetPriority(NotificationCompat.PriorityHigh)
            .SetVisibility(NotificationCompat.VisibilityPublic)
            .Build();

        // 启动前台服务
        StartForeground(NotificationId, notification);

        // 如果服务被杀死，自动重启
        return StartCommandResult.Sticky;
    }

    public override void OnTaskRemoved(Intent? rootIntent)
    {
        // 当应用被移除时，可以选择停止服务
        // 这里保持服务运行以维持后台连接
        base.OnTaskRemoved(rootIntent);
    }

    public override void OnDestroy()
    {
        base.OnDestroy();
        // 清理通知
        var notificationManager = (NotificationManager)GetSystemService(NotificationService);
        notificationManager?.Cancel(NotificationId);
    }

    private void CreateNotificationChannel()
    {
        if (Build.VERSION.SdkInt < BuildVersionCodes.O)
            return;

        var channel = new NotificationChannel(
            ServiceChannelId,
            ServiceChannelName,
            NotificationImportance.High);

        channel.Description = "保持 BLE 连接和后台翻页功能";
        channel.EnableVibration(false);
        channel.SetShowBadge(false);

        var notificationManager = (NotificationManager)GetSystemService(NotificationService);
        notificationManager?.CreateNotificationChannel(channel);
    }

    /// <summary>
    /// 检查 Android 13+ 通知是否启用（前台服务依赖通知）
    /// </summary>
    private static bool IsNotificationEnabledForService(Context context)
    {
        if (Build.VERSION.SdkInt < BuildVersionCodes.Tiramisu)
            return true; // Android 12 及以下无此限制
        
        try
        {
            var notificationManager = (NotificationManager?)context.GetSystemService(Context.NotificationService);
            if (notificationManager == null) return false;

            // 检查系统是否允许通知、渠道是否启用
            return notificationManager.AreNotificationsEnabled();
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// 启动 BLE 前台服务
    /// Android 13+ 需要通知权限才能正常启动
    /// </summary>
    public static void StartService(Context context)
    {
        // Android 13+ 通知检查：前台服务需要通知权限
        if (Build.VERSION.SdkInt >= BuildVersionCodes.Tiramisu && !IsNotificationEnabledForService(context))
        {
            System.Diagnostics.Debug.WriteLine(
                "BleForegroundService: Android 13+ 通知被禁用，前台服务启动可能失败。请在设置中开启应用通知权限。");
            // 在严格 ROM 上（如 vivo），通知禁用会导致 ForegroundServiceStartNotAllowedException
            // 此处返回以避免崩溃，等待用户开启通知后重试连接
            return;
        }

        try
        {
            var intent = new Intent(context, typeof(BleForegroundService));
            if (Build.VERSION.SdkInt >= BuildVersionCodes.O)
            {
                context.StartForegroundService(intent);
            }
            else
            {
                context.StartService(intent);
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine(
                $"BleForegroundService: 启动前台服务异常：{ex.Message}。检查项：1) 蓝牙权限(Android 12+) 2) 通知权限(Android 13+)");
        }
    }

    /// <summary>
    /// 停止 BLE 前台服务
    /// </summary>
    public static void StopService(Context context)
    {
        var intent = new Intent(context, typeof(BleForegroundService));
        context.StopService(intent);
    }
}
