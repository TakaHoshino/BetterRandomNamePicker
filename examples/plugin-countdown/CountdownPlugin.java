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
 * 倒计时插件（宿主源码零改动，纯插件 jar，部署 {@code plugins/}）。
 * <p>
 * 能力总览：
 * <ul>
 *   <li><b>两种显示形态</b>：贴着宿主悬浮球的"倒计时子球"（自绘圆窗 + 剩余比例进度环）、
 *       番茄钟式<b>全屏专注</b>（独占全屏，可回退全屏窗口），也可两者同时显示（共用同一会话，读数同步）；</li>
 *   <li><b>悬浮球交互仿照工作区内正计时子球</b>：单击悬浮球（非拖动、非双击序列）=显示/收起
 *       （显示即自动开始/继续，收起即暂停并保留读数）；宿主双击抽取、拖动移动保持原样；
 *       子球自身单击=开始/暂停/继续，双击=归零并收起，右键=快捷菜单，拖动=移动并记忆位置；</li>
 *   <li><b>设置面板入口</b>：宿主设置窗"插件"区"倒计时插件"按钮 → “倒计时设置”对话框，
 *       可改时长与预设、形态、尺寸/间距、五态配色（含<b>警戒色</b>）、全屏外观、交互，
 *       以及最重要的<b>自定义提醒音频</b>（选择文件 / 试听 / 格式校验 / 音量 / 重复次数 /
 *       最后读秒 / 系统提示音兜底）；全部即时生效并持久化到 {@code data/countdown_plugin.properties}；</li>
 *   <li>主窗/托盘/悬浮球三类菜单各挂：显示·收起、开始·暂停、归零、全屏专注、立即结束、设置、快捷时长。</li>
 * </ul>
 * 生命周期：{@code onLoad} 在后台加载线程，只做注册 + {@code invokeLater} 调度初始化；
 * {@code onUnload} 幂等清理（停 Timer、卸载 AWT 监听与宿主事件订阅、停音频、销毁窗口），
 * 并兼容整 jar 拒载回滚路径。宿主事件订阅使用公开 API（API 级别 2），不依赖宿主内部类。
 */
public class CountdownPlugin implements Plugin {

    private static final String NAME = "倒计时插件";
    private static final String VERSION = "1.0";
    /** 版本文本（自检报告等复用）。 */
    static final String VERSION_TEXT = VERSION;

    private PluginContext ctx;
    private volatile CountdownConfig config;
    private volatile CountdownController controller;
    private volatile CountdownSettingsDialog settingsDialog;
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

        // 1) 设置窗"插件"区按钮：生成"倒计时设置"窗口（时长/配色/警戒色/自定义提醒音频…）
        context.addUiAction(UiZone.SETTINGS_WINDOW, "倒计时插件", this::openSettings);

        // 2) 三类菜单（主窗 / 托盘 / 悬浮球右键）
        String[][] actions = {
                {"倒计时：显示/收起", "toggle"},
                {"倒计时：开始/暂停", "run"},
                {"倒计时：归零", "reset"},
                {"倒计时：全屏专注", "focus"},
                {"倒计时：立即结束", "finish"},
                {"倒计时：设置…", "settings"},
                {"倒计时：自检…", "selfcheck"},
        };
        for (String[] a : actions) {
            context.addMainMenuAction(a[0], e -> runOnEdt(() -> dispatch(a[1], e)));
            context.addTrayMenuAction(a[0], e -> runOnEdt(() -> dispatch(a[1], e)));
            context.addFloatingBallMenuAction(a[0], e -> runOnEdt(() -> dispatch(a[1], e)));
        }
        // 快捷时长（快捷改一轮新倒计时；同时便于全屏场景快速开始番茄钟）
        for (int m : CountdownConfig.PRESET_MINUTES) {
            final int minutes = m;
            String title = "倒计时：快捷 " + m + " 分钟";
            context.addMainMenuAction(title, e -> runOnEdt(() -> quickDuration(minutes)));
            context.addTrayMenuAction(title, e -> runOnEdt(() -> quickDuration(minutes)));
            context.addFloatingBallMenuAction(title, e -> runOnEdt(() -> quickDuration(minutes)));
        }

        context.log(NAME + " " + VERSION + " 已加载（宿主 API 级别 " + context.getApiLevel()
                + "，宿主版本 " + context.getHostVersion() + "）", "PLUGIN_LOADED");

        // 3) EDT 初始化（加载配置 + 建控制器：装全局 AWT 观察监听、启 tick、订阅宿主事件）
        SwingUtilities.invokeLater(this::initOnEdt);
    }

    @Override
    public void onUnload() {
        inactive = true;
        CountdownSettingsDialog dlg = settingsDialog;
        settingsDialog = null;
        if (dlg != null) {
            try {
                dlg.dispose();
            } catch (Throwable ignore) {
                // 忽略
            }
        }
        CountdownController c = controller;
        controller = null;
        config = null;
        if (c != null) {
            c.stop();
        }
        ctx = null;
        safeLog("倒计时插件 卸载", "PLUGIN_UNLOADED");
    }

    // ===================== 初始化 =====================

    private void initOnEdt() {
        if (inactive) {
            return;
        }
        ensureInitializedOnEdt();
    }

    /** 惰性装配（仅 EDT）：加载配置 → 建控制器 → 设置入口回调 → 启动。 */
    private void ensureInitializedOnEdt() {
        if (inactive) {
            return;
        }
        if (config == null) {
            CountdownConfig c = new CountdownConfig();
            c.load();
            config = c;
        }
        if (controller == null) {
            CountdownController ctrl = new CountdownController(ctx, config);
            ctrl.setSettingsOpener(owner -> openSettingsDialog(owner));
            ctrl.start();
            controller = ctrl;
            safeLog("倒计时插件-初始化完成 " + config.summary(), "CD_CONFIG");
        }
    }

    // ===================== 动作分发 =====================

    private void dispatch(String action, ActionEvent ev) {
        if (inactive) {
            return;
        }
        ensureInitializedOnEdt();
        CountdownController c = controller;
        if (c == null) {
            return;
        }
        Window owner = ev == null ? null : ownerOf(ev);
        switch (action) {
            case "toggle":
                c.toggleShowFromMenu(owner);
                break;
            case "run":
                c.toggleRunFromMenu(owner);
                break;
            case "reset":
                c.resetFromMenu(owner);
                break;
            case "focus":
                c.toggleFocusFromMenu(owner);
                break;
            case "finish":
                c.finishNowFromMenu(owner);
                break;
            case "settings":
                openSettingsDialog(owner);
                break;
            case "selfcheck":
                c.showSelfCheck(owner);
                break;
            default:
                break;
        }
    }

    private void quickDuration(int minutes) {
        if (inactive) {
            return;
        }
        ensureInitializedOnEdt();
        CountdownController c = controller;
        if (c != null) {
            c.setQuickDuration(minutes);
        }
    }

    // ===================== 设置窗按钮 → 倒计时设置对话框 =====================

    /** 宿主设置窗"插件"区按钮回调（EDT，由宿主 runMenuActionSafely 防御执行）。 */
    private void openSettings(ActionEvent ev) {
        runOnEdt(() -> {
            if (inactive) {
                return;
            }
            ensureInitializedOnEdt();
            Window owner = ev == null ? null : ownerOf(ev);
            if (owner == null && controller != null) {
                owner = controller.hostWindow();
            }
            openSettingsDialog(owner);
        });
    }

    /** 打开模态设置对话框（同一时刻只保留一个）。 */
    private void openSettingsDialog(Window owner) {
        if (inactive) {
            return;
        }
        ensureInitializedOnEdt();
        CountdownController c = controller;
        CountdownConfig cfg = config;
        if (c == null || cfg == null) {
            return;
        }
        if (settingsDialog != null && settingsDialog.isDisplayable()) {
            settingsDialog.toFront();
            return;
        }
        if (owner == null) {
            owner = c.hostWindow();
        }
        try {
            if (inactive) {
                return;
            }
            final CountdownSettingsDialog dlg = new CountdownSettingsDialog(owner, cfg, c,
                    () -> {
                        CountdownController cc = controller;
                        if (cc != null) {
                            cc.applyConfig();
                        }
                    });
            settingsDialog = dlg;
            dlg.setVisible(true);
            dlg.dispose();
        } catch (Throwable t) {
            safeLog("倒计时插件-设置窗打开失败: " + t, "PLUGIN_LOAD_ERROR");
            JOptionPane.showMessageDialog(owner == null ? null : owner,
                    "倒计时设置窗打开失败：" + t.getMessage(), "倒计时插件",
                    JOptionPane.ERROR_MESSAGE);
        } finally {
            settingsDialog = null;
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
