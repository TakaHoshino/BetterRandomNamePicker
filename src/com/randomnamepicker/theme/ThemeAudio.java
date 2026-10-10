package com.randomnamepicker.theme;

import com.randomnamepicker.core.LogManager;
import java.io.File;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;

/**
 * 背景音播放（零依赖路线 A）：用 {@code javax.sound.sampled} 的 {@link Clip} 循环播放
 * WAV / AIFF / AU（PCM）——格式支持由运行环境决定，解析失败只记日志，不影响贴图。
 * <p>
 * 口径：**全局单通道**（多窗口共享一路音频，避免回声）；音量 0–100 映射到 MASTER_GAIN（无该控件的实现退化为"仅静音开关"）；
 * 主题关闭 / 背景音设为空 / 宿主退出即停止。事件全部在 EDT。
 * </p>
 */
public final class ThemeAudio {

    private static final ThemeAudio INSTANCE = new ThemeAudio();

    private Clip clip;
    private String currentId = "";

    private ThemeAudio() {
    }

    public static ThemeAudio getInstance() {
        return INSTANCE;
    }

    /** 与 store 对齐（主题开关 / 背景音 id / 音量 / 静音）：所有 reapply 路径的收口点调用。 */
    public void syncFromStore() {
        if (!ThemeStore.isEnabled()) {
            stop();
            return;
        }
        String id = MediaLibrary.getAudioId();
        if (id.isEmpty()) {
            stop();
            return;
        }
        if (!id.equals(currentId) || clip == null || !clip.isOpen()) {
            open(id);
        }
        applyVolumeAndState();
    }

    /** 立即停止并释放（主题关闭/删除音频/退出）。 */
    public void stop() {
        if (clip != null) {
            try {
                clip.stop();
            } catch (Throwable ignore) {
                // 忽略
            }
            try {
                clip.close();
            } catch (Throwable ignore) {
                // 忽略
            }
        }
        clip = null;
        currentId = "";
    }

    /** 宿主退出：等同 {@link #stop()}（保留语义以便将来加淡出）。 */
    public void shutdown() {
        stop();
    }

    public boolean isPlaying() {
        return clip != null && clip.isRunning();
    }

    public String currentAudioId() {
        return currentId;
    }

    /** 试听（独立页“试听/停止”按钮）：强制按当前音量播放一次循环，忽略静音开关。 */
    public void previewNow() {
        String id = MediaLibrary.getAudioId();
        if (id.isEmpty()) {
            return;
        }
        if (clip != null && id.equals(currentId) && clip.isRunning()) {
            clip.stop();
            return;
        }
        stop();
        open(id);
        applyVolumeAndState(true);
    }

    // ===================== 内部 =====================

    private void open(String id) {
        stop();
        File f = MediaLibrary.mediaFile(id);
        if (f == null) {
            return;
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(f)) {
            Clip c = AudioSystem.getClip();
            c.open(in); // Clip 在 open 时整段缓冲，随后关闭输入流是安全的
            clip = c;
            currentId = id;
            LogManager.log("主题-背景音已载入: " + MediaLibrary.mediaDisplayName(id)
                    + "（" + c.getFormat() + "）", "THEME_DEBUG");
        } catch (Throwable t) {
            clip = null;
            currentId = "";
            LogManager.log("主题-背景音载入失败（将静音处理）: " + f.getAbsolutePath() + "（" + t + "）", "THEME_DEBUG");
        }
    }

    private void applyVolumeAndState() {
        applyVolumeAndState(false);
    }

    /** @param forcePlay 试听时忽略"静音"开关 */
    private void applyVolumeAndState(boolean forcePlay) {
        if (clip == null) {
            return;
        }
        int volume = MediaLibrary.getVolume();
        try {
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            if (gain != null) {
                if (volume <= 0) {
                    gain.setValue(gain.getMinimum());
                } else {
                    float db = (float) (20.0 * Math.log10(volume / 100.0));
                    db = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db));
                    gain.setValue(db);
                }
            }
        } catch (Throwable ignore) {
            // 无音量控件：退化为纯静音开关
        }
        boolean shouldPlay = forcePlay || (!MediaLibrary.isMuted() && volume > 0);
        try {
            if (shouldPlay) {
                if (!clip.isRunning()) {
                    clip.setFramePosition(0);
                    clip.loop(Clip.LOOP_CONTINUOUSLY);
                }
            } else if (clip.isRunning()) {
                clip.stop();
            }
        } catch (Throwable ignore) {
            // 播放异常不影响贴图
        }
    }
}
