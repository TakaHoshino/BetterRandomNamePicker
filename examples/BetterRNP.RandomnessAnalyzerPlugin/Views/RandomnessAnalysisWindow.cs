using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using BetterRNP.PluginSdk.Abstractions;
using BetterRNP.RandomnessAnalyzerPlugin.Analysis;
using BetterRNP.RandomnessAnalyzerPlugin;

namespace BetterRNP.RandomnessAnalyzerPlugin.Views;

public sealed class RandomnessAnalysisWindow : Window
{
    private readonly IPluginModeContext _scheme;
    private readonly string _modeId;
    private readonly string _modeDisplayName;
    private readonly string? _validationError;
    private readonly Func<string>? _candidateSupplier;
    private readonly Action<string>? _log;
    private readonly IPluginLocalizationContext? _localization;
    private readonly ComboBox _sampleSizeCombo;
    private readonly Button _analyzeButton;
    private readonly ProgressBar _progressBar;
    private readonly TextBlock _statusText;
    private readonly TextBlock _uniformityText;
    private readonly TextBlock _independenceText;
    private readonly TextBlock _conclusionText;
    private readonly DataGrid _resultsGrid;
    private readonly TextBlock _titleText;
    private readonly TextBlock _schemeText;
    private readonly TextBlock _sampleLabel;
    private readonly TextBlock _noteText;
    private readonly Dictionary<DataGridColumn, string> _columnSources = new();
    private string _conclusionSource = string.Empty;
    private bool _hasCompletedAnalysis;
    private CancellationTokenSource? _cancellation;

    public RandomnessAnalysisWindow(IPluginPickerSnapshot picker, Action<string>? log = null)
        : this(picker, null, log)
    {
    }

    public RandomnessAnalysisWindow(
        IPluginPickerSnapshot picker,
        IPluginLocalizationContext? localization,
        Action<string>? log = null)
        : this(
            picker.Scheme,
            picker.ModeId,
            picker.ModeDisplayName,
            picker.ValidationError,
            picker.CreateCandidateSupplier(),
            localization,
            log)
    {
    }

    public RandomnessAnalysisWindow(IPluginModeContext scheme, Action<string>? log = null)
        : this(scheme, null, log)
    {
    }

    public RandomnessAnalysisWindow(
        IPluginModeContext scheme,
        IPluginLocalizationContext? localization,
        Action<string>? log = null)
        : this(
            scheme,
            scheme.SchemeType,
            GetSchemeTypeLabel(scheme.SchemeType),
            null,
            null,
            localization,
            log)
    {
    }

    private RandomnessAnalysisWindow(
        IPluginModeContext scheme,
        string modeId,
        string modeDisplayName,
        string? validationError,
        Func<string>? candidateSupplier,
        IPluginLocalizationContext? localization,
        Action<string>? log)
    {
        _scheme = scheme;
        _modeId = modeId;
        _modeDisplayName = modeDisplayName;
        _validationError = validationError;
        _candidateSupplier = candidateSupplier;
        _localization = localization;
        _log = log;
        Title = localization.T("随机性分析");
        Width = 920;
        Height = 650;
        MinWidth = 760;
        MinHeight = 520;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;

        _sampleSizeCombo = new ComboBox
        {
            Width = 150,
            Margin = new Thickness(0, 0, 10, 0)
        };
        _sampleSizeCombo.Items.Add(CreateSampleItem("10,000", "10000"));
        _sampleSizeCombo.Items.Add(CreateSampleItem("50,000", "50000"));
        var defaultSamples = CreateSampleItem("100,000", "100000");
        defaultSamples.IsSelected = true;
        _sampleSizeCombo.Items.Add(defaultSamples);
        _sampleSizeCombo.Items.Add(CreateSampleItem("500,000", "500000"));

        _analyzeButton = new Button
        {
            Content = localization.T("开始分析"),
            Padding = new Thickness(20, 5, 20, 5)
        };
        _analyzeButton.Click += Analyze_Click;
        DockPanel.SetDock(_analyzeButton, Dock.Right);

        _progressBar = new ProgressBar
        {
            Height = 18,
            Margin = new Thickness(0, 0, 12, 0),
            VerticalAlignment = VerticalAlignment.Center
        };

        _statusText = new TextBlock
        {
            Text = localization.T("选择样本量后开始分析。"),
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 0, 0, 10)
        };
        _uniformityText = new TextBlock
        {
            Text = localization.T("频率检验：尚未运行")
        };
        _independenceText = new TextBlock
        {
            Text = localization.T("前后依赖检验：尚未运行"),
            Margin = new Thickness(0, 5, 0, 0)
        };
        _conclusionText = new TextBlock
        {
            Margin = new Thickness(0, 8, 0, 0),
            FontWeight = FontWeights.SemiBold,
            TextWrapping = TextWrapping.Wrap
        };
        _resultsGrid = CreateResultsGrid();

        _titleText = new TextBlock
        {
            Text = localization.T("抽取随机性分析"),
            FontSize = 23,
            FontWeight = FontWeights.SemiBold
        };
        _schemeText = new TextBlock
        {
            Text = Format("方案：{0}　模式：{1}", _scheme.SchemeName, _modeDisplayName),
            Margin = new Thickness(0, 5, 0, 0),
            Foreground = SystemColors.GrayTextBrush
        };
        _sampleLabel = new TextBlock
        {
            Text = localization.T("抽样次数"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 8, 0)
        };
        _noteText = new TextBlock
        {
            Margin = new Thickness(0, 10, 0, 0),
            Foreground = SystemColors.GrayTextBrush,
            TextWrapping = TextWrapping.Wrap,
            Text = localization.T(
                "方法：频率检验检查各类结果是否均匀；前后依赖检验检查上一次结果是否影响下一次结果。"
                + "大范围数字会自动分为 20 个等宽区间。通过检验只能说明没有发现显著异常，不能数学证明绝对随机。")
        };

        Content = BuildLayout();
        if (!string.IsNullOrWhiteSpace(_validationError))
        {
            _analyzeButton.IsEnabled = false;
            SetStatus(
                Format("当前抽取模式暂不可用：{0}", _validationError),
                isError: true);
        }
        Closed += (_, _) =>
        {
            _cancellation?.Cancel();
            _cancellation?.Dispose();
            _cancellation = null;
            if (_localization is not null)
            {
                _localization.LanguageChanged -= OnLanguageChanged;
            }
        };
        if (_localization is not null)
        {
            _localization.LanguageChanged += OnLanguageChanged;
        }
    }

    private UIElement BuildLayout()
    {
        var layout = new Grid { Margin = new Thickness(16) };
        layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        layout.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        var header = new StackPanel { Margin = new Thickness(0, 0, 0, 12) };
        header.Children.Add(_titleText);
        header.Children.Add(_schemeText);
        Grid.SetRow(header, 0);
        layout.Children.Add(header);

        var toolbar = new DockPanel { Margin = new Thickness(0, 0, 0, 10) };
        toolbar.Children.Add(_analyzeButton);
        toolbar.Children.Add(_sampleSizeCombo);
        toolbar.Children.Add(_sampleLabel);
        toolbar.Children.Add(_progressBar);
        Grid.SetRow(toolbar, 1);
        layout.Children.Add(toolbar);

        Grid.SetRow(_statusText, 2);
        layout.Children.Add(_statusText);

        var summary = new Border
        {
            Padding = new Thickness(12),
            Margin = new Thickness(0, 0, 0, 10),
            BorderBrush = SystemColors.ActiveBorderBrush,
            BorderThickness = new Thickness(1)
        };
        var summaryContent = new StackPanel();
        summaryContent.Children.Add(_uniformityText);
        summaryContent.Children.Add(_independenceText);
        summaryContent.Children.Add(_conclusionText);
        summary.Child = summaryContent;
        Grid.SetRow(summary, 3);
        layout.Children.Add(summary);

        Grid.SetRow(_resultsGrid, 4);
        layout.Children.Add(_resultsGrid);

        Grid.SetRow(_noteText, 5);
        layout.Children.Add(_noteText);
        return layout;
    }

    private DataGrid CreateResultsGrid()
    {
        var grid = new DataGrid
        {
            AutoGenerateColumns = false,
            IsReadOnly = true,
            CanUserAddRows = false,
            CanUserDeleteRows = false,
            CanUserResizeRows = false,
            HeadersVisibility = DataGridHeadersVisibility.Column,
            GridLinesVisibility = DataGridGridLinesVisibility.Horizontal,
            AlternatingRowBackground = new SolidColorBrush(Color.FromArgb(16, 128, 128, 128))
        };
        var outcomeColumn = new DataGridTextColumn
        {
            Header = _localization.T("结果"),
            Width = new DataGridLength(1, DataGridLengthUnitType.Star),
            Binding = new System.Windows.Data.Binding(nameof(CategoryResult.Outcome))
        };
        grid.Columns.Add(outcomeColumn);
        _columnSources[outcomeColumn] = "结果";
        AddColumn(grid, "出现次数", nameof(CategoryResult.Observed), "N0", 105);
        AddColumn(grid, "实际占比", nameof(CategoryResult.ObservedPercent), "P2", 105);
        AddColumn(grid, "期望次数", nameof(CategoryResult.Expected), "F1", 105);
        AddColumn(grid, "期望占比", nameof(CategoryResult.ExpectedPercent), "P2", 105);
        AddColumn(grid, "偏差", nameof(CategoryResult.Deviation), "F1", 95);
        return grid;
    }

    private void AddColumn(
        DataGrid grid,
        string source,
        string property,
        string format,
        double width)
    {
        var column = new DataGridTextColumn
        {
            Header = _localization.T(source),
            Width = width,
            Binding = new System.Windows.Data.Binding(property)
            {
                StringFormat = format
            }
        };
        grid.Columns.Add(column);
        _columnSources[column] = source;
    }

    private static ComboBoxItem CreateSampleItem(string text, string samples)
        => new() { Content = text, Tag = samples };

    private async void Analyze_Click(object sender, RoutedEventArgs e)
    {
        if (_cancellation is not null)
        {
            _cancellation.Cancel();
            return;
        }

        PopulationDefinition population;
        try
        {
            population = RandomnessAnalyzer.CreatePopulation(_scheme, _modeId);
        }
        catch (Exception ex)
        {
            SetStatus(Format("无法读取当前方案：{0}", ex.Message), isError: true);
            return;
        }

        if (population.Categories.Count < 2)
        {
            SetStatus(
                _localization.T("当前方案没有至少两个候选结果，无法进行随机性分析。"),
                isError: true);
            return;
        }

        var sampleCount = GetSelectedSampleCount();
        _cancellation = new CancellationTokenSource();
        _analyzeButton.Content = _localization.T("取消");
        _sampleSizeCombo.IsEnabled = false;
        _progressBar.Value = 0;
        _resultsGrid.ItemsSource = null;
        SetStatus(Format(
            "正在对 {0} 类候选结果抽取 {1:N0} 次…",
            population.Categories.Count,
            sampleCount));
        _log?.Invoke(
            $"开始随机性分析：方案={_scheme.SchemeName}｜模式={_modeDisplayName}｜"
            + $"样本={sampleCount}｜类别={population.Categories.Count}");

        var progress = new Progress<double>(value => _progressBar.Value = value * 100);
        try
        {
            var report = await Task.Run(() =>
            {
                if (_candidateSupplier is { } supplier)
                {
                    return RandomnessAnalyzer.AnalyzeSupplier(
                        population,
                        supplier,
                        sampleCount,
                        progress,
                        _cancellation.Token);
                }
                return RandomnessAnalyzer.Analyze(
                    population,
                    sampleCount,
                    Random.Shared,
                    progress,
                    _cancellation.Token);
            }, _cancellation.Token);

            _resultsGrid.ItemsSource = report.Categories;
            _uniformityText.Text = Format(
                "频率检验：χ² = {0:F3}，df = {1}，p = {2}",
                report.UniformityChiSquare,
                report.UniformityDegreesOfFreedom,
                FormatP(report.UniformityPValue));
            _independenceText.Text = report.IndependencePValue is { } independenceP
                ? Format(
                    "前后依赖检验：χ² = {0:F3}，df = {1}，p = {2}",
                    report.IndependenceChiSquare,
                    report.IndependenceDegreesOfFreedom,
                    FormatP(independenceP))
                : Format(
                    "前后依赖检验：类别数 {0}，超过 {1}，本次未执行",
                    population.Categories.Count,
                    RandomnessAnalyzer.MaxIndependenceCategories);
            _conclusionSource = report.Conclusion;
            _conclusionText.Text = _localization.T(_conclusionSource);
            _conclusionText.Foreground = report.HasSignificantDeviation
                ? Brushes.DarkRed
                : new SolidColorBrush(Color.FromRgb(0, 105, 55));
            _hasCompletedAnalysis = true;
            SetStatus(population.IsBinned
                ? Format(
                    "分析完成；数字范围已自动归并为 {0} 个等宽区间。",
                    population.Categories.Count)
                : _localization.T("分析完成。"));
            _progressBar.Value = 100;
            _log?.Invoke(
                $"随机性分析完成：方案={_scheme.SchemeName}｜模式={_modeDisplayName}｜样本={report.SampleCount}｜"
                + $"频率p={report.UniformityPValue:G4}｜依赖p={FormatP(report.IndependencePValue)}｜"
                + $"显著偏差={report.HasSignificantDeviation}");
        }
        catch (OperationCanceledException)
        {
            SetStatus(_localization.T("分析已取消。"));
        }
        catch (Exception ex)
        {
            SetStatus(Format("分析失败：{0}", ex.Message), isError: true);
            _log?.Invoke($"随机性分析失败：{ex}");
        }
        finally
        {
            _cancellation?.Dispose();
            _cancellation = null;
            _analyzeButton.Content = _localization.T("开始分析");
            _sampleSizeCombo.IsEnabled = true;
        }
    }

    private int GetSelectedSampleCount()
    {
        if (_sampleSizeCombo.SelectedItem is ComboBoxItem item
            && int.TryParse(item.Tag?.ToString(), out var value))
        {
            return value;
        }
        return 100_000;
    }

    private void SetStatus(string text, bool isError = false)
    {
        _statusText.Text = text;
        _statusText.Foreground = isError ? Brushes.DarkRed : SystemColors.ControlTextBrush;
    }

    private static string FormatP(double? value)
        => value is null
            ? "未执行"
            : value < 0.0001
                ? value.Value.ToString("0.00E+0", CultureInfo.InvariantCulture)
                : value.Value.ToString("0.0000", CultureInfo.InvariantCulture);

    private string Format(string source, params object?[] arguments)
        => string.Format(
            CultureInfo.CurrentCulture,
            _localization.T(source),
            arguments);

    private void OnLanguageChanged(object? sender, PluginLanguageChangedEventArgs e)
        => ApplyLanguage();

    private void ApplyLanguage()
    {
        Title = _localization.T("随机性分析");
        _titleText.Text = _localization.T("抽取随机性分析");
        _schemeText.Text = Format("方案：{0}　模式：{1}", _scheme.SchemeName, _modeDisplayName);
        _sampleLabel.Text = _localization.T("抽样次数");
        _noteText.Text = _localization.T(
            "方法：频率检验检查各类结果是否均匀；前后依赖检验检查上一次结果是否影响下一次结果。"
            + "大范围数字会自动分为 20 个等宽区间。通过检验只能说明没有发现显著异常，不能数学证明绝对随机。");
        _analyzeButton.Content = _cancellation is null
            ? _localization.T("开始分析")
            : _localization.T("取消");
        foreach (var pair in _columnSources)
        {
            pair.Key.Header = _localization.T(pair.Value);
        }
        if (!string.IsNullOrWhiteSpace(_conclusionSource))
        {
            _conclusionText.Text = _localization.T(_conclusionSource);
        }
        else if (!_hasCompletedAnalysis)
        {
            _statusText.Text = _localization.T("选择样本量后开始分析。");
            _uniformityText.Text = _localization.T("频率检验：尚未运行");
            _independenceText.Text = _localization.T("前后依赖检验：尚未运行");
        }
    }

    private static string GetSchemeTypeLabel(string type) => type switch
    {
        "number" => "数字模式",
        "seat" => "座位模式",
        _ => "名字列表模式"
    };
}
