using BetterRNP.PluginSdk.Abstractions;
using BetterRNP.PluginSdk.Models;
using BetterRNP.TimerPlugin.Timing;
using BetterRNP.TimerPlugin.Views;
using System.Windows.Threading;

namespace BetterRNP.TimerPlugin;

/// <summary>
/// 示例插件：在悬浮球右键菜单里加入“正计时”和“倒计时”，
/// 点击后弹出无标题栏、置顶、半透明白色的计时小窗。
/// </summary>
public sealed class TimerPlugin : IPlugin
{
    private IPluginContext? _context;
    private IPluginLocalizationContext? _localization;
    private TimerWindow? _countUp;
    private TimerWindow? _countDown;
    private DispatcherTimer? _settingsSaveTimer;
    private double _timerTransparencyPercent;

    public PluginManifest GetManifest() => new()
    {
        Id = "com.betterrnp.timer",
        Name = "BetterRNP 计时插件",
        Version = "1.1.0",
        Author = "BetterRNP",
        Type = PluginType.Command,
        Assembly = "BetterRNP.TimerPlugin.dll",
        EntryType = typeof(TimerPlugin).FullName!,
        ApiVersion = PluginApiVersion.Current,
        Description = "在悬浮球右键菜单中提供正计时与倒计时弹窗。"
    };

    public void OnLoad(IPluginContext context)
    {
        _context = context;
        if (context is IPluginLocalizationContext localization)
        {
            _localization = localization;
            localization.RegisterTimerTranslations();
            localization.RegisterLocalizedFloatingBallCommand(
                "count-up",
                () => _localization.T("正计时"),
                ShowCountUp);
            localization.RegisterLocalizedFloatingBallCommand(
                "count-down",
                () => _localization.T("倒计时"),
                ShowCountDown);
        }
        else
        {
            context.RegisterFloatingBallCommand("count-up", "正计时", ShowCountUp);
            context.RegisterFloatingBallCommand("count-down", "倒计时", ShowCountDown);
        }
        _timerTransparencyPercent = TimerPreferences.LoadTransparencyPercent();
        _settingsSaveTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(300)
        };
        _settingsSaveTimer.Tick += (_, _) =>
        {
            _settingsSaveTimer.Stop();
            TimerPreferences.SaveTransparencyPercent(_timerTransparencyPercent);
        };
        if (context is IPluginSettingsContext settings)
        {
            if (_localization is not null)
            {
                _localization.RegisterLocalizedSettingsItem(
                    "timer-appearance",
                    () => _localization.T("计时插件设置"),
                    () => new TimerSettingsView(
                        _localization,
                        _timerTransparencyPercent,
                        SetTimerTransparency));
            }
            else
            {
                settings.RegisterSettingsItem(
                    "timer-appearance",
                    "计时插件设置",
                    () => new TimerSettingsView(
                        null,
                        _timerTransparencyPercent,
                        SetTimerTransparency));
            }
        }
        context.Log("计时插件已加载");
    }

    public void OnUnload()
    {
        CloseWindow(ref _countUp);
        CloseWindow(ref _countDown);
        _settingsSaveTimer?.Stop();
        TimerPreferences.SaveTransparencyPercent(_timerTransparencyPercent);
        _settingsSaveTimer = null;
        _context?.Log("计时插件已卸载");
        _context = null;
        _localization = null;
    }

    private void ShowCountUp()
    {
        if (_countUp is { } existing)
        {
            existing.Activate();
            return;
        }

        _countUp = CreateWindow(TimerMode.CountUp, TimeSpan.Zero);
        _countUp.Show();
        _context?.Log("打开正计时窗口");
    }

    private void ShowCountDown()
    {
        var duration = new CountdownSetupWindow(_localization).PickDuration();
        if (duration is not { } selected)
        {
            return;
        }

        CloseWindow(ref _countDown);
        _countDown = CreateWindow(TimerMode.CountDown, selected);
        _countDown.Show();
        _context?.Log($"打开倒计时窗口（{TimerEngine.FormatTime(selected)}）");
        _countDown.BeginTiming();
    }

    private TimerWindow CreateWindow(TimerMode mode, TimeSpan duration)
    {
        var window = new TimerWindow(
            mode,
            duration,
            message => _context?.Log(message),
            _localization,
            _timerTransparencyPercent);
        window.Closed += (_, _) =>
        {
            if (ReferenceEquals(_countUp, window))
            {
                _countUp = null;
            }
            if (ReferenceEquals(_countDown, window))
            {
                _countDown = null;
            }
        };
        return window;
    }

    private static void CloseWindow(ref TimerWindow? window)
    {
        var target = window;
        window = null;
        target?.Close();
    }

    private void SetTimerTransparency(double value)
    {
        _timerTransparencyPercent = Math.Clamp(value, 0, 80);
        _countUp?.SetTransparencyPercent(_timerTransparencyPercent);
        _countDown?.SetTransparencyPercent(_timerTransparencyPercent);
        _settingsSaveTimer?.Stop();
        _settingsSaveTimer?.Start();
    }
}
