using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using BetterRNP.Core;
using BetterRNP.Plugins;
using Microsoft.Win32;

namespace BetterRNP.Views;

public partial class SettingsWindow : Window
{
    private readonly AppServices _services;
    private readonly PluginManager? _plugins;
    private readonly Dictionary<PluginSettingsItem, object> _pluginViews = new();
    private readonly DispatcherTimer _radiusLogTimer = new() { Interval = TimeSpan.FromMilliseconds(600) };
    private readonly DispatcherTimer _opacityLogTimer = new() { Interval = TimeSpan.FromMilliseconds(600) };
    private bool _loading;
    private bool _checkingUpdate;
    private int _previewRadius;
    private int _previewOpacity;
    private bool _radiusDirty;
    private bool _opacityDirty;

    public SettingsWindow(AppServices services, PluginManager? plugins = null)
    {
        _services = services;
        _plugins = plugins;
        InitializeComponent();
        _radiusLogTimer.Tick += (_, _) =>
        {
            _radiusLogTimer.Stop();
            if (_radiusDirty)
            {
                _services.Config.SetFloatingBallRadius(_previewRadius);
                _radiusDirty = false;
                _services.Log.Write($"悬浮球半径={_previewRadius}", "调整悬浮球外观");
            }
        };
        _opacityLogTimer.Tick += (_, _) =>
        {
            _opacityLogTimer.Stop();
            if (_opacityDirty)
            {
                _services.Config.SetFloatingBallOpacity(_previewOpacity);
                _opacityDirty = false;
                _services.Log.Write($"悬浮球透明度={_previewOpacity}", "调整悬浮球外观");
            }
        };
        _services.Theme.Attach(this, ThemeService.CategorySettings);
        LoadSettings();
        BuildPluginNavigation();
        GeneralNavigation.IsSelected = true;
        if (_plugins is not null)
        {
            _plugins.PluginsChanged += OnPluginsChanged;
        }
        LocalizationService.LanguageChanged += OnLanguageChanged;
        Closed += OnClosed;
    }

    private void OnPluginsChanged(object? sender, EventArgs e)
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(new Action(BuildPluginNavigation));
            return;
        }
        BuildPluginNavigation();
    }

    private void BuildPluginNavigation()
    {
        var selected = (SettingsNavigation.SelectedItem as TreeViewItem)?.Tag as PluginSettingsItem;
        var items = _plugins?.SettingsItems ?? Array.Empty<PluginSettingsItem>();
        var validItems = new HashSet<PluginSettingsItem>(items);
        foreach (var removed in _pluginViews.Keys.Where(item => !validItems.Contains(item)).ToList())
        {
            DisposePluginView(_pluginViews[removed]);
            _pluginViews.Remove(removed);
        }

        PluginsNavigation.Items.Clear();
        foreach (var item in items)
        {
            PluginsNavigation.Items.Add(new TreeViewItem
            {
                Header = item.DisplayTitle,
                Tag = item,
                ToolTip = item.PluginTitle
            });
        }
        PluginsNavigation.IsExpanded = items.Count > 0;

        var pluginCount = items.Select(item => item.PluginId).Distinct(StringComparer.Ordinal).Count();
        PluginSummaryText.Text = items.Count == 0
            ? LocalizationService.Get(UiText.NoPluginSettings)
            : LocalizationService.Format(
                UiText.PluginSettingsSummaryFormat,
                pluginCount,
                items.Count);

        if (selected is not null)
        {
            var replacement = PluginsNavigation.Items
                .OfType<TreeViewItem>()
                .FirstOrDefault(item => item.Tag is PluginSettingsItem candidate
                    && candidate.PluginId == selected.PluginId
                    && candidate.Id == selected.Id);
            if (replacement is not null)
            {
                replacement.IsSelected = true;
                return;
            }
            PluginsNavigation.IsSelected = true;
        }
    }

    private void SettingsNavigation_SelectedItemChanged(
        object sender,
        RoutedPropertyChangedEventArgs<object> e)
    {
        if (e.NewValue is not TreeViewItem item)
        {
            return;
        }
        if (item.Tag is PluginSettingsItem pluginSettings)
        {
            ShowPluginSettings(pluginSettings);
            return;
        }
        if (item.Tag is string page)
        {
            ShowCorePage(page);
        }
    }

    private void ShowCorePage(string page)
    {
        GeneralPage.Visibility = page == "general" ? Visibility.Visible : Visibility.Collapsed;
        LanguagePage.Visibility = page == "language" ? Visibility.Visible : Visibility.Collapsed;
        InterfacePage.Visibility = page == "interface" ? Visibility.Visible : Visibility.Collapsed;
        UpdatePage.Visibility = page == "update" ? Visibility.Visible : Visibility.Collapsed;
        SecurityPage.Visibility = page == "security" ? Visibility.Visible : Visibility.Collapsed;
        LogPage.Visibility = page == "logs" ? Visibility.Visible : Visibility.Collapsed;
        PluginsOverviewPage.Visibility = page == "plugins" ? Visibility.Visible : Visibility.Collapsed;
        PluginSettingsHost.Visibility = Visibility.Collapsed;
        PluginSettingsHost.Content = null;
        var title = page switch
        {
            "interface" => UiText.Interface,
            "language" => UiText.Language,
            "update" => UiText.Updates,
            "security" => UiText.Security,
            "logs" => UiText.Logs,
            "plugins" => UiText.Plugins,
            _ => UiText.General
        };
        LocalizationService.SetText(SettingsPageTitle, title);
    }

    private void ShowPluginSettings(PluginSettingsItem item)
    {
        GeneralPage.Visibility = Visibility.Collapsed;
        LanguagePage.Visibility = Visibility.Collapsed;
        InterfacePage.Visibility = Visibility.Collapsed;
        UpdatePage.Visibility = Visibility.Collapsed;
        SecurityPage.Visibility = Visibility.Collapsed;
        LogPage.Visibility = Visibility.Collapsed;
        PluginsOverviewPage.Visibility = Visibility.Collapsed;

        try
        {
            if (!_pluginViews.TryGetValue(item, out var view))
            {
                view = item.ViewFactory();
                if (view is not FrameworkElement)
                {
                    throw new InvalidOperationException(
                        LocalizationService.Get(UiText.PluginSettingsViewError));
                }
                _pluginViews[item] = view;
            }
            PluginSettingsHost.Content = view;
            PluginSettingsHost.Visibility = Visibility.Visible;
            SettingsPageTitle.Text = LocalizationService.Format(
                UiText.PluginSettingsTitleFormat,
                item.PluginTitle,
                item.DisplayTitle);
        }
        catch (Exception ex)
        {
            _services.Log.WriteException("PLUGIN_SETTINGS_VIEW_ERROR", ex);
            PluginSettingsHost.Content = new TextBlock
            {
                Text = LocalizationService.Format(
                    UiText.PluginSettingsLoadFailureFormat,
                    ex.Message),
                Foreground = System.Windows.Media.Brushes.DarkRed,
                TextWrapping = TextWrapping.Wrap
            };
            PluginSettingsHost.Visibility = Visibility.Visible;
            SettingsPageTitle.Text = item.DisplayTitle;
        }
    }

    private static void DisposePluginView(object view)
    {
        if (view is IDisposable disposable)
        {
            disposable.Dispose();
        }
    }

    private void OnClosed(object? sender, EventArgs e)
    {
        FlushAppearanceSettings();
        if (_plugins is not null)
        {
            _plugins.PluginsChanged -= OnPluginsChanged;
        }
        LocalizationService.LanguageChanged -= OnLanguageChanged;
        foreach (var view in _pluginViews.Values)
        {
            DisposePluginView(view);
        }
        _pluginViews.Clear();
    }

    private void OnLanguageChanged(object? sender, EventArgs e)
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(new Action(RefreshLanguageDependentContent));
            return;
        }
        RefreshLanguageDependentContent();
    }

    private void RefreshLanguageDependentContent()
    {
        BuildPluginNavigation();
        RefreshLocalizedValues();
        if ((SettingsNavigation.SelectedItem as TreeViewItem)?.Tag is PluginSettingsItem item)
        {
            ShowPluginSettings(item);
        }
        else if ((SettingsNavigation.SelectedItem as TreeViewItem)?.Tag is string page)
        {
            ShowCorePage(page);
        }
    }

    private void RefreshLocalizedValues()
    {
        InstallTypeText.Text = UpdateService.DetectCurrentInstallationKind()
            == UpdateAssetKind.SelfContained
                ? LocalizationService.Get(UiText.InstallTypeSelfContainedFormat)
                : LocalizationService.Get(UiText.InstallTypeFrameworkDependentFormat);
        RadiusValue.Text = _previewRadius > RadiusSlider.Maximum
            ? LocalizationService.Format(UiText.PixelValueAboveSliderFormat, _previewRadius)
            : LocalizationService.Format(UiText.PixelValueFormat, _previewRadius);
        OpacityValue.Text = OpacityText(_previewOpacity);
        LogPathText.Text = LocalizationService.Format(
            UiText.LogFilePathFormat,
            _services.Log.LogFile);
        UpdateLockButton();
    }

    private void FlushAppearanceSettings()
    {
        _radiusLogTimer.Stop();
        _opacityLogTimer.Stop();
        if (_radiusDirty)
        {
            _services.Config.SetFloatingBallRadius(_previewRadius);
            _radiusDirty = false;
        }
        if (_opacityDirty)
        {
            _services.Config.SetFloatingBallOpacity(_previewOpacity);
            _opacityDirty = false;
        }
    }

    private void OpenPluginManager_Click(object sender, RoutedEventArgs e)
    {
        if (_plugins is null)
        {
            return;
        }
        new PluginManagerWindow(_plugins) { Owner = this }.ShowDialog();
        BuildPluginNavigation();
    }

    private void LoadSettings()
    {
        _loading = true;
        try
        {
            AutoStartCheck.IsChecked = _services.Config.AutoStart;
            SelectLanguageItem(_services.Config.Language);
            MinimizeToTrayCheck.IsChecked = _services.Config.MinimizeToTray;
            AutoShowBallCheck.IsChecked = _services.Config.AutoShowFloatingBall;
            ThemeEnabledCheck.IsChecked = _services.Theme.IsEnabled;
            var updateSettings = _services.UpdateSettings.Load();
            CheckOnStartupCheck.IsChecked = updateSettings.CheckOnStartup;
            OfficialSourceRadio.IsChecked = updateSettings.Source == UpdateSource.Official;
            ProxySourceRadio.IsChecked = updateSettings.Source == UpdateSource.GhProxy;
            InstallTypeText.Text = UpdateService.DetectCurrentInstallationKind()
                == UpdateAssetKind.SelfContained
                ? LocalizationService.Get(UiText.InstallTypeSelfContainedFormat)
                : LocalizationService.Get(UiText.InstallTypeFrameworkDependentFormat);

            var radius = _services.Config.FloatingBallRadius;
            _previewRadius = radius;
            RadiusSlider.Value = Math.Clamp(radius, (int)RadiusSlider.Minimum, (int)RadiusSlider.Maximum);
            RadiusValue.Text = radius > RadiusSlider.Maximum
                ? LocalizationService.Format(UiText.PixelValueAboveSliderFormat, radius)
                : LocalizationService.Format(UiText.PixelValueFormat, radius);

            var opacity = _services.Config.FloatingBallOpacity;
            _previewOpacity = opacity;
            OpacitySlider.Value = Math.Clamp(opacity, (int)OpacitySlider.Minimum, (int)OpacitySlider.Maximum);
            OpacityValue.Text = OpacityText(opacity);
            UpdateLockButton();
            DefaultPasswordWarning.Visibility = _services.Passwords.IsUsingBuiltInDefaultPassword()
                ? Visibility.Visible
                : Visibility.Collapsed;
            LogPathText.Text = LocalizationService.Format(
                UiText.LogFilePathFormat,
                _services.Log.LogFile);
        }
        finally
        {
            _loading = false;
        }
    }

    private static string OpacityText(int opacity)
    {
        if (opacity < 100)
        {
            return LocalizationService.Format(UiText.HighTransparencyFormat, opacity);
        }
        if (opacity < 180)
        {
            return LocalizationService.Format(UiText.MediumTransparencyFormat, opacity);
        }
        return LocalizationService.Format(UiText.LowTransparencyFormat, opacity);
    }

    private void SelectLanguageItem(string languageCode)
    {
        var normalized = LocalizationService.NormalizeLanguage(languageCode);
        LanguageCombo.SelectedItem = LanguageCombo.Items
            .OfType<ComboBoxItem>()
            .FirstOrDefault(item => string.Equals(
                item.Tag?.ToString(),
                normalized,
                StringComparison.OrdinalIgnoreCase));
    }

    private void LanguageCombo_SelectionChanged(
        object sender,
        SelectionChangedEventArgs e)
    {
        if (_loading || LanguageCombo.SelectedItem is not ComboBoxItem item)
        {
            return;
        }
        var languageCode = LocalizationService.NormalizeLanguage(item.Tag?.ToString());
        _services.Config.SetLanguage(languageCode);
        LocalizationService.SetLanguage(languageCode);
        _services.Log.Write(languageCode, "LANGUAGE_CHANGED");
        LocalizationService.ApplyToApplication();
    }

    private void AutoStart_Click(object sender, RoutedEventArgs e)
    {
        if (_loading)
        {
            return;
        }
        var enabled = AutoStartCheck.IsChecked == true;
        _services.Config.SetAutoStart(enabled);
        if (!StartupRegistration.SetEnabled(enabled))
        {
            MessageBox.Show(this, UiText.StartupRegistrationFailed, UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
        _services.Log.Write($"开机自启：{(enabled ? "开启" : "关闭")}", "SETTING_CHANGED");
    }

    private void MinimizeToTray_Click(object sender, RoutedEventArgs e)
    {
        if (!_loading)
        {
            var enabled = MinimizeToTrayCheck.IsChecked == true;
            _services.Config.SetMinimizeToTray(enabled);
            _services.Log.Write($"关闭主窗口时最小化到托盘：{(enabled ? "开启" : "关闭")}", "SETTING_CHANGED");
        }
    }

    private void AutoShowBall_Click(object sender, RoutedEventArgs e)
    {
        if (_loading)
        {
            return;
        }
        var enabled = AutoShowBallCheck.IsChecked == true;
        _services.Config.SetAutoShowFloatingBall(enabled);
        _services.Log.Write($"启动时自动显示悬浮球：{(enabled ? "开启" : "关闭")}", "SETTING_CHANGED");
        if (enabled && Application.Current is App app && !app.IsFloatingBallVisible)
        {
            app.ShowFloatingBall();
        }
    }

    private void RadiusSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_loading || !IsLoaded || RadiusValue is null)
        {
            return;
        }
        var radius = (int)Math.Round(e.NewValue);
        _previewRadius = radius;
        RadiusValue.Text = LocalizationService.Format(UiText.PixelValueFormat, radius);
        if (Application.Current is App app)
        {
            app.UpdateFloatingBallAppearance(_previewRadius, _previewOpacity);
        }
        _radiusDirty = true;
        _radiusLogTimer.Stop();
        _radiusLogTimer.Start();
    }

    private void OpacitySlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_loading || !IsLoaded || OpacityValue is null)
        {
            return;
        }
        var opacity = (int)Math.Round(e.NewValue);
        _previewOpacity = opacity;
        OpacityValue.Text = OpacityText(opacity);
        if (Application.Current is App app)
        {
            app.UpdateFloatingBallAppearance(_previewRadius, _previewOpacity);
        }
        _opacityDirty = true;
        _opacityLogTimer.Stop();
        _opacityLogTimer.Start();
    }

    private void ResetFloatingBallDefaults_Click(object sender, RoutedEventArgs e)
    {
        _radiusLogTimer.Stop();
        _opacityLogTimer.Stop();
        _radiusDirty = false;
        _opacityDirty = false;
        _previewRadius = ConfigService.FloatingBallRadiusDefault;
        _previewOpacity = ConfigService.FloatingBallOpacityDefault;

        _loading = true;
        try
        {
            RadiusSlider.Value = _previewRadius;
            OpacitySlider.Value = _previewOpacity;
            RadiusValue.Text = LocalizationService.Format(UiText.PixelValueFormat, _previewRadius);
            OpacityValue.Text = OpacityText(_previewOpacity);
        }
        finally
        {
            _loading = false;
        }

        _services.Config.SetFloatingBallRadius(_previewRadius);
        _services.Config.SetFloatingBallOpacity(_previewOpacity);
        if (Application.Current is App app)
        {
            app.UpdateFloatingBallAppearance(_previewRadius, _previewOpacity);
        }
        _services.Log.Write(
            $"悬浮球半径={_previewRadius}｜透明度={_previewOpacity}",
            "还原悬浮球默认外观");
    }

    private void ThemeEnabled_Click(object sender, RoutedEventArgs e)
    {
        if (!_loading)
        {
            var enabled = ThemeEnabledCheck.IsChecked == true;
            _services.Theme.IsEnabled = enabled;
            _services.Log.Write($"照片背景：{(enabled ? "启用" : "停用")}", "SETTING_CHANGED");
        }
    }

    private void ViewLog_Click(object sender, RoutedEventArgs e)
        => LogWindow.ShowFor(Owner is { IsVisible: true } ? Owner : this, _services);

    private void UpdateSetting_Click(object sender, RoutedEventArgs e)
    {
        if (_loading)
        {
            return;
        }
        PersistUpdateSettings();
        _services.Log.Write(
            $"启动时检查更新：{(CheckOnStartupCheck.IsChecked == true ? "开启" : "关闭")}；"
            + $"下载源：{(ProxySourceRadio.IsChecked == true ? "gh-proxy 代理" : "官方")}",
            "SETTING_CHANGED");
    }

    private void PersistUpdateSettings()
    {
        _services.UpdateSettings.Save(new UpdateSettings
        {
            CheckOnStartup = CheckOnStartupCheck.IsChecked == true,
            Source = ProxySourceRadio.IsChecked == true
                ? UpdateSource.GhProxy
                : UpdateSource.Official
        });
    }

    private async void CheckNow_Click(object sender, RoutedEventArgs e)
    {
        if (_checkingUpdate)
        {
            return;
        }
        _checkingUpdate = true;
        CheckNowButton.IsEnabled = false;
        UpdateStatusText.Text = LocalizationService.Get(UiText.CheckingForUpdates);
        PersistUpdateSettings();
        try
        {
            var result = await UpdatePresenter.CheckAsync(this, _services, true);
            if (!IsLoaded)
            {
                return;
            }
            UpdateStatusText.Text = result is null
                ? LocalizationService.Get(UiText.CheckFailed)
                : result.UpdateAvailable
                    ? LocalizationService.Format(UiText.UpdateAvailableFormat, result.LatestTag)
                    : LocalizationService.Format(
                        UiText.LatestVersionFormat,
                        UpdateService.GetCurrentTag());
        }
        finally
        {
            _checkingUpdate = false;
            if (IsLoaded)
            {
                CheckNowButton.IsEnabled = true;
            }
        }
    }

    private void ThemeSettings_Click(object sender, RoutedEventArgs e)
    {
        new ThemeWindow(_services) { Owner = this }.ShowDialog();
        ThemeEnabledCheck.IsChecked = _services.Theme.IsEnabled;
    }

    private void ExportLog_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new SaveFileDialog
        {
            Title = LocalizationService.Get(UiText.ExportLogsTitle),
            Filter = LocalizationService.Get(UiText.TextFileFilter),
            FileName = "BetterRNP_Modifylog.txt"
        };
        if (dialog.ShowDialog(this) != true)
        {
            _services.Log.Write("用户取消导出日志", "导出日志");
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
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.ExportFailureFormat, ex.Message),
                UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Lock_Click(object sender, RoutedEventArgs e)
    {
        if (_services.Passwords.IsLocked)
        {
            _services.Log.Write("请求解锁配置", "解锁配置");
            var passwordWindow = new PasswordWindow(
                _services,
                UiText.PasswordVerificationTitle,
                UiText.EnterUnlockPassword)
            {
                Owner = this
            };
            passwordWindow.ShowDialog();
            if (passwordWindow.Verified)
            {
                _services.Log.Write("配置已解锁", "LOCK_CHANGED");
                MessageBox.Show(this, UiText.ConfigurationUnlocked, UiText.Notice,
                    MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }
        else
        {
            var result = MessageBox.Show(
                this,
                UiText.ConfirmLockMessage,
                UiText.ConfirmLockTitle,
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);
            if (result == MessageBoxResult.Yes)
            {
                _services.Passwords.Lock();
                MessageBox.Show(this, UiText.ConfigurationLocked, UiText.Notice,
                    MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }
        UpdateLockButton();
    }

    private void UpdateLockButton()
    {
        LocalizationService.SetContent(
            LockButton,
            _services.Passwords.IsLocked
                ? UiText.ConfigurationLockedButton
                : UiText.ConfigurationUnlockedButton);
    }

    private void ChangePassword_Click(object sender, RoutedEventArgs e)
    {
        if (_services.Passwords.IsLocked)
        {
            _services.Log.Write(LogLevel.Warn, "锁定状态下尝试修改密码", "操作被拒绝");
            MessageBox.Show(this, UiText.UnlockBeforeChangingPassword, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        _services.Log.Write("窗口=修改密码", "打开窗口");
        var verify = new PasswordWindow(
            _services,
            UiText.VerifyOriginalPassword,
            UiText.EnterOriginalPassword)
        {
            Owner = this
        };
        verify.ShowDialog();
        if (!verify.Verified)
        {
            return;
        }
        new ChangePasswordWindow(_services) { Owner = this }.ShowDialog();
        DefaultPasswordWarning.Visibility = _services.Passwords.IsUsingBuiltInDefaultPassword()
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
