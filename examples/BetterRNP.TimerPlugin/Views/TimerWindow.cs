using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using BetterRNP.TimerPlugin.Timing;

namespace BetterRNP.TimerPlugin.Views;

/// <summary>
/// 无标题栏、强制置顶的半透明白色计时弹窗。正计时与倒计时共用同一套外观，
/// 倒计时进入最后 10 秒时时间文字变为红色。
/// </summary>
public sealed class TimerWindow : Window
{
    private readonly TimerEngine _engine;
    private readonly TextBlock _timeText;
    private readonly Path _primaryGlyph;
    private readonly Button _primaryButton;
    private readonly Button _resetButton;
    private readonly DispatcherTimer _ticker;
    private readonly Action<string>? _log;

    public TimerWindow(TimerMode mode, TimeSpan duration, Action<string>? log = null)
    {
        _engine = new TimerEngine(mode, duration);
        _log = log;

        Ui.ApplyPopupWindow(this);
        Width = 236;
        SizeToContent = SizeToContent.Height;

        _timeText = new TextBlock
        {
            Text = _engine.DisplayText,
            FontFamily = new FontFamily("Segoe UI"),
            FontSize = 44,
            FontWeight = FontWeights.SemiBold,
            Foreground = Ui.Ink,
            HorizontalAlignment = HorizontalAlignment.Center,
            TextAlignment = TextAlignment.Center,
            Margin = new Thickness(0, 2, 0, 10)
        };
        // 等宽数字，保证倒计时跳动时整体不左右抖动。
        Typography.SetNumeralAlignment(_timeText, FontNumeralAlignment.Tabular);

        _primaryGlyph = Ui.CreateIcon(Icons.Play, IconPaint.Fill, 15);
        _primaryButton = Ui.IconButton(_primaryGlyph, "开始", Toggle);
        _resetButton = Ui.IconButton(Ui.CreateIcon(Icons.Reset, IconPaint.FillAndStroke, 17), "重置", ResetTimer);
        _resetButton.Margin = new Thickness(6, 0, 0, 0);
        _resetButton.Visibility = Visibility.Collapsed;

        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center
        };
        buttons.Children.Add(_primaryButton);
        buttons.Children.Add(_resetButton);

        var body = new StackPanel();
        body.Children.Add(BuildHeader(mode));
        body.Children.Add(_timeText);
        body.Children.Add(buttons);

        var host = Ui.ShadowHost(Ui.Card(body, new Thickness(16, 10, 16, 14)));
        Content = host;
        Ui.EnableDrag(this, host);

        _ticker = new DispatcherTimer(DispatcherPriority.Render)
        {
            Interval = TimeSpan.FromMilliseconds(100)
        };
        _ticker.Tick += OnTick;

        Closed += (_, _) => _ticker.Stop();
        Ui.CenterOnScreen(this);
        Render();
    }

    /// <summary>立即开始计时（倒计时选好时长后直接走这一步）。</summary>
    public void BeginTiming()
    {
        if (_engine.State is not (TimerState.Idle or TimerState.Paused))
        {
            return;
        }
        _engine.Start();
        _ticker.Start();
        Render();
        Log($"{DescribeMode()}开始（{_engine.DisplayText}）");
    }

    private UIElement BuildHeader(TimerMode mode)
    {
        var title = new TextBlock
        {
            Text = mode == TimerMode.CountUp ? "正计时" : "倒计时",
            FontSize = 11,
            Foreground = Ui.Subtle,
            VerticalAlignment = VerticalAlignment.Center
        };

        var close = Ui.IconButton(
            Ui.CreateIcon(Icons.Close, IconPaint.Stroke, 9, 1.4),
            "关闭",
            Close,
            24,
            Ui.CloseHover);
        close.HorizontalAlignment = HorizontalAlignment.Right;
        close.VerticalAlignment = VerticalAlignment.Center;

        var header = new Grid { Height = 24, Margin = new Thickness(0, 0, 0, 2) };
        header.Children.Add(title);
        header.Children.Add(close);
        return header;
    }

    private void Toggle()
    {
        if (_engine.State == TimerState.Running)
        {
            _engine.Pause();
            _ticker.Stop();
            Log($"暂停（{_engine.DisplayText}）");
        }
        else
        {
            var resuming = _engine.State == TimerState.Paused;
            _engine.Start();
            _ticker.Start();
            Log(resuming
                ? $"继续（{_engine.DisplayText}）"
                : $"{DescribeMode()}开始（{_engine.DisplayText}）");
        }
        Render();
    }

    private void ResetTimer()
    {
        _ticker.Stop();
        _engine.Reset();
        Render();
        Log($"重置（{_engine.DisplayText}）");
    }

    private void OnTick(object? sender, EventArgs e)
    {
        var finished = _engine.Tick();
        Render();
        if (!finished)
        {
            return;
        }

        _ticker.Stop();
        Log("倒计时结束");
        MessageBox.Show(
            this,
            "倒计时结束。",
            "倒计时",
            MessageBoxButton.OK,
            MessageBoxImage.Information);
    }

    private string DescribeMode() => _engine.Mode == TimerMode.CountDown ? "倒计时" : "正计时";

    private void Log(string message) => _log?.Invoke(message);

    private void Render()
    {
        _timeText.Text = _engine.DisplayText;
        _timeText.Foreground = _engine.IsWarning ? Ui.Danger : Ui.Ink;
        // H:MM:SS 比 MM:SS 长，缩小一号避免撑出卡片内边距。
        _timeText.FontSize = _engine.DisplayText.Length > 5 ? 36 : 44;

        var running = _engine.State == TimerState.Running;
        _primaryGlyph.Data = running ? Icons.Pause : Icons.Play;

        var caption = _engine.State switch
        {
            TimerState.Running => "暂停",
            TimerState.Paused => "继续",
            TimerState.Finished => "重新开始",
            _ => "开始"
        };
        _primaryButton.ToolTip = caption;
        AutomationProperties.SetName(_primaryButton, caption);

        // 暂停后才出现“重置”，符合“暂停时在旁边显示重置按钮”的交互。
        _resetButton.Visibility = _engine.State is TimerState.Paused or TimerState.Finished
            ? Visibility.Visible
            : Visibility.Collapsed;
    }
}
