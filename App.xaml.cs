using Microsoft.Extensions.DependencyInjection;
using GSDJX4DoubleSysFserv.Services;

namespace GSDJX4DoubleSysFserv;

public partial class App : Application
{
	public App(IServiceProvider serviceProvider)
	{
		InitializeComponent();
		_serviceProvider = serviceProvider;
	}

	private readonly IServiceProvider _serviceProvider;

	protected override Window CreateWindow(IActivationState? activationState)
	{
		// 启动时尝试自动连接已保存的蓝牙设备
		_ = Task.Run(async () =>
		{
			await Task.Delay(1000); // 等待应用完全初始化
			var bleService = _serviceProvider.GetService<IBleService>();
			if (bleService != null)
			{
				await bleService.TryAutoConnectOnStartupAsync();
			}
		});

		return new Window(new AppShell());
	}
}