using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Shapes;

namespace BetterRNP.TimerPlugin.Views;

/// <summary>图标是用填充、描边还是两者同时绘制。</summary>
internal enum IconPaint
{
    Fill,
    Stroke,
    FillAndStroke
}

/// <summary>文字按钮的配色。</summary>
internal enum ButtonTone
{
    Primary,
    Secondary,
    Chip
}

/// <summary>计时窗口与时间选择窗口共用的外观工具。</summary>
internal static class Ui
{
    public static readonly Brush Ink = Solid(0xFF, 0, 0, 0);
    public static readonly Brush Subtle = Solid(0xFF, 0x8A, 0x8A, 0x8A);
    public static readonly Brush Recess = Solid(0xFF, 0x6B, 0x6B, 0x6B);
    public static readonly Brush Panel = Solid(0xE6, 0xFF, 0xFF, 0xFF);
    public static readonly Brush Hover = Solid(0x16, 0, 0, 0);
    public static readonly Brush CloseHover = Solid(0x22, 0xC6, 0x28, 0x28);
    public static readonly Brush Accent = Solid(0xFF, 0x00, 0x78, 0xD7);
    public static readonly Brush AccentHover = Solid(0xFF, 0x00, 0x62, 0xAE);
    public static readonly Brush Danger = Solid(0xFF, 0xD9, 0x2D, 0x2D);
    public static readonly Brush FieldFill = Solid(0x0F, 0, 0, 0);
    public static readonly Brush FieldBorder = Solid(0x26, 0, 0, 0);
    public static readonly Brush QuietFill = Solid(0x14, 0, 0, 0);
    public static readonly Brush QuietHover = Solid(0x24, 0, 0, 0);

    /// <summary>无标题栏、置顶、透明背景，窗口外形完全由内容决定。</summary>
    public static void ApplyPopupWindow(Window window)
    {
        window.WindowStyle = WindowStyle.None;
        window.ResizeMode = ResizeMode.NoResize;
        window.AllowsTransparency = true;
        window.Background = Brushes.Transparent;
        window.ShowInTaskbar = false;
        window.Topmost = true;
        window.WindowStartupLocation = WindowStartupLocation.Manual;
        window.UseLayoutRounding = true;
        window.SnapsToDevicePixels = true;
        TextOptions.SetTextFormattingMode(window, TextFormattingMode.Display);
    }

    /// <summary>半透明白色圆角卡片，带柔和投影。</summary>
    public static Border Card(UIElement content, Thickness padding, double radius = 18)
    {
        return new Border
        {
            Background = Panel,
            CornerRadius = new CornerRadius(radius),
            Padding = padding,
            Child = content,
            Effect = new DropShadowEffect
            {
                BlurRadius = 24,
                ShadowDepth = 3,
                Direction = 270,
                Opacity = 0.22,
                Color = Colors.Black
            }
        };
    }

    /// <summary>给投影留出绘制空间。</summary>
    public static Grid ShadowHost(UIElement card)
    {
        var host = new Grid { Margin = new Thickness(14) };
        host.Children.Add(card);
        return host;
    }

    /// <summary>窗口显示时移到当前工作区中央稍偏上的位置。</summary>
    public static void CenterOnScreen(Window window, double verticalOffset = -24)
    {
        window.Loaded += (_, _) =>
        {
            var area = SystemParameters.WorkArea;
            window.Left = area.Left + (area.Width - window.ActualWidth) / 2;
            window.Top = area.Top + (area.Height - window.ActualHeight) / 2 + verticalOffset;
        };
    }

    /// <summary>按住空白区域即可拖动无边框窗口。</summary>
    public static void EnableDrag(Window window, UIElement surface)
    {
        surface.MouseLeftButtonDown += (_, e) =>
        {
            if (e.ButtonState != MouseButtonState.Pressed || e.OriginalSource is ButtonBase)
            {
                return;
            }
            try
            {
                window.DragMove();
            }
            catch (InvalidOperationException)
            {
                // DragMove 可能与其他鼠标捕获冲突，忽略即可。
            }
        };
    }

    public static Path CreateIcon(Geometry geometry, IconPaint paint, double size, double thickness = 1.6)
    {
        var path = new Path
        {
            Data = geometry,
            Stretch = Stretch.Uniform,
            Width = size,
            Height = size,
            StrokeThickness = thickness,
            StrokeStartLineCap = PenLineCap.Round,
            StrokeEndLineCap = PenLineCap.Round,
            StrokeLineJoin = PenLineJoin.Round,
            SnapsToDevicePixels = true
        };
        if (paint is IconPaint.Fill or IconPaint.FillAndStroke)
        {
            path.Fill = Ink;
        }
        if (paint is IconPaint.Stroke or IconPaint.FillAndStroke)
        {
            path.Stroke = Ink;
        }
        return path;
    }

    public static Button IconButton(
        UIElement content,
        string toolTip,
        Action onClick,
        double size = 36,
        Brush? hoverBrush = null)
    {
        var button = new Button
        {
            Width = size,
            Height = size,
            Content = content,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Padding = new Thickness(0),
            Focusable = false,
            Cursor = Cursors.Hand,
            ToolTip = toolTip,
            Template = CreateRoundTemplate(size / 2)
        };
        AutomationProperties.SetName(button, toolTip);

        var hover = hoverBrush ?? Hover;
        button.MouseEnter += (_, _) => button.Background = hover;
        button.MouseLeave += (_, _) => button.Background = Brushes.Transparent;
        button.Click += (_, _) => onClick();
        return button;
    }

    public static Button TextButton(string text, ButtonTone tone, Action onClick)
    {
        var (background, hover, foreground, fontSize) = tone switch
        {
            ButtonTone.Primary => (Accent, AccentHover, (Brush)Brushes.White, 13.0),
            ButtonTone.Chip => (QuietFill, QuietHover, Ink, 11.5),
            _ => (QuietFill, QuietHover, Ink, 13.0)
        };

        var button = new Button
        {
            Content = text,
            FontSize = fontSize,
            FontWeight = tone == ButtonTone.Primary ? FontWeights.SemiBold : FontWeights.Normal,
            Foreground = foreground,
            Background = background,
            BorderThickness = new Thickness(0),
            Padding = tone == ButtonTone.Chip ? new Thickness(10, 5, 10, 5) : new Thickness(16, 7, 16, 7),
            Cursor = Cursors.Hand,
            Template = CreateRoundTemplate(tone == ButtonTone.Chip ? 12 : 9)
        };
        button.MouseEnter += (_, _) => button.Background = hover;
        button.MouseLeave += (_, _) => button.Background = background;
        button.Click += (_, _) => onClick();
        return button;
    }

    private static ControlTemplate CreateRoundTemplate(double radius)
    {
        var presenter = new FrameworkElementFactory(typeof(ContentPresenter));
        presenter.SetValue(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Center);
        presenter.SetValue(FrameworkElement.VerticalAlignmentProperty, VerticalAlignment.Center);

        var border = new FrameworkElementFactory(typeof(Border));
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(radius));
        border.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Control.BackgroundProperty));
        border.SetValue(Border.PaddingProperty, new TemplateBindingExtension(Control.PaddingProperty));
        border.AppendChild(presenter);

        return new ControlTemplate(typeof(Button)) { VisualTree = border };
    }

    private static Brush Solid(byte alpha, byte red, byte green, byte blue)
    {
        var brush = new SolidColorBrush(Color.FromArgb(alpha, red, green, blue));
        brush.Freeze();
        return brush;
    }
}
