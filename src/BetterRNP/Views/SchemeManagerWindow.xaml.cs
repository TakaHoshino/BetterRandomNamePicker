using System.Windows;
using System.Windows.Controls;
using BetterRNP.Core;
using BetterRNP.Models;

namespace BetterRNP.Views;

public partial class SchemeManagerWindow : Window
{
    private const int MaxNameLength = 30;
    private const string InvalidCharacters = ",\\/:*?\"<>|";
    private readonly AppServices _services;

    public SchemeManagerWindow(AppServices services)
    {
        _services = services;
        InitializeComponent();
        _services.Theme.Attach(this, ThemeService.CategoryScheme);
        ReloadSchemes();
    }

    private void ReloadSchemes()
    {
        SchemeList.ItemsSource = _services.Schemes.GetAllSchemes();
        foreach (var scheme in _services.Schemes.GetAllSchemes()
                     .Where(s => s.Name == Scheme.BuiltInDefaultName))
        {
            _services.Log.Write(LogLevel.Warn, $"检测到与内置方案同名的自建方案: {scheme.Name}", "SCHEME_NAME_COLLISION");
        }
    }

    private void Create_Click(object sender, RoutedEventArgs e)
    {
        if (!RequireUnlocked())
        {
            return;
        }

        var name = SchemeNameInput.Text.Trim();
        var type = (TypeCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? string.Empty;
        if (name.Length == 0)
        {
            _services.Log.Write(LogLevel.Warn, "方案名称为空", "创建方案被拒绝");
            MessageBox.Show(this, "请输入方案名称！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        if (name.Length > MaxNameLength)
        {
            _services.Log.Write(LogLevel.Warn, $"名称={name}｜长度={name.Length}", "创建方案被拒绝");
            MessageBox.Show(this, $"方案名称过长（上限 {MaxNameLength} 个字符）！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        if (name.IndexOfAny(InvalidCharacters.ToCharArray()) >= 0)
        {
            _services.Log.Write(LogLevel.Warn, $"名称={name}", "创建方案被拒绝");
            MessageBox.Show(this, $"方案名称不能包含以下字符：{InvalidCharacters}", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        if (name == Scheme.BuiltInDefaultName)
        {
            _services.Log.Write(LogLevel.Warn, $"名称={name}", "创建方案被拒绝");
            MessageBox.Show(this, $"“{name}”为内置方案保留名称，请换一个名称！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        if (!_services.Schemes.AddScheme(name, type))
        {
            _services.Log.Write(LogLevel.Warn, $"名称={name}｜类型={type}", "方案已存在");
            MessageBox.Show(this, "方案名称已存在！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        SchemeNameInput.Clear();
        ReloadSchemes();
        MessageBox.Show(this, "方案创建成功！", "提示",
            MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (!RequireUnlocked())
        {
            return;
        }
        if (SchemeList.SelectedItem is not Scheme scheme)
        {
            MessageBox.Show(this, "请选择要删除的方案！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var confirm = MessageBox.Show(this,
            $"确定要删除方案 \"{scheme.Name}\" 吗？这将删除所有相关数据。",
            "确认删除", MessageBoxButton.YesNo, MessageBoxImage.Warning);
        if (confirm != MessageBoxResult.Yes)
        {
            _services.Log.Write($"方案={scheme.Name}", "取消删除方案");
            return;
        }
        _services.Schemes.RemoveScheme(scheme.Name);
        ReloadSchemes();
        MessageBox.Show(this, "方案删除成功！", "提示",
            MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private bool RequireUnlocked()
    {
        if (!_services.Passwords.IsLocked)
        {
            return true;
        }
        _services.Log.Write(LogLevel.Warn, "方案管理窗口处于锁定状态", "操作被拒绝");
        MessageBox.Show(this, "当前为锁定模式，请先解锁", "提示",
            MessageBoxButton.OK, MessageBoxImage.Warning);
        return false;
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
