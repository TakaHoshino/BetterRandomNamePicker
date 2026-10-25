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
        LocalizationService.LanguageChanged += OnLanguageChanged;
        Closed += (_, _) => LocalizationService.LanguageChanged -= OnLanguageChanged;
    }

    private void OnLanguageChanged(object? sender, EventArgs e)
    {
        var selectedName = (SchemeList.SelectedItem as Scheme)?.Name;
        ReloadSchemes();
        SchemeList.SelectedItem = SchemeList.Items
            .OfType<Scheme>()
            .FirstOrDefault(scheme => scheme.Name == selectedName);
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
            MessageBox.Show(this, UiText.EnterSchemeName, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        if (name.Length > MaxNameLength)
        {
            _services.Log.Write(LogLevel.Warn, $"名称={name}｜长度={name.Length}", "创建方案被拒绝");
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.SchemeNameTooLongFormat, MaxNameLength),
                UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        if (name.IndexOfAny(InvalidCharacters.ToCharArray()) >= 0)
        {
            _services.Log.Write(LogLevel.Warn, $"名称={name}", "创建方案被拒绝");
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.SchemeNameInvalidCharactersFormat, InvalidCharacters),
                UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        if (name == Scheme.BuiltInDefaultName)
        {
            _services.Log.Write(LogLevel.Warn, $"名称={name}", "创建方案被拒绝");
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.BuiltInSchemeNameReservedFormat, name),
                UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        if (!_services.Schemes.AddScheme(name, type))
        {
            _services.Log.Write(LogLevel.Warn, $"名称={name}｜类型={type}", "方案已存在");
            MessageBox.Show(this, UiText.SchemeNameExists, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        SchemeNameInput.Clear();
        ReloadSchemes();
        MessageBox.Show(this, UiText.SchemeCreated, UiText.Notice,
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
            MessageBox.Show(this, UiText.SelectSchemeToDelete, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var confirm = MessageBox.Show(
            this,
            LocalizationService.Format(UiText.DeleteSchemeConfirmationFormat, scheme.Name),
            UiText.ConfirmDeletion,
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);
        if (confirm != MessageBoxResult.Yes)
        {
            _services.Log.Write($"方案={scheme.Name}", "取消删除方案");
            return;
        }
        _services.Schemes.RemoveScheme(scheme.Name);
        ReloadSchemes();
        MessageBox.Show(this, UiText.SchemeDeleted, UiText.Notice,
            MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private bool RequireUnlocked()
    {
        if (!_services.Passwords.IsLocked)
        {
            return true;
        }
        _services.Log.Write(LogLevel.Warn, "方案管理窗口处于锁定状态", "操作被拒绝");
        MessageBox.Show(this, UiText.ConfigurationIsLockedUnlockItFirst, UiText.Notice,
            MessageBoxButton.OK, MessageBoxImage.Warning);
        return false;
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
