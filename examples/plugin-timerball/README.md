# 计时子球（TimerBall）— 正计时 + 倒计时合并版插件

调用**宿主正式分组 API** 的纯插件（宿主插件 API 级别 2，无插件业务逻辑进入主程序）。
把原先两个独立插件——**正计时子球**（`CountUpTimerBall`）与**倒计时子球**（`CountdownTimer`）——
合并为一个「计时子球」插件：`plugins/TimerBall.jar`，重启宿主生效。

- 快速部署：工作区根执行 `powershell -ExecutionPolicy Bypass -File tools\deploy-timerball.ps1`
  （编译 → 打包 → 复制 `plugins\` → 移除被取代的旧 jar → 跑自检；自检不通过即中止）。
- 编译/打包/验证细节与验收清单：见 [BUILD.txt](BUILD.txt)。

## 合并后的统一口径（本次变更核心）

| 约束 | 实现 |
|---|---|
| **左键只给正计时** | 单击宿主悬浮球 = 正计时子球 冒出/收回（显示即开始、收起即暂停）。倒计时子球**完全不接管左键**（不做单击/双击判定，因此**不再安装全局 AWT 观察监听**），宿主双击抽取、拖动移动手感零干扰 |
| **倒计时只走右键菜单** | 倒计时的一切命令都从右键菜单发起：宿主悬浮球右键的「计时子球」级联子菜单，或倒计时子球自身的右键菜单（开始/暂停、归零、切全屏专注、快捷时长、设置、收起） |
| **两球互不重叠** | 落位统一走 `BallLayout.placeAnchored`（候选位搜索：同侧 → 另一侧 → 左侧 → 右侧 → 对齐变体），倒计时把正计时子球当前矩形作为"占用矩形"传入；正计时冒出/收回时通过可见性回调通知倒计时 `relayout()` 重排 |
| **点任意倒计时功能即冒出并开始** | `commandStart / commandToggleRun / commandSetQuickDuration / commandToggleFocus` 都先确保子球可见再计时，不再依赖"显示即开始"配置项；记忆位置若落在屏幕外会被夹回屏内（修复"倒计时在跑却看不见球"） |
| **菜单分组走正式接口** | 插件调用 `addMainMenuAction/addTrayMenuAction/addFloatingBallMenuAction(String[] groupPath, title, action)`；宿主按 `MenuAction.getGroupPath()` 渲染级联子菜单。**宿主端不含任何插件专属逻辑/字样**（此前的 `▸` 标题约定已删除） |

## 依托的宿主改动（正式 API 面，通用实现）

新增宿主"菜单分组"接口（**纯新增、向后兼容**，无任何插件业务字样）：

| 位置 | 内容 |
|---|---|
| `plugin/PluginContext.java` | `addGroupedUiAction(UiZone, String[] groupPath, String title, ActionListener)`；便捷重载 `addMainMenuAction / addTrayMenuAction / addFloatingBallMenuAction / addSettingsWindowAction(String[] groupPath, String title, ActionListener)`；原平铺重载不变 |
| `plugin/PluginManager.java` | `MenuAction` 新增 `groupPath` 字段与 `getGroupPath()`（空 = 平铺）；`PerPluginContext.addGroupedUiAction` 实现（沿用 zone=null 抛异常的严格失败语义） |
| `plugin/MenuGroupSupport.java`（新） | 通用渲染器 `appendAction(s)/appendActions(...)`、`childMenu(...)`：按路径逐级创建/复用级联子菜单 |
| `floating/FloatingBall.java`、`ui/NamePickerApp.java`、`main/Main.java` | 三个插件渲染点统一改为调用 `MenuGroupSupport`（各减几行重复代码） |

点名/抽取核心（`mode`/`core`/`model` 与抽取流程）**未改动**；`git diff --stat -- src/` 为 5 个文件、净增约 60 行。

## 功能一览

| 能力 | 说明 |
|---|---|
| 正计时子球 | 自绘圆窗 + 计时状态机；单击悬浮球冒出/收回；子球单击 = 暂停/继续；暂停态双击 = 归零并收回；尺寸随悬浮球自动（×0.55 夹 36–60px）或固定 30–90px；三态配色 |
| 倒计时子球 | 自绘圆窗 + 剩余比例进度环；五态配色（就绪/运行/暂停/**警戒**/到时）+ 警戒阈值；尺寸自动或固定 30–300px；右键菜单操作 |
| 全屏专注（番茄钟） | 独占全屏（失败自动回退无边框全屏窗），深色大字 + 进度环 + 状态行；**顶部可即时编辑的标题（加粗黑色大字 + 浅色底衬，点击即改、随打随存）**；**底部两个带矢量图标按钮：暂停/继续（暂停条 ⇄ 播放三角）、复原（回转箭头，回到上次设定起点并重新开始）**；空格=暂停·继续、R=复原、ESC/双击=收起；**不倒计时也能进**（就绪状态直接进入，点按钮才开始） |
| 双形态同步 | 倒计时形态可选"子球 / 全屏专注 / 两者"，共用同一会话，读数天然同步 |
| **自定义提醒音频** | 选择文件（推荐 16bit PCM WAV；WAV/AIFF/AU 等可解码格式）、试听、格式校验、音量 0–100、到时重复 1–99 次、最后 10 秒读秒、不可用时回退系统提示音 |
| 控制面板 | 一级菜单首项「控制面板…」：一屏内同时管理两个计时（读数实时刷新），避免命令全塞进右键菜单 |
| 设置入口 | 宿主设置窗"插件"区两个按钮：**计时子球（正计时）** / **计时子球（倒计时）**；另有菜单「倒计时设置 ▸ 常规设置…」 |
| 持久化 | 正计时 `data/timerball_plugin.properties`；倒计时 `data/countdown_plugin.properties`（两者独立，互不覆盖）；音频建议放 `data/countdown/` |
| **跨会话记忆** | 退出时记住这一轮的**剩余时间与状态**（正在走/已暂停）；下次启动自动恢复（正计时沿用宿主无关；归零即清除记忆）。文件位置 `data/countdown_plugin.properties` 的 `remainingMs`/`wasRunning` |

## 右键菜单结构（悬浮球右键 / 托盘 / 主窗"插件"菜单一致）

```
计时子球                              ← 一级分组 groupPath={"计时子球"}
  ├ 控制面板…                         一屏管理两个计时（已提到一级菜单首位）
  ├ 正计时：冒出/收回                 收起→冒出并开始；显示中→收回并暂停
  ├ 正计时：开始
  ├ 正计时：归零
  ├ 倒计时：冒出/收回                 未显示→只冒出（不自动倒计时）；显示中→收起并暂停
  ├ 倒计时：开始                      就绪→开始；运行中→暂停；暂停→继续
  ├ 倒计时：归零
  ├ 倒计时：全屏专注                  直接进/出番茄钟（不需要先在倒计时）
  └ 倒计时设置                        ← 二级分组 groupPath={"计时子球","倒计时设置"}
      ├ 快捷 1/3/5/10/15/25/30/45 分钟
      ├ 显示形态…
      └ 常规设置…
```

> 倒计时子球**自身**的右键菜单不含子菜单（避免子菜单在本环境下的文字重影/乱码），条目为：
> 开始/暂停、归零、全屏专注、分隔线、快捷 1/3/5/10/15/25 分钟、分隔线、倒计时设置…、收起。
> 界面上的"自检…"入口已按需求移除（诊断能力保留在自动化自检 harness 中）。

## 源码结构（15 个文件，默认包）

| 文件 | 职责 |
|---|---|
| `TimerBallPlugin.java` | 插件入口：设置窗两个按钮 + 三类菜单（带 groupPath 调用宿主分组 API）+ 控制面板 + 生命周期（`onLoad` 只注册与调度，`onUnload` 双 tracker 幂等清理） |
| `UpTracker.java` | 正计时控制器：全局 AWT 观察监听（左键单击/双击/拖动判定）+ 几何轮询 + 可见性回调 + 只读接口 |
| `UpBall.java` / `UpBallConfig.java` / `UpSettingsDialog.java` | 正计时子球窗口 / 外观配置 / 正计时设置对话框 |
| `DownController.java` | 倒计时控制器：官方几何 API + `BALL_*` 事件追踪、避让排布、位置夹取、命令入口（**无 AWT 监听**） |
| `DownBall.java` / `DownFocus.java` / `DownSession.java` | 倒计时子球窗口（右键菜单专属）/ 全屏专注窗（含两个图标按钮）/ 倒计时核心状态机（含"复原到上次起点"） |
| `DownSettingsDialog.java` | 倒计时设置对话框（时长、形态、尺寸、五态配色与警戒阈值、全屏外观、提醒音频、交互、恢复默认） |
| `TimerBallConfig.java` | 倒计时侧配置模型（读写 `data/countdown_plugin.properties`） |
| `TimerAlert.java` | 提醒音频播放（`Clip` + `LineListener`，后台线程，音量/重复/兜底） |
| `BallLayout.java` | **两球互不重叠**的落位算法（纯计算，可单测） |
| `TimerIcons.java` | **矢量图标库**（暂停/播放/复原/复位，Java2D 自绘，无图片资源）+ 屏幕夹取工具 |
| `TimerControlPanel.java` | 控制面板窗口（Host 回调接口解耦，400ms 刷新读数） |
| `TimerBallSelfTest.java` | 自检 harness（不打入 jar） |

## 自检

```
javac -encoding UTF-8 -cp "build;..\..\RandomNamePicker.jar" -d build TimerBallSelfTest.java
java -cp "..\..\RandomNamePicker.jar;build" TimerBallSelfTest
```

覆盖 **110+ 项断言**，重点是合并与本次改造引入的新约束：

- **落位算法**：单子球结果与旧算法逐像素一致；第二个子球与第一个不相交；屏幕中心/三子球场景仍不重叠且都在屏内；窄屏与"候选位全占用"不抛异常；
- **分组接口**：宿主必须提供 `addGroupedUiAction` 与三个带 `String[] groupPath` 的便捷重载、`MenuAction.getGroupPath()`、`MenuGroupSupport`；插件源码必须调用带 groupPath 的重载，且**不得**再出现 `▸` 标题约定或"自检"菜单；
- **倒计时仅右键 / 冒出不再自动计时**：`DownBall` 已移除单击延迟机制与自身菜单的子菜单、保留右键菜单与拖动；`DownController` 无 `AWTEventListener`、使用官方几何查询、具备避让注入与重排入口、冒出/快捷时长只显示不开始、`commandStart` 才计时、全屏不强制开始、位置会被夹回屏内；
- **全屏按钮**：`DownFocus$Actions` 含 `onToggle` 与 `onRestore`；`TimerIcons` 提供 pause/play/restore 与屏幕夹取；复原语义（回到上次起点并重新开始、改设定时长不影响上次起点）；
- **状态机与配置**：倒计时状态机；两个配置文件不同名；时长/音频/警戒色/形态/位置往返与坏值回退；音频兜底；jar 打包禁令 + `Api-Level-Min: 2`。

## 人工验收（重启宿主后）

1. 日志：`PLUGIN_LOADED`（`计时子球 2.1 …`）与 `TB_CONFIG` 初始化摘要（含两个子球配置）；
2. **左键**：打开悬浮球 → 单击 → 正计时子球冒出并开始；再单击 → 收回并暂停；宿主双击抽取、拖动移动不受影响；
3. **右键**：右键悬浮球 → 看到 **「计时子球」级联子菜单**（9 项核心命令 + 二级「倒计时设置」11 项），不再是一长条平铺；
4. **冒出不再自动计时**：选「倒计时：冒出/收回」→ 倒计时子球出现但读数为设定值、状态=就绪；再点「倒计时：开始」或单击子球右键→开始，才开始倒计时；
5. **不重叠**：上面第 4 步后正计时也冒出 → 倒计时自动换到另一侧分列；收起正计时 → 倒计时回到首选位；
6. **倒计时只走右键**：左键单击/双击倒计时子球**不该有任何反应**（仅可拖动移动）；右键它 → 出命令菜单（无子菜单、文字清晰）；
7. **全屏番茄钟**：选「倒计时：全屏专注」（**就绪状态也能进**）→ 顶部黑色加粗大字标题（点一下即可改，随打随存）+ 底部两个图标按钮；点"暂停/继续"开始/停/续，点"复原"回到上次设定起点并重新开始；空格/R/ESC 同效（编辑标题时空格/R 归输入框）；
8. **设置**：主窗 设置 → 插件区 → 「计时子球（倒计时）」→ 改警戒色/警戒阈值/自定义提醒音频（试听）→ 立即生效并写入 `data/countdown_plugin.properties`；
9. **撤载**：托盘"退出程序" → 日志出现 `计时子球 卸载` 与 `PLUGIN_UNLOADED`；删除 `plugins\TimerBall.jar` 重启 → 回到无插件基线。

## 历史说明

- `examples/plugin-timer/`（正计时子球，文档 §10 案例）与 `examples/plugin-countdown/`（倒计时插件）为**合并前的独立版本**，保留作历史与文档依据；其 jar 已被 `plugins/TimerBall.jar` 取代。
- 合并与宿主改造全过程的源码文本编辑由 Java 工具完成（`tools/TimerRenamer.java`、`tools/MergeFixups.java`、`tools/HostGroupApiPatch.java`、`tools/HostMenuTreePatch.java`）：本环境的 PowerShell 在对 UTF-8 源码"读-改-写"时偶发单字符损坏，故改用显式 UTF-8 的 Java 工具保证字节精确。
