using BetterRNP.PluginSdk.Models;

namespace BetterRNP.PluginSdk.Abstractions;

/// <summary>Base contract for every BetterRNP plugin.</summary>
public interface IPlugin
{
    PluginManifest GetManifest();
    void OnLoad(IPluginContext context);
    void OnUnload();
}
