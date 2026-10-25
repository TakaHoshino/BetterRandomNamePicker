using System.Windows;
using BetterRNP.Core;

namespace BetterRNP;

internal static class MessageBox
{
    private static readonly string[] MessagePrefixKeys =
    [
        UiText.ImportFailed,
        UiText.ExportFailed,
        UiText.SaveFailed,
        UiText.PluginLoadFailed,
        UiText.PluginCommandFailed,
        UiText.PluginModeActionFailed,
        UiText.UpdateCheckFailed,
        UiText.UpdateFailed,
        UiText.SettingsAppliedToScheme,
        UiText.LogExportedTo,
        UiText.RangeIsTooLarge,
        UiText.TooManyRowsColumns
    ];

    public static MessageBoxResult Show(string messageBoxText)
        => System.Windows.MessageBox.Show(Translate(messageBoxText));

    public static MessageBoxResult Show(string messageBoxText, string caption)
        => System.Windows.MessageBox.Show(Translate(messageBoxText), LocalizationService.T(caption));

    public static MessageBoxResult Show(
        string messageBoxText,
        string caption,
        MessageBoxButton button)
        => System.Windows.MessageBox.Show(
            Translate(messageBoxText),
            LocalizationService.T(caption),
            button);

    public static MessageBoxResult Show(
        string messageBoxText,
        string caption,
        MessageBoxButton button,
        MessageBoxImage icon)
        => System.Windows.MessageBox.Show(
            Translate(messageBoxText),
            LocalizationService.T(caption),
            button,
            icon);

    public static MessageBoxResult Show(
        Window owner,
        string messageBoxText,
        string caption,
        MessageBoxButton button,
        MessageBoxImage icon)
        => System.Windows.MessageBox.Show(
            owner,
            Translate(messageBoxText),
            LocalizationService.T(caption),
            button,
            icon);

    public static MessageBoxResult Show(
        Window owner,
        string messageBoxText,
        string caption,
        MessageBoxButton button,
        MessageBoxImage icon,
        MessageBoxResult defaultResult)
        => System.Windows.MessageBox.Show(
            owner,
            Translate(messageBoxText),
            LocalizationService.T(caption),
            button,
            icon,
            defaultResult);

    private static string Translate(string text)
    {
        var exact = LocalizationService.T(text);
        if (!string.Equals(exact, text, StringComparison.Ordinal))
        {
            return exact;
        }

        foreach (var prefixKey in MessagePrefixKeys)
        {
            var source = LocalizationService.GetDefault(prefixKey);
            if (!text.StartsWith(source, StringComparison.Ordinal))
            {
                continue;
            }
            return LocalizationService.Get(prefixKey) + text[source.Length..];
        }
        return text;
    }
}
