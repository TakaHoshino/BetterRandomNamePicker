using System.Windows;
using BetterRNP.Core;
using BetterRNP.Views;

namespace BetterRNP;

public partial class App : Application
{
    private const string InstanceMutexName =
        @"Local\BetterRNP.SingleInstance.8D56DF47D9514C0A8BF0A6475E0C711B";
    private const string InstanceShowEventName =
        @"Local\BetterRNP.SingleInstance.Show.8D56DF47D9514C0A8BF0A6475E0C711B";

    private SingleInstanceGuard? _instanceGuard;
    private FloatingBallWindow? _floatingBall;
    private NativeTrayService? _tray;

    public AppServices Services { get; private set; } = null!;
    public MainWindow ShellWindow { get; private set; } = null!;
    public bool IsShuttingDown { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        _instanceGuard = new SingleInstanceGuard(InstanceMutexName, InstanceShowEventName);
        if (!_instanceGuard.IsPrimary)
        {
            _instanceGuard.SignalExisting();
            _instanceGuard.Dispose();
            _instanceGuard = null;
            Shutdown();
            return;
        }
        _instanceGuard.StartListening(
            () => Dispatcher.BeginInvoke(new Action(ShowMainWindow)));

        Services = AppServices.Create();
        Services.Log.Write(
            UpdateService.DetectCurrentInstallationKind() == UpdateAssetKind.SelfContained
                ? "自包含版"
                : "框架依赖版",
            "UPDATE_INSTALL_TYPE");

        if (Services.Config.AutoStart)
        {
            StartupRegistration.SetEnabled(true);
        }

        ShellWindow = new MainWindow(Services);
        MainWindow = ShellWindow;
        _tray = new NativeTrayService(
            showMain: ShowMainWindow,
            hideMain: HideMainWindow,
            toggleBall: ToggleFloatingBall,
            exit: () => ExitApplication(true));
        _tray.Install();

        ShellWindow.Show();
        if (Services.Config.AutoShowFloatingBall)
        {
            ShowFloatingBall();
            Services.Log.Write("按设置自动显示悬浮球", "BALL_AUTO_SHOW");
        }
    }

    public void ShowMainWindow()
    {
        if (ShellWindow is null)
        {
            return;
        }
        ShellWindow.Show();
        if (ShellWindow.WindowState == WindowState.Minimized)
        {
            ShellWindow.WindowState = WindowState.Normal;
        }
        ShellWindow.Activate();
        ShellWindow.Topmost = true;
        ShellWindow.Topmost = false;
        ShellWindow.Focus();
    }

    public void HideMainWindow()
    {
        ShellWindow.Hide();
    }

    public void ToggleFloatingBall()
    {
        if (_floatingBall is { IsVisible: true })
        {
            HideFloatingBall();
        }
        else
        {
            ShowFloatingBall();
        }
    }

    public void ShowFloatingBall()
    {
        HideFloatingBall();
        _floatingBall = new FloatingBallWindow(Services, this);
        _floatingBall.Closed += (_, _) =>
        {
            _floatingBall = null;
            ShellWindow.RefreshFloatingBallButton();
        };
        _floatingBall.Show();
        ShellWindow.RefreshFloatingBallButton();
        Services.Log.Write("悬浮球已显示", "BALL_SHOWN");
    }

    public void HideFloatingBall()
    {
        if (_floatingBall is null)
        {
            return;
        }
        var ball = _floatingBall;
        _floatingBall = null;
        ball.Close();
        ShellWindow.RefreshFloatingBallButton();
        Services.Log.Write("悬浮球已隐藏", "BALL_HIDDEN");
    }

    public bool IsFloatingBallVisible => _floatingBall is { IsVisible: true };

    public void ExitApplication(bool confirm)
    {
        if (confirm)
        {
            var result = MessageBox.Show(
                ShellWindow,
                "确定要完全退出 BetterRNP 吗？\n退出后悬浮球也将关闭。",
                "确认退出",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);
            if (result != MessageBoxResult.Yes)
            {
                return;
            }
        }

        IsShuttingDown = true;
        HideFloatingBall();
        ShellWindow.ShutdownPlugins();
        _tray?.Dispose();
        _tray = null;
        Services.Log.Write("程序退出", "APP_EXIT");
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _instanceGuard?.Dispose();
        _instanceGuard = null;
        base.OnExit(e);
    }
}
