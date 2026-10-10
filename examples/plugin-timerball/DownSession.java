import com.randomnamepicker.core.LogManager;

/**
 * 倒计时核心（形态无关；子球与全屏专注共用同一实例，故两处显示天然同步）。
 * <p>
 * 状态机：{@code IDLE → RUNNING ⇄ PAUSED → FINISHED → (reset) IDLE}。
 * 计时以系统时间戳累计（显示值 = 已累计 + 运行中增量），防 Swing Timer 漂移；
 * 宿主与插件各有各的 Timer，本类自身不含定时器。
 * </p>
 * <p>仅 EDT 调用（控制器在 EDT tick 中驱动）。</p>
 */
public class DownSession {

    /** 倒计时状态。 */
    public enum State { IDLE, RUNNING, PAUSED, FINISHED }

    /** 到时回调（控制器用来播提醒音频/闪烁；在 EDT 调用）。 */
    public interface FinishListener {
        void onFinished(boolean skippedToEnd);
    }

    /** 读秒回调（进入最后 N 秒时每秒一次；在 EDT 调用）。 */
    public interface LastSecondListener {
        void onLastSecond(int remainingSeconds);
    }

    private volatile State state = State.IDLE;
    private long durationMs;
    private long accumulatedMs; // 已计入的时长
    private long resumeBaseMs;  // 本段运行起点
    private int lastWholeSecond = -1;
    private boolean endNotified = false;
    /** 上一次"开始"时使用的时长（秒）——"复原"按钮回退的目标值。 */
    private int lastStartSeconds;
    /** 全屏番茄钟顶部可编辑标题（空 = 未设置）。 */
    private String title = "";
    private FinishListener finishListener;
    private LastSecondListener lastSecondListener;

    public DownSession(int durationSeconds) {
        setDurationSeconds(durationSeconds);
        this.lastStartSeconds = Math.max(1, durationSeconds);
        this.accumulatedMs = 0L;
        this.resumeBaseMs = now();
    }

    // ===================== 状态 =====================

    public State getState() {
        return state;
    }

    public boolean isIdle() {
        return state == State.IDLE;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public boolean isPaused() {
        return state == State.PAUSED;
    }

    public boolean isFinished() {
        return state == State.FINISHED;
    }

    public boolean isVisibleState() {
        return state != State.IDLE;
    }

    public void setFinishListener(FinishListener l) {
        finishListener = l;
    }

    public void setLastSecondListener(LastSecondListener l) {
        lastSecondListener = l;
    }

    public int getDurationSeconds() {
        return (int) Math.max(1L, Math.round(durationMs / 1000.0));
    }

    public long getDurationMs() {
        return durationMs;
    }

    /** 设置时长（不改变当前状态；调用方通常随后 reset()）。 */
    public void setDurationSeconds(int seconds) {
        int s = Math.max(1, seconds);
        durationMs = s * 1000L;
    }

    // ===================== 计时读数 =====================

    private long now() {
        return System.currentTimeMillis();
    }

    /** 已累计毫秒（含运行中未暂停的增量）。 */
    public long elapsedMs() {
        long base = accumulatedMs;
        if (state == State.RUNNING) {
            base += now() - resumeBaseMs;
        }
        return Math.max(0L, base);
    }

    /** 剩余毫秒（≥0）。 */
    public long remainingMs() {
        return Math.max(0L, durationMs - elapsedMs());
    }

    /** 剩余整秒（向上取整：剩 0.4s 显示 1 秒，到点显示 0）。 */
    public int remainingSecondsCeil() {
        long r = remainingMs();
        if (r <= 0L) {
            return 0;
        }
        return (int) ((r + 999L) / 1000L);
    }

    /** 已用进度（0.0–1.0）。 */
    public double progress() {
        if (durationMs <= 0L) {
            return 1.0;
        }
        double p = (double) elapsedMs() / (double) durationMs;
        return Math.max(0.0, Math.min(1.0, p));
    }

    /** 剩余秒是否已进入警戒阈值。 */
    public boolean isWarning(int warnSeconds) {
        if (state != State.RUNNING && state != State.PAUSED) {
            return false;
        }
        int r = remainingSecondsCeil();
        return r > 0 && r <= Math.max(1, warnSeconds);
    }

    /**
     * 显示文本：默认 mm:ss（或 h:mm:ss）；{@code showDecisecond} 且剩余 &lt; 10s 时
     * 显示 {@code s.d} 十分之一秒（更直观的读秒）。
     */
    public String displayText(boolean showHours, boolean showDecisecond) {
        long rem = remainingMs();
        if (showDecisecond && rem < 10_000L) {
            long tenths = (rem + 99L) / 100L; // 向上取整到 0.1s，避免提前显示 0.0
            return (tenths / 10L) + "." + (tenths % 10L);
        }
        long sec = (rem + 999L) / 1000L;
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        if (showHours || h > 0L) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    // ===================== 操作 =====================

    /** 归零为 IDLE（保持设定时长，读数回到满值）。 */
    public void reset() {
        accumulatedMs = 0L;
        resumeBaseMs = now();
        state = State.IDLE;
        lastWholeSecond = -1;
        endNotified = false;
    }

    /** 以设定时长重新开始计时（RUNNING），并记下本次起点供"复原"使用。 */
    public void restart() {
        accumulatedMs = 0L;
        resumeBaseMs = now();
        state = State.RUNNING;
        lastWholeSecond = -1;
        endNotified = false;
        lastStartSeconds = getDurationSeconds();
    }

    /** 上次"开始"时使用的时长（秒）——"复原"按钮的目标值。 */
    public int getLastStartSeconds() {
        return Math.max(1, lastStartSeconds);
    }

    /** 设置"上次起点"（快捷时长等"只改设定、尚未开始"的场景，让随后的开始/复原以新时长为准）。 */
    public void setLastStartSeconds(int seconds) {
        lastStartSeconds = Math.max(1, seconds);
    }

    // ===================== 跨会话记忆（程序退出/重启后恢复这一轮倒计时） =====================

    /** 记忆快照：剩余毫秒 + 记忆时的状态 + 设定时长。 */
    public static final class Snapshot {
        public final long remainingMs;
        public final State state;
        public final int durationSeconds;
        public final int lastStartSeconds;

        Snapshot(long remainingMs, State state, int durationSeconds, int lastStartSeconds) {
            this.remainingMs = remainingMs;
            this.state = state;
            this.durationSeconds = durationSeconds;
            this.lastStartSeconds = lastStartSeconds;
        }
    }

    /** 取当前记忆快照（空闲态不记忆，避免把"没开始"记成"已开始"）。 */
    public Snapshot snapshot() {
        if (state == State.IDLE) {
            return null;
        }
        return new Snapshot(remainingMs(), state, getDurationSeconds(), getLastStartSeconds());
    }

    /**
     * 按记忆恢复：把剩余时间还原，并按"记忆时的状态"决定是继续走还是停在暂停。
     *
     * @param remainingMs   记忆的剩余毫秒
     * @param wasRunning    true=记忆时正在走（恢复后继续走）；false=记忆时已暂停（恢复后保持暂停）
     */
    public void restore(long remainingMs, boolean wasRunning) {
        long remain = Math.max(0L, remainingMs);
        accumulatedMs = Math.max(0L, durationMs - remain);
        resumeBaseMs = now();
        lastWholeSecond = -1;
        endNotified = false;
        if (remain <= 0L) {
            accumulatedMs = durationMs;
            state = State.FINISHED;
        } else {
            state = wasRunning ? State.RUNNING : State.PAUSED;
        }
    }

    /** 全屏番茄钟标题（可由界面即时编辑）。 */
    public String getTitle() {
        return title == null ? "" : title;
    }

    public void setTitle(String text) {
        title = text == null ? "" : text;
    }

    /**
     * 复原：回到"上次设定的倒计时起点"并立即重新开始。
     * <p>
     * 用于全屏番茄钟的"复原"按钮语义：不管当前是运行中/已暂停/已到时，都把读数拉回
     * 上一次开始时使用的时长（{@link #getLastStartSeconds()}）并从头计时。
     * </p>
     */
    public void restoreToLastStart() {
        setDurationSeconds(getLastStartSeconds());
        restart();
    }

    /** 开始（IDLE/FINISHED → RUNNING；PAUSED → 继续）。 */
    public void startOrResume() {
        if (state == State.RUNNING) {
            return;
        }
        if (state == State.IDLE || state == State.FINISHED) {
            restart();
            return;
        }
        resumeBaseMs = now();
        state = State.RUNNING;
    }

    /** 暂停并保留读数（仅 RUNNING 有效）。 */
    public void pause() {
        if (state != State.RUNNING) {
            return;
        }
        accumulatedMs += now() - resumeBaseMs;
        state = State.PAUSED;
    }

    /** 暂停/继续切换（IDLE/FINISHED 视为开始）。 */
    public void toggle() {
        if (state == State.RUNNING) {
            pause();
        } else {
            startOrResume();
        }
    }

    /**
     * 由控制器 tick 驱动：推进状态、到期结算、读秒回调。
     *
     * @return true 表示本次 tick 发生了"到时"结算（调用方应刷新显示）
     */
    public boolean tick() {
        if (state != State.RUNNING) {
            return false;
        }
        long rem = remainingMs();
        if (rem <= 0L) {
            accumulatedMs = durationMs;
            state = State.FINISHED;
            if (!endNotified) {
                endNotified = true;
                finishListenerSafely(false);
            }
            return true;
        }
        int whole = remainingSecondsCeil();
        if (whole != lastWholeSecond) {
            lastWholeSecond = whole;
            if (whole > 0 && whole <= 10 && lastSecondListener != null) {
                try {
                    lastSecondListener.onLastSecond(whole);
                } catch (Throwable t) {
                    logQuiet("倒计时插件-读秒回调异常: " + t);
                }
            }
        }
        return false;
    }

    /** 立即结束（菜单"立即结束"用；记 skippedToEnd=true 便于区分）。 */
    public void finishNow() {
        if (state == State.FINISHED) {
            return;
        }
        accumulatedMs = durationMs;
        state = State.FINISHED;
        if (!endNotified) {
            endNotified = true;
            finishListenerSafely(true);
        }
    }

    /** 到时通知（播放提醒音频由控制器负责；此处只做回调与日志）。 */
    public void notifyFinishedOnce() {
        if (!endNotified) {
            endNotified = true;
            finishListenerSafely(false);
        }
    }

    private void finishListenerSafely(boolean skipped) {
        try {
            LogManager.log("倒计时插件-到时 时长=" + TimerBallConfig.formatDuration(getDurationSeconds())
                    + (skipped ? "（手动结束）" : ""), "CD_FINISH");
        } catch (Throwable ignore) {
            // 日志失败不影响运行
        }
        if (finishListener != null) {
            try {
                finishListener.onFinished(skipped);
            } catch (Throwable t) {
                logQuiet("倒计时插件-到时回调异常: " + t);
            }
        }
    }

    private static void logQuiet(String detail) {
        try {
            LogManager.log(detail, "PLUGIN_LOAD_ERROR");
        } catch (Throwable ignore) {
            // 忽略
        }
    }
}
