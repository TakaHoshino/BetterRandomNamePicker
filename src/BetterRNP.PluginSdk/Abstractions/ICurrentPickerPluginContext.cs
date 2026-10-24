namespace BetterRNP.PluginSdk.Abstractions;

/// <summary>
/// Read-only snapshot of the picker mode that is currently selected in the host.
/// </summary>
public interface IPluginPickerSnapshot
{
    IPluginModeContext Scheme { get; }
    string ModeId { get; }
    string ModeDisplayName { get; }
    string? ValidationError { get; }
    Func<string> CreateCandidateSupplier();
}

/// <summary>
/// Optional host capability for commands that need the exact current picker
/// implementation, including custom plugin modes.
/// </summary>
public interface ICurrentPickerPluginContext
{
    void RegisterCurrentPickerCommand(
        string id,
        string title,
        Action<IPluginPickerSnapshot> action);
}
