using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using BetterRNP.PluginSdk.Abstractions;
using BetterRNP.RandomnessAnalyzerPlugin.Analysis;

namespace BetterRNP.RandomnessAnalyzerPlugin.Views;

public sealed class RandomnessAnalysisWindow : Window
{
    private readonly IPluginModeContext _scheme;
    private readonly string _modeId;
    private readonly string _modeDisplayName;
    private readonly string? _validationError;
    private readonly Func<string>? _candidateSupplier;
    private readonly Action<string>? _log;
    private readonly ComboBox _sampleSizeCombo;
    private readonly Button _analyzeButton;
    private readonly ProgressBar _progressBar;
    private readonly TextBlock _statusText;
    private readonly TextBlock _uniformityText;
    private readonly TextBlock _independenceText;
    private readonly TextBlock _conclusionText;
    private readonly DataGrid _resultsGrid;
    private CancellationTokenSource? _cancellation;

    public RandomnessAnalysisWindow(IPluginPickerSnapshot picker, Action<string>? log = null)
        : this(
            picker.Scheme,
            picker.ModeId,
            picker.ModeDisplayName,
            picker.ValidationError,
            picker.CreateCandidateSupplier(),
            log)
    {
    }

    public RandomnessAnalysisWindow(IPluginModeContext scheme, Action<string>? log = null)
        : this(
            scheme,
            scheme.SchemeType,
            GetSchemeTypeLabel(scheme.SchemeType),
            null,
            null,
            log)
    {
    }

    private RandomnessAnalysisWindow(
        IPluginModeContext scheme,
        string modeId,
        string modeDisplayName,
        string? validationError,
        Func<string>? candidateSupplier,
        Action<string>? log)
    {
        _scheme = scheme;
        _modeId = modeId;
        _modeDisplayName = modeDisplayName;
        _validationError = validationError;
        _candidateSupplier = candidateSupplier;
        _log = log;
        Title = "随机性分析";
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
            Content = "开始分析",
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
            Text = "选择样本量后开始分析。",
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 0, 0, 10)
        };
        _uniformityText = new TextBlock { Text = "频率检验：尚未运行" };
        _independenceText = new TextBlock
        {
            Text = "前后依赖检验：尚未运行",
            Margin = new Thickness(0, 5, 0, 0)
        };
        _conclusionText = new TextBlock
        {
            Margin = new Thickness(0, 8, 0, 0),
            FontWeight = FontWeights.SemiBold,
            TextWrapping = TextWrapping.Wrap
        };
        _resultsGrid = CreateResultsGrid();

        Content = BuildLayout();
        if (!string.IsNullOrWhiteSpace(_validationError))
        {
            _analyzeButton.IsEnabled = false;
            SetStatus($"当前抽取模式暂不可用：{_validationError}", isError: true);
        }
        Closed += (_, _) =>
        {
            _cancellation?.Cancel();
            _cancellation?.Dispose();
            _cancellation = null;
        };
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
        header.Children.Add(new TextBlock
        {
            Text = "抽取随机性分析",
            FontSize = 23,
            FontWeight = FontWeights.SemiBold
        });
        header.Children.Add(new TextBlock
        {
            Text = $"方案：{_scheme.SchemeName}　模式：{_modeDisplayName}",
            Margin = new Thickness(0, 5, 0, 0),
            Foreground = SystemColors.GrayTextBrush
        });
        Grid.SetRow(header, 0);
        layout.Children.Add(header);

        var toolbar = new DockPanel { Margin = new Thickness(0, 0, 0, 10) };
        toolbar.Children.Add(_analyzeButton);
        toolbar.Children.Add(_sampleSizeCombo);
        toolbar.Children.Add(new TextBlock
        {
            Text = "抽样次数",
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 8, 0)
        });
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

        var note = new TextBlock
        {
            Margin = new Thickness(0, 10, 0, 0),
            Foreground = SystemColors.GrayTextBrush,
            TextWrapping = TextWrapping.Wrap,
            Text = "方法：频率检验检查各类结果是否均匀；前后依赖检验检查上一次结果是否影响下一次结果。"
                + "大范围数字会自动分为 20 个等宽区间。通过检验只能说明没有发现显著异常，不能数学证明绝对随机。"
        };
        Grid.SetRow(note, 5);
        layout.Children.Add(note);
        return layout;
    }

    private static DataGrid CreateResultsGrid()
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
        grid.Columns.Add(new DataGridTextColumn
        {
            Header = "结果",
            Width = new DataGridLength(1, DataGridLengthUnitType.Star),
            Binding = new System.Windows.Data.Binding(nameof(CategoryResult.Outcome))
        });
        AddColumn(grid, "出现次数", nameof(CategoryResult.Observed), "N0", 105);
        AddColumn(grid, "实际占比", nameof(CategoryResult.ObservedPercent), "P2", 105);
        AddColumn(grid, "期望次数", nameof(CategoryResult.Expected), "F1", 105);
        AddColumn(grid, "期望占比", nameof(CategoryResult.ExpectedPercent), "P2", 105);
        AddColumn(grid, "偏差", nameof(CategoryResult.Deviation), "F1", 95);
        return grid;
    }

    private static void AddColumn(
        DataGrid grid,
        string header,
        string property,
        string format,
        double width)
    {
        grid.Columns.Add(new DataGridTextColumn
        {
            Header = header,
            Width = width,
            Binding = new System.Windows.Data.Binding(property)
            {
                StringFormat = format
            }
        });
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
            SetStatus($"无法读取当前方案：{ex.Message}", isError: true);
            return;
        }

        if (population.Categories.Count < 2)
        {
            SetStatus("当前方案没有至少两个候选结果，无法进行随机性分析。", isError: true);
            return;
        }

        var sampleCount = GetSelectedSampleCount();
        _cancellation = new CancellationTokenSource();
        _analyzeButton.Content = "取消";
        _sampleSizeCombo.IsEnabled = false;
        _progressBar.Value = 0;
        _resultsGrid.ItemsSource = null;
        SetStatus($"正在对 {population.Categories.Count} 类候选结果抽取 {sampleCount:N0} 次…");
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
            _uniformityText.Text = string.Create(
                CultureInfo.CurrentCulture,
                $"频率检验：χ² = {report.UniformityChiSquare:F3}，df = {report.UniformityDegreesOfFreedom}，p = {FormatP(report.UniformityPValue)}");
            _independenceText.Text = report.IndependencePValue is { } independenceP
                ? $"前后依赖检验：χ² = {report.IndependenceChiSquare:F3}，df = {report.IndependenceDegreesOfFreedom}，p = {FormatP(independenceP)}"
                : $"前后依赖检验：类别数 {population.Categories.Count}，超过 {RandomnessAnalyzer.MaxIndependenceCategories}，本次未执行";
            _conclusionText.Text = report.Conclusion;
            _conclusionText.Foreground = report.HasSignificantDeviation
                ? Brushes.DarkRed
                : new SolidColorBrush(Color.FromRgb(0, 105, 55));
            SetStatus(
                population.IsBinned
                    ? $"分析完成；数字范围已自动归并为 {population.Categories.Count} 个等宽区间。"
                    : "分析完成。");
            _progressBar.Value = 100;
            _log?.Invoke(
                $"随机性分析完成：方案={_scheme.SchemeName}｜模式={_modeDisplayName}｜样本={report.SampleCount}｜"
                + $"频率p={report.UniformityPValue:G4}｜依赖p={FormatP(report.IndependencePValue)}｜"
                + $"显著偏差={report.HasSignificantDeviation}");
        }
        catch (OperationCanceledException)
        {
            SetStatus("分析已取消。");
        }
        catch (Exception ex)
        {
            SetStatus($"分析失败：{ex.Message}", isError: true);
            _log?.Invoke($"随机性分析失败：{ex}");
        }
        finally
        {
            _cancellation?.Dispose();
            _cancellation = null;
            _analyzeButton.Content = "开始分析";
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

    private static string GetSchemeTypeLabel(string type) => type switch
    {
        "number" => "数字模式",
        "seat" => "座位模式",
        _ => "名字列表模式"
    };
}
