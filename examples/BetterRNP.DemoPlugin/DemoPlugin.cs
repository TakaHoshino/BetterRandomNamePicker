using System.Windows;
using System.Windows.Controls;
using BetterRNP.PluginSdk.Abstractions;
using BetterRNP.PluginSdk.Models;

namespace BetterRNP.DemoPlugin;

public sealed class DemoPlugin : IPlugin
{
    private IPluginContext? _context;
    private IPluginLocalizationContext? _localization;

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
        if (context is IPluginLocalizationContext localization)
        {
            _localization = localization;
            RegisterTranslations(localization);
            localization.RegisterLocalizedCommand(
                "show-info",
                () => localization.Translate("示例插件说明"),
                ShowInformation);
        }
        else
        {
            context.RegisterCommand("show-info", "示例插件说明", ShowInformation);
        }
        context.RegisterMode(new DemoMode(_localization));
        if (context is IPluginSettingsContext settings)
        {
            if (_localization is not null)
            {
                _localization.RegisterLocalizedSettingsItem(
                    "demo-settings",
                    () => _localization.Translate("示例插件设置"),
                    () => CreateSettingsView(_localization));
            }
            else
            {
                settings.RegisterSettingsItem(
                    "demo-settings",
                    "示例插件设置",
                    () => CreateSettingsView());
            }
        }
    }

    public void OnUnload()
    {
        _context?.Log("示例插件已卸载");
        _context = null;
        _localization = null;
    }

    private void ShowInformation()
    {
        const string bodySource =
            "这是 BetterRNP 的 .rnpp 示例插件。\n\n"
            + "它可以向“插件”菜单添加命令，也可以注册自定义抽取模式。";
        MessageBox.Show(
            _localization?.Translate(bodySource) ?? bodySource,
            _localization?.Translate("BetterRNP 示例插件") ?? "BetterRNP 示例插件",
            MessageBoxButton.OK,
            MessageBoxImage.Information);
    }

    private static object CreateSettingsView(IPluginLocalizationContext? localization = null)
        => new DemoSettingsView(localization);

    private static void RegisterTranslations(IPluginLocalizationContext localization)
    {
        localization.RegisterTranslations("en-US", Strings(
            "Demo plugin information",
            "BetterRNP demo plugin",
            "This is a .rnpp demo plugin for BetterRNP.\n\nIt can add commands to the Plugins menu and register a custom picker mode.",
            "Demo plugin settings",
            "This settings page is registered by a .rnpp plugin.",
            "Plugins can provide their own WPF settings view through IPluginSettingsContext. The host places it under the Plugins category."));
        localization.RegisterTranslations("zh-Hant", Strings(
            "外掛範例說明",
            "BetterRNP 範例外掛",
            "這是 BetterRNP 的 .rnpp 範例外掛。\n\n它可以將命令加入「外掛」選單，也可以註冊自訂抽取模式。",
            "範例外掛設定",
            "這是由 .rnpp 外掛註冊的設定頁面。",
            "外掛可以透過 IPluginSettingsContext 提供自己的 WPF 設定檢視，宿主會將它放在設定視窗左側的「外掛」分類。"));
        localization.RegisterTranslations("zh-HK", Strings(
            "外掛例子說明",
            "BetterRNP 例子外掛",
            "呢個係 BetterRNP 嘅 .rnpp 例子外掛。\n\n佢可以喺「外掛」選單加命令，亦可以註冊自訂抽籤模式。",
            "例子外掛設定",
            "呢個設定頁係由 .rnpp 外掛註冊嘅。",
            "外掛可以用 IPluginSettingsContext 提供自己嘅 WPF 設定畫面，宿主會放喺設定視窗左邊嘅「外掛」分類。"));
        localization.RegisterTranslations("ja-JP", Strings(
            "プラグインの説明",
            "BetterRNP デモプラグイン",
            "これは BetterRNP の .rnpp デモプラグインです。\n\n「プラグイン」メニューにコマンドを追加し、カスタム抽選モードを登録できます。",
            "デモプラグイン設定",
            "この設定ページは .rnpp プラグインから登録されています。",
            "プラグインは IPluginSettingsContext 経由で独自の WPF 設定画面を提供でき、設定ウィンドウ左側の「プラグイン」に表示されます。"));
        localization.RegisterTranslations("ru-RU", Strings(
            "Информация о демо-плагине",
            "Демонстрационный плагин BetterRNP",
            "Это демонстрационный .rnpp-плагин для BetterRNP.\n\nОн добавляет команды в меню «Плагины» и может регистрировать собственный режим выбора.",
            "Настройки демо-плагина",
            "Эта страница настроек зарегистрирована .rnpp-плагином.",
            "Плагины могут предоставлять собственный интерфейс настроек WPF через IPluginSettingsContext. Он отображается в категории «Плагины»."));
        RegisterModeTranslations(localization);
    }

    private static void RegisterModeTranslations(IPluginLocalizationContext localization)
    {
        localization.RegisterTranslations("en-US", ModeStrings(
            "Demo picker mode", "Plugin hint", "Demo plugin result",
            "Demo plugin: {0}", "Current scheme: {0} ({1})"));
        localization.RegisterTranslations("zh-Hant", ModeStrings(
            "外掛範例模式", "外掛提示", "外掛範例結果",
            "外掛範例：{0}", "目前方案：{0}（{1}）"));
        localization.RegisterTranslations("zh-HK", ModeStrings(
            "外掛例子模式", "外掛提示", "外掛例子結果",
            "外掛例子：{0}", "而家嘅方案：{0}（{1}）"));
        localization.RegisterTranslations("ja-JP", ModeStrings(
            "デモ抽選モード", "プラグインのヒント", "デモプラグインの結果",
            "デモプラグイン：{0}", "現在のプリセット：{0}（{1}）"));
        localization.RegisterTranslations("ru-RU", ModeStrings(
            "Демонстрационный режим", "Подсказка плагина", "Результат демо-плагина",
            "Демо-плагин: {0}", "Текущий набор: {0} ({1})"));
    }

    private static Dictionary<string, string> ModeStrings(
        string modeName,
        string buttonText,
        string result,
        string resultFormat,
        string schemeFormat)
        => new(StringComparer.Ordinal)
        {
            ["插件示例模式"] = modeName,
            ["插件提示"] = buttonText,
            ["插件示例结果"] = result,
            ["插件示例：{0}"] = resultFormat,
            ["当前方案：{0}（{1}）"] = schemeFormat
        };

    private static Dictionary<string, string> Strings(
        string commandTitle,
        string messageTitle,
        string messageBody,
        string settingsTitle,
        string settingsHeader,
        string settingsBody)
        => new(StringComparer.Ordinal)
        {
            ["示例插件说明"] = commandTitle,
            ["BetterRNP 示例插件"] = messageTitle,
            ["这是 BetterRNP 的 .rnpp 示例插件。\n\n它可以向“插件”菜单添加命令，也可以注册自定义抽取模式。"] = messageBody,
            ["示例插件设置"] = settingsTitle,
            ["这是由 .rnpp 插件注册的设置页面。"] = settingsHeader,
            ["插件可以通过 IPluginSettingsContext 提供自己的 WPF 设置视图，宿主会把它放入设置窗口左侧的“插件”分类。"] = settingsBody
        };

    private sealed class DemoSettingsView : StackPanel, IDisposable
    {
        private readonly IPluginLocalizationContext? _localization;
        private readonly TextBlock _header;
        private readonly TextBlock _body;

        public DemoSettingsView(IPluginLocalizationContext? localization)
        {
            _localization = localization;
            _header = new TextBlock
            {
                FontSize = 16,
                FontWeight = FontWeights.SemiBold
            };
            _body = new TextBlock
            {
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 8, 0, 0)
            };
            Children.Add(_header);
            Children.Add(_body);
            ApplyLanguage();
            if (_localization is not null)
            {
                _localization.LanguageChanged += OnLanguageChanged;
            }
        }

        public void Dispose()
        {
            if (_localization is not null)
            {
                _localization.LanguageChanged -= OnLanguageChanged;
            }
        }

        private void OnLanguageChanged(object? sender, PluginLanguageChangedEventArgs e)
            => ApplyLanguage();

        private void ApplyLanguage()
        {
            const string headerSource = "这是由 .rnpp 插件注册的设置页面。";
            const string bodySource =
                "插件可以通过 IPluginSettingsContext 提供自己的 WPF 设置视图，"
                + "宿主会把它放入设置窗口左侧的“插件”分类。";
            _header.Text = _localization?.Translate(headerSource) ?? headerSource;
            _body.Text = _localization?.Translate(bodySource) ?? bodySource;
        }
    }
}

internal sealed class DemoMode : IPluginMode
{
    private readonly IPluginLocalizationContext? _localization;

    public DemoMode(IPluginLocalizationContext? localization)
    {
        _localization = localization;
    }

    public string Id => "demo-mode";
    public string DisplayName => _localization?.Translate("插件示例模式") ?? "插件示例模式";
    public string? Button1Text => _localization?.Translate("插件提示") ?? "插件提示";
    public string? Button2Text => null;

    public string? CanPick(IPluginModeContext context) => null;

    public string NextCandidate(IPluginModeContext context)
    {
        var names = context.Names;
        return names.Count == 0
            ? _localization?.Translate("插件示例结果") ?? "插件示例结果"
            : string.Format(
                _localization?.Translate("插件示例：{0}") ?? "插件示例：{0}",
                names[Random.Shared.Next(names.Count)]);
    }

    public void HandleButton1(IPluginModeContext context)
    {
        context.ShowMessage(string.Format(
            _localization?.Translate("当前方案：{0}（{1}）")
                ?? "当前方案：{0}（{1}）",
            context.SchemeName,
            context.SchemeType));
    }

    public void HandleButton2(IPluginModeContext context)
    {
    }
}
