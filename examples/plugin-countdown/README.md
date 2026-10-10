# 倒计时插件（CountdownTimer）

宿主 **零改动**的纯插件（宿主插件 API 级别 2），部署到程序运行目录 `plugins/`，重启宿主生效。
与工作区内 `examples/plugin-timer/`（正计时子球）互为配套：一个正计时、一个倒计时，交互语言一致。

- 快速部署：工作区根执行 `powershell -ExecutionPolicy Bypass -File tools\deploy-countdown.ps1`
  （编译 → 打包 → 复制到 `plugins\` → 跑自检；自检不通过会中止并报错）。
- 编译/打包/安装/验收细节与预期效果：见 [BUILD.txt](BUILD.txt)。

## 功能一览

| 能力 | 说明 |
|---|---|
| 倒计时子球 | 自绘圆窗 + 剩余比例进度环，贴着宿主悬浮球显示；尺寸随悬浮球自动（×0.55 夹 36–60px）或固定 30–300px |
| 全屏专注（番茄钟） | 独占全屏（失败自动回退无边框全屏窗），深色大字 + 进度环 + 状态行，可显示最近一次抽取结果 |
| 双形态同步 | 形态可选"子球 / 全屏专注 / 两者"，两者共用同一会话，读数天然同步 |
| 悬浮球交互（仿正计时子球） | 单击悬浮球 = 显示/收起（显示即自动开始/继续，收起即暂停并保留读数）；宿主双击抽取、拖动移动保持原样 |
| 子球交互 | 单击 = 开始/暂停/继续；双击 = 归零并收起；右键 = 快捷菜单（快捷时长/切全屏/设置）；拖动 = 移动并记忆位置 |
| 全屏交互 | 空格 = 暂停/继续；R = 归零；ESC 或双击 = 收起 |
| 设置入口 | 宿主**设置窗 → 插件区 →「倒计时插件」按钮**，或在主窗/托盘/悬浮球右键菜单选"倒计时：设置…" |
| 常规设置 | 时长与预设（1/3/5/10/15/25/30/45 分钟 + 自定义 mm:ss）、显示形态、子球尺寸与间距、显示即开始、跟随悬浮球、进度环、时刻格式、恢复默认 |
| 配色 | 五态可改：**就绪 / 运行 / 暂停 / 警戒 / 到时**，并可设定警戒阈值（剩余 ≤N 秒切警戒色） |
| **自定义提醒音频** | 选择音频文件（推荐 16bit PCM WAV；WAV/AIFF/AU 等 `javax.sound.sampled` 可解码格式）、试听、格式校验、音量（0–100，MASTER_GAIN）、到时重复播放次数（1–99）、最后 10 秒读秒提醒、自定义音频不可用时回退系统提示音 |
| 菜单动作 | 主窗/托盘/悬浮球三类菜单：显示·收起、开始·暂停、归零、全屏专注、立即结束、设置…、自检…，以及 8 个快捷时长 |
| 自检报告 | "倒计时：自检…"只读展示：宿主 API 级别、配置路径、形态与可见性、本轮读数与状态、悬浮球可达性、音频文件校验与响铃设置、操作提示 |
| 持久化 | `data/countdown_plugin.properties`（插件自持，不触碰宿主 `data/config.properties`）；音频建议放 `data/countdown/` |

## 设计要点（对照《插件开发文档.md》）

- **只用公开 API**：`PluginContext`（`addUiAction`/三类菜单/`addHostEventListener`/`log`）、
  `ModeHost`（`isFloatingBallVisible`/`getFloatingBallBounds`/`getHostWindow`）、`HostEvent`（`PICK_FINISHED` 等）。
  刻意**不引用** `com.randomnamepicker.floating.FloatingBall` 等宿主内部类：判定"是否点在悬浮球上"用的是
  官方几何矩形（§15.4 G1）做纯几何命中测试，而非 `instanceof` 内部类。
- **线程规约**：`onLoad` 仅注册 + `invokeLater` 调度初始化；全部窗口/计时/Timer 在 EDT；
  提醒音频在后台线程播放（`Clip` + `LineListener` 等待结束），绝不阻塞 EDT；
  `onUnload` 幂等清理（停 Timer → 卸载 AWT 监听 → 退订宿主事件 → 停音频 → 销毁窗口）。
- **失败隔离**：`canPick`/宿主无钩子的场景一律兜底；配置坏值回退默认；音频不可用回退系统提示音；
  异常只记日志（`PLUGIN_LOAD_ERROR`），不影响宿主与其它插件。

## 自检

```
javac -encoding UTF-8 -cp "build;..\..\RandomNamePicker.jar" -d build CountdownSelfTest.java
java -cp "..\..\RandomNamePicker.jar;build" CountdownSelfTest
```

覆盖 72 项断言：配置默认值/往返/坏值回退、时长解析与格式化、会话状态机（开始-暂停-继续-归零-到时-读秒-
不重复回调）、提醒音频兜底路径、jar 打包禁令（无 `com/randomnamepicker/**`）与 Manifest `Api-Level-Min: 2`、
两种形态窗口构建。退出码 0 = 全部通过。
