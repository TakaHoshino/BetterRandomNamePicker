# BetterRNP

BetterRNP 是一个使用 **.NET 8 / C# / WPF** 重写的课堂与活动随机抽取工具，提供随机点名、随机数字和随机座位抽取。

程序标题和托盘提示均为 `BetterRNP`。界面使用 WPF 默认控件、系统字体和系统颜色，呈现接近 Win32/Windows 原生对话框的视觉风格；托盘图标及其右键菜单使用 Win32 `Shell_NotifyIcon` + `CreatePopupMenu` / `TrackPopupMenu` 原生实现。

## 功能

- 三种抽取模式：
  - 名字列表模式：从当前方案名单中随机抽取。
  - 数字模式：支持负数、单值范围，跨度上限为 `2^31-1`。
  - 座位模式：从已选座位中随机抽取，行列上限为 `30 × 30`。
- 滚停式抽取：开始后每 50ms 刷新候选，再点一次停止并定格。
- 方案管理：每套方案包含名称和 `name_list`、`number` 或 `seat` 类型。
- 名单管理：添加、删除、导入 TXT、导出 TXT、显式保存。
- 数字与座位设置：显式保存、未保存关闭确认、座位越界处理。
- 配置锁定：锁定后拦截名单修改、导入导出和方案变更；支持修改密码。
- 系统托盘：单/双击显示主窗口，右键弹出 Win32 原生菜单。
- 悬浮球：置顶、拖动、双击快速抽取、右键配置入口。
- 插件管理：采用类似 FuckETS 的 Win32 原生表格式管理界面，支持加载、移除、启用/禁用 `.rnpp` 插件。
- 插件扩展：插件可向“插件”菜单添加命令，也可注册自定义抽取模式。
- 检查更新：基于 GitHub Releases，启动时可自动检查，也可在设置或帮助菜单手动检查；支持官方源与 gh-proxy 镜像源。
- 在线更新：自动检测当前安装是自包含版还是框架依赖版，默认选择对应更新包，也可手动切换；下载完成后覆盖重启。
- 背景主题：导入本地图片、全局图片、按窗口类别指定、适配方式和遮罩。
- 开机自启：写入当前用户的 Windows `Run` 注册表项。
- 操作日志：写入 `log/Modifylog.txt`，可从设置窗口导出。

## 数据兼容

新版保留原版的主要文件布局与加密格式：

```text
data/
├── config.properties
├── schemes/
│   ├── index.txt
│   ├── <方案>_names.txt
│   ├── <方案>_number.txt
│   └── <方案>_seat.txt
├── backup/
└── themes/
    ├── theme.properties
    └── images/
log/
├── Modifylog.txt
└── 加密数据第三副本
```

- 数据文件继续使用 AES-GCM，方案名参与 PBKDF2 派生盐。
- 文件头部和 SHA-256 校验格式与原版一致，已有加密名单、数字范围和座位配置可以直接读取。
- 名单、数字和座位各自维护 `data/schemes`、`data/backup`、`log` 三份副本；主文件损坏时按 `log > backup > data` 顺序恢复。
- 密码兼容旧版无盐 SHA-256，并在成功验证后自动升级为 PBKDF2-HMAC-SHA256（21 万轮 + 随机盐）。
- 旧版 Java 插件 jar 无法在 .NET 运行时中加载；新版插件使用 `.rnpp` 包，具体格式见下文。

默认密码与原版一致：

```text
#include<bits/stdc++.h>usingnamespacestd;intmain(){return0;}
```

## 构建

需要 Windows 和 .NET 8 SDK：

```bat
dotnet build BetterRNP.sln -c Release
```

主程序输出：

```text
src\BetterRNP\bin\Release\net8.0-windows\BetterRNP.exe
```

同时构建 Release 用的框架依赖版与自包含版：

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
```

生成：

```text
artifacts\BetterRNP_win-x64_framework-dependent_v2.0.0.zip
artifacts\BetterRNP_win-x64_self-contained_v2.0.0.zip
```

直接运行：

```bat
dotnet run --project src\BetterRNP\BetterRNP.csproj
```

## 测试

核心冒烟测试覆盖加密/解密、Java 数据兼容、三副本恢复、密码升级、名单、方案、数字、座位和配置持久化：

```bat
dotnet run --project tests\BetterRNP.Tests\BetterRNP.Tests.csproj -c Release
```

界面启动快速检查：

```powershell
powershell -ExecutionPolicy Bypass -File tools\ui-smoke.ps1
```

检查脚本会启动 Release 版本、确认主窗口标题为 `BetterRNP`，并使用 `PrintWindow` 生成 `ui-smoke.png` 截图。

## 项目结构

```text
BetterRNP.sln
src/BetterRNP/
├── App.xaml / App.xaml.cs
├── MainWindow.xaml / MainWindow.xaml.cs
├── NativeTrayService.cs          # Win32 托盘与原生右键菜单
├── Core/                         # 配置、加密、三副本数据、密码、主题、滚停引擎
├── Models/                       # 方案、数字范围、座位配置
├── Modes/                        # 名字、数字、座位三种抽取策略
├── Plugins/                      # .rnpp 插件宿主、可卸载加载上下文、启停持久化
└── Views/                        # 名单、方案、数字、座位、设置、主题、密码等窗口
src/BetterRNP.PluginSdk/          # 插件作者引用的 SDK：清单、接口与 .rnpp 包工具
examples/BetterRNP.DemoPlugin/    # 同时注册命令和抽取模式的示例插件
tests/BetterRNP.Tests/            # 无第三方测试框架的核心冒烟测试
tools/ui-smoke.ps1                # 启动与截图检查
```

## 插件包 `.rnpp`

插件包是一个 zip 文件，扩展名固定为 `.rnpp`，包内至少包含：

```text
plugin.json
YourPlugin.dll
```

`plugin.json` 示例：

```json
{
  "id": "com.example.myplugin",
  "name": "我的插件",
  "version": "1.0.0",
  "author": "Author",
  "type": "mixed",
  "assembly": "YourPlugin.dll",
  "entryType": "MyPlugin.Entry",
  "apiVersion": "1.0",
  "description": "插件说明"
}
```

`type` 可取 `command`、`mode`、`mixed`。插件程序集引用 `BetterRNP.PluginSdk`，实现 `IPlugin`，并在 `OnLoad` 中调用：

- `context.RegisterCommand(...)`：向主窗口“插件”菜单添加命令。
- `context.RegisterMode(...)`：向抽取模式下拉框添加自定义模式。

插件管理窗口会显示名称、标识、作者、来源、类型、启用状态，并支持切换启停。禁用后，插件命令和自定义模式会立即从主界面移除，状态保存在 `data/plugins.json`。插件 DLL 使用可卸载的 `AssemblyLoadContext` 加载，SDK 程序集复用主程序版本。

构建示例插件包：

```powershell
powershell -ExecutionPolicy Bypass -File examples\BetterRNP.DemoPlugin\build-rnpp.ps1
```

生成文件：

```text
examples\BetterRNP.DemoPlugin\BetterRNP.DemoPlugin.rnpp
```

## 原生样式说明

WPF 控件不是 Win32 公共控件的直接实例，因此无法像 WinForms 那样让每个控件都由 `comctl32` 绘制。当前实现采用以下方式获得原生观感：

- 使用 `SystemFonts.MessageFontFamily` 与系统字号。
- 使用系统窗口、文本、边框和高亮颜色。
- 使用 WPF 自带默认按钮、文本框、下拉框和菜单模板，不覆盖为自绘圆角控件。
- 保持 Windows 默认窗口边框与标题栏。
- 托盘右键菜单使用真正的 Win32 菜单 API。

如果后续要求“每个控件本身必须是 Win32 原生控件”，应将界面层改为 WinForms/Win32 宿主；这与当前 WPF 技术选型属于不同实现路线。
