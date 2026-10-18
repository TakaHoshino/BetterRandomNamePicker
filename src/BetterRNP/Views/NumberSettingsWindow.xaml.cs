using System.ComponentModel;
using System.Windows;
using BetterRNP.Core;

namespace BetterRNP.Views;

public partial class NumberSettingsWindow : Window
{
    private readonly AppServices _services;
    private readonly string _schemeName;
    private RollingPicker? _picker;
    private bool _isPicking;
    private string? _lastResult;
    private string _committedMin = string.Empty;
    private string _committedMax = string.Empty;
    private bool _closingApproved;

    public NumberSettingsWindow(AppServices services, string schemeName)
    {
        _services = services;
        _schemeName = schemeName;
        InitializeComponent();
        _services.Theme.Attach(this, ThemeService.CategoryNumber);
        LoadCurrentRange();
        Closing += OnClosing;
    }

    private void LoadCurrentRange()
    {
        var range = _services.Schemes.GetNumberRange(_schemeName);
        MinInput.Text = range?.Min.ToString() ?? "1";
        MaxInput.Text = range?.Max.ToString() ?? "100";
        _committedMin = MinInput.Text.Trim();
        _committedMax = MaxInput.Text.Trim();
    }

    private void Pick_Click(object sender, RoutedEventArgs e)
    {
        if (_isPicking)
        {
            StopPicking();
        }
        else
        {
            StartPicking();
        }
    }

    private void StartPicking()
    {
        if (!TryGetRange(out var min, out var max))
        {
            return;
        }

        _isPicking = true;
        PickButton.Content = "停止";
        _lastResult = null;
        _picker = new RollingPicker(
            () => Random.Shared.NextInt64(min, (long)max + 1).ToString(),
            value =>
            {
                _lastResult = value;
                ResultText.Text = $"抽取结果: {value}";
            });
        _picker.Start();
        _services.Log.Write(
            $"方案={_schemeName}｜模式=数字模式｜范围=[{min},{max}]", "开始抽取");
    }

    private void StopPicking()
    {
        _isPicking = false;
        PickButton.Content = "开始抽取";
        _picker?.Stop();
        _picker = null;
        if (_lastResult is not null)
        {
            _services.Log.Write(
                $"方案={_schemeName}｜模式=数字模式｜结果={_lastResult}", "抽取结果");
        }
    }

    private bool TryGetRange(out int min, out int max)
    {
        min = 0;
        max = 0;
        if (!int.TryParse(MinInput.Text.Trim(), out min)
            || !int.TryParse(MaxInput.Text.Trim(), out max))
        {
            _services.Log.Write(
                LogLevel.Warn, $"最小值={MinInput.Text}｜最大值={MaxInput.Text}", "数字范围无效");
            MessageBox.Show(this, "请输入有效的数字！", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
            return false;
        }
        if (min > max)
        {
            _services.Log.Write(LogLevel.Warn, $"最小值={min}｜最大值={max}", "数字范围无效");
            MessageBox.Show(this, "最小值不能大于最大值！", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
            return false;
        }
        var span = (long)max - min + 1L;
        if (span > int.MaxValue)
        {
            _services.Log.Write(LogLevel.Warn, $"最小值={min}｜最大值={max}｜跨度={span}", "数字范围过大");
            MessageBox.Show(this, $"范围过大：取值跨度需 ≤ {int.MaxValue - 1L}（当前 {span}）", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
            return false;
        }
        return true;
    }

    private void Save_Click(object sender, RoutedEventArgs e) => Save(notify: true);

    private bool Save(bool notify)
    {
        if (!TryGetRange(out var min, out var max))
        {
            return false;
        }
        _services.Schemes.SaveNumberRange(_schemeName, new Models.NumberRange(min, max));
        _committedMin = MinInput.Text.Trim();
        _committedMax = MaxInput.Text.Trim();
        if (notify)
        {
            MessageBox.Show(this, "设置已保存！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        return true;
    }

    private void Apply_Click(object sender, RoutedEventArgs e)
    {
        if (!Save(notify: false))
        {
            return;
        }
        _services.Log.Write($"方案={_schemeName}", "应用数字范围到方案");
        MessageBox.Show(this, $"设置已应用到方案：{_schemeName}（已保存）", "提示",
            MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private bool HasUnsavedChanges()
        => MinInput.Text.Trim() != _committedMin || MaxInput.Text.Trim() != _committedMax;

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        if (_closingApproved || !HasUnsavedChanges())
        {
            StopPicking();
            return;
        }
        var result = MessageBox.Show(this,
            "当前数字范围修改尚未保存，确定放弃并关闭？", "提示",
            MessageBoxButton.YesNo, MessageBoxImage.Warning);
        if (result == MessageBoxResult.Yes)
        {
            _closingApproved = true;
            _services.Log.Write($"方案={_schemeName}", "放弃未保存的修改");
            StopPicking();
        }
        else
        {
            e.Cancel = true;
            _services.Log.Write($"方案={_schemeName}", "取消关闭数字设置");
        }
    }
}
