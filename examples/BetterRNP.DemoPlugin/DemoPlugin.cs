using System.Windows;
using BetterRNP.PluginSdk.Abstractions;
using BetterRNP.PluginSdk.Models;

namespace BetterRNP.DemoPlugin;

public sealed class DemoPlugin : IPlugin
{
    private IPluginContext? _context;

    public PluginManifest GetManifest() => new()
    {
        Id = "com.betterrnp.demo",
        Name = "BetterRNP 示例插件",
        Version = "1.0.0",
        Author = "BetterRNP",
        Type = PluginType.Mixed,
        Assembly = "BetterRNP.DemoPlugin.dll",
        EntryType = typeof(DemoPlugin).FullName!,
        ApiVersion = PluginApiVersion.Current,
        Description = "演示插件命令与自定义抽取模式。"
    };

    public void OnLoad(IPluginContext context)
    {
        _context = context;
        context.RegisterCommand(
            "show-info",
            "示例插件说明",
            () => MessageBox.Show(
                "这是 BetterRNP 的 .rnpp 示例插件。\n\n"
                + "它可以向“插件”菜单添加命令，也可以注册自定义抽取模式。",
                "BetterRNP 示例插件",
                MessageBoxButton.OK,
                MessageBoxImage.Information));
        context.RegisterMode(new DemoMode());
    }

    public void OnUnload()
    {
        _context?.Log("示例插件已卸载");
        _context = null;
    }
}

internal sealed class DemoMode : IPluginMode
{
    public string Id => "demo-mode";
    public string DisplayName => "插件示例模式";
    public string? Button1Text => "插件提示";
    public string? Button2Text => null;

    public string? CanPick(IPluginModeContext context) => null;

    public string NextCandidate(IPluginModeContext context)
    {
        var names = context.Names;
        return names.Count == 0
            ? "插件示例结果"
            : $"插件示例：{names[Random.Shared.Next(names.Count)]}";
    }

    public void HandleButton1(IPluginModeContext context)
    {
        context.ShowMessage($"当前方案：{context.SchemeName}（{context.SchemeType}）");
    }

    public void HandleButton2(IPluginModeContext context)
    {
    }
}
