import com.randomnamepicker.core.LogManager;
import com.randomnamepicker.mode.ModeHost;
import com.randomnamepicker.plugin.HostApi;
import com.randomnamepicker.plugin.HostEvent;
import com.randomnamepicker.plugin.HostEventListener;
import com.randomnamepicker.plugin.PluginContext;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 倒计时控制器：把"倒计时核心 + 子球形态 + 全屏专注形态 + 宿主交互"装配在一起。
 * <p>
 * 与工作区内正计时子球（{@code FloatingBallTracker}）保持同一交互口径，但宿主交互改用
 * <b>官方 API</b>（《插件开发文档.md》§15.4 G1/G2）：几何与可见性经
 * {@link ModeHost#isFloatingBallVisible()} / {@link ModeHost#getFloatingBallBounds()} 与
 * {@code BALL_SHOWN/HIDDEN/MOVED} 事件获得，<b>不依赖 {@code com.randomnamepicker.floating.FloatingBall}</b>
 * 这一宿主内部类（仅用几何判定"点击是否落在悬浮球矩形内"，避开内部类型）。
 * </p>
 * <ul>
 *   <li>单击悬浮球（非拖动、非双击序列）≡ 菜单"倒计时：显示/收起"：显示时按设置自动开始/继续，
 *       收起时暂停并保留读数（与正计时子球一致）；宿主双击抽取、拖动移动均保持原样；</li>
 *   <li>子球单击=开始/暂停/继续，双击=归零并收起；右键=快捷菜单；拖动=移动并记忆位置；</li>
 *   <li>全屏专注窗：双击/ESC/右键收起，空格暂停·继续，R 归零；</li>
 *   <li>形态可选：子球 / 全屏专注 / 两者（共用同一会话，读数天然同步）。</li>
 * </ul>
 * 公开方法仅限 EDT 调用（start/stop 内部自行切换）。
 */
public class CountdownController implements CountdownBall.Actions, CountdownFocus.Actions {

    private static final int POLL_MS = 100;
    private static final int TICK_MS = 100;
    /** AUTO 联动口径（与正计时子球一致）：悬浮球直径 × 0.55，夹 36–60px。 */
    private static final double SIZE_RATIO = 0.55;
    private static final int AUTO_MIN_DIAMETER = 36;
    private static final int AUTO_MAX_DIAMETER = 60;
    private static final int INITIAL_DIAMETER = 44;

    private final PluginContext ctx;
    private final CountdownConfig config;
    private final CountdownSession session;
    private final CountdownAlert alert;
    private final CountdownBall ball;
    private final CountdownFocus focus;

    private final Timer pollTimer = new Timer(POLL_MS, e -> onPollTick());
    private final Timer tickTimer = new Timer(TICK_MS, e -> onTick());
    private AWTEventListener awtListener;
    private HostEventListener hostListener;

    private boolean stopped;
    private boolean ballVisible;
    private boolean focusVisible;
    private Rectangle ballBounds;
    private boolean lastBallSeen;

    // 悬浮球单击延迟-取消判定（与正计时子球 §1.3 同口径）
    private boolean pressedInBall;
    private boolean dragSincePress;
    private Timer singleToggleTimer;
    private int multiClickIntervalMs = 500;

    /** 设置窗回调（控制器不拥有对话框，由插件入口提供）。 */
    public interface SettingsOpener {
        void open(Window owner);
    }

    private SettingsOpener settingsOpener;

    public CountdownController(PluginContext ctx, CountdownConfig config) {
        this.ctx = ctx;
        this.config = config;
        this.session = new CountdownSession(config.getDurationSeconds());
        this.alert = new CountdownAlert(config);
        this.ball = new CountdownBall(session, config, this);
        this.focus = new CountdownFocus(session, config, this);

        session.setFinishListener(skipped -> onFinished());
        session.setLastSecondListener(remaining -> onLastSecond(remaining));
        readMultiClickInterval();
    }

    public void setSettingsOpener(SettingsOpener opener) {
        this.settingsOpener = opener;
    }

    private void readMultiClickInterval() {
        try {
            Object v = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
            if (v instanceof Integer && (Integer) v > 0) {
                multiClickIntervalMs = Math.max(120, Math.min(1000, (Integer) v));
            }
        } catch (Throwable ignore) {
            // 保持默认 500ms
        }
    }

    // ===================== 生命周期（EDT） =====================

    /** 安装全局 AWT 观察监听、启动轮询与计时 tick、订阅宿主事件。 */
    public void start() {
        if (stopped) {
            return;
        }
        awtListener = this::onAwtEvent;
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(awtListener,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        } catch (Throwable t) {
            awtListener = null;
            LogManager.log("倒计时插件-AWT监听注册失败: " + t, "PLUGIN_LOAD_ERROR");
        }
        hostListener = this::onHostEvent;
        try {
            ctx.addHostEventListener(hostListener);
        } catch (Throwable t) {
            hostListener = null;
            LogManager.log("倒计时插件-宿主事件订阅失败: " + t, "PLUGIN_LOAD_ERROR");
        }
        pollTimer.start();
        tickTimer.start();
    }

    /** 停止一切并销毁窗口（onUnload；内部切 EDT 处置窗口）。 */
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        pollTimer.stop();
        tickTimer.stop();
        if (singleToggleTimer != null) {
            singleToggleTimer.stop();
            singleToggleTimer = null;
        }
        if (awtListener != null) {
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener);
            } catch (Throwable ignore) {
                // 忽略
            }
            awtListener = null;
        }
        if (hostListener != null) {
            try {
                ctx.removeHostEventListener(hostListener);
            } catch (Throwable ignore) {
                // 双保险：宿主也会按 jar+实现类归属自动清理
            }
            hostListener = null;
        }
        alert.shutdown();
        runOnEdtNow(() -> {
            try {
                focus.hideQuiet();
                focus.dispose();
            } catch (Throwable ignore) {
                // 忽略
            }
            try {
                ball.setVisible(false);
                ball.dispose();
            } catch (Throwable ignore) {
                // 忽略
            }
        });
    }

    private void runOnEdtNow(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ignore) {
            // 忽略
        }
    }

    // ===================== 宿主事件 =====================

    private void onHostEvent(HostEvent ev) {
        if (stopped || ev == null) {
            return;
        }
        switch (ev.getType()) {
            case BALL_MOVED:
            case BALL_SHOWN:
                if (ev.getNewBounds() != null) {
                    ballBounds = ev.getNewBounds();
                } else if (ev.getBounds() != null) {
                    ballBounds = ev.getBounds();
                }
                lastBallSeen = true;
                if (ballVisible) {
                    syncBallGeometry();
                }
                break;
            case BALL_HIDDEN:
                lastBallSeen = false;
                ballBounds = null;
                if (ballVisible) {
                    hideBallAndPause();
                }
                break;
            case PICK_FINISHED:
                if (config.isFocusShowResult()) {
                    focus.setResultText(ev.getResult());
                }
                logQuiet("倒计时插件-抽取定格 来源=" + ev.getSource() + " 方案=" + ev.getSchemeName()
                        + " 模式=" + ev.getModeDisplayName() + " 结果=" + ev.getResult(), "CD_PICK");
                break;
            case SCHEME_CHANGED:
                logQuiet("倒计时插件-方案切换 " + ev.getSchemeName(), "CD_HOST_EVENT");
                break;
            default:
                break;
        }
    }

    // ===================== 计时 tick =====================

    private void onTick() {
        if (stopped) {
            return;
        }
        session.tick(); // 到期时内部回调 onFinished（播提醒音频）
        if (ballVisible) {
            ball.refresh();
        }
        if (focusVisible) {
            focus.refresh();
        }
    }

    private void onFinished() {
        // 到时：刷新显示 + 播放自定义提醒音频（未设置则系统提示音兜底）
        if (ballVisible) {
            ball.refresh();
        }
        if (focusVisible) {
            focus.refresh();
        }
        if (config.isAlertOnFinish()) {
            alert.play("到时");
        }
    }

    private void onLastSecond(int remaining) {
        if (config.isAlertEachLastSecond() && config.isAlertOnFinish()) {
            alert.play("读秒 " + remaining);
        }
    }

    // ===================== 几何轮询（官方只读 API） =====================

    private void onPollTick() {
        if (stopped) {
            return;
        }
        Rectangle b = queryBallBounds();
        boolean seen = b != null;
        if (seen) {
            ballBounds = b;
            lastBallSeen = true;
            if (ballVisible) {
                try {
                    syncBallGeometry();
                    ball.toFront();
                } catch (Throwable ignore) {
                    // 几何异常忽略，下个 tick 重试
                }
            }
        } else {
            if (lastBallSeen && ballVisible) {
                // 悬浮球被隐藏/关闭 → 子球收起并暂停（保留读数），重现后不自动冒出
                hideBallAndPause();
            }
            lastBallSeen = false;
            ballBounds = null;
        }
    }

    /** 宿主悬浮球当前 bounds（官方 G1 只读查询；不可见返回 null）。 */
    private Rectangle queryBallBounds() {
        try {
            ModeHost host = ctx.getModeHost();
            if (host == null) {
                return null;
            }
            if (!host.isFloatingBallVisible()) {
                return null;
            }
            Rectangle r = host.getFloatingBallBounds();
            return r == null ? null : new Rectangle(r);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 子球尺寸（AUTO=随宿主悬浮球联动，与正计时子球口径一致）。 */
    private void syncBallGeometry() {
        int d;
        if (config.isFixedSize()) {
            d = config.getFixedDiameter();
        } else {
            int ballSize = ballBounds == null ? INITIAL_DIAMETER
                    : Math.min(ballBounds.width, ballBounds.height);
            d = (int) Math.round(Math.max(1, ballSize) * SIZE_RATIO);
            d = Math.max(AUTO_MIN_DIAMETER, Math.min(AUTO_MAX_DIAMETER, d));
        }
        ball.setDiameter(d);
        if (config.isFollowBall()) {
            placeRelativeToBall();
        }
    }

    /** 冒出方向（与正计时子球一致）：悬浮球在屏幕下半 → 子球在其正上方；上半 → 正下方；边界 clamp。 */
    private void placeRelativeToBall() {
        Rectangle b = ballBounds;
        if (b == null) {
            return;
        }
        int d = ball.getDiameter();
        int gap = config.getGapPx();
        Rectangle screen = screenBoundsFor(b);
        int cx = b.x + b.width / 2;
        boolean putAbove = (b.y + b.height / 2) > (screen.y + screen.height / 2);
        int x = clamp(cx - d / 2, screen.x, screen.x + Math.max(0, screen.width - d));
        int y;
        if (putAbove) {
            y = b.y - gap - d;
            if (y < screen.y) {
                y = Math.max(screen.y, b.y + b.height + gap);
            }
        } else {
            y = b.y + b.height + gap;
            if (y + d > screen.y + screen.height) {
                y = Math.max(screen.y, b.y - gap - d);
            }
        }
        ball.setLocation(x, y);
        config.setBallPos(x, y);
    }

    private Rectangle screenBoundsFor(Rectangle r) {
        try {
            GraphicsConfiguration gc = ball.getGraphicsConfiguration();
            if (gc != null) {
                return gc.getBounds();
            }
        } catch (Throwable ignore) {
            // 回退主屏
        }
        java.awt.Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        return new Rectangle(0, 0, d.width, d.height);
    }

    private static int clamp(int v, int lo, int hi) {
        if (lo > hi) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    // ===================== 显示/收起 =====================

    /**
     * 菜单"倒计时：显示/收起"≡ 单击宿主悬浮球（与正计时子球同口径）。
     */
    public void toggleShowFromMenu(Window parent) {
        if (stopped) {
            return;
        }
        if (ballVisible || focusVisible) {
            hideAll(true);
            return;
        }
        showAll(parent, true);
    }

    /** 显示（按配置形态）。{@code needBall} 为 true 时若未打开悬浮球则提示。 */
    public void showAll(Window parent, boolean needBall) {
        if (stopped) {
            return;
        }
        boolean wantBall = config.isBallEnabled();
        boolean wantFocus = config.isFocusEnabled();
        if (wantBall) {
            Rectangle b = queryBallBounds();
            if (b == null && needBall && !wantFocus) {
                JOptionPane.showMessageDialog(parent,
                        "请先打开宿主悬浮球（主窗“悬浮球”按钮），或把显示形态改为“全屏专注”。",
                        "倒计时", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (b != null) {
                ballBounds = b;
                lastBallSeen = true;
            }
        }
        applySessionStartOnShow();
        if (wantBall) {
            showBall();
        }
        if (wantFocus) {
            showFocus();
        }
        logQuiet("倒计时插件-显示 形态=" + config.getDisplayMode()
                + " 时长=" + CountdownConfig.formatDuration(session.getDurationSeconds()),
                "CD_SHOW");
    }

    /** 收起（保留读数）；{@code pauseWhenRunning} 为 true 时运行中先暂停（口径同正计时子球）。 */
    public void hideAll(boolean pauseWhenRunning) {
        if (stopped) {
            return;
        }
        if (pauseWhenRunning && session.isRunning()) {
            session.pause();
            logQuiet("倒计时插件-暂停(收起)", "CD_PAUSE");
        }
        if (focusVisible) {
            focus.hideQuiet();
            focusVisible = false;
        }
        if (ballVisible) {
            ball.setVisible(false);
            ballVisible = false;
        }
        logQuiet("倒计时插件-收起", "CD_HIDE");
    }

    /** 只有在配置要求且未在计时时，显示动作才自动开始/继续。 */
    private void applySessionStartOnShow() {
        if (!config.isAutoStartOnShow()) {
            return;
        }
        if (session.isRunning()) {
            return;
        }
        boolean wasPaused = session.isPaused() && !session.isFinished();
        session.startOrResume();
        logQuiet(wasPaused ? "倒计时插件-继续(显示)" : "倒计时插件-开始(显示)",
                wasPaused ? "CD_RESUME" : "CD_START");
    }

    private void showBall() {
        Rectangle b = queryBallBounds();
        if (b != null) {
            ballBounds = b;
            lastBallSeen = true;
            syncBallGeometry();
        } else {
            // 未跟随/无悬浮球：用记忆位置或屏幕右下角
            if (!config.isFollowBall() && config.hasBallPos()) {
                ball.setLocation(config.getBallPosX(), config.getBallPosY());
            } else {
                java.awt.Dimension s = Toolkit.getDefaultToolkit().getScreenSize();
                ball.setLocation(s.width - 140, (int) (s.height * 0.30));
            }
            if (config.isFixedSize()) {
                ball.setDiameter(config.getFixedDiameter());
            } else {
                ball.setDiameter(INITIAL_DIAMETER);
            }
        }
        ball.refresh();
        ball.setVisible(true);
        ballVisible = true;
        try {
            ball.toFront();
        } catch (Throwable ignore) {
            // 忽略
        }
    }

    private void showFocus() {
        if (config.isFocusShowResult()) {
            focus.setResultText("");
        }
        focus.showFocus();
        focusVisible = true;
    }

    /** 子球收起（保留读数）。 */
    private void hideBallOnly() {
        if (ballVisible) {
            ball.setVisible(false);
            ballVisible = false;
            logQuiet("倒计时插件-子球收起", "CD_HIDE");
        }
    }

    /** 悬浮球消失时的自动收起（先暂停，保留读数）。 */
    private void hideBallAndPause() {
        if (session.isRunning()) {
            session.pause();
            logQuiet("倒计时插件-暂停(悬浮球关闭)", "CD_PAUSE");
        }
        hideBallOnly();
    }

    // ===================== 菜单动作 =====================

    /** "开始/暂停"：未显示则先显示；运行中→暂停；否则开始/继续。 */
    public void toggleRunFromMenu(Window parent) {
        if (stopped) {
            return;
        }
        if (!ballVisible && !focusVisible) {
            showAll(parent, true);
            return;
        }
        if (session.isRunning()) {
            session.pause();
            logQuiet("倒计时插件-暂停(菜单)", "CD_PAUSE");
        } else {
            boolean wasPaused = session.isPaused() && !session.isFinished();
            session.startOrResume();
            logQuiet(wasPaused ? "倒计时插件-继续(菜单)" : "倒计时插件-开始(菜单)",
                    wasPaused ? "CD_RESUME" : "CD_START");
        }
        refreshAll();
    }

    /** "归零"：最终落点 = 读数满值 + 收起（口径同正计时子球"归零"）。 */
    public void resetFromMenu(Window parent) {
        if (stopped) {
            return;
        }
        alert.stop();
        session.reset();
        logQuiet("倒计时插件-归零(菜单)", "CD_RESET");
        hideAll(false);
        refreshAll();
    }

    /** "立即结束"：跳到到时并触发提醒。 */
    public void finishNowFromMenu(Window parent) {
        if (stopped) {
            return;
        }
        session.finishNow();
        refreshAll();
    }

    /** "全屏专注"：显示/收起全屏专注形态（不动子球形态）。 */
    public void toggleFocusFromMenu(Window parent) {
        if (stopped) {
            return;
        }
        if (focusVisible) {
            focus.hideQuiet();
            focusVisible = false;
            logQuiet("倒计时插件-全屏专注收起", "CD_HIDE");
            return;
        }
        if (config.isAutoStartOnShow() && !session.isRunning()) {
            boolean wasPaused = session.isPaused() && !session.isFinished();
            session.startOrResume();
            logQuiet(wasPaused ? "倒计时插件-继续(全屏)" : "倒计时插件-开始(全屏)",
                    wasPaused ? "CD_RESUME" : "CD_START");
        }
        showFocus();
        logQuiet("倒计时插件-全屏专注显示", "CD_SHOW");
    }

    /** 快捷时长：设定时长并从头开始（若已显示则立即可见效果）。 */
    public void setQuickDuration(int minutes) {
        if (stopped) {
            return;
        }
        int seconds = Math.max(1, minutes) * 60;
        config.setDurationSeconds(seconds);
        config.save();
        alert.stop();
        session.setDurationSeconds(seconds);
        session.reset();
        session.startOrResume();
        logQuiet("倒计时插件-快捷时长 " + minutes + " 分钟", "CD_START");
        refreshAll();
    }

    /** 打开常规设置（由插件入口提供对话框）。 */
    public void openSettings(Window owner) {
        SettingsOpener opener = settingsOpener;
        if (opener != null) {
            opener.open(owner);
        }
    }

    /**
     * 自检报告（"倒计时：自检…"菜单）：只读展示关键运行事实，便于排查
     * "音频不响/悬浮球没反应/设置没生效"等问题。
     */
    public String selfCheckReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("倒计时插件 ").append(CountdownPlugin.VERSION_TEXT).append('\n');
        sb.append("宿主 API 级别：").append(HostApi.PLUGIN_API_LEVEL)
                .append("（插件声明 Api-Level-Min: 2）\n");
        sb.append("配置状态：");
        java.io.File cfgFile = new java.io.File(config.getFilePath());
        sb.append(cfgFile.getAbsolutePath()).append(cfgFile.exists() ? "（已存在）" : "（尚未生成，改动设置后创建）");
        sb.append('\n');
        sb.append("显示形态：").append(config.getDisplayMode())
                .append("　子球可见=").append(ballVisible)
                .append("　全屏专注可见=").append(focusVisible).append('\n');
        sb.append("本轮读数：").append(session.displayText(config.isShowHours(), false))
                .append("　状态=").append(session.getState())
                .append("　设定=").append(CountdownConfig.formatDuration(config.getDurationSeconds())).append('\n');
        Rectangle b = queryBallBounds();
        sb.append("宿主悬浮球：").append(b == null ? "不可见（请先在主窗打开悬浮球）" : ("可见 " + b));
        sb.append('\n');
        sb.append("提醒音频：到点响铃=").append(config.isAlertOnFinish())
                .append("　读秒=").append(config.isAlertEachLastSecond())
                .append("　音量=").append(config.getAlertVolume()).append('%')
                .append("　重复=").append(config.getAlertRepeat())
                .append("　系统音兜底=").append(config.isSystemBeepFallback()).append('\n');
        String path = config.getAlertFile();
        if (path.isEmpty()) {
            sb.append("自定义音频：未设置（到时使用系统提示音）\n");
        } else {
            sb.append("自定义音频：").append(path).append('\n');
            sb.append("  校验：").append(CountdownAlert.checkFile(path)).append('\n');
        }
        sb.append("操作提示：\n");
        sb.append("  单击宿主悬浮球 = 显示/收起（显示即开始、收起即暂停）\n");
        sb.append("  子球：单击=开始/暂停/继续，双击=归零并收起，右键=快捷菜单，拖动=移动\n");
        sb.append("  全屏专注：空格=暂停/继续，R=归零，ESC/双击=收起\n");
        sb.append("  宿主双击悬浮球抽取、拖动移动保持原样\n");
        return sb.toString();
    }

    /** 打开自检报告对话框（只读）。 */
    public void showSelfCheck(Window owner) {
        String report = selfCheckReport();
        logQuiet("倒计时插件-自检 " + report.replace('\n', ' '), "CD_SELFCHECK");
        javax.swing.JTextArea area = new javax.swing.JTextArea(report, 18, 56);
        area.setEditable(false);
        area.setFont(new java.awt.Font("微软雅黑", java.awt.Font.PLAIN, 12));
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(owner, new javax.swing.JScrollPane(area),
                "倒计时插件 · 自检", JOptionPane.INFORMATION_MESSAGE);
    }

    // ===================== 形态回调实现 =====================

    @Override
    public void onPrimaryClick() {
        if (stopped) {
            return;
        }
        if (session.isRunning()) {
            session.pause();
            logQuiet("倒计时插件-暂停(子球单击)", "CD_PAUSE");
        } else {
            boolean wasPaused = session.isPaused() && !session.isFinished();
            session.startOrResume();
            logQuiet(wasPaused ? "倒计时插件-继续(子球单击)" : "倒计时插件-开始(子球单击)",
                    wasPaused ? "CD_RESUME" : "CD_START");
        }
        refreshAll();
    }

    @Override
    public void onResetAndHide() {
        alert.stop();
        session.reset();
        logQuiet("倒计时插件-归零(子球双击)", "CD_RESET");
        hideBallOnly();
        refreshAll();
    }

    @Override
    public void onSwitchToFocus() {
        hideBallOnly();
        if (config.isAutoStartOnShow() && !session.isRunning()) {
            session.startOrResume();
        }
        showFocus();
        logQuiet("倒计时插件-切全屏专注", "CD_SHOW");
    }

    @Override
    public void onQuickDuration(int minutes) {
        setQuickDuration(minutes);
    }

    @Override
    public void onOpenSettings() {
        openSettings(hostWindow());
    }

    @Override
    public void onHide() {
        hideBallOnly();
    }

    @Override
    public void onMoved(Point location) {
        if (location != null) {
            config.setBallPos(location.x, location.y);
            config.save();
        }
    }

    @Override
    public void onExit() {
        focusVisible = false;
    }

    @Override
    public void onToggle() {
        alert.stop();
        session.toggle();
        logQuiet(session.isRunning() ? "倒计时插件-继续(全屏空格)" : "倒计时插件-暂停(全屏空格)",
                session.isRunning() ? "CD_RESUME" : "CD_PAUSE");
        refreshAll();
    }

    // ===================== 配置变更即时应用 =====================

    /** 设置窗任一改动后调用：即时换肤/重排/置顶。 */
    public void applyConfig() {
        if (stopped) {
            return;
        }
        // 时长：仅在未开始计时（就绪/到时）时同步到会话，避免打断正在计时的这一轮
        if (!session.isRunning() && !session.isPaused()) {
            session.setDurationSeconds(config.getDurationSeconds());
            session.reset();
        }
        if (ballVisible) {
            Rectangle b = queryBallBounds();
            if (b != null) {
                ballBounds = b;
            }
            if (config.isFixedSize()) {
                ball.setDiameter(config.getFixedDiameter());
            } else if (ballBounds != null) {
                syncBallGeometry();
            }
            if (config.isFollowBall() && ballBounds != null) {
                placeRelativeToBall();
            }
            ball.refresh();
        }
        if (focusVisible) {
            focus.applyAppearance();
            focus.refresh();
        } else if (config.isFocusAlwaysOnTop()) {
            focus.applyAppearance();
        }
    }

    /** 设置对话框"测试提醒音频"用。 */
    public CountdownAlert alert() {
        return alert;
    }

    /** 当前会话（设置对话框显示状态用）。 */
    public CountdownSession session() {
        return session;
    }

    private void refreshAll() {
        if (ballVisible) {
            ball.refresh();
        }
        if (focusVisible) {
            focus.refresh();
        }
    }

    // ===================== 通路 A：全局 AWT 观察监听 =====================

    private void onAwtEvent(AWTEvent ev) {
        if (stopped || !(ev instanceof MouseEvent)) {
            return;
        }
        MouseEvent me = (MouseEvent) ev;
        switch (me.getID()) {
            case MouseEvent.MOUSE_PRESSED:
                if (!SwingUtilities.isLeftMouseButton(me)) {
                    pressedInBall = false;
                    break;
                }
                pressedInBall = hitsFloatingBall(me);
                dragSincePress = false;
                break;
            case MouseEvent.MOUSE_DRAGGED:
                // 口径同正计时子球：按下期间任一 DRAGGED 即视为拖动（无位移阈值）
                if (pressedInBall) {
                    dragSincePress = true;
                }
                break;
            case MouseEvent.MOUSE_RELEASED:
                pressedInBall = false;
                break;
            case MouseEvent.MOUSE_CLICKED:
                if (!SwingUtilities.isLeftMouseButton(me) || !hitsFloatingBall(me)) {
                    break;
                }
                if (me.getClickCount() == 1) {
                    if (dragSincePress) {
                        dragSincePress = false; // 拖动后释放：不触发
                        break;
                    }
                    scheduleSingleToggle();
                } else {
                    // 双击（宿主已在其第二次按下时执行随机抽取）：取消挂起单击，不冒出/收回
                    cancelSingleToggle();
                    dragSincePress = false;
                }
                break;
            default:
                break;
        }
    }

    /** 鼠标事件是否落在宿主悬浮球矩形内（纯几何判定，不依赖宿主内部类）。 */
    private boolean hitsFloatingBall(MouseEvent me) {
        Rectangle b = ballBounds != null ? ballBounds : queryBallBounds();
        if (b == null) {
            return false;
        }
        Component c = me.getComponent();
        if (belongsTo(c, ball) || belongsTo(c, focus)) {
            return false; // 插件自己的窗口不算
        }
        Point p;
        try {
            p = me.getLocationOnScreen();
        } catch (Throwable t) {
            return false;
        }
        if (p == null) {
            return false;
        }
        // 容差 2px：命中宿主给悬浮球加边框/阴影时的边缘点击
        return p.x >= b.x - 2 && p.y >= b.y - 2
                && p.x < b.x + b.width + 2 && p.y < b.y + b.height + 2;
    }

    /** 事件源是否属于指定顶层窗口（沿祖先链上溯）。 */
    private static boolean belongsTo(Component c, Window w) {
        while (c != null) {
            if (c == w) {
                return true;
            }
            if (c instanceof Window) {
                return false;
            }
            c = c.getParent();
        }
        return false;
    }

    private void scheduleSingleToggle() {
        if (singleToggleTimer == null) {
            singleToggleTimer = new Timer(multiClickIntervalMs, e -> {
                singleToggleTimer.stop();
                fireSingleToggle();
            });
            singleToggleTimer.setRepeats(false);
        }
        singleToggleTimer.stop();
        singleToggleTimer.setInitialDelay(multiClickIntervalMs);
        singleToggleTimer.start();
    }

    private void cancelSingleToggle() {
        if (singleToggleTimer != null) {
            singleToggleTimer.stop();
        }
    }

    private void fireSingleToggle() {
        if (stopped) {
            return;
        }
        if (ballVisible || focusVisible) {
            hideAll(true); // 收起并暂停（保留读数）
            return;
        }
        Rectangle b = queryBallBounds();
        if (b == null && !config.isFocusEnabled()) {
            return; // 球已不在（如点击后 500ms 内被关闭）：静默忽略
        }
        showAll(hostWindow(), false);
    }

    // ===================== 工具 =====================

    /** 插件宿主窗口（对话框父窗；顶级主窗的 getOwner() 为 null，故这里用 getHostWindow()）。 */
    public Window hostWindow() {
        try {
            ModeHost host = ctx.getModeHost();
            if (host != null) {
                Window w = host.getHostWindow();
                if (w != null) {
                    return w;
                }
                return host.getOwner();
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        return null;
    }

    private static void logQuiet(String detail, String opCode) {
        try {
            LogManager.log(detail, opCode);
        } catch (Throwable ignore) {
            // 日志不可用不影响功能
        }
    }
}
