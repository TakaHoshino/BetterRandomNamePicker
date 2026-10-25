using System.Windows;
using BetterRNP.Core;
using BetterRNP.Plugins;
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
    private readonly bool _suppressStartup;

    public App()
    {
    }

    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public App(bool suppressStartup)
    {
        _suppressStartup = suppressStartup;
    }

    public AppServices Services { get; private set; } = null!;
    public MainWindow ShellWindow { get; private set; } = null!;
    public PluginManager Plugins => ShellWindow.Plugins;
    public bool IsShuttingDown { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        if (_suppressStartup)
        {
            return;
        }
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
        var cacheCleanResult = PluginCacheService.Clear(Services.Paths);
        Services.Log.Write(
            cacheCleanResult.Success ? LogLevel.Info : LogLevel.Warn,
            $"已清理 {cacheCleanResult.RemovedEntries} 项，失败 {cacheCleanResult.FailedEntries} 项",
            "PLUGIN_CACHE_CLEANED");
        LocalizationService.Initialize(Services.Config.Language);
        LocalizationService.LanguageChanged += OnLanguageChanged;
        Services.Log.Write(
            UpdateService.DetectCurrentInstallationKind() == UpdateAssetKind.SelfContained
                ? "自包含版"
                : "框架依赖版",
            "UPDATE_INSTALL_TYPE");
        Services.Log.Write(
            $"{UpdateService.GetCurrentTag()}｜{Environment.OSVersion.VersionString}｜"
            + $"{(Environment.Is64BitProcess ? "64" : "32")} 位｜数据目录 {Services.Paths.Root}"
            + $"｜设置 {Services.Settings.DisplayPath}",
            "APP_START");
        WireCrashLogging();

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
            exit: () => ExitApplication(true),
            log: message => Services.Log.Write(message, "TRAY"));

        ShellWindow.Show();
        _tray.Install();
        if (Services.Config.AutoShowFloatingBall)
        {
            ShowFloatingBall();
            Services.Log.Write("按设置自动显示悬浮球", "BALL_AUTO_SHOW");
        }
    }

    private void WireCrashLogging()
    {
        DispatcherUnhandledException += (_, args) =>
            Services.Log.WriteException("UNHANDLED_UI_EXCEPTION", args.Exception);

        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            if (args.ExceptionObject is Exception exception)
            {
                Services.Log.WriteException("UNHANDLED_EXCEPTION", exception);
            }
        };

        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            Services.Log.WriteException("UNOBSERVED_TASK_EXCEPTION", args.Exception);
            args.SetObserved();
        };
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
        Services.Log.Write("显示主窗口", "显示主窗口");
    }

    public void HideMainWindow()
    {
        ShellWindow.Hide();
        Services.Log.Write("隐藏主窗口", "隐藏主窗口");
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

    public void UpdateFloatingBallAppearance(int radius, int opacity)
    {
        _floatingBall?.UpdateAppearance(radius, opacity);
    }

    public void ExitApplication(bool confirm)
    {
        if (confirm)
        {
            var result = MessageBox.Show(
                ShellWindow,
                UiText.ExitConfirmationMessage,
                UiText.ExitConfirmationTitle,
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);
            if (result != MessageBoxResult.Yes)
            {
                Services.Log.Write("用户取消退出", "取消退出");
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
        LocalizationService.LanguageChanged -= OnLanguageChanged;
        _instanceGuard?.Dispose();
        _instanceGuard = null;
        base.OnExit(e);
    }

    private void OnLanguageChanged(object? sender, EventArgs e)
    {
        Dispatcher.BeginInvoke(new Action(() =>
        {
            _floatingBall?.RefreshLanguage();
            ShellWindow?.RefreshLanguage();
        }));
    }
}
