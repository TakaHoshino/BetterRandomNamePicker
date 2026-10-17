namespace BetterRNP.PluginSdk.Abstractions;

public readonly record struct PluginSeat(int Row, int Column);

/// <summary>Read-only scheme data and host feedback available to custom modes.</summary>
public interface IPluginModeContext
{
    string SchemeName { get; }
    string SchemeType { get; }
    IReadOnlyList<string> Names { get; }
    (int Min, int Max)? NumberRange { get; }
    IReadOnlyList<PluginSeat> Seats { get; }
    void ShowMessage(string message);
}
