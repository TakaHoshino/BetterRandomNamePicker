using System.Windows;
using System.Windows.Controls;
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
        PluginDirLabel.Text = $"插件目录：{_plugins.PluginDirectory}";
        RefreshList();
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
            Title = "选择插件包",
            Filter = $"BetterRNP 插件 (*{RnpPackager.Extension})|*{RnpPackager.Extension}"
        };
        if (dialog.ShowDialog(this) != true)
        {
            return;
        }

        var result = _plugins.LoadFromPackage(dialog.FileName, copyIntoPluginDirectory: true);
        if (!result.Success)
        {
            MessageBox.Show(this, $"插件加载失败：\n{result.Error}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }

        RefreshList();
        MessageBox.Show(this, $"插件已加载：{result.Info!.DisplayName}", "插件管理",
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
            MessageBox.Show(this, "内置插件不可移除。", "提示",
                MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        var confirm = MessageBox.Show(this,
            $"确定移除插件「{info.DisplayName}」吗？\n（将同时删除其 .rnpp 文件）",
            "确认移除", MessageBoxButton.YesNo, MessageBoxImage.Question);
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
