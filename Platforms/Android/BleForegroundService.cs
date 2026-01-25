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
[Service(Exported = false)]
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
    /// 启动 BLE 前台服务
    /// </summary>
    public static void StartService(Context context)
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

    /// <summary>
    /// 停止 BLE 前台服务
    /// </summary>
    public static void StopService(Context context)
    {
        var intent = new Intent(context, typeof(BleForegroundService));
        context.StopService(intent);
    }
}
