namespace BetterRNP.PluginSdk.Abstractions;

/// <summary>Optional custom picker mode supplied by a plugin.</summary>
public interface IPluginMode
{
    string Id { get; }
    string DisplayName { get; }
    string? Button1Text { get; }
    string? Button2Text { get; }
    string? CanPick(IPluginModeContext context);
    string NextCandidate(IPluginModeContext context);
    void HandleButton1(IPluginModeContext context);
    void HandleButton2(IPluginModeContext context);
}
