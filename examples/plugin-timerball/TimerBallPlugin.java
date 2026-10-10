import com.randomnamepicker.core.LogManager;
import com.randomnamepicker.plugin.HostApi;
import com.randomnamepicker.plugin.Plugin;
import com.randomnamepicker.plugin.PluginContext;
import com.randomnamepicker.plugin.UiZone;
import java.awt.Component;
import java.awt.Window;
import java.awt.event.ActionEvent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * 计时子球插件（正计时 + 倒计时合并版）。
 * <p>
 * <b>右键菜单结构（按需求重排）</b>——全部条目都经<b>宿主正式的分组接口</b>注册：
 * {@code addMainMenuAction / addTrayMenuAction / addFloatingBallMenuAction(String[] groupPath, String title, ActionListener)}，
 * 由宿主按 groupPath 建出级联子菜单（宿主端不含任何插件业务字样）：
 * </p>
 * <pre>
 * 计时子球                        ← 一级分组（groupPath = {"计时子球"}）
 *   ├ 正计时：冒出                 显示正计时子球并开始
 *   ├ 正计时：收回                 收起并暂停（保留读数）
 *   ├ 正计时：开始
 *   ├ 正计时：归零
 *   ├ 倒计时：冒出                 显示倒计时子球并开始倒计时
 *   ├ 倒计时：收回
 *   ├ 倒计时：开始
 *   ├ 倒计时：归零
 *   ├ 倒计时：全屏专注             进/出番茄钟全屏
 *   └ 倒计时设置                    ← 二级分组（groupPath = {"计时子球","倒计时设置"}）
 *       ├ 快捷 1/3/5/10/15/25/30/45 分钟
 *       ├ 控制面板…（已提到一级菜单首位）
 *       └ 常规设置…
 * </pre>
 * <p>
 * 说明：倒计时"暂停"与正计时同一条命令（点"开始"时运行中即暂停），因此不再单独设"开始/暂停"项；
 * 界面上的"自检…"入口已按需求移除（诊断能力保留在自动化自检 harness 里）。
 * </p>
 * <p>
 * 生命周期：{@code onLoad} 在后台加载线程只做注册 + {@code invokeLater}；{@code onUnload} 幂等清理
 * （停两个控制器的 Timer/订阅/音频、销毁全部窗口），兼容整 jar 拒载回滚。
 * </p>
 */
public class TimerBallPlugin implements Plugin {

    private static final String NAME = "计时子球";
    private static final String VERSION = "2.1";
    /** 版本文本（自检报告等复用）。 */
    static final String VERSION_TEXT = VERSION;

    /** 一级分组名（宿主按 MenuAction.groupPath 生成级联子菜单）。 */
    static final String GROUP = "计时子球";
    /** 二级分组名（倒计时的其余功能都收在这里）。 */
    static final String GROUP_DOWN_SETTINGS = "倒计时设置";
    /** 一级分组路径。 */
    private static final String[] PATH_TOP = {GROUP};
    /** 二级分组路径（计时子球 组下的 倒计时设置 子组）。 */
    private static final String[] PATH_DOWN_SETTINGS = {GROUP, GROUP_DOWN_SETTINGS};

    private PluginContext ctx;
    private volatile UpBallConfig upConfig;
    private volatile TimerBallConfig downConfig;
    private volatile UpTracker upTracker;
    private volatile DownController downController;
    private volatile TimerControlPanel controlPanel;
    private volatile UpSettingsDialog upSettingsDialog;
    private volatile DownSettingsDialog downSettingsDialog;
    private volatile boolean inactive = false;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public void onLoad(PluginContext context) {
        this.ctx = context;

        // 1) 设置窗"插件"区两个按钮（平铺，无分组）
        context.addUiAction(UiZone.SETTINGS_WINDOW, "计时子球（正计时）", this::openUpSettings);
        context.addUiAction(UiZone.SETTINGS_WINDOW, "计时子球（倒计时）", this::openDownSettings);

        // 2) 一级菜单：控制面板置顶（最常用的统一入口），随后是正计时/倒计时的核心命令
        registerEverywhere(context, PATH_TOP, "控制面板…", "panel");
        registerEverywhere(context, PATH_TOP, "正计时：冒出/收回", "upToggle");
        registerEverywhere(context, PATH_TOP, "正计时：开始", "upStart");
        registerEverywhere(context, PATH_TOP, "正计时：归零", "upReset");
        registerEverywhere(context, PATH_TOP, "倒计时：冒出/收回", "downToggle");
        registerEverywhere(context, PATH_TOP, "倒计时：开始", "downStart");
        registerEverywhere(context, PATH_TOP, "倒计时：归零", "downReset");
        registerEverywhere(context, PATH_TOP, "倒计时：全屏专注", "downFocus");

        // 3) 二级菜单（倒计时其余功能，收在「倒计时设置」里；控制面板已提到一级）
        for (int m : TimerBallConfig.PRESET_MINUTES) {
            final int minutes = m;
            registerEverywhere(context, PATH_DOWN_SETTINGS, "快捷 " + m + " 分钟",
                    e -> runOnEdt(() -> quickDown(minutes)));
        }
        // 说明：原先"显示形态…"与"常规设置…"打开同一个对话框（形态是对话框里的一组选项），
        // 属于重复入口，按需求只保留一个。
        registerEverywhere(context, PATH_DOWN_SETTINGS, "常规设置…", "downSettings");

        context.log(NAME + " " + VERSION + " 已加载（宿主 API 级别 " + context.getApiLevel()
                + "，宿主版本 " + context.getHostVersion()
                + "；一级菜单=控制面板 + 正计时冒出/收回·开始·归零 + 倒计时冒出/收回·开始·归零·全屏，"
                + "倒计时其余功能收在二级菜单）", "PLUGIN_LOADED");

        // 4) EDT 初始化（加载两份配置 + 建两个控制器 + 互不重叠排布联动）
        SwingUtilities.invokeLater(this::initOnEdt);
    }

    /** 同一个动作同时注册到主窗菜单、托盘菜单、悬浮球右键（同一分组路径 + 同一标题）。 */
    private void registerEverywhere(PluginContext context, String[] groupPath, String title, String action) {
        java.awt.event.ActionListener listener = e -> runOnEdt(() -> dispatch(action, e));
        registerEverywhere(context, groupPath, title, listener);
    }

    /** 同一个自定义动作同时注册到三类菜单（用于快捷时长等直接回调）。 */
    private void registerEverywhere(PluginContext context, String[] groupPath, String title,
                                    java.awt.event.ActionListener action) {
        context.addMainMenuAction(groupPath, title, action);
        context.addTrayMenuAction(groupPath, title, action);
        context.addFloatingBallMenuAction(groupPath, title, action);
    }

    @Override
    public void onUnload() {
        inactive = true;
        closeDialogs();
        DownController d = downController;
        downController = null;
        UpTracker u = upTracker;
        upTracker = null;
        upConfig = null;
        downConfig = null;
        if (d != null) {
            d.stop();
        }
        if (u != null) {
            u.stop();
        }
        ctx = null;
        safeLog(NAME + " 卸载", "PLUGIN_UNLOADED");
    }

    private void closeDialogs() {
        TimerControlPanel p = controlPanel;
        controlPanel = null;
        UpSettingsDialog us = upSettingsDialog;
        upSettingsDialog = null;
        DownSettingsDialog ds = downSettingsDialog;
        downSettingsDialog = null;
        for (java.awt.Window w : new java.awt.Window[]{p, us, ds}) {
            if (w != null) {
                try {
                    w.dispose();
                } catch (Throwable ignore) {
                    // 忽略
                }
            }
        }
    }

    // ===================== 初始化 =====================

    private void initOnEdt() {
        if (inactive) {
            return;
        }
        ensureInitializedOnEdt();
    }

    /** 惰性装配（仅 EDT）：两份配置 + 两个控制器 + 倒计时对正计时的避让联动。 */
    private void ensureInitializedOnEdt() {
        if (inactive) {
            return;
        }
        if (upConfig == null) {
            UpBallConfig c = new UpBallConfig();
            c.load();
            upConfig = c;
        }
        if (downConfig == null) {
            TimerBallConfig c = new TimerBallConfig();
            c.load();
            downConfig = c;
        }
        if (upTracker == null) {
            UpTracker t = new UpTracker(upConfig);
            // 正计时子球冒出/收回后：让倒计时子球重新排布（保证两颗子球不重叠）
            t.setVisibilityListener(() -> {
                DownController d = downController;
                if (d != null) {
                    d.relayout();
                }
            });
            t.start();
            upTracker = t;
        }
        if (downController == null) {
            DownController d = new DownController(ctx, downConfig);
            d.setSettingsOpener(owner -> openDownSettingsDialog(owner));
            // 避让提供者：正计时子球可见时把它的矩形交给倒计时落位算法
            d.setAvoidanceProvider(() -> {
                UpTracker t = upTracker;
                if (t == null || !t.isChildVisible()) {
                    return new java.awt.Rectangle[0];
                }
                java.awt.Rectangle r = t.getChildBounds();
                return r == null ? new java.awt.Rectangle[0] : new java.awt.Rectangle[]{r};
            });
            d.start();
            downController = d;
        }
        safeLog("计时子球-初始化完成 正计时[" + upConfig.summary() + "] 倒计时[" + downConfig.summary() + "]",
                "TB_CONFIG");
    }

    // ===================== 动作分发 =====================

    private void dispatch(String action, ActionEvent ev) {
        if (inactive) {
            return;
        }
        ensureInitializedOnEdt();
        Window owner = ev == null ? null : ownerOf(ev);
        if (owner == null) {
            owner = hostWindow();
        }
        switch (action) {
            case "upToggle":
                upToggle(owner);
                break;
            case "upStart":
                upStart(owner);
                break;
            case "upReset":
                upReset(owner);
                break;
            case "downToggle":
                downToggle(owner);
                break;
            case "downStart":
                downStart(owner);
                break;
            case "downReset":
                downReset(owner);
                break;
            case "downFocus":
                downFocus(owner);
                break;
            case "downSettings":
                openDownSettingsDialog(owner);
                break;
            case "panel":
                openControlPanel(owner);
                break;
            default:
                break;
        }
    }

    // ===================== 正计时：冒出/收回 / 开始 / 归零 =====================

    /**
     * 正计时"冒出/收回"（合并为一项，与合并前插件的口径一致）：
     * 收起且就绪 → 冒出并开始；显示中 → 收回并暂停（保留读数）。
     */
    private void upToggle(Window owner) {
        UpTracker u = upTracker;
        if (u != null) {
            u.toggleShowFromMenu(owner);
        }
    }

    /** 正计时"开始"：确保冒出并开始/继续。 */
    private void upStart(Window owner) {
        UpTracker u = upTracker;
        if (u == null) {
            return;
        }
        if (!u.isChildVisible()) {
            u.toggleShowFromMenu(owner);
            return;
        }
        if (u.getBall().isRunning()) {
            return; // 已在运行：不重复开始
        }
        if (u.getBall().isPaused()) {
            u.getBall().resumeCount();
            safeLog("计时子球·正计时-继续(菜单)", "TIMER_RESUME");
        } else {
            u.getBall().startCount();
            safeLog("计时子球·正计时-开始(菜单)", "TIMER_START");
        }
        u.getBall().refresh();
    }

    /** 正计时"归零"：读数清零并收起（口径同原插件）。 */
    private void upReset(Window owner) {
        UpTracker u = upTracker;
        if (u != null) {
            u.resetFromMenu(owner);
        }
    }

    // ===================== 倒计时：冒出/收回 / 开始 / 归零 / 全屏 =====================

    /**
     * 倒计时"冒出/收回"（合并为一项）：
     * 未显示 → 仅冒出子球（<b>不自动开始</b>，等用户单击子球或选"倒计时：开始"）；显示中 → 收起并暂停。
     */
    private void downToggle(Window owner) {
        DownController d = downController;
        if (d != null) {
            d.toggleShowFromMenu(owner);
        }
    }

    /** 倒计时"开始"：运行中再点 = 暂停（与正计时同一条命令）。 */
    private void downStart(Window owner) {
        DownController d = downController;
        if (d != null) {
            d.commandToggleRun(owner);
        }
    }

    /** 倒计时"归零"：读数回到设定时长并收起。 */
    private void downReset(Window owner) {
        DownController d = downController;
        if (d != null) {
            d.commandReset(owner);
        }
    }

    /** 倒计时"全屏专注"：进/出番茄钟全屏。 */
    private void downFocus(Window owner) {
        DownController d = downController;
        if (d != null) {
            d.commandToggleFocus(owner);
        }
    }

    private void quickDown(int minutes) {
        if (inactive) {
            return;
        }
        ensureInitializedOnEdt();
        DownController d = downController;
        if (d != null) {
            d.commandSetQuickDuration(minutes);
        }
    }

    // ===================== 控制面板 =====================

    private void openControlPanel(Window owner) {
        if (inactive) {
            return;
        }
        ensureInitializedOnEdt();
        if (controlPanel != null && controlPanel.isDisplayable()) {
            controlPanel.toFront();
            return;
        }
        try {
            TimerControlPanel panel = new TimerControlPanel(owner, new PanelHost());
            controlPanel = panel;
            panel.setVisible(true);
        } catch (Throwable t) {
            safeLog("计时子球-控制面板打开失败: " + t, "PLUGIN_LOAD_ERROR");
        }
    }

    /** 控制面板 → 两个控制器的适配器。 */
    private class PanelHost implements TimerControlPanel.Host {

        @Override
        public boolean isUpVisible() {
            UpTracker u = upTracker;
            return u != null && u.isChildVisible();
        }

        @Override
        public String upStateText() {
            UpTracker u = upTracker;
            if (u == null) {
                return "未初始化";
            }
            UpBall ball = u.getBall();
            String state;
            switch (ball.getState()) {
                case RUNNING:
                    state = "正计时中";
                    break;
                case PAUSED:
                    state = "已暂停";
                    break;
                default:
                    state = "就绪";
                    break;
            }
            return (u.isChildVisible() ? "已显示" : "已收起") + "　" + state;
        }

        @Override
        public void toggleUpShowHide() {
            UpTracker u = upTracker;
            if (u != null) {
                u.toggleShowFromMenu(hostWindow());
            }
        }

        @Override
        public void toggleUpRun() {
            UpTracker u = upTracker;
            if (u == null) {
                return;
            }
            if (!u.isChildVisible()) {
                u.toggleShowFromMenu(hostWindow());
                return;
            }
            UpBall ball = u.getBall();
            if (ball.isRunning()) {
                ball.pauseCount();
                safeLog("计时子球·正计时-暂停(控制面板)", "TIMER_PAUSE");
            } else if (ball.isPaused()) {
                ball.resumeCount();
                safeLog("计时子球·正计时-继续(控制面板)", "TIMER_RESUME");
            } else {
                ball.startCount();
                safeLog("计时子球·正计时-开始(控制面板)", "TIMER_START");
            }
            ball.refresh();
        }

        @Override
        public void resetUp() {
            UpTracker u = upTracker;
            if (u != null) {
                u.resetFromMenu(hostWindow());
            }
        }

        @Override
        public boolean isDownVisible() {
            DownController d = downController;
            return d != null && d.isBallVisible();
        }

        @Override
        public String downReadout() {
            DownController d = downController;
            if (d == null) {
                return "--:--";
            }
            return d.session().displayText(downConfig != null && downConfig.isShowHours(), false);
        }

        @Override
        public String downStateText() {
            DownController d = downController;
            if (d == null) {
                return "未初始化";
            }
            String state;
            switch (d.session().getState()) {
                case RUNNING:
                    state = "倒计时中";
                    break;
                case PAUSED:
                    state = "已暂停";
                    break;
                case FINISHED:
                    state = "时间到";
                    break;
                default:
                    state = "就绪";
                    break;
            }
            return state + "　设定 "
                    + TimerBallConfig.formatDuration(downConfig == null
                    ? TimerBallConfig.DEFAULT_DURATION_SECONDS : downConfig.getDurationSeconds());
        }

        @Override
        public void toggleDownShowHide() {
            DownController d = downController;
            if (d != null) {
                d.toggleShowFromMenu(hostWindow());
            }
        }

        @Override
        public void toggleDownRun() {
            DownController d = downController;
            if (d != null) {
                d.commandToggleRun(hostWindow());
            }
        }

        @Override
        public void resetDown() {
            DownController d = downController;
            if (d != null) {
                d.commandReset(hostWindow());
            }
        }

        @Override
        public void showDownFocus() {
            DownController d = downController;
            if (d != null) {
                d.commandToggleFocus(hostWindow());
            }
        }

        @Override
        public void setDownDuration(int minutes) {
            DownController d = downController;
            if (d != null) {
                d.commandSetQuickDuration(minutes);
            }
        }

        @Override
        public void openUpSettings() {
            openUpSettingsDialog(hostWindow());
        }

        @Override
        public void openDownSettings() {
            openDownSettingsDialog(hostWindow());
        }
    }

    // ===================== 设置窗按钮 → 两个设置对话框 =====================

    private void openUpSettings(ActionEvent ev) {
        runOnEdt(() -> {
            if (inactive) {
                return;
            }
            ensureInitializedOnEdt();
            Window owner = ev == null ? null : ownerOf(ev);
            openUpSettingsDialog(owner == null ? hostWindow() : owner);
        });
    }

    private void openDownSettings(ActionEvent ev) {
        runOnEdt(() -> {
            if (inactive) {
                return;
            }
            ensureInitializedOnEdt();
            Window owner = ev == null ? null : ownerOf(ev);
            openDownSettingsDialog(owner == null ? hostWindow() : owner);
        });
    }

    private void openUpSettingsDialog(Window owner) {
        if (inactive) {
            return;
        }
        ensureInitializedOnEdt();
        UpBallConfig cfg = upConfig;
        if (cfg == null) {
            return;
        }
        if (upSettingsDialog != null && upSettingsDialog.isDisplayable()) {
            upSettingsDialog.toFront();
            return;
        }
        try {
            final UpSettingsDialog dlg = new UpSettingsDialog(owner, cfg, this::applyUpConfig);
            upSettingsDialog = dlg;
            dlg.setVisible(true);
            dlg.dispose();
        } catch (Throwable t) {
            safeLog("计时子球-正计时设置窗打开失败: " + t, "PLUGIN_LOAD_ERROR");
            JOptionPane.showMessageDialog(owner, "正计时设置窗打开失败：" + t.getMessage(),
                    NAME, JOptionPane.ERROR_MESSAGE);
        } finally {
            upSettingsDialog = null;
        }
    }

    private void openDownSettingsDialog(Window owner) {
        if (inactive) {
            return;
        }
        ensureInitializedOnEdt();
        TimerBallConfig cfg = downConfig;
        DownController d = downController;
        if (cfg == null || d == null) {
            return;
        }
        if (downSettingsDialog != null && downSettingsDialog.isDisplayable()) {
            downSettingsDialog.toFront();
            return;
        }
        try {
            final DownSettingsDialog dlg = new DownSettingsDialog(owner, cfg, d, this::applyDownConfig);
            downSettingsDialog = dlg;
            dlg.setVisible(true);
            dlg.dispose();
        } catch (Throwable t) {
            safeLog("计时子球-倒计时设置窗打开失败: " + t, "PLUGIN_LOAD_ERROR");
            JOptionPane.showMessageDialog(owner, "倒计时设置窗打开失败：" + t.getMessage(),
                    NAME, JOptionPane.ERROR_MESSAGE);
        } finally {
            downSettingsDialog = null;
        }
    }

    private void applyUpConfig() {
        UpTracker u = upTracker;
        if (u != null) {
            u.applyConfig();
        }
        // 正计时尺寸/间距变化后，倒计时也需要重新避让排布
        DownController d = downController;
        if (d != null) {
            d.relayout();
        }
    }

    private void applyDownConfig() {
        DownController d = downController;
        if (d != null) {
            d.applyConfig();
        }
    }

    // ===================== 工具 =====================

    /** 按钮事件源所在 Window（宿主 UI 槽按钮位于设置窗内）。 */
    private Window ownerOf(ActionEvent ev) {
        try {
            if (ev != null && ev.getSource() instanceof Component) {
                Window w = SwingUtilities.getWindowAncestor((Component) ev.getSource());
                if (w != null) {
                    return w;
                }
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        return null;
    }

    /** 宿主主窗（对话框父窗；顶级主窗 getOwner() 为 null，故用 getHostWindow()）。 */
    private Window hostWindow() {
        try {
            if (ctx != null && ctx.getModeHost() != null) {
                Window w = ctx.getModeHost().getHostWindow();
                if (w != null) {
                    return w;
                }
                return ctx.getModeHost().getOwner();
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        return null;
    }

    private void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private void safeLog(String detail, String opCode) {
        PluginContext c = ctx;
        try {
            if (c != null) {
                c.log(detail, opCode);
            } else {
                LogManager.log(detail, opCode);
            }
        } catch (Throwable ignore) {
            // 忽略
        }
    }

    /** 宿主 API 级别自检（仅用于日志提示，门控由宿主按 Manifest 执行）。 */
    public static int hostApiLevel() {
        return HostApi.PLUGIN_API_LEVEL;
    }
}
