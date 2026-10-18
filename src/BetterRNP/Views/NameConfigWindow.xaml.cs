using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using BetterRNP.Core;
using BetterRNP.Models;
using Microsoft.Win32;

namespace BetterRNP.Views;

public partial class NameConfigWindow : Window
{
    private readonly AppServices _services;
    private readonly ObservableCollection<string> _names = new();

    public NameConfigWindow(AppServices services)
    {
        _services = services;
        InitializeComponent();
        NameList.ItemsSource = _names;
        SchemeCombo.ItemsSource = BuildSchemeList();
        _services.Theme.Attach(this, ThemeService.CategoryConfig);
        Loaded += (_, _) =>
        {
            if (SchemeCombo.Items.Count > 0)
            {
                SchemeCombo.SelectedIndex = 0;
            }
            NameInput.Focus();
        };
    }

    private List<Scheme> BuildSchemeList()
    {
        var list = new List<Scheme>
        {
            new(Scheme.BuiltInDefaultName, Scheme.NameListType, true)
        };
        list.AddRange(_services.Schemes.GetAllSchemes());
        return list;
    }

    private void SchemeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        LoadNames();
        if (SchemeCombo.SelectedItem is Scheme scheme)
        {
            _services.Log.Write(
                $"方案={scheme.Name}｜名单数={_names.Count}", "名单配置_切换方案");
        }
    }

    private void LoadNames()
    {
        if (SchemeCombo.SelectedItem is not Scheme scheme)
        {
            return;
        }
        try
        {
            var names = _services.Names.LoadNames(scheme.Name);
            _names.Clear();
            foreach (var name in names)
            {
                _names.Add(name);
            }
            CountText.Text = $"共 {_names.Count} 条";
        }
        catch (DataCorruptedException)
        {
            _services.Names.DeleteCorruptedCopies(scheme.Name);
            _names.Clear();
            CountText.Text = "共 0 条";
            _services.Log.Write(LogLevel.Warn, $"方案={scheme.Name}", "名单损坏");
            MessageBox.Show(this, "名单已损坏，请重新导入", "数据损坏警告",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private bool RequireUnlocked()
    {
        if (!_services.Passwords.IsLocked)
        {
            return true;
        }
        _services.Log.Write(LogLevel.Warn, "名单配置窗口处于锁定状态", "操作被拒绝");
        MessageBox.Show(this, "当前为锁定模式，请先解锁", "提示",
            MessageBoxButton.OK, MessageBoxImage.Warning);
        return false;
    }

    private void Add_Click(object sender, RoutedEventArgs e) => AddName();

    private void AddName()
    {
        if (!RequireUnlocked())
        {
            return;
        }
        var name = NameInput.Text.Trim();
        if (name.Length == 0)
        {
            return;
        }
        _names.Add(name);
        NameInput.Clear();
        NameInput.Focus();
        CountText.Text = $"共 {_names.Count} 条";
        _services.Log.Write(
            $"方案={CurrentSchemeName()}｜姓名={name}｜共{_names.Count}条", "添加姓名");
    }

    private void NameInput_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            AddName();
            e.Handled = true;
        }
    }

    private void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (!RequireUnlocked())
        {
            return;
        }
        var selected = NameList.SelectedItems.Cast<string>().ToList();
        if (selected.Count == 0)
        {
            MessageBox.Show(this, "请选择要删除的姓名", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        foreach (var name in selected)
        {
            _names.Remove(name);
        }
        CountText.Text = $"共 {_names.Count} 条";
        _services.Log.Write(
            $"方案={CurrentSchemeName()}｜姓名={string.Join("、", selected)}｜剩余{_names.Count}条", "删除姓名");
    }

    private void Import_Click(object sender, RoutedEventArgs e)
    {
        if (!RequireUnlocked() || SchemeCombo.SelectedItem is not Scheme scheme)
        {
            return;
        }
        var dialog = new OpenFileDialog
        {
            Filter = "文本文件 (*.txt)|*.txt|所有文件 (*.*)|*.*",
            Title = "导入名单"
        };
        if (dialog.ShowDialog(this) != true)
        {
            _services.Log.Write("用户取消导入名单", "导入名单");
            return;
        }
        try
        {
            _services.Names.ImportNames(scheme.Name, dialog.FileName);
            LoadNames();
            MessageBox.Show(this, "导入成功！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"导入失败：{ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Export_Click(object sender, RoutedEventArgs e)
    {
        if (!RequireUnlocked() || SchemeCombo.SelectedItem is not Scheme scheme)
        {
            return;
        }
        var dialog = new SaveFileDialog
        {
            Filter = "文本文件 (*.txt)|*.txt",
            FileName = $"{scheme.Name}_名单.txt",
            Title = "导出名单"
        };
        if (dialog.ShowDialog(this) != true)
        {
            _services.Log.Write("用户取消导出名单", "导出名单");
            return;
        }
        try
        {
            _services.Names.ExportNames(scheme.Name, dialog.FileName);
            MessageBox.Show(this, "导出成功！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"导出失败：{ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private string CurrentSchemeName()
        => (SchemeCombo.SelectedItem as Scheme)?.Name ?? Scheme.BuiltInDefaultName;

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        if (!RequireUnlocked() || SchemeCombo.SelectedItem is not Scheme scheme)
        {
            return;
        }
        try
        {
            _services.Names.SaveNames(scheme.Name, _names);
            MessageBox.Show(this, "保存成功！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"保存失败：{ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }
}
