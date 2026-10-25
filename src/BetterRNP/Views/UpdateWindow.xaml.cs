using System.ComponentModel;
using System.Windows;
using BetterRNP.Core;

namespace BetterRNP.Views;

public partial class UpdateWindow : Window
{
    private readonly AppServices _services;
    private readonly UpdateCheckResult _result;
    private readonly UpdateAsset _asset;
    private readonly CancellationTokenSource _cts = new();
    private bool _applying;
    private bool _done;

    public UpdateWindow(AppServices services, UpdateCheckResult result, UpdateAsset asset)
    {
        _services = services;
        _result = result;
        _asset = asset;
        InitializeComponent();
        _services.Theme.Attach(this, ThemeService.CategoryOther);
        Closing += OnClosing;
        Loaded += async (_, _) => await RunAsync();
    }

    private async Task RunAsync()
    {
        try
        {
            var settings = _services.UpdateSettings.Load();
            var uri = _services.Update.ResolveDownloadUri(_asset, settings.Source);
            StatusText.Text = LocalizationService.Format(
                UiText.DownloadingFormat,
                _result.LatestTag,
                _asset.Name);
            _services.Log.Write(uri.AbsoluteUri, "UPDATE_DOWNLOAD");

            var progress = new Progress<(long Bytes, long Total)>(value =>
            {
                if (value.Total > 0)
                {
                    DownloadBar.IsIndeterminate = false;
                    DownloadBar.Maximum = value.Total;
                    DownloadBar.Value = value.Bytes;
                    ProgressText.Text =
                        $"{value.Bytes / 1048576.0:F1} / {value.Total / 1048576.0:F1} MB";
                }
                else
                {
                    ProgressText.Text = $"{value.Bytes / 1048576.0:F1} MB";
                }
            });

            var zipPath = await _services.Update.DownloadAsync(uri, progress, _cts.Token);
            _services.Log.Write(
                $"版本={_result.LatestTag}｜文件={Path.GetFileName(zipPath)}｜大小={new FileInfo(zipPath).Length} 字节",
                "UPDATE_DOWNLOADED");
            _applying = true;
            CancelButton.IsEnabled = false;
            StatusText.Text = LocalizationService.Get(UiText.DownloadCompleteApplying);
            DownloadBar.IsIndeterminate = true;
            ProgressText.Text = string.Empty;

            _services.Update.Apply(zipPath);
            _services.Log.Write($"版本={_result.LatestTag}", "UPDATE_APPLIED");
            StatusText.Text = LocalizationService.Get(UiText.UpdatePreparedRestart);
            await Task.Delay(1000);
            _done = true;
            if (Application.Current is App app)
            {
                app.ExitApplication(false);
            }
            else
            {
                Application.Current.Shutdown();
            }
        }
        catch (OperationCanceledException)
        {
            if (_cts.IsCancellationRequested)
            {
                _services.Log.Write("用户取消更新", "UPDATE_CANCELLED");
                _done = true;
                Close();
                return;
            }
            _services.Log.Write(LogLevel.Warn, "更新下载超时", "UPDATE_TIMEOUT");
            _done = true;
            MessageBox.Show(
                this,
                UiText.UpdateTimeout,
                UiText.CheckForUpdates,
                MessageBoxButton.OK,
                MessageBoxImage.Warning);
            Close();
        }
        catch (Exception ex)
        {
            _services.Log.Write(LogLevel.Error, $"更新失败: {ex}", "UPDATE_ERROR");
            _done = true;
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.UpdateFailureFormat, ex.Message),
                UiText.CheckForUpdates,
                MessageBoxButton.OK, MessageBoxImage.Error);
            Close();
        }
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e)
    {
        if (!_applying)
        {
            _cts.Cancel();
        }
    }

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        if (_done)
        {
            return;
        }
        if (_applying)
        {
            e.Cancel = true;
            return;
        }
        _cts.Cancel();
    }
}
