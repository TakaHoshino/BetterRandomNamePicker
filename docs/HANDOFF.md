# BetterRNP 交接文档

> 更新时间：2026-10-25
>
> 当前分支：`main`
>
> 当前工作区：`v2.3.2` 发布候选，隐藏软件内的备用通道操作细节

Release：

https://github.com/TakaHoshino/BetterRandomNamePicker/releases

## 维护约定

每次修改代码后，必须同步更新本文档的“当前状态”“关键实现”“测试记录”和“已知风险”部分。不要只记录“改了什么”，还要说明设置格式、插件 API、数据兼容性是否发生变化。

## 项目概况

BetterRNP 是面向课堂、会议和活动现场的 Windows 随机抽取工具，目标是替代 RandomNamePicker：

- 三种抽取模式：名字列表、数字、座位。
- 方案、名单、数字范围和座位配置加密保存。
- 系统托盘、悬浮球、背景主题、操作日志和自动更新。
- 六种界面语言：简体中文、繁體中文、粵語、English、Русский、日本語。
- `.rnpp` 插件扩展，包括自定义命令、抽取模式、设置页和当前抽取结果分析。

技术栈为 .NET 8、C#、WPF、Windows Forms `NotifyIcon`、Win32 菜单和 Windows 注册表。

## 当前状态

已完成并验证的本地改动：

1. 所有用户可见文本迁移到 XAML `ResourceDictionary`。
2. C# 界面代码只引用 `UiText` 资源键；默认资源 389 项，四种完整语言字典均无缺键。
3. 语言切换统一刷新静态 XAML、代码创建控件、插件菜单、主题选项和方案显示名。
4. 内置方案不再显示 `name_list` 等内部 ID。
5. 修复随机性分析插件把无效或自身窗口设为 `Owner` 的异常。
6. 启动时在插件加载前清空 `data/plugin-cache`；清理失败只记日志。
7. 应用设置统一迁移到 `HKCU\Software\BetterRNP\Settings`；旧设置文件成功后删除。
8. 主程序版本已提升到 `v2.3.1`，插件 SDK 保持 `1.4.0`。
9. “设置 → 安全”新增 README 指引按钮，优先打开 GitHub 在线 README，失败时回退本地文件。
10. 默认不设置密码；旧版内置默认密码散列会在升级时自动移除。
11. `Ctrl+L` 备用通道默认关闭，仅在设置密码并完成鉴权后允许开启。
12. 未设置密码时，主界面以红色小字提示名单和方案可能被未经授权修改。
13. 软件界面和操作日志不再披露备用通道的具体按键或操作次数。

当前测试结果：

- `dotnet build BetterRNP.sln --no-restore`：0 警告、0 错误。
- `dotnet build BetterRNP.sln -c Release --no-restore`：0 警告、0 错误。
- `dotnet run --project tests/BetterRNP.Tests/BetterRNP.Tests.csproj --no-build`：184 项通过、0 项失败。
- `tools/build-release.ps1`：fd/sc 发布包、随机性分析插件、SDK 1.4.0 和开发签名证书已生成。
- GitHub Release `v2.3.1` 已发布，5 个附件均为 `uploaded` 状态。
- `v2.3.1` 不改变设置结构或插件 API。

## 设置存储

生产环境：

```text
HKEY_CURRENT_USER\Software\BetterRNP\Settings
```

| 子键 | 内容 |
|---|---|
| `General` | `language`、托盘/自动启动开关、`lastScheme` |
| `FloatingBall` | `floatingBallRadius`、`floatingBallOpacity` |
| `Security` | `PasswordHash` |
| `Update` | `CheckOnStartup`、`Source` |
| `Plugins\Disabled` | 插件 ID → DWORD `1` |
| `Theme` | 背景开关、遮罩、全局/分类图片、适配模式和裁切 |

根键 `SchemaVersion` 当前为 `1`。详细说明见 `docs/SETTINGS_STORAGE.md`。

旧设置来源：

- `data/config.properties`
- `data/themes/theme.properties`
- `data/update.json`
- `data/plugins.json`

迁移由 `LegacySettingsMigrator` 完成。只有全部数据成功写入且 `SchemaVersion` 更新后，才删除旧文件和对应的 `.bak`。失败时保留文件并记录错误，下一次启动重试。

测试与无界面运行在显式传入 `AppServices.Create(root)` 时使用隔离的内存设置存储，避免写真实用户注册表。生产启动不传 root，使用真实 `HKCU`。

## 架构地图

| 路径 | 职责 |
|---|---|
| `src/BetterRNP/App.xaml.cs` | 单实例、服务初始化、插件缓存清理、托盘和窗口生命周期 |
| `src/BetterRNP/Core/AppServices.cs` | 所有服务和存储入口的组合根 |
| `src/BetterRNP/Core/AppPaths.cs` | 程序数据目录、日志、插件、主题图片和注册表作用域 |
| `src/BetterRNP/Core/RegistrySettingsStore.cs` | 统一注册表设置读写、分区和 `SchemaVersion` |
| `src/BetterRNP/Core/LegacySettingsMigrator.cs` | 旧 properties/JSON 设置迁移 |
| `src/BetterRNP/Core/ConfigService.cs` | 语言、窗口、托盘、悬浮球和密码散列 |
| `src/BetterRNP/Core/ThemeService.cs` | 背景主题设置与 WPF 窗口背景应用 |
| `src/BetterRNP/Core/UpdateSettingsService.cs` | 更新源和启动检查 |
| `src/BetterRNP/Core/PluginCacheService.cs` | 启动时清理插件解压缓存 |
| `src/BetterRNP/Core/DataStore.cs` | AES-GCM、SHA-256、主副本/备份副本/日志副本自愈 |
| `src/BetterRNP/Core/LocalizationService.cs` | XAML 资源字典加载、运行时切换和视觉树刷新 |
| `src/BetterRNP/Core/UiText.cs` | C# 使用的本地化资源键常量 |
| `src/BetterRNP/Resources/Strings*.xaml` | 六种语言的界面文本资源 |
| `src/BetterRNP/MainWindow.xaml(.cs)` | 主抽取流程、模式切换、菜单和插件入口 |
| `src/BetterRNP/Modes/` | 名字、数字、座位和插件抽取模式 |
| `src/BetterRNP/Views/` | 设置、方案、名单、主题、日志、更新和对话框 |
| `src/BetterRNP.PluginSdk/` | 插件接口、清单验证、`.rnpp` 打包 |
| `examples/` | Demo、计时和随机性分析三个插件 |
| `tests/BetterRNP.Tests/` | 数据兼容、插件、UI、主题、更新和本地化回归测试 |
| `tools/build-release.ps1` | fd/sc 应用包、插件和 SDK 发布 |

用户数据仍保存在程序目录：

```text
data/schemes/          方案、加密名单、数字范围和座位配置
data/backup/           用户数据备份
data/themes/images/    主题图片
data/plugin-cache/     可随时清理的插件解压缓存
log/                   操作日志与加密备份
plugins/               .rnpp 插件
```

应用设置不再写入上述文件。

## 关键行为

### 启动顺序

1. 创建单实例守卫。
2. 创建 `AppServices`。
3. 初始化注册表设置并迁移旧文件。
4. 清理 `data/plugin-cache`。
5. 初始化本地化。
6. 创建主窗口和插件管理器，扫描插件目录。
7. 安装托盘，按设置显示悬浮球。

### 本地化

- XAML 使用 `DynamicResource`。
- C# 创建/更新的控件使用 `LocalizationService.Get`、`Format`、`SetText`、`SetContent` 或 `SetTitle`。
- `LocalizationService` 同时维护默认文本、当前语言值、反向文本键和语言缓存。
- 切换语言会更新已打开窗口；插件通过 `IPluginLocalizationContext.LanguageChanged` 刷新自己的窗口。

### 插件

- 插件 API 当前版本为 `1.0`，SDK 包为 `1.1.0`。
- 插件包必须使用 `.rnpp`，清单和入口程序集由 `PluginManager` 验证。
- 插件禁用状态保存在注册表。
- 插件窗口设置 `Owner` 前必须满足：所有者已加载、可见链路有效且不是窗口自身。

## 历史演进

当前仓库基线为 `bb6d211`（`v2.2.0`）。主要历史提交：

- `3ae6b13`：.NET 8/WPF 重写。
- `437f629`：GitHub Release 更新。
- `35ca123`：主题缩略图与裁切。
- `ac0a432`：抽取结果区域透明化。
- `1bb56c1`：插件 SDK 自动打包。
- `f48d5de`：图标、结构化日志、计时插件和签名工具。
- `bb6d211`：随机性分析插件与演示焦点修复。

### Java 时期（V0.4–V1.0.4）

- 原始 RandomNamePicker 为 Java Swing/JAR。
- V1.0.3 强化主题功能。
- V1.0.4 合并 TimerBall 计时插件并加入插件菜单分组 API。
- 旧 Java 加密名单、数字范围、座位数据和数据格式仍需兼容读取。

### v2.0.0 `.NET` 重写

- Java Swing 重写为 .NET 8/C#/WPF。
- 保留三种抽取模式、AES-GCM、PBKDF2 和 Java 数据兼容。
- 新增 `.rnpp`、GitHub Release 更新、原生 Win32 托盘和图片主题。

### v2.0.1

- 主题卡片透明化。
- 图片缩略图、图片裁切、按类别存储裁切区域。

### v2.1.0

- 发布 `BetterRNP.PluginSdk` NuGet 包。
- 插件构建自动生成 `.rnpp`。
- SDK 包自动排除宿主程序集。

### v2.1.1

- 新应用图标和签名工具。
- 结构化操作日志、日志查看器。
- 计时插件、悬浮球插件命令和插件缓存目录。
- 托盘宿主兼容 Explorer 重启。

### v2.2.0

- 随机性分析插件和插件 SDK 1.1.0。
- 当前抽取模式真实候选生成器接口。
- 卡方频率/前后依赖检验和大范围数字分箱。
- 悬浮球 `WS_EX_NOACTIVATE`、`WS_EX_TOOLWINDOW` 焦点修复。
- PPT/WPS 放映结束后恢复焦点。

### v2.3.0

- 全部用户可见文本迁移到 XAML 资源，六种语言均完整覆盖。
- 语言切换可刷新打开中的窗口、插件菜单、设置页和方案/模式名称。
- 应用设置统一迁移到注册表，旧 properties/JSON 设置自动迁移并删除。
- 启动时自动清理插件缓存。
- 插件 SDK 1.4.0 增加设置页、悬浮球状态和插件多语言接口。
- 修复插件窗口 Owner 设置异常，并新增关于窗口。

### v2.3.1

- 默认不再设置密码，首次启动保持解锁；用户可在安全设置中自行设置密码。
- 旧版内置默认密码散列自动移除。
- `Ctrl+L` 备用通道默认关闭，只有设置密码并鉴权后才能启用。
- 主界面在未设置密码时显示红色安全警告。
- 安全设置中的 README 指引优先打开 GitHub 在线版本。

### v2.3.2

- 软件安全设置和操作日志不再显示备用通道的具体按键或操作步骤。
- 软件内只说明备用通道默认关闭，以及设置密码并鉴权后可配置。

## 构建与验证

```powershell
dotnet build BetterRNP.sln --no-restore
dotnet run --project tests\BetterRNP.Tests\BetterRNP.Tests.csproj --no-build
dotnet build BetterRNP.sln -c Release
```

应用运行前应先关闭旧进程，否则单实例守卫会让新进程退出：

```powershell
Get-Process BetterRNP -ErrorAction SilentlyContinue | Stop-Process -Force
```

Release 发布：

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
```

签名说明见 `docs/CODE_SIGNING.md`。

## 已知风险与后续工作

- 注册表迁移依赖当前用户对 `HKCU\Software\BetterRNP` 的写权限。失败时会保留旧文件，但设置只能使用默认值，直到权限恢复。
- 只有设置数据进入注册表；方案、加密名单、座位、数字范围、主题图片、插件文件和日志仍是磁盘数据。
- 测试使用独立内存设置存储，真实注册表写入应由交互式应用启动再验证一次。
- 本次在自动化执行环境中尝试启动真实应用时，执行宿主限制了对临时数据目录的访问，因此未能完成交互式注册表冒烟；迁移逻辑、持久化和删除旧文件已由测试覆盖。
- 发布打包已验证单文件发布警告：关于窗口不再依赖单文件模式下不可用的 `Assembly.Location`。
- 当前工作区有大量未提交改动；提交前应审查本地化资源、设置迁移和测试辅助代码，避免把临时诊断逻辑带入发布。
- 现有 Release 的更新逻辑会覆盖程序目录；用户数据目录和 `HKCU` 设置不在更新包覆盖范围内。
- 插件运行在独立可回收 `AssemblyLoadContext` 中，插件卸载后的程序集释放仍依赖运行时 GC。
- 搜索到本地 `BetterRNP` 进程时，不要盲目结束用户正在使用的实例；构建 Release 或启动新版本前应先确认。

## 交接检查清单

- [ ] 关闭旧版 BetterRNP 进程。
- [ ] 检查 `HKCU\Software\BetterRNP\Settings\SchemaVersion`。
- [ ] 确认旧设置文件已成功迁移并删除。
- [ ] 运行 Debug 构建和完整测试。
- [ ] 运行 Release 构建。
- [ ] 如发布：验证签名、fd/sc 包、插件 SDK 和 Release Notes。
- [ ] 下次修改代码后立即更新本文档。
