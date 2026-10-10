package com.randomnamepicker.theme;

import com.randomnamepicker.core.LogManager;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.swing.Timer;

/**
 * 动态背景**播放时钟**（零依赖路线 A）：全局单实例，持有一份 {@link MediaSource}，
 * 按帧时长（GIF 用帧自带 delay / 帧序列用 {@code theme.media.fps}）推进帧号，
 * 并通知所有已登记的 {@link WindowBackdrop} 重绘。
 * <p>
 * 口径：**全局一个解码器 + 多窗口共享同一帧**（避免 N 个窗口 N 路解码）；
 * 事件全部在 EDT（Swing {@link Timer}），无后台线程；窗口关闭/宿主退出即摘除登记，无动态背景时定时器不运行（零 CPU）。
 * </p>
 */
public final class MediaClock {

    private static final MediaClock INSTANCE = new MediaClock();

    private final Set<WindowBackdrop> targets =
            Collections.newSetFromMap(new IdentityHashMap<WindowBackdrop, Boolean>());
    private final Timer timer = new Timer(100, e -> tick());

    private MediaSource source = MediaSource.NONE;
    private String activeId = "";
    private int index = 0;
    private boolean running = false;

    private MediaClock() {
        timer.setCoalesce(true);
    }

    public static MediaClock getInstance() {
        return INSTANCE;
    }

    // ===================== 与配置同步 =====================

    /** 与 store 对齐（主题开关 / 全局动态背景）：所有 reapply 路径的收口点调用。 */
    public void syncFromStore() {
        setActive(ThemeStore.isEnabled() ? MediaLibrary.getMediaId() : "");
        if (running && source.useFixedFps()) {
            scheduleNext(); // 帧序列的帧率可能刚被改过
        }
    }

    /** 切换当前媒体（id 为空=停止）。重复设置同一 id 只做"确保在播"。 */
    public void setActive(String mediaId) {
        String id = mediaId == null ? "" : mediaId.trim();
        if (id.equals(activeId)) {
            if (!running && source.frameCount() >= 2) {
                index = 0;
                startTimer();
            }
            return;
        }
        closeSource();
        activeId = id;
        index = 0;
        if (!id.isEmpty()) {
            MediaSource opened = MediaSource.open(id);
            if (opened != null) {
                source = opened;
                startTimer();
            } else {
                LogManager.log("主题-动态背景不可用（按无动态背景处理）: " + id, "THEME_DEBUG");
            }
        }
        repaintTargets();
    }

    /** 媒体文件被删除/替换后调用：丢弃当前帧源并按配置重开。 */
    public void invalidate() {
        closeSource();
        activeId = "";
        setActive(MediaLibrary.getMediaId());
    }

    // ===================== 帧访问（绘制侧调用） =====================

    /** 当前应绘制的帧；无动态背景返回 null。 */
    public BufferedImage currentFrame() {
        if (source.frameCount() <= 0) {
            return null;
        }
        return source.frame(index);
    }

    public int currentIndex() {
        return index;
    }

    public int frameCount() {
        return source.frameCount();
    }

    public String activeId() {
        return activeId;
    }

    public boolean isRunning() {
        return running;
    }

    // ===================== 重绘登记 =====================

    public void addTarget(WindowBackdrop backdrop) {
        if (backdrop != null) {
            targets.add(backdrop);
        }
    }

    public void removeTarget(WindowBackdrop backdrop) {
        if (backdrop != null) {
            targets.remove(backdrop);
        }
    }

    // ===================== 停止/退出 =====================

    /** 停表并丢弃帧源（主题关闭/无动态背景时调用）。 */
    public void stop() {
        closeSource();
        activeId = "";
        repaintTargets();
    }

    /** 宿主退出：停表、丢源、清空登记。 */
    public void shutdown() {
        timer.stop();
        running = false;
        closeSource();
        activeId = "";
        targets.clear();
    }

    // ===================== 内部 =====================

    private void startTimer() {
        if (source.frameCount() < 2) {
            running = false;
            return;
        }
        running = true;
        scheduleNext();
    }

    private void scheduleNext() {
        if (!running || source.frameCount() < 2) {
            return;
        }
        int delay = source.useFixedFps()
                ? Math.max(MediaSource.MIN_DELAY_MS, Math.round(1000f / Math.max(1, MediaLibrary.getFps())))
                : source.delayMillis(index);
        timer.setInitialDelay(delay);
        timer.setDelay(delay);
        timer.restart();
    }

    private void tick() {
        if (source.frameCount() < 2) {
            running = false;
            timer.stop();
            return;
        }
        index++;
        if (index >= source.frameCount()) {
            if (MediaLibrary.isLoop()) {
                index = 0;
            } else {
                index = source.frameCount() - 1;
                running = false;
                timer.stop();
                repaintTargets();
                return;
            }
        }
        repaintTargets();
        scheduleNext();
    }

    private void repaintTargets() {
        for (WindowBackdrop b : targets) {
            b.repaint();
        }
    }

    private void closeSource() {
        timer.stop();
        running = false;
        if (source != null && source != MediaSource.NONE) {
            source.dispose();
        }
        source = MediaSource.NONE;
        index = 0;
    }
}
