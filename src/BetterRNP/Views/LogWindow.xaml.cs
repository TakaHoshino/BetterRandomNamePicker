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
        RefreshLevelOptions();

        _ready = true;
        _services.Log.EntryWritten += OnEntryWritten;
        LocalizationService.LanguageChanged += OnLanguageChanged;
        Closed += (_, _) =>
        {
            _services.Log.EntryWritten -= OnEntryWritten;
            LocalizationService.LanguageChanged -= OnLanguageChanged;
        };
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

    private void OnLanguageChanged(object? sender, EventArgs e)
    {
        var selected = LevelCombo.SelectedIndex;
        RefreshLevelOptions();
        LevelCombo.SelectedIndex = selected;
        UpdateStatus();
    }

    private void RefreshLevelOptions()
    {
        LevelCombo.ItemsSource = new[]
        {
            LocalizationService.Get(UiText.AllLevels),
            LocalizationService.Get(UiText.InformationLevel),
            LocalizationService.Get(UiText.WarningLevel),
            LocalizationService.Get(UiText.ErrorLevel)
        };
        if (LevelCombo.SelectedIndex < 0)
        {
            LevelCombo.SelectedIndex = 0;
        }
    }

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
        => StatusText.Text = LocalizationService.Format(
            UiText.LogCountFormat,
            _all.Count,
            _visible.Count,
            FormatSize(_services.Log.GetFileSize()),
            _services.Log.LogFile);

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
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.OpenLogFolderFailedFormat, ex.Message),
                UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Export_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new SaveFileDialog
        {
            Title = LocalizationService.Get(UiText.ExportLogsTitle),
            Filter = LocalizationService.Get(UiText.TextFileFilter),
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
                File.WriteAllText(dialog.FileName, LocalizationService.Get(UiText.NoLogEntries));
            }
            _services.Log.Write(dialog.FileName, "导出日志");
            MessageBox.Show(
                this,
                LocalizationService.Get(UiText.LogExportedTo) + "\n" + dialog.FileName,
                UiText.ExportedSuccessfully,
                MessageBoxButton.OK, MessageBoxImage.Information);
            Reload();
        }
        catch (Exception ex)
        {
            _services.Log.WriteException("EXPORT_LOG_ERROR", ex);
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.ExportFailureFormat, ex.Message),
                UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Clear_Click(object sender, RoutedEventArgs e)
    {
        var result = MessageBox.Show(
            this,
            UiText.ClearLogConfirmation,
            UiText.ClearLogTitle,
            MessageBoxButton.YesNo,
            MessageBoxImage.Question);
        if (result != MessageBoxResult.Yes)
        {
            return;
        }

        if (!_services.Log.Clear())
        {
            MessageBox.Show(this, UiText.ClearLogFailed, UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }

        _services.Log.Write("日志已清空", "LOG_CLEAR");
        Reload();
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
