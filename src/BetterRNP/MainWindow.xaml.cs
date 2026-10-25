using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using BetterRNP.Core;
using BetterRNP.Modes;
using BetterRNP.Models;
using BetterRNP.Plugins;
using BetterRNP.Views;

namespace BetterRNP;

public partial class MainWindow : Window
{
    private readonly AppServices _services;
    private readonly PluginManager _plugins;
    private readonly ObservableCollection<Scheme> _schemes = new();
    private readonly ObservableCollection<string> _modeNames = new();
    private RollingPicker? _rollingPicker;
    private IPickMode? _currentMode;
    private bool _suppressEvents;
    private bool _isPicking;
    private string? _pickSchemeName;
    private string? _pickModeName;
    private bool _startupUpdateCheckStarted;

    public MainWindow(AppServices services)
    {
        _services = services;
        InitializeComponent();
        _plugins = new PluginManager(
            services,
            CreateCurrentPluginModeContext,
            CreateCurrentPickerPluginSnapshot);
        SchemeCombo.ItemsSource = _schemes;
        ModeCombo.ItemsSource = _modeNames;
        _plugins.PluginsChanged += OnPluginsChanged;
        ThemeEnabledItem.IsChecked = _services.Theme.IsEnabled;
        _services.Theme.Attach(this, ThemeService.CategoryMain);
        RefreshPluginMenu();
        RefreshModeNames(null);
        Loaded += OnLoaded;
        Closing += OnClosing;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        _services.Log.Write("主窗口已显示", "显示主窗口");
        LoadSchemes(_services.Config.LastScheme);
        ModeCombo.SelectedIndex = 0;
        UpdateModeSpecificButtons();
        UpdateDisplayText();
        if (!_startupUpdateCheckStarted)
        {
            _startupUpdateCheckStarted = true;
            _ = CheckUpdateOnStartupAsync();
        }
    }

    private void LoadSchemes(string? selectName = null)
    {
        var selected = selectName ?? (SchemeCombo.SelectedItem as Scheme)?.Name;
        _suppressEvents = true;
        try
        {
            _schemes.Clear();
            _schemes.Add(new Scheme(Scheme.BuiltInDefaultName, Scheme.NameListType, true));
            foreach (var scheme in _services.Schemes.GetAllSchemes())
            {
                _schemes.Add(scheme);
            }
            SchemeCombo.ItemsSource = null;
            SchemeCombo.ItemsSource = _schemes;

            var match = _schemes.FirstOrDefault(s => s.Name == selected)
                ?? _schemes.FirstOrDefault(s => s.Name == _services.Config.LastScheme)
                ?? _schemes[0];
            SchemeCombo.SelectedItem = match;
        }
        finally
        {
            _suppressEvents = false;
        }
    }

    private Scheme? CurrentScheme => SchemeCombo.SelectedItem as Scheme;

    private void SchemeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressEvents || CurrentScheme is null)
        {
            return;
        }

        _services.Config.SetLastScheme(CurrentScheme.Name);
        var targetMode = CurrentScheme.Type switch
        {
            Scheme.NumberType => LocalizationService.Get(UiText.Numbers),
            Scheme.SeatType => LocalizationService.Get(UiText.Seats),
            _ => LocalizationService.Get(UiText.NameList)
        };
        _suppressEvents = true;
        ModeCombo.SelectedItem = targetMode;
        _suppressEvents = false;
        UpdateModeSpecificButtons();
        UpdateDisplayText();
        _services.Log.Write(
            $"方案={CurrentScheme.Name}｜类型={CurrentScheme.Type}｜默认模式={targetMode}", "切换方案");
    }

    private void ModeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressEvents)
        {
            return;
        }
        UpdateModeSpecificButtons();
        UpdateDisplayText();
        if (ModeCombo.SelectedItem is string modeName)
        {
            _services.Log.Write($"模式={modeName}", "切换模式");
        }
    }

    private void UpdateModeSpecificButtons()
    {
        var name = ModeCombo.SelectedItem as string;
        _currentMode = CreateMode(name);

        ApplyModeButton(ModeButton1, _currentMode?.Button1Text);
        ApplyModeButton(ModeButton2, _currentMode?.Button2Text);
    }

    private static void ApplyModeButton(Button button, string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            button.Visibility = Visibility.Collapsed;
            LocalizationService.SetContent(button, string.Empty);
        }
        else
        {
            button.Visibility = Visibility.Visible;
            LocalizationService.SetContent(button, text);
        }
    }

    private void UpdateDisplayText()
    {
        var modeName = ModeCombo.SelectedItem as string;
        DisplayText.Text = string.IsNullOrWhiteSpace(modeName)
            ? LocalizationService.Get(UiText.SelectASchemeAndModeToStartPicking)
            : $"{LocalizationService.T(modeName)}: {LocalizationService.Get(UiText.ClickStartToPick)}";
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
        var scheme = CurrentScheme;
        if (scheme is null)
        {
            MessageBox.Show(this, UiText.PleaseSelectScheme, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        if (_currentMode is null)
        {
            MessageBox.Show(this, UiText.PleaseSelectPickerMode, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var blockReason = _currentMode.CanPick();
        if (blockReason is not null)
        {
            _services.Log.Write(
                LogLevel.Warn,
                $"方案={scheme.Name}｜模式={_currentMode.DisplayName}｜原因={blockReason}",
                "抽取被拦截");
            MessageBox.Show(this, blockReason,
                _currentMode.Id == "name_list" ? UiText.Error : UiText.Notice,
                MessageBoxButton.OK,
                _currentMode.Id == "name_list" ? MessageBoxImage.Error : MessageBoxImage.Warning);
            return;
        }

        _isPicking = true;
        LocalizationService.SetContent(PickButton, UiText.Stop);
        _pickSchemeName = scheme.Name;
        _pickModeName = _currentMode.DisplayName;
        _rollingPicker = new RollingPicker(
            _currentMode.CreateCandidateSupplier(),
            candidate => DisplayText.Text = candidate);
        _rollingPicker.Start();
        _services.Log.Write(
            $"方案={scheme.Name}｜模式={_currentMode.DisplayName}", "开始抽取");
    }

    private void StopPicking()
    {
        _isPicking = false;
        LocalizationService.SetContent(PickButton, UiText.Start);
        _rollingPicker?.Stop();
        _rollingPicker = null;
        if (_pickSchemeName is not null && _pickModeName is not null)
        {
            _services.Log.Write(
                $"方案={_pickSchemeName}｜模式={_pickModeName}｜结果={DisplayText.Text}", "抽取结果");
        }
        _pickSchemeName = null;
        _pickModeName = null;
        RestorePresentationFocus();
    }

    private void RestorePresentationFocus()
    {
        if (Application.Current is App { IsShuttingDown: true })
        {
            return;
        }

        Dispatcher.BeginInvoke(new Action(() =>
        {
            if (PresentationFocusService.TryRestore())
            {
                _services.Log.Write("已恢复演示文稿放映窗口焦点", "PPT_FOCUS");
            }
        }), DispatcherPriority.Background);
    }

    private void Names_Click(object sender, RoutedEventArgs e) => ShowNameConfigWindow();

    private void Schemes_Click(object sender, RoutedEventArgs e) => ShowSchemes();

    private void ShowSchemes()
    {
        _services.Log.Write("窗口=方案管理", "打开窗口");
        new SchemeManagerWindow(_services) { Owner = this }.ShowDialog();
        LoadSchemes();
        UpdateModeSpecificButtons();
    }

    private void Settings_Click(object sender, RoutedEventArgs e) => ShowSettingsWindow();

    private void ThemeEnabled_Click(object sender, RoutedEventArgs e)
    {
        _services.Theme.IsEnabled = ThemeEnabledItem.IsChecked;
        _services.Log.Write(
            $"照片背景：{(ThemeEnabledItem.IsChecked ? "启用" : "停用")}", "SETTING_CHANGED");
    }

    private void ThemeSettings_Click(object sender, RoutedEventArgs e)
    {
        _services.Log.Write("窗口=背景主题", "打开窗口");
        new ThemeWindow(_services) { Owner = this }.ShowDialog();
        ThemeEnabledItem.IsChecked = _services.Theme.IsEnabled;
    }

    private void Floating_Click(object sender, RoutedEventArgs e)
    {
        (Application.Current as App)?.ToggleFloatingBall();
    }

    public void RefreshFloatingBallButton()
    {
        var app = Application.Current as App;
        LocalizationService.SetContent(
            FloatingButton,
            app?.IsFloatingBallVisible == true
                ? UiText.HideFloatingBall
                : UiText.FloatingBall);
    }

    public void RefreshLanguage()
    {
        var modeId = _currentMode?.Id;
        LoadSchemes(CurrentScheme?.Name);
        RefreshModeNames(null, modeId);
        UpdateModeSpecificButtons();
        UpdateDisplayText();
        RefreshPluginMenu();
    }

    private void ModeButton1_Click(object sender, RoutedEventArgs e)
    {
        _services.Log.Write(
            $"模式={_currentMode?.DisplayName}｜按钮={ModeButton1.Content}", "模式按钮");
        try
        {
            _currentMode?.HandleButton1(this);
        }
        catch (Exception ex)
        {
            _services.Log.WriteException("MODE_ACTION_ERROR", ex);
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.ModeButtonErrorFormat, ex.Message),
                UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void ModeButton2_Click(object sender, RoutedEventArgs e)
    {
        _services.Log.Write(
            $"模式={_currentMode?.DisplayName}｜按钮={ModeButton2.Content}", "模式按钮");
        try
        {
            _currentMode?.HandleButton2(this);
        }
        catch (Exception ex)
        {
            _services.Log.WriteException("MODE_ACTION_ERROR", ex);
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.ModeButtonErrorFormat, ex.Message),
                UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void About_Click(object sender, RoutedEventArgs e)
    {
        _services.Log.Write("窗口=关于", "打开窗口");
        new AboutWindow { Owner = this }.ShowDialog();
    }

    private void ShowLog_Click(object sender, RoutedEventArgs e) => LogWindow.ShowFor(this, _services);

    private async void CheckUpdate_Click(object sender, RoutedEventArgs e)
    {
        _services.Log.Write("手动检查更新", "检查更新");
        await UpdatePresenter.CheckAsync(this, _services, true);
    }

    private async Task CheckUpdateOnStartupAsync()
    {
        try
        {
            if (!_services.UpdateSettings.Load().CheckOnStartup)
            {
                return;
            }
            await Task.Delay(TimeSpan.FromSeconds(3));
            if (!IsVisible)
            {
                return;
            }
            await UpdatePresenter.CheckAsync(this, _services, false);
        }
        catch (Exception ex)
        {
            _services.Log.Write(LogLevel.Error, $"启动时检查更新失败: {ex.Message}", "UPDATE_CHECK_ERROR");
        }
    }

    private void OnPluginsChanged(object? sender, EventArgs e)
    {
        Dispatcher.Invoke(() =>
        {
            RefreshPluginMenu();
            RefreshModeNames(ModeCombo.SelectedItem as string, _currentMode?.Id);
            UpdateModeSpecificButtons();
        });
    }

    private void RefreshPluginMenu()
    {
        PluginsMenu.Items.Clear();
        var managerItem = new MenuItem { Header = LocalizationService.Get(UiText.ManagePlugins) };
        managerItem.Click += (_, _) =>
        {
            _services.Log.Write("窗口=插件管理", "打开窗口");
            new PluginManagerWindow(_plugins) { Owner = this }.ShowDialog();
        };
        PluginsMenu.Items.Add(managerItem);

        var commands = _plugins.Commands;
        if (commands.Count == 0)
        {
            return;
        }
        PluginsMenu.Items.Add(new Separator());
        foreach (var command in commands)
        {
            var item = new MenuItem { Header = command.DisplayTitle };
            item.Click += (_, _) => _plugins.ExecuteCommand(command, this);
            PluginsMenu.Items.Add(item);
        }
    }

    private void RefreshModeNames(string? preferred, string? preferredModeId = null)
    {
        var previous = preferred ?? ModeCombo.SelectedItem as string;
        _suppressEvents = true;
        try
        {
            _modeNames.Clear();
            _modeNames.Add(LocalizationService.Get(UiText.NameList));
            _modeNames.Add(LocalizationService.Get(UiText.Numbers));
            _modeNames.Add(LocalizationService.Get(UiText.Seats));
            foreach (var registration in _plugins.Modes)
            {
                if (!_modeNames.Contains(registration.Mode.DisplayName))
                {
                    _modeNames.Add(registration.Mode.DisplayName);
                }
            }
            var target = preferredModeId switch
            {
                "name_list" => LocalizationService.Get(UiText.NameList),
                "number" => LocalizationService.Get(UiText.Numbers),
                "seat" => LocalizationService.Get(UiText.Seats),
                not null => _plugins.Modes
                    .FirstOrDefault(mode => mode.Mode.Id == preferredModeId)
                    ?.Mode.DisplayName,
                _ => null
            };
            ModeCombo.SelectedItem = target is not null && _modeNames.Contains(target)
                ? target
                : previous is not null && _modeNames.Contains(previous)
                    ? previous
                    : _modeNames[0];
        }
        finally
        {
            _suppressEvents = false;
        }
    }

    public void ShutdownPlugins() => _plugins.Dispose();

    public PluginManager Plugins => _plugins;

    public Scheme? SelectedScheme => CurrentScheme;

    public IPickMode? BuildCurrentMode() => CreateMode(ModeCombo.SelectedItem as string);

    private PluginSdk.Abstractions.IPluginModeContext? CreateCurrentPluginModeContext()
    {
        if (CurrentScheme is not { } scheme)
        {
            return null;
        }

        return SchemePluginModeContext.Create(
            _services,
            scheme,
            message => MessageBox.Show(
                this,
                message,
                UiText.PluginNotice,
                MessageBoxButton.OK,
                MessageBoxImage.Information));
    }

    private PluginSdk.Abstractions.IPluginPickerSnapshot? CreateCurrentPickerPluginSnapshot()
    {
        if (CurrentScheme is not { } scheme || BuildCurrentMode() is not { } mode)
        {
            return null;
        }

        return SchemePluginModeContext.CreatePicker(
            _services,
            scheme,
            mode,
            message => MessageBox.Show(
                this,
                message,
                UiText.PluginNotice,
                MessageBoxButton.OK,
                MessageBoxImage.Information));
    }

    private IPickMode? CreateMode(string? displayName)
    {
        if (string.IsNullOrWhiteSpace(displayName))
        {
            return null;
        }
        switch (displayName)
        {
            case var modeName when modeName == LocalizationService.Get(UiText.Numbers):
                return new NumberMode(_services, () => CurrentScheme);
            case var modeName when modeName == LocalizationService.Get(UiText.Seats):
                return new SeatMode(_services, () => CurrentScheme);
            case var modeName when modeName == LocalizationService.Get(UiText.NameList):
                return new NameListMode(_services, () => CurrentScheme);
        }

        var pluginMode = _plugins.Modes.FirstOrDefault(registration =>
            string.Equals(registration.Mode.DisplayName, displayName, StringComparison.Ordinal));
        return pluginMode is not null && CurrentScheme is not null
            ? new PluginPickModeAdapter(pluginMode, _services, CurrentScheme, this)
            : null;
    }

    public void ShowNameConfigWindow()
    {
        _services.Log.Write(
            $"窗口=名单配置｜方案={CurrentScheme?.Name ?? Scheme.BuiltInDefaultName}", "打开窗口");
        new NameConfigWindow(_services) { Owner = this }.ShowDialog();
    }

    public void ShowSettingsWindow()
    {
        _services.Log.Write("窗口=系统设置", "打开窗口");
        new SettingsWindow(_services, _plugins) { Owner = this }.ShowDialog();
        ThemeEnabledItem.IsChecked = _services.Theme.IsEnabled;
    }

    public void ShowNumberSettingsWindow()
    {
        if (CurrentScheme is not null)
        {
            _services.Log.Write($"窗口=数字设置｜方案={CurrentScheme.Name}", "打开窗口");
            new NumberSettingsWindow(_services, CurrentScheme.Name) { Owner = this }.ShowDialog();
        }
    }

    public void ShowSeatSettingsWindow()
    {
        if (CurrentScheme is not null)
        {
            _services.Log.Write($"窗口=座位设置｜方案={CurrentScheme.Name}", "打开窗口");
            new SeatSettingsWindow(_services, CurrentScheme.Name) { Owner = this }.ShowDialog();
        }
    }

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        var app = Application.Current as App;
        if (app?.IsShuttingDown == true)
        {
            return;
        }

        if (_isPicking)
        {
            StopPicking();
        }

        if (_services.Config.MinimizeToTray)
        {
            e.Cancel = true;
            _services.Log.Write("主窗口已最小化到托盘", "隐藏主窗口");
            Hide();
        }
        else
        {
            e.Cancel = true;
            app?.ExitApplication(true);
        }
    }
}
