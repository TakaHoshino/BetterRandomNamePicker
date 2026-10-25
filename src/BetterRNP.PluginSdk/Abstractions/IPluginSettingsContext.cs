namespace BetterRNP.PluginSdk.Abstractions;

/// <summary>
/// Optional host capability for plugins that want to contribute a page to the
/// application's settings window.
/// </summary>
public interface IPluginSettingsContext
{
    /// <summary>
    /// Registers an embeddable settings view. The factory is invoked on the UI
    /// thread and should return a WPF FrameworkElement when hosted by BetterRNP.
    /// </summary>
    void RegisterSettingsItem(
        string id,
        string title,
        Func<object> viewFactory);
}
