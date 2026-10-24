using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using System.Runtime.InteropServices;
using BetterRNP.Core;
using BetterRNP.Modes;

namespace BetterRNP.Views;

public sealed class FloatingBallWindow : Window
{
    private const int GWL_EXSTYLE = -20;
    private const long WS_EX_TOOLWINDOW = 0x00000080;
    private const long WS_EX_APPWINDOW = 0x00040000;
    private const long WS_EX_NOACTIVATE = 0x08000000;

    private readonly AppServices _services;
    private readonly App _app;
    private readonly TextBlock _label;
    private RollingPicker? _picker;

    public FloatingBallWindow(AppServices services, App app)
    {
        _services = services;
        _app = app;
        var radius = services.Config.FloatingBallRadius;
        var opacity = services.Config.FloatingBallOpacity;
        var fontSize = Math.Clamp((int)(radius * 0.4), 12, 36);

        Width = radius * 2;
        Height = radius * 2;
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        AllowsTransparency = true;
        ShowActivated = false;
        Background = Brushes.Transparent;
        ShowInTaskbar = false;
        Topmost = true;
        WindowStartupLocation = WindowStartupLocation.Manual;

        _label = new TextBlock
        {
            Text = "抽取",
            Foreground = Brushes.White,
            FontSize = fontSize,
            FontWeight = FontWeights.Bold,
            TextAlignment = TextAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            TextTrimming = TextTrimming.CharacterEllipsis
        };
        Content = new Border
        {
            Background = new SolidColorBrush(Color.FromArgb((byte)opacity, 0, 120, 215)),
            CornerRadius = new CornerRadius(radius),
            Padding = new Thickness(10),
            Child = _label
        };
        ContextMenu = BuildContextMenu();
        _app.Plugins.PluginsChanged += OnPluginsChanged;

        var workArea = SystemParameters.WorkArea;
        Left = Math.Max(0, workArea.Right - Width - 50);
        Top = Math.Max(0, workArea.Bottom - Height - 50);

        MouseLeftButtonDown += OnMouseLeftButtonDown;
        SourceInitialized += (_, _) => HideFromAltTab();
        Closed += (_, _) =>
        {
            _app.Plugins.PluginsChanged -= OnPluginsChanged;
            _picker?.Stop();
            _picker = null;
        };
    }

    private void HideFromAltTab()
    {
        var handle = new WindowInteropHelper(this).Handle;
        if (handle == IntPtr.Zero)
        {
            return;
        }

        var extendedStyle = GetWindowLongPtr(handle, GWL_EXSTYLE).ToInt64();
        extendedStyle |= WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE;
        extendedStyle &= ~WS_EX_APPWINDOW;
        SetWindowLongPtr(handle, GWL_EXSTYLE, new IntPtr(extendedStyle));
    }

    private void OnPluginsChanged(object? sender, EventArgs e)
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(new Action(RefreshContextMenu));
            return;
        }
        RefreshContextMenu();
    }

    private void RefreshContextMenu() => ContextMenu = BuildContextMenu();

    private ContextMenu BuildContextMenu()
    {
        var menu = new ContextMenu();
        var pick = new MenuItem { Header = "随机抽取" };
        pick.Click += (_, _) => PerformRandomPick();
        menu.Items.Add(pick);

        var mode = _app.ShellWindow.BuildCurrentMode();
        if (mode is not null)
        {
            var config = new MenuItem
            {
                Header = mode.Id switch
                {
                    "name_list" => "配置名单",
                    "number" => "数字设置",
                    "seat" => "座位设置",
                    _ => "模式设置"
                }
            };
            config.Click += (_, _) =>
            {
                switch (mode.Id)
                {
                    case "name_list":
                        _app.ShellWindow.ShowNameConfigWindow();
                        break;
                    case "number":
                        _app.ShellWindow.ShowNumberSettingsWindow();
                        break;
                    case "seat":
                        _app.ShellWindow.ShowSeatSettingsWindow();
                        break;
                }
            };
            menu.Items.Add(config);
        }

        var ballCommands = _app.Plugins.FloatingBallCommands;
        if (ballCommands.Count > 0)
        {
            menu.Items.Add(new Separator());
            foreach (var command in ballCommands)
            {
                var item = new MenuItem { Header = command.Title };
                item.Click += (_, _) => _app.Plugins.ExecuteCommand(command, this);
                menu.Items.Add(item);
            }
        }

        menu.Items.Add(new Separator());
        var settings = new MenuItem { Header = "悬浮球设置" };
        settings.Click += (_, _) => _app.ShellWindow.ShowSettingsWindow();
        menu.Items.Add(settings);
        menu.Items.Add(new Separator());
        var close = new MenuItem { Header = "关闭" };
        close.Click += (_, _) =>
        {
            _services.Log.Write("通过悬浮球菜单关闭悬浮球", "BALL_HIDDEN");
            Close();
        };
        menu.Items.Add(close);
        return menu;
    }

    private void OnMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ClickCount == 2)
        {
            PerformRandomPick();
            return;
        }
        try
        {
            DragMove();
        }
        catch (InvalidOperationException)
        {
            // DragMove can race with another mouse capture; ignore.
        }
    }

    private void PerformRandomPick()
    {
        var scheme = _app.ShellWindow.SelectedScheme;
        if (scheme is null)
        {
            _services.Log.Write(LogLevel.Warn, "当前没有可用方案", "悬浮球抽取失败");
            _label.Text = "无方案";
            return;
        }

        var mode = _app.ShellWindow.BuildCurrentMode();
        if (mode is null)
        {
            _services.Log.Write(LogLevel.Warn, $"方案={scheme.Name}", "悬浮球模式无效");
            _label.Text = "无效模式";
            return;
        }

        var block = mode.CanPick();
        if (block is not null)
        {
            _services.Log.Write(
                LogLevel.Warn,
                $"方案={scheme.Name}｜模式={mode.DisplayName}｜原因={block}",
                "悬浮球抽取受阻");
            _label.Text = mode.Id switch
            {
                "name_list" => "损坏",
                "number" => "无范围",
                "seat" => "无座位",
                _ => block
            };
            return;
        }

        _picker?.Stop();
        _picker = new RollingPicker(
            mode.CreateCandidateSupplier(),
            value => _label.Text = FormatForBall(mode.Id, value),
            maxTicks: 20,
            autoStopped: () =>
            {
                var result = _picker?.LastValue ?? string.Empty;
                _services.Log.Write(
                    $"方案={scheme.Name}｜模式={mode.DisplayName}｜结果={result}", "悬浮球抽取结果");
                RestorePresentationFocus();
            });
        _picker.Start();
    }

    private void RestorePresentationFocus()
    {
        if (Application.Current is App { IsShuttingDown: true })
        {
            return;
        }

        Dispatcher.BeginInvoke(new Action(() =>
        {
            if (PresentationFocusService.TryRestore())
            {
                _services.Log.Write("已恢复演示文稿放映窗口焦点", "PPT_FOCUS");
            }
        }), DispatcherPriority.Background);
    }

    private static string FormatForBall(string modeId, string value)
    {
        if (modeId == "name_list" && value.Length > 5)
        {
            return value[..5] + "...";
        }
        if (modeId == "seat")
        {
            return value.Replace(", ", ",");
        }
        return value;
    }

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
    private static extern IntPtr GetWindowLongPtr(IntPtr window, int index);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
    private static extern IntPtr SetWindowLongPtr(IntPtr window, int index, IntPtr newValue);
}
