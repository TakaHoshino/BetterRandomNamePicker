using System.Windows;
using BetterRNP.Core;
using BetterRNP.Models;
using Microsoft.Win32;

namespace BetterRNP.Modes;

public sealed class NameListMode : IPickMode
{
    private readonly AppServices _services;
    private readonly Func<Scheme?> _currentScheme;
    private IReadOnlyList<string> _snapshot = Array.Empty<string>();

    public NameListMode(AppServices services, Func<Scheme?> currentScheme)
    {
        _services = services;
        _currentScheme = currentScheme;
    }

    public string Id => "name_list";
    public string DisplayName => LocalizationService.Get(UiText.NameList);
    public string Button1Text => LocalizationService.Get(UiText.ImportNames);
    public string Button2Text => LocalizationService.Get(UiText.ExportNames);

    public string? CanPick()
    {
        var scheme = _currentScheme();
        if (scheme is null)
        {
        return LocalizationService.Get(UiText.NamesDamagedReimport);
        }

        try
        {
            var names = _services.Names.LoadNames(scheme.Name);
            if (names.Count == 0)
            {
                _snapshot = Array.Empty<string>();
        return LocalizationService.Get(UiText.NamesDamagedReimport);
            }
            _snapshot = names;
            return null;
        }
        catch (DataCorruptedException)
        {
            _snapshot = Array.Empty<string>();
        return LocalizationService.Get(UiText.NamesDamagedReimport);
        }
    }

    public Func<string> CreateCandidateSupplier()
    {
        var snapshot = _snapshot;
        return () => snapshot.Count == 0 ? string.Empty : snapshot[Random.Shared.Next(snapshot.Count)];
    }

    public void HandleButton1(Window owner)
    {
        if (RequireUnlocked(owner))
        {
            ShowImport(owner);
        }
    }

    public void HandleButton2(Window owner)
    {
        if (RequireUnlocked(owner))
        {
            ShowExport(owner);
        }
    }

    private bool RequireUnlocked(Window owner)
    {
        if (!_services.Passwords.IsLocked)
        {
            return true;
        }
        MessageBox.Show(owner, UiText.ConfigurationIsLockedUnlockItFirst, UiText.Notice,
            MessageBoxButton.OK, MessageBoxImage.Warning);
        return false;
    }

    private void ShowImport(Window owner)
    {
        var scheme = _currentScheme();
        if (scheme is null)
        {
        MessageBox.Show(owner, UiText.PleaseSelectScheme, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var dialog = new OpenFileDialog
        {
            Title = LocalizationService.Get(UiText.ImportNames),
            Filter = LocalizationService.Get(UiText.TextOrAllFilesFilter)
        };
        if (dialog.ShowDialog(owner) != true)
        {
            return;
        }
        try
        {
            _services.Names.ImportNames(scheme.Name, dialog.FileName);
        MessageBox.Show(owner, UiText.NamesImportedSuccessfully, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            _services.Log.Write(LogLevel.Error, $"导入失败: {ex.Message}", "IMPORT_ERROR");
        MessageBox.Show(
            owner,
            LocalizationService.Format(UiText.ImportFailureFormat, ex.Message),
            UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void ShowExport(Window owner)
    {
        var scheme = _currentScheme();
        if (scheme is null)
        {
        MessageBox.Show(owner, UiText.PleaseSelectScheme, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var dialog = new SaveFileDialog
        {
            Title = LocalizationService.Get(UiText.ExportNames),
            Filter = LocalizationService.Get(UiText.TextFileFilter),
            FileName = scheme.Name + LocalizationService.Get(UiText.NameListFileSuffix)
        };
        if (dialog.ShowDialog(owner) != true)
        {
            return;
        }
        try
        {
            _services.Names.ExportNames(scheme.Name, dialog.FileName);
        MessageBox.Show(owner, UiText.NamesExportedSuccessfully, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            _services.Log.Write(LogLevel.Error, $"导出失败: {ex.Message}", "EXPORT_ERROR");
        MessageBox.Show(
            owner,
            LocalizationService.Format(UiText.ExportFailureFormat, ex.Message),
            UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }
}
