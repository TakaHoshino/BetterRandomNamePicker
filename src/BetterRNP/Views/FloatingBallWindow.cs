using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using BetterRNP.Core;
using BetterRNP.Modes;

namespace BetterRNP.Views;

public sealed class FloatingBallWindow : Window
{
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

        var workArea = SystemParameters.WorkArea;
        Left = Math.Max(0, workArea.Right - Width - 50);
        Top = Math.Max(0, workArea.Bottom - Height - 50);

        MouseLeftButtonDown += OnMouseLeftButtonDown;
        Closed += (_, _) =>
        {
            _picker?.Stop();
            _picker = null;
        };
    }

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

        menu.Items.Add(new Separator());
        var settings = new MenuItem { Header = "悬浮球设置" };
        settings.Click += (_, _) => _app.ShellWindow.ShowSettingsWindow();
        menu.Items.Add(settings);
        menu.Items.Add(new Separator());
        var close = new MenuItem { Header = "关闭" };
        close.Click += (_, _) => Close();
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
            _label.Text = "无方案";
            return;
        }

        var mode = _app.ShellWindow.BuildCurrentMode();
        if (mode is null)
        {
            _label.Text = "无效模式";
            return;
        }

        var block = mode.CanPick();
        if (block is not null)
        {
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
                _services.Log.Write($"{scheme.Name}-{mode.DisplayName}={result}", "悬浮球抽取结果");
            });
        _picker.Start();
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
}
