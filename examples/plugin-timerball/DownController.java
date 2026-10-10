import com.randomnamepicker.core.LogManager;
import com.randomnamepicker.mode.ModeHost;
import com.randomnamepicker.plugin.HostApi;
import com.randomnamepicker.plugin.HostEvent;
import com.randomnamepicker.plugin.HostEventListener;
import com.randomnamepicker.plugin.PluginContext;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 倒计时子球控制器（计时子球插件·倒计时侧）。
 * <p>
 * <b>合并与交互口径</b>：
 * </p>
 * <ul>
 *   <li><b>点任意倒计时功能 = 冒出子球并开始倒计时</b>：{@link #commandStart} / {@link
 *       #commandToggleRun} / {@link #commandSetQuickDuration} / {@link #commandToggleFocus}
 *       都会先把子球显示出来（若尚未显示）再开始计时，不再依赖"显示即开始"配置项，
 *       避免"点了没反应/看不到球"；</li>
 *   <li><b>暂停逻辑与正计时一致</b>：同一条"开始/暂停"命令；暂停只暂停读数、子球保持显示
 *       （菜单里不再有单独的"开始/暂停"项，统一走一级菜单的同一条命令）；</li>
 *   <li><b>倒计时只走右键菜单</b>：不接管宿主悬浮球左键（不安装全局 AWT 观察监听），
 *       子球左键仅用于拖动；</li>
 *   <li><b>与正计时子球不重叠</b>：落位走 {@link BallLayout#placeAnchored} 并把正计时子球矩形
 *       作为占用矩形；正计时冒出/收回时由插件调用 {@link #relayout()} 重排；</li>
 *   <li><b>位置一定可见</b>：记忆位置若落在屏幕外（换分辨率/曾拖到边缘），落位时会被夹回屏内，
 *       彻底避免"倒计时在跑但看不到球"。</li>
 * </ul>
 * 公开方法仅限 EDT 调用（start/stop 内部自行切换）。
 */
public class DownController implements DownBall.Actions, DownFocus.Actions {

    private static final int POLL_MS = 100;
    private static final int TICK_MS = 100;
    /** AUTO 联动口径（与正计时子球一致）：悬浮球直径 × 0.55，夹 36–60px。 */
    private static final double SIZE_RATIO = 0.55;
    private static final int AUTO_MIN_DIAMETER = 36;
    private static final int AUTO_MAX_DIAMETER = 60;
    private static final int INITIAL_DIAMETER = 44;
    /** 记忆位置至少要有这么多像素落在某块屏幕内，才认为它"还看得见"（60×60 的子球）。 */
    private static final long MIN_VISIBLE_AREA = 600L;

    private final PluginContext ctx;
    private final TimerBallConfig config;
    private final DownSession session;
    private final TimerAlert alert;
    private final DownBall ball;
    private final DownFocus focus;

    private final Timer pollTimer = new Timer(POLL_MS, e -> onPollTick());
    private final Timer tickTimer = new Timer(TICK_MS, e -> onTick());
    private HostEventListener hostListener;

    private boolean stopped;
    private boolean ballVisible;
    private boolean focusVisible;
    private Rectangle ballBounds;
    private boolean lastBallSeen;

    /** 需要避让的矩形提供者（合并形态下由插件注入：正计时子球矩形）。 */
    public interface AvoidanceProvider {
        Rectangle[] occupiedRects();
    }

    /** 设置窗回调（控制器不拥有对话框，由插件入口提供）。 */
    public interface SettingsOpener {
        void open(Window owner);
    }

    private AvoidanceProvider avoidanceProvider;
    private SettingsOpener settingsOpener;

    public DownController(PluginContext ctx, TimerBallConfig config) {
        this.ctx = ctx;
        this.config = config;
        this.session = new DownSession(config.getDurationSeconds());
        this.session.setTitle(config.getFocusTitle());
        this.alert = new TimerAlert(config);
        this.ball = new DownBall(session, config, this);
        this.focus = new DownFocus(session, config, this);

        session.setFinishListener(skipped -> onFinished());
        session.setLastSecondListener(remaining -> onLastSecond(remaining));
        restoreSavedSession();
    }

    /**
     * 恢复上次退出时记住的这一轮倒计时（跨会话记忆）。
     * <p>
     * 记忆内容：剩余毫秒 + 当时是否在走。恢复后：正在走的继续走、已暂停的保持暂停、
     * 已走完的显示 00:00（时间到）。这样重启程序不会把正在进行的倒计时丢掉。
     * </p>
     */
    private void restoreSavedSession() {
        long remain = config.getSavedRemainingMs();
        if (remain < 0L) {
            return; // 上次没有未完成的一轮
        }
        try {
            if (remain > session.getDurationMs()) {
                // 设定时长后来被改小了：夹到设定值以内
                remain = session.getDurationMs();
            }
            session.restore(remain, config.isSavedWasRunning());
            logQuiet("计时子球-倒计时：已恢复上次退出时的进度 剩余="
                    + TimerBallConfig.formatDuration((int) Math.round(remain / 1000.0))
                    + " 状态=" + session.getState(), "TB_DOWN_RESTORE_MEMORY");
        } catch (Throwable t) {
            logQuiet("计时子球-倒计时：恢复上次进度失败: " + t, "PLUGIN_LOAD_ERROR");
        }
    }

    /**
     * 把当前这一轮写入配置（退出/卸载时调用；空闲态则清除记忆）。
     */
    public void persistSession() {
        try {
            DownSession.Snapshot snap = session.snapshot();
            if (snap == null) {
                config.clearSession();
            } else {
                config.saveSession(snap.remainingMs,
                        snap.state == DownSession.State.RUNNING);
            }
            config.save();
        } catch (Throwable t) {
            logQuiet("计时子球-倒计时：保存本轮进度失败: " + t, "PLUGIN_LOAD_ERROR");
        }
    }

    public void setAvoidanceProvider(AvoidanceProvider provider) {
        this.avoidanceProvider = provider;
    }

    public void setSettingsOpener(SettingsOpener opener) {
        this.settingsOpener = opener;
    }

    // ===================== 生命周期（EDT） =====================

    /** 启动几何轮询与计时 tick、订阅宿主事件（不安装全局 AWT 监听——倒计时不做左键交互）。 */
    public void start() {
        if (stopped) {
            return;
        }
        hostListener = this::onHostEvent;
        try {
            ctx.addHostEventListener(hostListener);
        } catch (Throwable t) {
            hostListener = null;
            LogManager.log("计时子球-倒计时：宿主事件订阅失败: " + t, "PLUGIN_LOAD_ERROR");
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
        persistSession(); // 退出前记住这一轮进度（跨会话记忆）
        pollTimer.stop();
        tickTimer.stop();
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
                logQuiet("计时子球-倒计时：抽取定格 来源=" + ev.getSource() + " 方案=" + ev.getSchemeName()
                        + " 模式=" + ev.getModeDisplayName() + " 结果=" + ev.getResult(), "TB_PICK");
                break;
            case SCHEME_CHANGED:
                logQuiet("计时子球-倒计时：方案切换 " + ev.getSchemeName(), "TB_HOST_EVENT");
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
                // 宿主悬浮球被隐藏/关闭 → 子球收起并暂停（保留读数），重现后不自动冒出
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
        } else {
            ensureOnScreen();
        }
    }

    /** 冒出于悬浮球旁，并避开正计时子球（合并形态：两颗子球不重叠）。 */
    private void placeRelativeToBall() {
        Rectangle b = ballBounds;
        if (b == null) {
            ensureOnScreen();
            return;
        }
        int d = ball.getDiameter();
        int gap = config.getGapPx();
        Rectangle screen = BallLayout.screenBoundsFor(ball);
        Point p = BallLayout.placeAnchored(b, d, gap, screen, occupiedRects());
        if (p != null) {
            ball.setLocation(p.x, p.y);
            config.setBallPos(p.x, p.y);
        }
    }

    /**
     * 确保子球完全落在某块屏幕内（"跟随"关闭时用记忆位置，也要保证看得见）。
     * <p>
     * 修复"倒计时在跑却看不到球"的缺陷：记忆位置可能因为换分辨率、曾拖到屏幕边缘/副屏、
     * 或配置文件里的旧坐标而落在可视区外。
     * </p>
     */
    private void ensureOnScreen() {
        ensureOnScreen(false);
    }

    private void ensureOnScreen(boolean forceRecenter) {
        int d = ball.getDiameter();
        Rectangle current = new Rectangle(ball.getLocation().x, ball.getLocation().y, d, d);
        boolean usable = !forceRecenter
                && TimerIcons.visibleArea(current) >= Math.min(MIN_VISIBLE_AREA, (long) d * d);
        if (usable) {
            return;
        }
        Rectangle target = null;
        if (config.hasBallPos() && !forceRecenter) {
            Rectangle saved = new Rectangle(config.getBallPosX(), config.getBallPosY(), d, d);
            if (TimerIcons.visibleArea(saved) >= Math.min(MIN_VISIBLE_AREA, (long) d * d)) {
                target = TimerIcons.clampToScreen(saved);
            }
        }
        if (target == null) {
            Rectangle screen = TimerIcons.primaryScreenBounds();
            target = new Rectangle(screen.x + screen.width - d - 90,
                    screen.y + (int) (screen.height * 0.30), d, d);
            target = TimerIcons.clampToScreen(target);
        }
        ball.setLocation(target.x, target.y);
        config.setBallPos(target.x, target.y);
    }

    /** 需要避让的矩形（合并形态：正计时子球可见时返回其矩形）。 */
    private Rectangle[] occupiedRects() {
        AvoidanceProvider provider = avoidanceProvider;
        if (provider == null) {
            return new Rectangle[0];
        }
        try {
            Rectangle[] r = provider.occupiedRects();
            return r == null ? new Rectangle[0] : r;
        } catch (Throwable t) {
            return new Rectangle[0];
        }
    }

    // ===================== 显示/收起 =====================

    /**
     * 右键菜单"倒计时：冒出/收回"。
     * <p>
     * 口径（按需求）：<b>冒出时不立刻倒计时</b>——只把子球显示出来（读数=设定时长、状态=就绪），
     * 等用户单击子球或用"倒计时：开始"命令才开始；已显示时再选本项 = 收起并暂停（保留读数）。
     * </p>
     */
    public void toggleShowFromMenu(Window parent) {
        if (stopped) {
            return;
        }
        if (ballVisible || focusVisible) {
            hideAll(true);
            return;
        }
        showAll(parent, true, false); // autoStart=false：仅冒出，不开始计时
    }

    /** 显示（按配置形态）。 */
    public void showAll(Window parent, boolean needBall) {
        showAll(parent, needBall, true);
    }

    /**
     * 显示。
     *
     * @param parent      对话框父窗
     * @param needBall    子球形态下若悬浮球不可见是否提示（菜单入口用 true；内部"先显示再计时"用 false）
     * @param autoStart   是否套用"显示即开始"配置项（菜单"显示/收起"用 true；命令式启动用 false）
     */
    public void showAll(Window parent, boolean needBall, boolean autoStart) {
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
                        "计时子球", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (b != null) {
                ballBounds = b;
                lastBallSeen = true;
            }
        }
        if (autoStart) {
            applySessionStartOnShow();
        }
        if (wantBall) {
            showBall();
        }
        if (wantFocus) {
            showFocus();
        }
        logQuiet("计时子球-倒计时显示 形态=" + config.getDisplayMode()
                + " 时长=" + TimerBallConfig.formatDuration(session.getDurationSeconds()),
                "TB_DOWN_SHOW");
    }

    /** 收起（保留读数）；{@code pauseWhenRunning} 为 true 时运行中先暂停（口径同正计时子球）。 */
    public void hideAll(boolean pauseWhenRunning) {
        if (stopped) {
            return;
        }
        if (pauseWhenRunning && session.isRunning()) {
            session.pause();
            logQuiet("计时子球-倒计时暂停(收起)", "TB_DOWN_PAUSE");
        }
        if (focusVisible) {
            focus.hideQuiet();
            focusVisible = false;
        }
        if (ballVisible) {
            ball.setVisible(false);
            ballVisible = false;
        }
        logQuiet("计时子球-倒计时收起", "TB_DOWN_HIDE");
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
        if (!wasPaused) {
            session.restoreToLastStart();
        } else {
            session.startOrResume();
        }
        logQuiet(wasPaused ? "计时子球-倒计时继续(显示)" : "计时子球-倒计时开始(显示)",
                wasPaused ? "TB_DOWN_RESUME" : "TB_DOWN_START");
    }

    private void showBall() {
        Rectangle b = queryBallBounds();
        if (b != null) {
            ballBounds = b;
            lastBallSeen = true;
            syncBallGeometry();
        } else {
            // 无悬浮球：先按记忆位置，位置不可见则夹回屏内（绝不能落在屏幕外）
            if (config.isFixedSize()) {
                ball.setDiameter(config.getFixedDiameter());
            } else {
                ball.setDiameter(INITIAL_DIAMETER);
            }
            if (config.hasBallPos()) {
                ball.setLocation(config.getBallPosX(), config.getBallPosY());
            }
            ensureOnScreen();
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
            logQuiet("计时子球-倒计时子球收起", "TB_DOWN_HIDE");
        }
    }

    /** 悬浮球消失时的自动收起（先暂停，保留读数）。 */
    private void hideBallAndPause() {
        if (session.isRunning()) {
            session.pause();
            logQuiet("计时子球-倒计时暂停(悬浮球关闭)", "TB_DOWN_PAUSE");
        }
        hideBallOnly();
    }

    // ===================== 命令（供右键菜单/控制面板调用） =====================

    /**
     * 菜单/面板的"倒计时：开始/暂停"。
     * <p>
     * 合并口径（与正计时子球一致）：<b>点一下先把子球冒出来并开始倒计时</b>；
     * 已在运行时再点一下 = 暂停（子球保持显示）；已暂停时再点 = 继续。
     * </p>
     */
    public void commandToggleRun(Window parent) {
        if (stopped) {
            return;
        }
        if (!ballVisible && !focusVisible) {
            showBallForStart(parent);
            // 首次启动：从"上次起点"重新开始（配置改过时长也以最新设定为准）
            session.restoreToLastStart();
            logQuiet("计时子球-倒计时开始(菜单，自动冒出)", "TB_DOWN_START");
            refreshAll();
            return;
        }
        if (session.isRunning()) {
            session.pause();
            logQuiet("计时子球-倒计时暂停(菜单)", "TB_DOWN_PAUSE");
        } else {
            boolean wasPaused = session.isPaused() && !session.isFinished();
            if (wasPaused) {
                session.startOrResume();
            } else {
                session.restoreToLastStart();
            }
            logQuiet(wasPaused ? "计时子球-倒计时继续(菜单)" : "计时子球-倒计时开始(菜单)",
                    wasPaused ? "TB_DOWN_RESUME" : "TB_DOWN_START");
        }
        refreshAll();
    }

    /**
     * 菜单/面板的"倒计时：归零"：读数回到设定时长并收起（口径同正计时子球"归零"）。
     */
    public void commandReset(Window parent) {
        if (stopped) {
            return;
        }
        alert.stop();
        session.setDurationSeconds(config.getDurationSeconds());
        session.reset();
        config.clearSession(); // 归零即放弃这一轮，退出后不再恢复
        config.save();
        logQuiet("计时子球-倒计时归零(菜单)", "TB_DOWN_RESET");
        hideAll(false);
        refreshAll();
    }

    /**
     * "倒计时：开始"（一级菜单入口）：确保子球可见并开始/继续倒计时。
     * <ul>
     *   <li>未显示 → 冒出子球并从"上次起点"开始；</li>
     *   <li>已暂停 → 继续（不重置读数）；</li>
     *   <li>就绪/到时 → 按最新设定时长从起点重新开始；</li>
     *   <li>已运行 → 仅确保可见（暂停请用"开始/暂停"切换入口或全屏按钮）。</li>
     * </ul>
     */
    public void commandStart(Window parent) {
        if (stopped) {
            return;
        }
        alert.stop();
        ensureDownVisible(parent);
        if (session.isPaused()) {
            session.startOrResume();
            logQuiet("计时子球-倒计时继续(命令)", "TB_DOWN_RESUME");
        } else if (session.isRunning()) {
            // 已在运行：只保证可见
        } else {
            // 就绪（未开始）或已到时：采用最新设定时长作为起点并开始
            session.setDurationSeconds(config.getDurationSeconds());
            session.setLastStartSeconds(config.getDurationSeconds());
            session.restoreToLastStart();
            logQuiet("计时子球-倒计时开始(命令) 时长="
                    + TimerBallConfig.formatDuration(session.getDurationSeconds()), "TB_DOWN_START");
        }
        refreshAll();
    }

    /** "倒计时：收回"（一级菜单入口）：收起子球并暂停（保留读数）。 */
    public void commandHide(Window parent) {
        if (stopped) {
            return;
        }
        hideAll(true);
    }

    /** 立即结束：跳到到时并触发提醒。 */
    public void commandFinishNow(Window parent) {
        if (stopped) {
            return;
        }
        session.finishNow();
        refreshAll();
    }

    /**
     * 全屏专注：显示/收起全屏专注形态（不动子球形态）。
     * <p>
     * 按需求：<b>不倒计时状态下也能进入番茄钟</b>——本方法只切显示，不自动开始计时；
     * 是否计时完全沿用当前会话状态（就绪则保持就绪，等用户在番茄钟里点"暂停/继续"或用"倒计时：开始"）。
     * </p>
     */
    public void commandToggleFocus(Window parent) {
        if (stopped) {
            return;
        }
        if (focusVisible) {
            focus.hideQuiet();
            focusVisible = false;
            logQuiet("计时子球-全屏专注收起", "TB_DOWN_HIDE");
            return;
        }
        showFocus();
        logQuiet("计时子球-全屏专注显示（当前状态=" + session.getState() + "）", "TB_DOWN_SHOW");
    }

    /**
     * 快捷时长：设定时长、**冒出子球但不自动开始**（等用户单击子球或选"倒计时：开始"）。
     */
    public void commandSetQuickDuration(int minutes) {
        if (stopped) {
            return;
        }
        int seconds = Math.max(1, minutes) * 60;
        config.setDurationSeconds(seconds);
        config.save();
        alert.stop();
        session.setDurationSeconds(seconds);
        session.setLastStartSeconds(seconds); // 让"开始/复原"以本次快捷时长为起点
        session.reset();
        ensureDownVisible(null);
        logQuiet("计时子球-倒计时快捷时长 " + minutes + " 分钟（已就绪，等待开始）", "TB_DOWN_READY");
        refreshAll();
    }

    /** 确保倒计时形态可见（子球优先；无悬浮球且形态含全屏时改全屏）。 */
    private void ensureDownVisible(Window parent) {
        if (ballVisible || focusVisible) {
            return;
        }
        if (config.isBallEnabled() && queryBallBounds() != null) {
            showBall();
        } else if (config.isFocusEnabled()) {
            showFocus();
        } else if (config.isBallEnabled()) {
            showBall(); // 无悬浮球：用记忆位置/右下角兜底，并保证在屏内
        }
    }

    /** 强制"开始"路径：先把子球冒出来（无视 autoStart 配置），再调用方负责开始计时。 */
    private void showBallForStart(Window parent) {
        if (config.isFocusEnabled() && !config.isBallEnabled()) {
            showFocus();
            return;
        }
        showBall();
    }

    /** 打开常规设置（由插件入口提供对话框）。 */
    public void openSettings(Window owner) {
        SettingsOpener opener = settingsOpener;
        if (opener != null) {
            opener.open(owner);
        }
    }

    /**
     * 自检报告文本（仅供自动化诊断/开发者使用；界面上的"自检…"菜单项已按需求移除）。
     */
    public String selfCheckReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("计时子球插件 ").append(TimerBallPlugin.VERSION_TEXT)
                .append("（正计时 + 倒计时合并版）\n");
        sb.append("宿主 API 级别：").append(HostApi.PLUGIN_API_LEVEL).append('\n');
        java.io.File cfgFile = new java.io.File(config.getFilePath());
        sb.append("倒计时配置：").append(cfgFile.getAbsolutePath())
                .append(cfgFile.exists() ? "（已存在）" : "（尚未生成）").append('\n');
        sb.append("显示形态：").append(config.getDisplayMode())
                .append("　子球可见=").append(ballVisible)
                .append("　全屏专注可见=").append(focusVisible).append('\n');
        sb.append("本轮读数：").append(session.displayText(config.isShowHours(), false))
                .append("　状态=").append(session.getState())
                .append("　设定=").append(TimerBallConfig.formatDuration(config.getDurationSeconds()))
                .append("　上次起点=").append(TimerBallConfig.formatDuration(session.getLastStartSeconds()))
                .append('\n');
        Rectangle b = queryBallBounds();
        sb.append("宿主悬浮球：").append(b == null ? "不可见" : String.valueOf(b)).append('\n');
        sb.append("子球位置：").append(ball.getLocation())
                .append(" 可见面积=").append(TimerIcons.visibleArea(new Rectangle(
                        ball.getLocation().x, ball.getLocation().y, ball.getWidth(), ball.getHeight())))
                .append('\n');
        Rectangle[] occupied = occupiedRects();
        sb.append("避让矩形（正计时子球）：")
                .append(occupied.length == 0 ? "无" : occupied[0].toString()).append('\n');
        sb.append("提醒音频：到点响铃=").append(config.isAlertOnFinish())
                .append("　音量=").append(config.getAlertVolume()).append('%')
                .append("　文件=").append(config.getAlertFile().isEmpty() ? "(未设置)"
                        : config.getAlertFile()).append('\n');
        return sb.toString();
    }

    // ===================== 形态回调实现 =====================

    @Override
    public void onPrimaryClick() {
        // 子球自身右键菜单的"开始/暂停"（左键单击子球不触发：倒计时只走右键菜单）
        if (stopped) {
            return;
        }
        if (session.isRunning()) {
            session.pause();
            logQuiet("计时子球-倒计时暂停(子球菜单)", "TB_DOWN_PAUSE");
        } else {
            boolean wasPaused = session.isPaused() && !session.isFinished();
            if (wasPaused) {
                session.startOrResume();
            } else {
                session.restoreToLastStart();
            }
            logQuiet(wasPaused ? "计时子球-倒计时继续(子球菜单)" : "计时子球-倒计时开始(子球菜单)",
                    wasPaused ? "TB_DOWN_RESUME" : "TB_DOWN_START");
        }
        refreshAll();
    }

    @Override
    public void onResetAndHide() {
        alert.stop();
        session.setDurationSeconds(config.getDurationSeconds());
        session.reset();
        logQuiet("计时子球-倒计时归零(子球菜单)", "TB_DOWN_RESET");
        hideBallOnly();
        refreshAll();
    }

    @Override
    public void onSwitchToFocus() {
        hideBallOnly();
        if (!session.isRunning() && !session.isPaused()) {
            session.restoreToLastStart();
        }
        showFocus();
        logQuiet("计时子球-切全屏专注", "TB_DOWN_SHOW");
    }

    @Override
    public void onQuickDuration(int minutes) {
        commandSetQuickDuration(minutes);
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
        logQuiet(session.isRunning() ? "计时子球-倒计时继续(全屏按钮/空格)" : "计时子球-倒计时暂停(全屏按钮/空格)",
                session.isRunning() ? "TB_DOWN_RESUME" : "TB_DOWN_PAUSE");
        refreshAll();
    }

    @Override
    public void onRestore() {
        alert.stop();
        int target = session.getLastStartSeconds();
        session.restoreToLastStart();
        logQuiet("计时子球-倒计时复原到起点 " + TimerBallConfig.formatDuration(target), "TB_DOWN_RESTORE");
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
            } else {
                ensureOnScreen();
            }
            ball.refresh();
        }
        if (focusVisible) {
            focus.applyAppearance();
            focus.refresh();
        } else {
            focus.applyAppearance();
        }
    }

    /** 设置对话框"测试提醒音频"用。 */
    public TimerAlert alert() {
        return alert;
    }

    /** 当前会话（设置对话框/控制面板显示状态用）。 */
    public DownSession session() {
        return session;
    }

    /** 倒计时子球当前是否可见。 */
    public boolean isBallVisible() {
        return ballVisible;
    }

    /** 倒计时子球当前屏幕矩形（不可见返回 null；供排布避让/自检）。 */
    public Rectangle getBallBounds() {
        if (!ballVisible) {
            return null;
        }
        try {
            Point p = ball.getLocation();
            return new Rectangle(p.x, p.y, ball.getWidth(), ball.getHeight());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 请求重新排布（正计时子球冒出/收回后由插件调用，保证两颗子球不重叠）。 */
    public void relayout() {
        if (stopped || !ballVisible) {
            return;
        }
        if (!config.isFollowBall()) {
            ensureOnScreen();
            return;
        }
        Rectangle b = queryBallBounds();
        if (b != null) {
            ballBounds = b;
            placeRelativeToBall();
        } else {
            ensureOnScreen();
        }
    }

    private void refreshAll() {
        if (ballVisible) {
            ball.refresh();
        }
        if (focusVisible) {
            focus.refresh();
        }
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
