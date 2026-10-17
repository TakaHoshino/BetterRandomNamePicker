using System.Windows;
using BetterRNP.Core;
using BetterRNP.Models;
using BetterRNP.Views;

namespace BetterRNP.Modes;

public sealed class NumberMode : IPickMode
{
    private readonly AppServices _services;
    private readonly Func<Scheme?> _currentScheme;
    private NumberRange? _snapshot;

    public NumberMode(AppServices services, Func<Scheme?> currentScheme)
    {
        _services = services;
        _currentScheme = currentScheme;
    }

    public string Id => "number";
    public string DisplayName => "数字模式";
    public string Button1Text => "设置数字范围";
    public string Button2Text => "查看当前设置";

    public string? CanPick()
    {
        var scheme = _currentScheme();
        var range = scheme is null ? null : _services.Schemes.GetNumberRange(scheme.Name);
        _snapshot = range;
        return range is null ? "请先设置数字范围！" : null;
    }

    public Func<string> CreateCandidateSupplier()
    {
        var range = _snapshot;
        return () =>
        {
            if (range is null)
            {
                return string.Empty;
            }
            return Random.Shared.NextInt64(range.Min, (long)range.Max + 1).ToString();
        };
    }

    public void HandleButton1(Window owner)
    {
        var scheme = _currentScheme();
        if (scheme is null)
        {
            MessageBox.Show(owner, "请先选择一个方案！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        new NumberSettingsWindow(_services, scheme.Name) { Owner = owner }.ShowDialog();
    }

    public void HandleButton2(Window owner)
    {
        var scheme = _currentScheme();
        if (scheme is null)
        {
            MessageBox.Show(owner, "请先选择一个方案！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var range = _services.Schemes.GetNumberRange(scheme.Name);
        if (range is null)
        {
            MessageBox.Show(owner, "当前方案尚未设置数字范围！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        _services.Log.Write($"{scheme.Name}-[{range.Min},{range.Max}]", "查看数字范围");
        MessageBox.Show(owner,
            $"当前数字范围：[{range.Min}, {range.Max}]\n（修改请在“设置数字范围”中保存）",
            "提示", MessageBoxButton.OK, MessageBoxImage.Information);
    }
}
