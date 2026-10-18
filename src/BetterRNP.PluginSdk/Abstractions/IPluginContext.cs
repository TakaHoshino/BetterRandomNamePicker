namespace BetterRNP.PluginSdk.Abstractions;

/// <summary>Capabilities exposed to a plugin during loading.</summary>
public interface IPluginContext
{
    string PluginId { get; }

    /// <summary>Adds an action to the main window's Plugins menu.</summary>
    void RegisterCommand(string id, string title, Action action);

    /// <summary>
    /// Adds an action to the floating ball's right-click menu. Items appear in
    /// registration order and follow the enabled state of the plugin.
    /// </summary>
    void RegisterFloatingBallCommand(string id, string title, Action action);

    /// <summary>Adds a custom picker mode to the mode selector.</summary>
    void RegisterMode(IPluginMode mode);

    void Log(string message);
}
