using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using BetterRNP.Core;
using Microsoft.Win32;

namespace BetterRNP.Views;

/// <summary>日志查看器：筛选、搜索、自动刷新、导出与清空。</summary>
public partial class LogWindow : Window
{
    private static LogWindow? _current;

    private readonly AppServices _services;
    private readonly ObservableCollection<LogEntry> _visible = new();
    private readonly List<LogEntry> _all = new();
    private bool _ready;
    private bool _reloadPending;

    public LogWindow(AppServices services)
    {
        _services = services;
        InitializeComponent();
        _services.Theme.Attach(this, ThemeService.CategorySettings);

        EntriesGrid.ItemsSource = _visible;
        LevelCombo.ItemsSource = new[] { "全部", "信息", "警告", "错误" };
        LevelCombo.SelectedIndex = 0;

        _ready = true;
        _services.Log.EntryWritten += OnEntryWritten;
        Closed += (_, _) => _services.Log.EntryWritten -= OnEntryWritten;
        Reload();
    }

    /// <summary>打开日志窗口；已有实例时直接激活，避免重复窗口。</summary>
    public static void ShowFor(Window? owner, AppServices services)
    {
        if (_current is { IsLoaded: true } existing)
        {
            existing.Activate();
            return;
        }

        var window = new LogWindow(services);
        services.Log.Write("窗口=日志查看器", "打开窗口");
        if (owner is { IsVisible: true })
        {
            window.Owner = owner;
        }
        _current = window;
        window.Closed += (_, _) =>
        {
            if (ReferenceEquals(_current, window))
            {
                _current = null;
            }
        };
        window.Show();
    }

    private void Filter_SelectionChanged(object sender, SelectionChangedEventArgs e) => ApplyFilter();

    private void Filter_TextChanged(object sender, TextChangedEventArgs e) => ApplyFilter();

    private void AutoRefresh_Changed(object sender, RoutedEventArgs e)
    {
        if (AutoRefreshCheck.IsChecked == true)
        {
            Reload();
        }
    }

    private void OnEntryWritten(object? sender, LogEntry entry)
    {
        if (AutoRefreshCheck.IsChecked != true)
        {
            return;
        }
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(new Action(ScheduleReload));
            return;
        }
        ScheduleReload();
    }

    private void ScheduleReload()
    {
        if (_reloadPending)
        {
            return;
        }
        _reloadPending = true;
        Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() =>
        {
            _reloadPending = false;
            Reload();
        }));
    }

    private void Reload()
    {
        if (!_ready)
        {
            return;
        }
        _all.Clear();
        _all.AddRange(_services.Log.ReadEntries());
        ApplyFilter();
    }

    private void ApplyFilter()
    {
        if (!_ready)
        {
            return;
        }

        var level = LevelCombo.SelectedIndex switch
        {
            1 => (LogLevel?)LogLevel.Info,
            2 => LogLevel.Warn,
            3 => LogLevel.Error,
            _ => null
        };
        var keyword = SearchBox.Text?.Trim() ?? string.Empty;

        _visible.Clear();
        foreach (var entry in _all)
        {
            if (level is not null && entry.Level != level)
            {
                continue;
            }
            if (keyword.Length > 0
                && !entry.Operation.Contains(keyword, StringComparison.OrdinalIgnoreCase)
                && !entry.Detail.Contains(keyword, StringComparison.OrdinalIgnoreCase)
                && !entry.LevelText.Contains(keyword, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }
            _visible.Add(entry);
        }
        UpdateStatus();
    }

    private void UpdateStatus()
        => StatusText.Text =
            $"共 {_all.Count} 条，显示 {_visible.Count} 条 · {FormatSize(_services.Log.GetFileSize())} · {_services.Log.LogFile}";

    private static string FormatSize(long bytes) => bytes switch
    {
        >= 1024 * 1024 => $"{bytes / 1024d / 1024d:0.0} MB",
        >= 1024 => $"{bytes / 1024d:0.0} KB",
        _ => $"{bytes} B"
    };

    private void Refresh_Click(object sender, RoutedEventArgs e) => Reload();

    private void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var directory = Path.GetDirectoryName(_services.Log.LogFile)!;
            Directory.CreateDirectory(directory);
            Process.Start(new ProcessStartInfo { FileName = directory, UseShellExecute = true });
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"无法打开日志目录：{ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Export_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new SaveFileDialog
        {
            Title = "导出日志",
            Filter = "文本文件 (*.txt)|*.txt",
            FileName = $"BetterRNP_Modifylog_{DateTime.Now:yyyyMMdd_HHmmss}.txt"
        };
        if (dialog.ShowDialog(this) != true)
        {
            return;
        }

        try
        {
            if (File.Exists(_services.Log.LogFile))
            {
                File.Copy(_services.Log.LogFile, dialog.FileName, true);
            }
            else
            {
                File.WriteAllText(dialog.FileName, "暂无日志记录");
            }
            _services.Log.Write(dialog.FileName, "导出日志");
            MessageBox.Show(this, $"日志已成功导出到：\n{dialog.FileName}", "导出成功",
                MessageBoxButton.OK, MessageBoxImage.Information);
            Reload();
        }
        catch (Exception ex)
        {
            _services.Log.WriteException("EXPORT_LOG_ERROR", ex);
            MessageBox.Show(this, $"导出失败：{ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Clear_Click(object sender, RoutedEventArgs e)
    {
        var result = MessageBox.Show(
            this,
            "确定要清空当前日志文件吗？\n历史归档（Modifylog.1.txt 等）不会被删除。",
            "清空日志",
            MessageBoxButton.YesNo,
            MessageBoxImage.Question);
        if (result != MessageBoxResult.Yes)
        {
            return;
        }

        if (!_services.Log.Clear())
        {
            MessageBox.Show(this, "清空日志失败，文件可能正被其他程序占用。", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }

        _services.Log.Write("日志已清空", "LOG_CLEAR");
        Reload();
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
