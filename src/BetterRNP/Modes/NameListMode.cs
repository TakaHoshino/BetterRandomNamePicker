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
    public string DisplayName => "名字列表模式";
    public string Button1Text => "导入名单";
    public string Button2Text => "导出名单";

    public string? CanPick()
    {
        var scheme = _currentScheme();
        if (scheme is null)
        {
            return "名单已损坏，请重新导入";
        }

        try
        {
            var names = _services.Names.LoadNames(scheme.Name);
            if (names.Count == 0)
            {
                _snapshot = Array.Empty<string>();
                return "名单已损坏，请重新导入";
            }
            _snapshot = names;
            return null;
        }
        catch (DataCorruptedException)
        {
            _snapshot = Array.Empty<string>();
            return "名单已损坏，请重新导入";
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
        MessageBox.Show(owner, "当前为锁定模式，请先解锁", "提示",
            MessageBoxButton.OK, MessageBoxImage.Warning);
        return false;
    }

    private void ShowImport(Window owner)
    {
        var scheme = _currentScheme();
        if (scheme is null)
        {
            MessageBox.Show(owner, "请先选择一个方案！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var dialog = new OpenFileDialog
        {
            Title = "导入名单",
            Filter = "文本文件 (*.txt)|*.txt|所有文件 (*.*)|*.*"
        };
        if (dialog.ShowDialog(owner) != true)
        {
            return;
        }
        try
        {
            _services.Names.ImportNames(scheme.Name, dialog.FileName);
            MessageBox.Show(owner, "名单导入成功！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            _services.Log.Write($"导入失败: {ex.Message}", "IMPORT_ERROR");
            MessageBox.Show(owner, $"导入失败：{ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void ShowExport(Window owner)
    {
        var scheme = _currentScheme();
        if (scheme is null)
        {
            MessageBox.Show(owner, "请先选择一个方案！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var dialog = new SaveFileDialog
        {
            Title = "导出名单",
            Filter = "文本文件 (*.txt)|*.txt",
            FileName = $"{scheme.Name}_名单.txt"
        };
        if (dialog.ShowDialog(owner) != true)
        {
            return;
        }
        try
        {
            _services.Names.ExportNames(scheme.Name, dialog.FileName);
            MessageBox.Show(owner, "名单导出成功！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            _services.Log.Write($"导出失败: {ex.Message}", "EXPORT_ERROR");
            MessageBox.Show(owner, $"导出失败：{ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }
}
