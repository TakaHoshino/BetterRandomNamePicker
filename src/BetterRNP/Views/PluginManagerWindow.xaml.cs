using System.Windows;
using System.Windows.Controls;
using BetterRNP.Core;
using BetterRNP.Plugins;
using BetterRNP.PluginSdk.Packaging;
using Microsoft.Win32;

namespace BetterRNP.Views;

public partial class PluginManagerWindow : Window
{
    private readonly PluginManager _plugins;

    public PluginManagerWindow(PluginManager plugins)
    {
        _plugins = plugins;
        InitializeComponent();
        UpdateDirectoryLabel();
        RefreshList();
        LocalizationService.LanguageChanged += OnLanguageChanged;
        Closed += (_, _) => LocalizationService.LanguageChanged -= OnLanguageChanged;
    }

    private void OnLanguageChanged(object? sender, EventArgs e)
    {
        UpdateDirectoryLabel();
        RefreshList();
    }

    private void UpdateDirectoryLabel()
    {
        PluginDirLabel.Text =
            $"{LocalizationService.Get(UiText.PluginFolder)}{_plugins.PluginDirectory}";
    }

    private void RefreshList()
    {
        PluginList.ItemsSource = null;
        PluginList.ItemsSource = _plugins.Plugins;
    }

    private void LoadPluginButton_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = LocalizationService.Get(UiText.SelectPluginPackage),
            Filter = $"BetterRNP {LocalizationService.Get(UiText.Plugins)} (*{RnpPackager.Extension})|*{RnpPackager.Extension}"
        };
        if (dialog.ShowDialog(this) != true)
        {
            return;
        }

        var result = _plugins.LoadFromPackage(dialog.FileName, copyIntoPluginDirectory: true);
        if (!result.Success)
        {
            MessageBox.Show(
                this,
                LocalizationService.Get(UiText.PluginLoadFailed) + "\n" + result.Error,
                UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }

        RefreshList();
        MessageBox.Show(
            this,
            LocalizationService.Format(UiText.PluginLoadedFormat, result.Info!.DisplayName),
            UiText.PluginManager,
            MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void RemoveButton_Click(object sender, RoutedEventArgs e)
    {
        if (PluginList.SelectedItem is not PluginInfo info)
        {
            return;
        }
        if (info.BuiltIn)
        {
            MessageBox.Show(this, UiText.BuiltInPluginCannotBeRemoved, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        var confirm = MessageBox.Show(
            this,
            LocalizationService.Format(UiText.RemovePluginConfirmationFormat, info.DisplayName),
            UiText.ConfirmRemoval,
            MessageBoxButton.YesNo,
            MessageBoxImage.Question);
        if (confirm != MessageBoxResult.Yes)
        {
            return;
        }
        _plugins.RemoveExternal(info.Manifest.Id);
        RemoveButton.IsEnabled = false;
        RefreshList();
    }

    private void ToggleButton_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: string id })
        {
            return;
        }
        var info = _plugins.Plugins.FirstOrDefault(p => p.Manifest.Id == id);
        if (info is null)
        {
            return;
        }
        _plugins.SetEnabled(id, !info.Enabled);
        RefreshList();
    }

    private void RefreshButton_Click(object sender, RoutedEventArgs e) => RefreshList();

    private void PluginList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        RemoveButton.IsEnabled = PluginList.SelectedItem is PluginInfo { BuiltIn: false };
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e) => Close();
}
