namespace BetterRNP.PluginSdk.Abstractions;

public sealed class PluginLanguageChangedEventArgs : EventArgs
{
    public PluginLanguageChangedEventArgs(string languageCode)
    {
        LanguageCode = languageCode;
    }

    public string LanguageCode { get; }
}

/// <summary>
/// Optional localization capability for plugins. Plugin translations are
/// isolated by plugin and fall back to host translations.
/// </summary>
public interface IPluginLocalizationContext
{
    string CurrentLanguage { get; }

    event EventHandler<PluginLanguageChangedEventArgs>? LanguageChanged;

    void RegisterTranslations(
        string languageCode,
        IReadOnlyDictionary<string, string> translations);

    string Translate(string source);

    void RegisterLocalizedCommand(
        string id,
        Func<string> titleProvider,
        Action action);

    void RegisterLocalizedFloatingBallCommand(
        string id,
        Func<string> titleProvider,
        Action action);

    void RegisterLocalizedSettingsItem(
        string id,
        Func<string> titleProvider,
        Func<object> viewFactory);

    void RegisterLocalizedSchemeCommand(
        string id,
        Func<string> titleProvider,
        Action<IPluginModeContext> action);

    void RegisterLocalizedCurrentPickerCommand(
        string id,
        Func<string> titleProvider,
        Action<IPluginPickerSnapshot> action);
}
