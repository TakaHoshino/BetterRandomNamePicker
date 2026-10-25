# BetterRNP 插件开发文档

本文档面向 BetterRNP `.rnpp` 插件开发者，说明插件包结构、SDK 接口、菜单命令、自定义抽取模式、自动打包、调试和发布流程。

当前版本约定：

- 主程序版本：BetterRNP 2.3.1
- 插件 SDK 版本：`BetterRNP.PluginSdk 1.4.0`
- 插件 API 版本：`1.0`
- 插件包扩展名：`.rnpp`
- SDK 目标框架：`.NET 8`

## 1. 插件能力概览

BetterRNP 插件在宿主进程内运行，可以：

- 向主窗口“插件”菜单添加命令。
- 向悬浮球右键菜单添加命令。
- 向抽取模式下拉框添加自定义抽取模式。
- 通过日志接口写入 BetterRNP 日志。
- 在命令或模式按钮中创建自己的 WPF 窗口、访问文件或调用受信任的本地服务。

插件当前不能直接调用宿主内部数据写入接口。自定义模式通过 `IPluginModeContext` 读取当前方案的名单、数字范围和座位快照；需要修改宿主配置时应引导用户使用宿主界面。

> 插件与宿主运行在同一进程并拥有完整 .NET 权限，不存在沙箱隔离。只安装来源可信的 `.rnpp` 包。

## 2. 开发环境

需要：

- Windows 10 / 11 64 位
- .NET 8 SDK
- 可选：Visual Studio 2022、JetBrains Rider 或其他支持 WPF 的 .NET IDE

典型插件项目：

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net8.0-windows</TargetFramework>
    <UseWPF>true</UseWPF>
    <ImplicitUsings>enable</ImplicitUsings>
    <Nullable>enable</Nullable>
    <AssemblyName>MyPlugin</AssemblyName>
    <RootNamespace>MyPlugin</RootNamespace>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="BetterRNP.PluginSdk" Version="1.0.0" />
  </ItemGroup>
</Project>
```

如果插件不需要 WPF 界面，也可以使用 `net8.0` 作为目标框架；但 BetterRNP 本身是 Windows WPF 程序，实际发布目标仍应是 Windows。

## 3. 获取 SDK

### 3.1 从 GitHub Release 获取

SDK 作为 Release 附件发布：

```text
BetterRNP.PluginSdk.1.4.0.nupkg
```

下载后放入一个本地目录，例如：

```text
C:\BetterRNP-SDK
```

添加为本地 NuGet 源：

```bat
dotnet nuget add source "C:\BetterRNP-SDK" -n BetterRNP.Local
```

然后即可在插件项目中使用 `PackageReference`。

### 3.2 使用仓库中的项目引用

如果不使用 NuGet 包，也可以直接引用仓库中的 SDK：

```xml
<ProjectReference Include="path\to\BetterRandomNamePicker\src\BetterRNP.PluginSdk\BetterRNP.PluginSdk.csproj" />
<Import Project="path\to\BetterRandomNamePicker\src\BetterRNP.PluginSdk\build\BetterRNP.PluginSdk.targets" />
```

`PackageReference` 会自动导入 `.targets`；`ProjectReference` 默认不会，因此必须显式 `Import` 才能自动生成 `.rnpp`。

仓库中的 `examples/Directory.Build.targets` 已经为示例插件统一导入该目标。

## 4. 最小插件项目

一个可加载的插件至少需要：

```text
MyPlugin/
├── MyPlugin.csproj
├── plugin.json
└── MyPlugin.cs
```

### 4.1 `plugin.json`

```json
{
  "id": "com.example.myplugin",
  "name": "我的 BetterRNP 插件",
  "version": "1.0.0",
  "author": "Your Name",
  "type": "command",
  "assembly": "MyPlugin.dll",
  "entryType": "MyPlugin.MyPlugin",
  "apiVersion": "1.0",
  "description": "插件用途说明"
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 全局唯一插件标识。建议使用反向域名，如 `com.example.myplugin`。 |
| `name` | 是 | 插件显示名。 |
| `version` | 建议 | 插件自身版本，仅用于显示和插件开发者管理。 |
| `author` | 建议 | 作者或组织名称。 |
| `type` | 是 | `command`、`pickerMode` 或 `mixed`。`core` 仅供宿主内置核心使用。 |
| `assembly` | 是 | `.rnpp` 包内入口程序集文件名，如 `MyPlugin.dll`。 |
| `entryType` | 是 | 实现 `IPlugin` 的完整类型名。 |
| `apiVersion` | 是 | 插件依赖的 BetterRNP 插件 API 版本。当前为 `1.0`。 |
| `description` | 否 | 插件描述。 |

类型含义：

- `command`：只注册菜单命令（主窗口“插件”菜单和/或悬浮球右键菜单）。
- `pickerMode`：只注册自定义抽取模式。
- `mixed`：同时注册命令和模式。

清单校验要求：

- `type` 必须显式存在且为字符串。
- `id`、`name`、`assembly`、`entryType`、`apiVersion` 不能为空。
- `apiVersion` 主版本必须与宿主插件 API 主版本一致。当前 1.x 插件可由宿主 1.x API 加载。

### 4.2 入口类

```csharp
using BetterRNP.PluginSdk.Abstractions;
using BetterRNP.PluginSdk.Models;

namespace MyPlugin;

public sealed class MyPlugin : IPlugin
{
    private IPluginContext? _context;

    public PluginManifest GetManifest() => new()
    {
        Id = "com.example.myplugin",
        Name = "我的 BetterRNP 插件",
        Version = "1.0.0",
        Author = "Your Name",
        Type = PluginType.Command,
        Assembly = "MyPlugin.dll",
        EntryType = typeof(MyPlugin).FullName!,
        ApiVersion = PluginApiVersion.Current,
        Description = "插件用途说明"
    };

    public void OnLoad(IPluginContext context)
    {
        _context = context;
        context.RegisterCommand(
            "hello",
            "插件示例命令",
            () => context.Log("插件命令已执行"));
    }

    public void OnUnload()
    {
        _context = null;
    }
}
```

建议 `plugin.json` 与 `GetManifest()` 中的关键字段保持一致。宿主加载以 `plugin.json` 为入口，菜单和插件管理器主要使用清单中的元数据。

## 5. 构建与自动打包

插件项目根目录存在 `plugin.json` 时，构建结束后 SDK 会自动：

1. 将 `plugin.json` 复制到输出目录。
2. 检查 `assembly` 指向的入口程序集是否存在。
3. 收集输出目录中的插件 DLL、依赖、资源、`.deps.json` 等文件。
4. 排除 `BetterRNP.exe`、`BetterRNP.dll`、`BetterRNP.PluginSdk.dll` 和 PDB 文件。
5. 在输出目录生成 `<插件 id>.rnpp`。

执行：

```bat
dotnet build -c Release
```

输出示例：

```text
bin\Release\net8.0-windows\com.example.myplugin.rnpp
```

自动打包目标只在 Windows 上使用 PowerShell，这是 BetterRNP 桌面程序的发布目标环境要求。

如果构建后没有生成 `.rnpp`，检查：

- `plugin.json` 是否位于项目根目录。
- 是否使用 SDK NuGet 包，或是否显式导入了 `BetterRNP.PluginSdk.targets`。
- `plugin.json` 的 `assembly` 是否与实际程序集名一致。
- 输出目录中是否有入口 DLL。

## 6. `.rnpp` 包结构

`.rnpp` 本质上是一个 zip 包，至少包含：

```text
plugin.json
MyPlugin.dll
```

自动打包还会包含插件输出中的依赖和资源，例如：

```text
plugin.json
MyPlugin.dll
MyPlugin.deps.json
Dependency.dll
Resources\icon.png
```

不要手动把以下文件打入插件包：

- `BetterRNP.exe`
- `BetterRNP.dll`
- `BetterRNP.PluginSdk.dll`

这些程序集由宿主提供。SDK 使用 parent-first 共享方式加载，避免插件携带不同版本的 SDK 导致接口类型不一致。

手动读取或创建 `.rnpp` 可使用 SDK 中的 `RnpPackager`：

```csharp
using BetterRNP.PluginSdk.Packaging;

var read = RnpPackager.ReadManifest("MyPlugin.rnpp");
if (!read.Success)
{
    Console.WriteLine(read.Error);
}
```

## 7. 插件加载与生命周期

### 7.1 加载流程

宿主启动时会扫描：

```text
<BetterRNP 程序目录>\plugins\*.rnpp
```

加载过程：

1. 读取并校验 `plugin.json`。
2. 检查插件 `id` 是否重复。
3. 将 `.rnpp` 解压到临时目录。
4. 为插件创建可卸载的 `AssemblyLoadContext`。
5. 加载入口程序集并查找 `entryType`。
6. 检查类型是否实现 `IPlugin`。
7. 创建插件实例并调用 `OnLoad(context)`。
8. 收集 `OnLoad` 期间注册的命令和模式。
9. 检查模式显示名、模式 ID 冲突。
10. 将插件加入管理器，并在启用状态下显示其命令和模式。

插件也通过“插件管理”窗口手动加载，加载时会复制 `.rnpp` 到 `plugins/` 目录。

### 7.2 生命周期

```text
创建实例
  ↓
OnLoad(IPluginContext)
  ↓
启用时显示命令/模式
  ↓
禁用（暂时隐藏命令/模式）
  ↓
重新启用
  ↓
OnUnload()
  ↓
卸载 AssemblyLoadContext
```

说明：

- 插件即使上次处于禁用状态，仍会实例化并调用 `OnLoad`；但命令和模式不会显示。
- 手动移除插件时，宿主会先调用 `OnUnload`，再卸载加载上下文并删除 `.rnpp` 文件。
- 程序退出时，宿主会逐个调用 `OnUnload`。
- `OnUnload` 应释放插件自己的计时器、线程、文件句柄和窗口引用。
- `OnLoad`、命令、模式校验、候选生成、模式按钮和 `OnUnload` 均由宿主的 UI 线程调用。不要在回调中执行长时间同步阻塞。

如果 `OnLoad` 抛出异常，插件加载失败，不会显示其命令或模式。异常会写入 BetterRNP 日志。

## 8. 注册菜单命令

插件可以让命令出现在两个位置：主窗口的“插件”菜单，以及悬浮球的右键菜单。两者都接收一个动作委托，由宿主在 UI 线程调用。

### 8.1 主窗口“插件”菜单

接口：

```csharp
void RegisterCommand(string id, string title, Action action);
```

示例：

```csharp
context.RegisterCommand(
    "show-info",
    "插件说明",
    () => System.Windows.MessageBox.Show(
        "这是插件命令。",
        "BetterRNP 插件",
        System.Windows.MessageBoxButton.OK,
        System.Windows.MessageBoxImage.Information));
```

规则：

- `id` 在同一插件内必须唯一。
- `title` 会直接显示在主窗口“插件”菜单中。
- 命令动作在 UI 线程执行。
- 动作抛出异常时，宿主记录 `PLUGIN_COMMAND_ERROR` 并显示错误提示。
- 命令标题不要求跨插件唯一。

### 8.2 悬浮球右键菜单

接口：

```csharp
void RegisterFloatingBallCommand(string id, string title, Action action);
```

示例：

```csharp
context.RegisterFloatingBallCommand("count-up", "正计时", ShowCountUp);
context.RegisterFloatingBallCommand("count-down", "倒计时", ShowCountDown);
```

注册后，悬浮球右键菜单会在“随机抽取”和模式设置之后、悬浮球设置之前插入这些命令，并用分隔线隔开。

规则：

- 命令按 `OnLoad` 中的注册顺序排列。
- `id` 在同一插件内必须唯一；标题不要求跨插件唯一。
- 命令只在插件启用时出现；悬浮球显示期间启用、禁用或移除插件，菜单会立即重建。
- 动作在 UI 线程执行；异常会记录 `PLUGIN_COMMAND_ERROR` 并提示错误。
- 悬浮球是宿主窗口，插件命令应自行创建需要的弹窗。宿主不会在悬浮球关闭时自动关闭插件窗口，窗口生命周期由插件在 `OnUnload` 中负责。

完整的窗口型命令示例见 `examples/BetterRNP.TimerPlugin`：它在悬浮球菜单中加入“正计时”和“倒计时”，并弹出无标题栏、置顶的半透明计时窗口。

### 8.3 当前方案命令（可选扩展）

需要读取当前方案数据的命令可以实现为“方案命令”。该能力通过独立接口提供，原有 `IPluginContext` 保持不变，旧插件无需修改。

```csharp
void RegisterSchemeCommand(
    string id,
    string title,
    Action<IPluginModeContext> action);
```

使用前先检查宿主是否支持：

```csharp
if (context is ISchemeCommandPluginContext schemeCommands)
{
    schemeCommands.RegisterSchemeCommand(
        "analyze-scheme",
        "分析当前方案…",
        scheme => ShowAnalysis(scheme));
}
```

命令执行时，宿主会重新读取当前选中的方案，并提供最新的名单、数字范围或座位快照。方案不存在或已损坏时命令会报错，不会使用过期快照。

完整示例见 `examples/BetterRNP.RandomnessAnalyzerPlugin`：它使用卡方频率检验和前后依赖检验分析当前方案的抽取随机性。

### 8.4 当前抽取模式命令（可选扩展）

如果需要验证用户当前选中的具体抽取实现，而不只是方案数据，可以检测
`ICurrentPickerPluginContext`：

```csharp
void RegisterCurrentPickerCommand(
    string id,
    string title,
    Action<IPluginPickerSnapshot> action);
```

`IPluginPickerSnapshot` 包含：

- 当前方案快照 `Scheme`
- 模式 ID 与显示名
- 当前模式的校验错误
- `CreateCandidateSupplier()`：宿主当前实际使用的候选生成函数

```csharp
if (context is ICurrentPickerPluginContext currentPicker)
{
    currentPicker.RegisterCurrentPickerCommand(
        "analyze-current-picker",
        "分析当前抽取…",
        snapshot =>
        {
            var next = snapshot.CreateCandidateSupplier();
            // 对 next() 的实际返回值进行统计。
        });
}
```

该接口可以覆盖插件自定义模式。`ISchemeCommandPluginContext`、`IPluginContext`
等现有接口均保持不变。

### 8.5 插件设置项（可选扩展）

插件可以检测 `IPluginSettingsContext` 并注册一个嵌入主程序设置窗口的设置页面：

```csharp
void RegisterSettingsItem(
    string id,
    string title,
    Func<object> viewFactory);
```

示例：

```csharp
if (context is IPluginSettingsContext settings)
{
    settings.RegisterSettingsItem(
        "my-settings",
        "我的插件设置",
        () => new MyPluginSettingsView());
}
```

规则：

- `id` 在同一插件内必须唯一。
- `viewFactory` 在 UI 线程调用，BetterRNP 宿主会接收其返回的 WPF `FrameworkElement` 并嵌入右侧设置区。
- 视图会在左侧“插件”分类下显示，标题为 `title`；鼠标悬停可查看所属插件名称。
- 插件禁用或移除时，对应设置项立即隐藏；已创建的视图会被移出缓存，若实现 `IDisposable` 则同时释放。
- 设置视图的保存策略由插件自行管理。主程序设置页目前采用即时保存模式。

完整示例见 `examples/BetterRNP.DemoPlugin`。

### 8.6 悬浮球实时状态（可选扩展）

宿主可以实现 `IFloatingBallStateContext`，向插件提供悬浮球的实时几何和颜色信息：

```csharp
FloatingBallState? CurrentFloatingBallState { get; }

event EventHandler<FloatingBallStateChangedEventArgs>?
    FloatingBallStateChanged;
```

`FloatingBallState` 字段：

| 字段 | 含义 |
|---|---|
| `X`, `Y` | 悬浮球左上角的屏幕物理像素坐标 |
| `Width`, `Height` | 窗口物理像素尺寸 |
| `ColorHex` | 当前背景颜色的 `#RRGGBB` 值 |
| `IsVisible` | 当前是否可见 |

使用示例：

```csharp
if (context is IFloatingBallStateContext ballState)
{
    var current = ballState.CurrentFloatingBallState;
    context.Log($"悬浮球：{current?.X}, {current?.Y} {current?.ColorHex}");

    ballState.FloatingBallStateChanged += (_, args) =>
    {
        var state = args.State;
        context.Log(
            $"移动/缩放：{state.X}, {state.Y} " +
            $"{state.Width}x{state.Height} {state.ColorHex}");
    };
}
```

位置和尺寸使用屏幕物理像素，适合与 Win32、截图和覆盖窗口配合。颜色只包含 RGB；
透明度由窗口背景的 Alpha 通道单独控制。事件在 UI 线程触发。插件卸载时宿主会自动
移除仍未解除的订阅。

### 8.7 插件多语言（可选扩展）

插件可以检测 `IPluginLocalizationContext`，注册自己的翻译表并跟随宿主语言切换：

```csharp
if (context is IPluginLocalizationContext localization)
{
    localization.RegisterTranslations("en-US", new Dictionary<string, string>
    {
        ["打开计时器"] = "Open timer"
    });

    localization.RegisterLocalizedCommand(
        "open-timer",
        () => localization.Translate("打开计时器"),
        OpenTimer);

    localization.LanguageChanged += (_, args) =>
    {
        // 更新自定义窗口中的文本。
        UpdateLanguage(args.LanguageCode);
    };
}
```

支持的宿主语言代码：

| 语言 | 代码 |
|---|---|
| 简体中文 | `zh-Hans` |
| 繁體中文 | `zh-Hant` |
| 繁體中文（粵語） | `zh-HK` |
| English | `en-US` |
| Русский | `ru-RU` |
| 日本語 | `ja-JP` |

`Translate()` 优先使用该插件注册的当前语言翻译，找不到时回退到宿主翻译，再回退到原文。

动态标题接口：

```csharp
void RegisterLocalizedCommand(
    string id,
    Func<string> titleProvider,
    Action action);

void RegisterLocalizedFloatingBallCommand(
    string id,
    Func<string> titleProvider,
    Action action);

void RegisterLocalizedSettingsItem(
    string id,
    Func<string> titleProvider,
    Func<object> viewFactory);

void RegisterLocalizedSchemeCommand(
    string id,
    Func<string> titleProvider,
    Action<IPluginModeContext> action);

void RegisterLocalizedCurrentPickerCommand(
    string id,
    Func<string> titleProvider,
    Action<IPluginPickerSnapshot> action);
```

语言切换后，宿主会重新获取菜单和设置项标题，无需重启。插件设置视图应在
`LanguageChanged` 中更新自身文本，并在视图实现 `IDisposable` 时解除事件订阅。
示例可参考 `examples/BetterRNP.DemoPlugin`。

## 9. 注册自定义抽取模式

### 9.1 `IPluginMode`

```csharp
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
```

各成员含义：

| 成员 | 说明 |
|---|---|
| `Id` | 模式稳定 ID，必须跨所有插件唯一。 |
| `DisplayName` | 模式下拉框显示名，不能与内置模式或其他插件模式重名。 |
| `Button1Text` | 主窗口模式按钮 1 文案；返回 `null` 或空字符串时隐藏按钮。 |
| `Button2Text` | 主窗口模式按钮 2 文案；返回 `null` 或空字符串时隐藏按钮。 |
| `CanPick` | 返回 `null` 表示可以抽取；返回字符串表示阻塞原因。 |
| `NextCandidate` | 返回下一次滚动显示的候选文本。 |
| `HandleButton1` | 点击模式按钮 1 时调用。 |
| `HandleButton2` | 点击模式按钮 2 时调用。 |

内置显示名 `名字列表模式`、`数字模式`、`座位模式` 为保留名称，不能复用。

### 9.2 模式上下文

```csharp
public interface IPluginModeContext
{
    string SchemeName { get; }
    string SchemeType { get; }
    IReadOnlyList<string> Names { get; }
    (int Min, int Max)? NumberRange { get; }
    IReadOnlyList<PluginSeat> Seats { get; }
    void ShowMessage(string message);
}
```

- `Names`：创建模式实例时从当前方案读取的名单快照。
- `NumberRange`：当前方案数字范围；未设置时为 `null`。
- `Seats`：当前方案已选座位；无配置时为空列表。
- `ShowMessage`：使用宿主主窗口作为所有者显示信息框。

上下文是只读快照，滚动抽取期间不会因外部方案切换而变化。

### 9.3 完整模式示例

```csharp
using BetterRNP.PluginSdk.Abstractions;

internal sealed class ExampleMode : IPluginMode
{
    public string Id => "com.example.myplugin.example-mode";
    public string DisplayName => "我的示例模式";
    public string? Button1Text => "查看方案";
    public string? Button2Text => null;

    public string? CanPick(IPluginModeContext context)
    {
        return context.Names.Count == 0
            ? "当前方案没有名单"
            : null;
    }

    public string NextCandidate(IPluginModeContext context)
    {
        return context.Names[Random.Shared.Next(context.Names.Count)];
    }

    public void HandleButton1(IPluginModeContext context)
    {
        context.ShowMessage(
            $"方案：{context.SchemeName}\n类型：{context.SchemeType}");
    }

    public void HandleButton2(IPluginModeContext context)
    {
    }
}
```

注册：

```csharp
context.RegisterMode(new ExampleMode());
```

### 9.4 抽取行为

宿主调用顺序：

1. 用户选择插件模式。
2. 点击“开始抽取”时调用 `CanPick(context)`。
3. 返回非 `null` 时显示原因，不开始滚动。
4. 返回 `null` 时，宿主每 50ms 调用一次 `NextCandidate(context)` 并更新结果区。
5. 用户点击“停止”后停止调用并记录最终结果。

插件模式不会自动映射方案类型。切换方案后，宿主会按方案类型切换内置模式；用户需要重新选择插件模式。

### 9.5 异常处理

- `CanPick` 异常会记录 `PLUGIN_MODE_ERROR`，主窗口显示“插件模式校验异常”。
- `NextCandidate` 异常会记录日志，并显示“插件模式异常”占位文本。
- 模式按钮异常会显示错误提示。

插件应自行捕获可恢复错误，并向用户给出清楚说明。

## 10. 插件管理和状态

插件管理窗口支持：

- 加载 `.rnpp`。
- 显示名称、标识、作者、来源、类型、状态。
- 启用/禁用插件。
- 移除外部插件。

管理行为：

- 内置核心 `betterrnp.core` 不可移除、不可禁用。
- 禁用插件后，其命令和模式立即从主窗口消失。
- 启用状态保存在：

```text
data\plugins.json
```

- 移除外部插件会删除对应的 `plugins\<文件名>.rnpp`。
- 插件重复 `id` 会被拒绝。
- 模式 `Id` 在插件内部和跨插件范围都必须唯一。
- 模式 `DisplayName` 不能与内置模式或其他插件模式重名。

## 11. 日志与调试

插件日志使用：

```csharp
context.Log("初始化完成");
```

日志以 `PLUGIN_LOG` 级别写入主日志，查看器中显示为：

```text
[2026-10-18 15:30:12.345] [INFO ] 【PLUGIN_LOG】[插件显示名] 初始化完成
```

日志文件为 `log\Modifylog.txt`，带级别、毫秒时间戳和自动滚动；可用“帮助 → 查看日志…”查看、筛选和导出。完整的记录清单见 [日志说明](LOGGING.md)。

相关操作码包括：

- `PLUGIN_LOADED`
- `PLUGIN_LOAD_ERROR`
- `PLUGIN_UNLOAD_ERROR`
- `PLUGIN_COMMAND`
- `PLUGIN_COMMAND_ERROR`
- `PLUGIN_MODE_ERROR`
- `PLUGIN_STATE`
- `PLUGIN_REMOVED`

调试建议：

1. 在 Debug 配置下构建插件，保留 PDB 便于附加调试。
2. 将生成的 `.rnpp` 放入 BetterRNP 的 `plugins/` 目录。
3. 启动 BetterRNP，或使用“插件管理”窗口加载。
4. 在 IDE 中附加到 `BetterRNP.exe`，为插件代码设置断点。
5. 查看 `log\Modifylog.txt` 中的加载错误和运行异常。

发布包会自动排除 PDB 文件。

## 12. API 兼容性

- 宿主只接受与当前插件 API 主版本相同的插件。
- 当前 API 为 `1.0`，因此 `1.x` 插件可以加载。
- SDK 版本和插件 API 版本是两个独立概念：
  - SDK NuGet 包版本：`1.0.0`
  - 插件 API 版本：`1.0`
- 宿主加载插件时会优先使用自身的 SDK 程序集。插件不要尝试打包或强制加载另一份 SDK。
- 不要把 `BetterRNP.dll` 或宿主内部类型作为插件依赖；宿主内部类型不是稳定插件 API。

## 13. 安全与限制

- 插件在宿主进程内以完整权限运行，可以访问文件、网络和系统 API。
- 宿主不提供权限沙箱、资源配额或恶意代码隔离。
- 只分发和安装可信插件。
- 插件命令可以直接创建 WPF 窗口，但应保持 BetterRNP 主窗口作为所有者。
- 长时间任务应异步执行，避免阻塞 UI 线程。
- 插件卸载后，卸载上下文可能因仍被对象或线程引用而延迟回收。`OnUnload` 必须主动停止计时器、后台循环并解除事件订阅。
- `IPluginModeContext` 只提供只读方案数据，不提供宿主配置写入接口。

## 14. 发布插件

建议发布内容：

```text
MyPlugin-1.0.0.rnpp
README.md
```

发布前检查：

- [ ] `plugin.json` 与 `GetManifest()` 关键字段一致。
- [ ] `id` 全局唯一。
- [ ] `entryType` 与命名空间、类名完全一致。
- [ ] `apiVersion` 与目标 BetterRNP 兼容。
- [ ] `.rnpp` 中包含入口程序集和所需依赖。
- [ ] 包内没有 `BetterRNP.dll`、`BetterRNP.exe`、`BetterRNP.PluginSdk.dll`。
- [ ] 启用、禁用、移除、重新加载均无异常。
- [ ] 模式与内置模式及其他插件模式无重名。
- [ ] `OnUnload` 能停止所有插件自有资源。

## 15. 常见问题

| 现象 | 可能原因 | 处理 |
|---|---|---|
| 插件管理器没有显示插件 | 扩展名不是 `.rnpp`；不在 `plugins/`；清单非法 | 检查文件扩展名、目录和 `log\Modifylog.txt` |
| 加载被拒：缺少程序集 | `assembly` 与输出 DLL 名不一致 | 修改清单或程序集名后重新构建 |
| 入口类型无效 | `entryType` 错误，或类型没有实现 `IPlugin` | 使用完整命名空间和类名 |
| 加载被拒：API 版本不兼容 | `apiVersion` 主版本与宿主不同 | 改用兼容 API，或升级宿主 |
| 命令没有出现 | 插件被禁用；`OnLoad` 抛异常；命令 ID 重复 | 检查插件状态和日志 |
| 悬浮球菜单没有出现命令 | 插件被禁用；没有调用 `RegisterFloatingBallCommand`；命令 ID 重复 | 检查插件状态和日志 |
| 模式没有出现 | 模式显示名冲突、模式 ID 冲突、插件禁用 | 修改为唯一名称和 ID |
| 模式始终显示异常 | 插件内部异常 | 查看 `PLUGIN_MODE_ERROR` 日志并添加异常处理 |
| 构建后没有 `.rnpp` | 未导入 SDK targets；`plugin.json` 不在项目根目录 | 使用 `PackageReference`，或显式导入 targets |
| 移除后无法删除文件 | 插件仍持有程序集或文件 | 在 `OnUnload` 中释放资源；重启后再次删除 |

## 16. SDK API 速查

BetterRNP.PluginSdk 使用 **Apache License 2.0**。插件本身可以由作者选择不同的许可证，但分发 SDK 或基于 SDK 的衍生程序时应保留 Apache 2.0 许可证与 NOTICE 信息。

```csharp
public interface IPlugin
{
    PluginManifest GetManifest();
    void OnLoad(IPluginContext context);
    void OnUnload();
}

public interface IPluginContext
{
    string PluginId { get; }
    void RegisterCommand(string id, string title, Action action);
    void RegisterFloatingBallCommand(string id, string title, Action action);
    void RegisterMode(IPluginMode mode);
    void Log(string message);
}

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

public interface IPluginModeContext
{
    string SchemeName { get; }
    string SchemeType { get; }
    IReadOnlyList<string> Names { get; }
    (int Min, int Max)? NumberRange { get; }
    IReadOnlyList<PluginSeat> Seats { get; }
    void ShowMessage(string message);
}
```
