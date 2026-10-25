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
    public string DisplayName => LocalizationService.Get(UiText.Numbers);
    public string Button1Text => LocalizationService.Get(UiText.SetNumberRange);
    public string Button2Text => LocalizationService.Get(UiText.ViewCurrentSettings);

    public string? CanPick()
    {
        var scheme = _currentScheme();
        var range = scheme is null ? null : _services.Schemes.GetNumberRange(scheme.Name);
        _snapshot = range;
        return range is null ? LocalizationService.Get(UiText.NumberRangeRequired) : null;
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
            MessageBox.Show(owner, UiText.PleaseSelectScheme, UiText.Notice,
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
            MessageBox.Show(owner, UiText.PleaseSelectScheme, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var range = _services.Schemes.GetNumberRange(scheme.Name);
        if (range is null)
        {
            MessageBox.Show(
                owner,
                UiText.NoNumberRangeIsConfiguredForThisScheme,
                UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        _services.Log.Write($"方案={scheme.Name}｜范围=[{range.Min},{range.Max}]", "查看数字范围");
        MessageBox.Show(
            owner,
            LocalizationService.Format(UiText.CurrentNumberRangeFormat, range.Min, range.Max),
            UiText.Notice,
            MessageBoxButton.OK,
            MessageBoxImage.Information);
    }
}
