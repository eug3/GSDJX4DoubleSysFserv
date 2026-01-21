namespace GSDJX4DoubleSysFserv;

public partial class AppShell : Shell
{
	public AppShell()
	{
		InitializeComponent();

		// 注册页面路由
		Routing.RegisterRoute("EPDReadingPage", typeof(Views.EPDReadingPage));
	}
}
