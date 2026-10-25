using System.Windows;
using System.Windows.Controls;
using BetterRNP.PluginSdk.Abstractions;

namespace BetterRNP.TimerPlugin.Views;

public sealed class TimerSettingsView : StackPanel, IDisposable
{
    private readonly IPluginLocalizationContext? _localization;
    private readonly Action<double> _applyTransparency;
    private readonly TextBlock _title;
    private readonly TextBlock _hint;
    private readonly TextBlock _label;
    private readonly TextBlock _value;

    public TimerSettingsView(
        IPluginLocalizationContext? localization,
        double initialTransparencyPercent,
        Action<double> applyTransparency)
    {
        _localization = localization;
        _applyTransparency = applyTransparency;
        Margin = new Thickness(0, 2, 0, 0);

        _title = new TextBlock
        {
            Text = localization.T("计时窗口透明度"),
            FontSize = 16,
            FontWeight = FontWeights.SemiBold,
            Margin = new Thickness(0, 0, 0, 8)
        };
        _hint = new TextBlock
        {
            Text = localization.T("拖动滑块会实时应用到正在运行的正计时和倒计时窗口。"),
            TextWrapping = TextWrapping.Wrap,
            Opacity = 0.72,
            Margin = new Thickness(0, 0, 0, 14)
        };
        _label = new TextBlock
        {
            Text = localization.T("透明度"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 10, 0)
        };
        _value = new TextBlock
        {
            Text = $"{initialTransparencyPercent:0}%",
            VerticalAlignment = VerticalAlignment.Center,
            TextAlignment = TextAlignment.Right,
            Width = 42,
            Margin = new Thickness(10, 0, 0, 0)
        };
        TransparencySlider = new Slider
        {
            Minimum = 0,
            Maximum = 80,
            Value = Math.Clamp(initialTransparencyPercent, 0, 80),
            TickFrequency = 1,
            IsSnapToTickEnabled = false,
            VerticalAlignment = VerticalAlignment.Center
        };
        TransparencySlider.ValueChanged += OnTransparencyChanged;

        var row = new Grid();
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(_label, 0);
        Grid.SetColumn(TransparencySlider, 1);
        Grid.SetColumn(_value, 2);
        row.Children.Add(_label);
        row.Children.Add(TransparencySlider);
        row.Children.Add(_value);

        Children.Add(_title);
        Children.Add(_hint);
        Children.Add(row);

        if (_localization is not null)
        {
            _localization.LanguageChanged += OnLanguageChanged;
        }
    }

    public Slider TransparencySlider { get; }

    public void Dispose()
    {
        TransparencySlider.ValueChanged -= OnTransparencyChanged;
        if (_localization is not null)
        {
            _localization.LanguageChanged -= OnLanguageChanged;
        }
    }

    private void OnTransparencyChanged(
        object sender,
        RoutedPropertyChangedEventArgs<double> e)
    {
        var value = Math.Clamp(e.NewValue, 0, 80);
        _value.Text = $"{value:0}%";
        _applyTransparency(value);
    }

    private void OnLanguageChanged(object? sender, PluginLanguageChangedEventArgs e)
    {
        _title.Text = _localization.T("计时窗口透明度");
        _hint.Text = _localization.T("拖动滑块会实时应用到正在运行的正计时和倒计时窗口。");
        _label.Text = _localization.T("透明度");
    }
}
