using System.Windows;
using BetterRNP.Core;
using BetterRNP.Models;
using BetterRNP.Plugins;
using BetterRNP.PluginSdk.Abstractions;

namespace BetterRNP.Modes;

public sealed class PluginPickModeAdapter : IPickMode
{
    private readonly PluginModeRegistration _registration;
    private readonly AppServices _services;
    private IPluginModeContext _snapshot;

    public PluginPickModeAdapter(
        PluginModeRegistration registration,
        AppServices services,
        Scheme scheme,
        Window owner)
    {
        _registration = registration;
        _services = services;
        _snapshot = SchemePluginModeContext.Create(
            services,
            scheme,
            message => MessageBox.Show(
                owner,
                message,
                "插件提示",
                MessageBoxButton.OK,
                MessageBoxImage.Information));
    }

    public string Id => _registration.Mode.Id;
    public string DisplayName => _registration.Mode.DisplayName;
    public string? Button1Text => _registration.Mode.Button1Text;
    public string? Button2Text => _registration.Mode.Button2Text;

    public string? CanPick()
    {
        try
        {
            return _registration.Mode.CanPick(_snapshot);
        }
        catch (Exception ex)
        {
            _services.Log.Write(LogLevel.Error, $"插件模式校验异常 {DisplayName}: {ex}", "PLUGIN_MODE_ERROR");
            return "插件模式校验异常";
        }
    }

    public Func<string> CreateCandidateSupplier()
    {
        var mode = _registration.Mode;
        var snapshot = _snapshot;
        return () =>
        {
            try
            {
                return mode.NextCandidate(snapshot);
            }
            catch (Exception ex)
            {
                _services.Log.Write(LogLevel.Error, $"插件模式候选异常 {DisplayName}: {ex}", "PLUGIN_MODE_ERROR");
                return "插件模式异常";
            }
        };
    }

    public void HandleButton1(Window owner)
    {
        try
        {
            _registration.Mode.HandleButton1(_snapshot);
        }
        catch (Exception ex)
        {
            _services.Log.Write(LogLevel.Error, $"插件模式按钮异常 {DisplayName}: {ex}", "PLUGIN_MODE_ERROR");
            MessageBox.Show(owner, $"插件模式按钮执行失败：{ex.Message}", "插件错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    public void HandleButton2(Window owner)
    {
        try
        {
            _registration.Mode.HandleButton2(_snapshot);
        }
        catch (Exception ex)
        {
            _services.Log.Write(LogLevel.Error, $"插件模式按钮异常 {DisplayName}: {ex}", "PLUGIN_MODE_ERROR");
            MessageBox.Show(owner, $"插件模式按钮执行失败：{ex.Message}", "插件错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

}
