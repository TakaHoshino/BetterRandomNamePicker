using System.Windows;
using BetterRNP.PluginSdk.Abstractions;
using BetterRNP.PluginSdk.Models;
using BetterRNP.RandomnessAnalyzerPlugin.Views;

namespace BetterRNP.RandomnessAnalyzerPlugin;

public sealed class RandomnessAnalyzerPlugin : IPlugin
{
    private IPluginContext? _context;
    private RandomnessAnalysisWindow? _window;

    public PluginManifest GetManifest() => new()
    {
        Id = "com.betterrnp.randomness-analyzer",
        Name = "BetterRNP 随机性分析插件",
        Version = "1.0.0",
        Author = "BetterRNP",
        Type = PluginType.Command,
        Assembly = "BetterRNP.RandomnessAnalyzerPlugin.dll",
        EntryType = typeof(RandomnessAnalyzerPlugin).FullName!,
        ApiVersion = PluginApiVersion.Current,
        Description = "对当前方案连续抽样，分析抽取结果是否均匀且相互独立。"
    };

    public void OnLoad(IPluginContext context)
    {
        _context = context;
        if (context is ICurrentPickerPluginContext currentPicker)
        {
            currentPicker.RegisterCurrentPickerCommand(
                "analyze-randomness",
                "分析抽取随机性…",
                ShowAnalysis);
            context.Log("随机性分析插件已加载（当前抽取模式接口）");
            return;
        }
        if (context is ISchemeCommandPluginContext schemeCommands)
        {
            schemeCommands.RegisterSchemeCommand(
                "analyze-randomness",
                "分析抽取随机性…",
                ShowSchemeAnalysis);
            context.Log("随机性分析插件已加载（方案快照兼容模式）");
            return;
        }

        context.Log("当前宿主不支持随机性分析所需的方案读取接口，未注册菜单。");
    }

    public void OnUnload()
    {
        _window?.Close();
        _window = null;
        _context?.Log("随机性分析插件已卸载");
        _context = null;
    }

    private void ShowAnalysis(IPluginPickerSnapshot picker)
    {
        _window?.Close();
        _window = new RandomnessAnalysisWindow(
            picker,
            message => _context?.Log(message))
        {
            Owner = Application.Current?.MainWindow
        };
        _window.Closed += (_, _) => _window = null;
        _window.Show();
        _context?.Log(
            $"打开随机性分析窗口：方案={picker.Scheme.SchemeName}｜模式={picker.ModeDisplayName}");
    }

    private void ShowSchemeAnalysis(IPluginModeContext scheme)
    {
        _window?.Close();
        _window = new RandomnessAnalysisWindow(
            scheme,
            message => _context?.Log(message))
        {
            Owner = Application.Current?.MainWindow
        };
        _window.Closed += (_, _) => _window = null;
        _window.Show();
        _context?.Log($"打开随机性分析窗口（兼容模式）：方案={scheme.SchemeName}");
    }
}
