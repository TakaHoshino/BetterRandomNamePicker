namespace BetterRNP.PluginSdk.Abstractions;

/// <summary>
/// Optional host capability for commands that need a fresh snapshot of the
/// currently selected scheme when the command is invoked.
/// </summary>
public interface ISchemeCommandPluginContext
{
    void RegisterSchemeCommand(
        string id,
        string title,
        Action<IPluginModeContext> action);
}
