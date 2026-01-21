using Android.App;
using Android.Runtime;
using Shiny;

namespace GSDJX4DoubleSysFserv;

[Application]
public class MainApplication : AndroidShinyHost
{
	public MainApplication(IntPtr handle, JniHandleOwnership ownership)
		: base(handle, ownership)
	{
	}

	protected override MauiApp CreateMauiApp() => MauiProgram.CreateMauiApp();
}
