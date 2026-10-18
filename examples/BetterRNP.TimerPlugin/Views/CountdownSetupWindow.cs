using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace BetterRNP.TimerPlugin.Views;

/// <summary>
/// 选择倒计时时长的小弹窗：可以直接用键盘输入时/分/秒，也可以用旁边的
/// 加减按钮或常用时长按钮设置。
/// </summary>
public sealed class CountdownSetupWindow : Window
{
    private static readonly (string Label, int Seconds)[] Presets =
    {
        ("1 分钟", 60),
        ("3 分钟", 180),
        ("5 分钟", 300),
        ("10 分钟", 600)
    };

    private readonly TimeField _hours;
    private readonly TimeField _minutes;
    private readonly TimeField _seconds;
    private readonly TextBlock _error;

    public CountdownSetupWindow()
    {
        Ui.ApplyPopupWindow(this);
        Width = 320;
        SizeToContent = SizeToContent.Height;

        _hours = new TimeField(0, 23, 0);
        _minutes = new TimeField(0, 59, 5);
        _seconds = new TimeField(0, 59, 0);

        var fields = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 6, 0, 0)
        };
        fields.Children.Add(BuildField(_hours, "时"));
        fields.Children.Add(BuildColon());
        fields.Children.Add(BuildField(_minutes, "分"));
        fields.Children.Add(BuildColon());
        fields.Children.Add(BuildField(_seconds, "秒"));

        var presets = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 10, 0, 0)
        };
        foreach (var (label, seconds) in Presets)
        {
            var value = seconds;
            var chip = Ui.TextButton(label, ButtonTone.Chip, () => ApplyPreset(value));
            chip.Margin = new Thickness(3, 0, 3, 0);
            presets.Children.Add(chip);
        }

        _error = new TextBlock
        {
            FontSize = 11,
            Foreground = Ui.Danger,
            HorizontalAlignment = HorizontalAlignment.Center,
            Height = 16,
            Margin = new Thickness(0, 4, 0, 0)
        };

        var footer = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(0, 4, 0, 0)
        };
        var cancel = Ui.TextButton("取消", ButtonTone.Secondary, () => CloseWith(false));
        cancel.Margin = new Thickness(0, 0, 8, 0);
        footer.Children.Add(cancel);
        footer.Children.Add(Ui.TextButton("开始倒计时", ButtonTone.Primary, Confirm));

        var body = new StackPanel();
        body.Children.Add(BuildHeader());
        body.Children.Add(fields);
        body.Children.Add(presets);
        body.Children.Add(_error);
        body.Children.Add(footer);

        var host = Ui.ShadowHost(Ui.Card(body, new Thickness(18, 12, 18, 16)));
        Content = host;
        Ui.EnableDrag(this, host);
        Ui.CenterOnScreen(this);

        PreviewKeyDown += OnPreviewKeyDown;
        Loaded += (_, _) =>
        {
            _minutes.Box.Focus();
            _minutes.Box.SelectAll();
        };
    }

    /// <summary>用户确认后选择的时长；取消时为 <c>null</c>。</summary>
    public TimeSpan? SelectedDuration { get; private set; }

    /// <summary>以模态方式显示，用户确认时返回所选时长。</summary>
    public TimeSpan? PickDuration() => ShowDialog() == true ? SelectedDuration : null;

    private UIElement BuildHeader()
    {
        var title = new TextBlock
        {
            Text = "设置倒计时",
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            Foreground = Ui.Ink,
            VerticalAlignment = VerticalAlignment.Center
        };

        var close = Ui.IconButton(
            Ui.CreateIcon(Icons.Close, IconPaint.Stroke, 9, 1.4),
            "关闭",
            () => CloseWith(false),
            24,
            Ui.CloseHover);
        close.HorizontalAlignment = HorizontalAlignment.Right;
        close.VerticalAlignment = VerticalAlignment.Center;

        var header = new Grid { Height = 24 };
        header.Children.Add(title);
        header.Children.Add(close);
        return header;
    }

    private static FrameworkElement BuildField(TimeField field, string label)
    {
        var chrome = new Border
        {
            Width = 46,
            Background = Ui.FieldFill,
            BorderBrush = Ui.FieldBorder,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(9),
            Padding = new Thickness(1, 3, 1, 3),
            Child = field.Box
        };

        var spinner = new StackPanel
        {
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(3, 0, 0, 0)
        };
        spinner.Children.Add(Ui.IconButton(
            Ui.CreateIcon(Icons.ChevronUp, IconPaint.Stroke, 8, 1.4),
            "增加",
            () => field.Step(1),
            20));
        spinner.Children.Add(Ui.IconButton(
            Ui.CreateIcon(Icons.ChevronDown, IconPaint.Stroke, 8, 1.4),
            "减少",
            () => field.Step(-1),
            20));

        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(chrome);
        row.Children.Add(spinner);

        var caption = new TextBlock
        {
            Text = label,
            FontSize = 11,
            Foreground = Ui.Subtle,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 4, 23, 0)
        };

        var stack = new StackPanel();
        stack.Children.Add(row);
        stack.Children.Add(caption);
        return stack;
    }

    private static TextBlock BuildColon() => new()
    {
        Text = ":",
        FontSize = 20,
        Foreground = Ui.Recess,
        VerticalAlignment = VerticalAlignment.Top,
        Margin = new Thickness(6, 7, 6, 0)
    };

    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            Confirm();
            e.Handled = true;
        }
        else if (e.Key == Key.Escape)
        {
            CloseWith(false);
            e.Handled = true;
        }
    }

    private void ApplyPreset(int totalSeconds)
    {
        _hours.SetValue(totalSeconds / 3600);
        _minutes.SetValue(totalSeconds / 60 % 60);
        _seconds.SetValue(totalSeconds % 60);
        _error.Text = string.Empty;
    }

    private void Confirm()
    {
        var total = new TimeSpan(_hours.Commit(), _minutes.Commit(), _seconds.Commit());
        if (total <= TimeSpan.Zero)
        {
            _error.Text = "请输入大于 0 的倒计时时长";
            _minutes.Box.Focus();
            _minutes.Box.SelectAll();
            return;
        }

        SelectedDuration = total;
        CloseWith(true);
    }

    private void CloseWith(bool result)
    {
        try
        {
            // ShowDialog 显示时用 DialogResult 结束模态循环。
            DialogResult = result;
        }
        catch (InvalidOperationException)
        {
            // 非模态显示时 DialogResult 不可写，直接关闭窗口。
            Close();
        }
    }

    /// <summary>时/分/秒输入框：限制为两位数字，失焦时补零并夹到有效区间。</summary>
    private sealed class TimeField
    {
        private readonly int _min;
        private readonly int _max;

        public TimeField(int min, int max, int initial)
        {
            _min = min;
            _max = max;

            Box = new TextBox
            {
                Text = Math.Clamp(initial, min, max).ToString("00"),
                Width = 42,
                Height = 34,
                MaxLength = 2,
                FontFamily = new FontFamily("Consolas"),
                FontSize = 20,
                Foreground = Ui.Ink,
                CaretBrush = Ui.Accent,
                TextAlignment = TextAlignment.Center,
                VerticalContentAlignment = VerticalAlignment.Center,
                Background = Brushes.Transparent,
                BorderThickness = new Thickness(0),
                Padding = new Thickness(0)
            };
            Value = Math.Clamp(initial, min, max);

            Box.PreviewTextInput += (_, e) =>
            {
                foreach (var ch in e.Text)
                {
                    if (!char.IsAsciiDigit(ch))
                    {
                        e.Handled = true;
                        return;
                    }
                }
            };
            Box.PreviewKeyDown += (_, e) =>
            {
                if (e.Key == Key.Up)
                {
                    Step(1);
                    e.Handled = true;
                }
                else if (e.Key == Key.Down)
                {
                    Step(-1);
                    e.Handled = true;
                }
            };
            DataObject.AddPastingHandler(Box, (_, e) =>
            {
                var text = e.DataObject.GetData(DataFormats.UnicodeText) as string;
                if (text is null || !text.All(char.IsAsciiDigit))
                {
                    e.CancelCommand();
                }
            });
            Box.GotKeyboardFocus += (_, _) => Box.SelectAll();
            Box.LostKeyboardFocus += (_, _) => Commit();
        }

        public TextBox Box { get; }

        public int Value { get; private set; }

        public void SetValue(int value)
        {
            Value = Math.Clamp(value, _min, _max);
            Box.Text = Value.ToString("00");
        }

        public void Step(int delta) => SetValue(ReadText() + delta);

        public int Commit()
        {
            SetValue(ReadText());
            return Value;
        }

        private int ReadText() => int.TryParse(Box.Text, out var parsed) ? parsed : Value;
    }
}
