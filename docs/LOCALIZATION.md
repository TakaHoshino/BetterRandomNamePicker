# BetterRNP 多语言支持

BetterRNP 支持以下界面语言：

- 简体中文（`zh-Hans`）
- 繁體中文（`zh-Hant`）
- 繁體中文（粵語，`zh-HK`）
- English（`en-US`）
- Русский（`ru-RU`）
- 日本語（`ja-JP`）

语言可在“设置 → 语言”中切换，修改会立即应用到已打开的窗口，并保存到程序配置。

## 资源文件

翻译资源位于 WPF `ResourceDictionary`：

```text
src/BetterRNP/Resources/
├── Strings.xaml            # 简体中文（默认）
├── Strings.en-US.xaml
├── Strings.ja-JP.xaml
├── Strings.ru-RU.xaml
├── Strings.zh-Hant.xaml
└── Strings.zh-HK.xaml
```

界面代码只引用 `UiText` 中的资源键，不再直接保存用户可见文本。
XAML 使用 `DynamicResource`，代码创建的控件使用 `LocalizationService.Get`、
`Format`、`SetText`、`SetContent` 或 `SetTitle`。粤语资源会先加载繁體中文，
再用粤语写法覆盖对应条目。

## 运行时应用

`LocalizationService` 加载默认的 `Strings.xaml` 和当前语言覆盖资源，因此：

- 切换语言时可刷新现有窗口
- 切换回简体中文时可恢复原文
- 数据绑定文本不会被改写
- 语言选择下拉框使用 `Tag="__i18n_ignore__"` 保持各语言的原生名称

## 新增文本

新增用户可见文本时，先在 `Strings.xaml` 中增加资源键，并在 `UiText` 中增加对应常量；
再按需在目标语言 XAML 资源中添加覆盖项。缺少翻译时回退到简体中文原文。

日志操作码、内部异常信息、配置文件注释和用于数据兼容的固定格式不要求翻译。

## 插件本地化

插件通过 `IPluginLocalizationContext` 注册独立翻译表，并使用
`RegisterLocalizedCommand`、`RegisterLocalizedFloatingBallCommand`、
`RegisterLocalizedSettingsItem` 等接口提供动态标题。插件窗口订阅
`LanguageChanged` 后即可在宿主切换语言时实时刷新。

示例插件均提供了六语言资源：

- `examples/BetterRNP.DemoPlugin`
- `examples/BetterRNP.TimerPlugin`
- `examples/BetterRNP.RandomnessAnalyzerPlugin`
