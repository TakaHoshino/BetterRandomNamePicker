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
        LocalizationService.LanguageChanged += OnLanguageChanged;
        Closed += (_, _) => LocalizationService.LanguageChanged -= OnLanguageChanged;
    }

    private void OnLanguageChanged(object? sender, EventArgs e)
    {
        var selectedName = (SchemeCombo.SelectedItem as Scheme)?.Name;
        SchemeCombo.ItemsSource = null;
        SchemeCombo.ItemsSource = BuildSchemeList();
        SchemeCombo.SelectedItem = SchemeCombo.Items
            .OfType<Scheme>()
            .FirstOrDefault(scheme => scheme.Name == selectedName)
            ?? SchemeCombo.Items.OfType<Scheme>().FirstOrDefault();
        CountText.Text = LocalizationService.Format(UiText.CountFormat, _names.Count);
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
            CountText.Text = LocalizationService.Format(UiText.CountFormat, _names.Count);
        }
        catch (DataCorruptedException)
        {
            _services.Names.DeleteCorruptedCopies(scheme.Name);
            _names.Clear();
            CountText.Text = LocalizationService.Get(UiText.Text0Items);
            _services.Log.Write(LogLevel.Warn, $"方案={scheme.Name}", "名单损坏");
            MessageBox.Show(this, UiText.NamesDamagedReimport, UiText.DataCorruptionWarning,
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
        MessageBox.Show(this, UiText.ConfigurationIsLockedUnlockItFirst, UiText.Notice,
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
        CountText.Text = LocalizationService.Format(UiText.CountFormat, _names.Count);
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
            MessageBox.Show(this, UiText.SelectNameToDelete, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        foreach (var name in selected)
        {
            _names.Remove(name);
        }
        CountText.Text = LocalizationService.Format(UiText.CountFormat, _names.Count);
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
            Filter = LocalizationService.Get(UiText.TextOrAllFilesFilter),
            Title = LocalizationService.Get(UiText.ImportNames)
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
            MessageBox.Show(this, UiText.ImportedSuccessfully, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.ImportFailureFormat, ex.Message),
                UiText.Error,
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
            Filter = LocalizationService.Get(UiText.TextFileFilter),
            FileName = scheme.Name + LocalizationService.Get(UiText.NameListFileSuffix),
            Title = LocalizationService.Get(UiText.ExportNames)
        };
        if (dialog.ShowDialog(this) != true)
        {
            _services.Log.Write("用户取消导出名单", "导出名单");
            return;
        }
        try
        {
            _services.Names.ExportNames(scheme.Name, dialog.FileName);
            MessageBox.Show(this, UiText.ExportedSuccessfully, UiText.Notice,
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
            MessageBox.Show(this, UiText.SavedSuccessfully, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.SaveFailureFormat, ex.Message),
                UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }
}
