import com.randomnamepicker.core.LogManager;
import java.awt.Toolkit;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * 提醒音频播放器（倒计时插件的"自定义提醒音频"能力）。
 * <p>
 * 规则：
 * <ul>
 *   <li>优先播放用户在设置窗选定的自定义音频文件（WAV/AIFF/AU 等 {@code javax.sound.sampled} 可解码格式，
 *       推荐 16bit PCM WAV）；</li>
 *   <li>自定义文件缺失/格式不支持/设备不可用 → 回退系统提示音（{@link Toolkit#beep()}），
 *       回退开关由配置项 {@code systemBeepFallback} 控制；</li>
 *   <li>播放与回退均在后台线程，绝不阻塞 EDT；{@link #stop()} 立即打断播放（onUnload/重新开始用）；</li>
 *   <li>失败只记日志（操作码 {@code CD_ALERT_FAIL}），不影响倒计时主流程。</li>
 * </ul>
 * 音量对自定义音频用 MASTER_GAIN（0–100 映射到约 -40dB–+6dB），系统提示音无音量控制。
 * </p>
 */
public final class CountdownAlert {

    private final CountdownConfig config;
    private Thread player;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private Clip activeClip;
    private volatile boolean inactive = false;

    public CountdownAlert(CountdownConfig config) {
        this.config = config;
    }

    /** 标记停用（onUnload）；之后所有播放请求直接忽略。 */
    public void shutdown() {
        inactive = true;
        stop();
    }

    /** 立即停止播放（打断后台线程并关闭音频行）。 */
    public void stop() {
        cancel.set(true);
        Clip c = activeClip;
        if (c != null) {
            try {
                c.stop();
                c.close();
            } catch (Throwable ignore) {
                // 忽略
            }
            activeClip = null;
        }
        Thread t = player;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
        player = null;
    }

    /**
     * 播放提醒（按配置重复 {@code alertRepeat} 次）。
     *
     * @param reason 触发原因（写日志用，如"到时"）
     */
    public void play(String reason) {
        if (inactive) {
            return;
        }
        final int repeat = Math.max(1, config.getAlertRepeat());
        final String file = config.getAlertFile();
        final int volume = config.getAlertVolume();
        final boolean fallback = config.isSystemBeepFallback();
        stop(); // 打断上一次播放，避免叠音
        cancel.set(false);
        Thread t = new Thread(() -> {
            boolean customOk = false;
            if (!file.isEmpty()) {
                customOk = playFile(file, volume, repeat);
            }
            if (!customOk && fallback && !cancel.get()) {
                beep(repeat);
            }
            logQuiet("倒计时插件-提醒音频(" + reason + ") "
                    + (customOk ? "自定义音频" : (fallback ? "系统提示音(兜底)" : "无可用音频"))
                    + " 重复=" + repeat + " 文件=" + (file.isEmpty() ? "(未设置)" : file),
                    "CD_ALERT");
        }, "countdown-alert");
        t.setDaemon(true);
        player = t;
        t.start();
    }

    /** 只播一次（测试按钮用；不写 CD_ALERT 日志，避免污染运行日志）。 */
    public boolean testPlay(String path, int volume) {
        stop();
        cancel.set(false);
        if (path != null && !path.trim().isEmpty()) {
            return playFile(path.trim(), volume, 1);
        }
        beep(1);
        return false;
    }

    /** 校验音频文件是否可解码（设置对话框"校验"用）。 */
    public static String checkFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "未选择文件（将使用系统提示音）";
        }
        File f = new File(path.trim());
        if (!f.exists()) {
            return "文件不存在：" + f.getAbsolutePath();
        }
        if (!f.isFile()) {
            return "不是文件：" + f.getAbsolutePath();
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(f)) {
            AudioFormat fmt = in.getFormat();
            return "可播放：" + fmt.getEncoding() + " " + (int) fmt.getSampleRate() + "Hz "
                    + fmt.getSampleSizeInBits() + "bit " + fmt.getChannels() + "声道"
                    + " 时长约" + Math.round(in.getFrameLength() / Math.max(1f, fmt.getFrameRate()) * 10f) / 10.0 + "秒";
        } catch (UnsupportedAudioFileException e) {
            return "格式不支持（建议 16bit PCM WAV）：" + e.getMessage();
        } catch (IOException e) {
            return "读取失败：" + e.getMessage();
        } catch (Throwable t) {
            return "无法解析：" + t;
        }
    }

    // ===================== 内部实现 =====================

    private boolean playFile(String path, int volume, int repeat) {
        for (int i = 0; i < repeat && !cancel.get() && !inactive; i++) {
            if (!playOnce(path, volume)) {
                return false;
            }
        }
        return true;
    }

    private boolean playOnce(String path, int volume) {
        File f = new File(path);
        if (!f.exists() || !f.isFile()) {
            logQuiet("倒计时插件-提醒音频文件不存在: " + f.getAbsolutePath(), "CD_ALERT_FAIL");
            return false;
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(f)) {
            AudioFormat fmt = in.getFormat();
            DataLine.Info info = new DataLine.Info(Clip.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                logQuiet("倒计时插件-音频行不支持: " + fmt, "CD_ALERT_FAIL");
                return false;
            }
            Clip clip = (Clip) AudioSystem.getLine(info);
            activeClip = clip;
            final Object done = new Object();
            clip.addLineListener(ev -> {
                if (ev.getType() == LineEvent.Type.STOP) {
                    synchronized (done) {
                        done.notifyAll();
                    }
                }
            });
            applyVolume(clip, volume);
            clip.open(in);
            if (cancel.get()) {
                closeQuiet(clip);
                return true;
            }
            clip.start();
            synchronized (done) {
                // 等待播放结束（含被外部 stop 打断）或取消
                long deadline = System.currentTimeMillis() + 10L * 60L * 1000L;
                while (clip.isRunning() && !cancel.get() && !inactive
                        && System.currentTimeMillis() < deadline) {
                    done.wait(200L);
                }
            }
            closeQuiet(clip);
            return true;
        } catch (UnsupportedAudioFileException e) {
            logQuiet("倒计时插件-提醒音频格式不支持（建议 16bit PCM WAV）: " + e.getMessage(), "CD_ALERT_FAIL");
            return false;
        } catch (LineUnavailableException e) {
            logQuiet("倒计时插件-音频设备不可用: " + e.getMessage(), "CD_ALERT_FAIL");
            return false;
        } catch (IOException e) {
            logQuiet("倒计时插件-提醒音频读取失败: " + e.getMessage(), "CD_ALERT_FAIL");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Throwable t) {
            logQuiet("倒计时插件-提醒音频播放异常: " + t, "CD_ALERT_FAIL");
            return false;
        } finally {
            activeClip = null;
        }
    }

    private void closeQuiet(Clip clip) {
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

    private void applyVolume(Clip clip, int volume) {
        try {
            if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                return;
            }
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float min = gain.getMinimum();
            float max = gain.getMaximum();
            int v = Math.max(0, Math.min(100, volume));
            float db;
            if (v == 0) {
                db = min;
            } else {
                db = (float) (20.0 * Math.log10(v / 100.0)); // 1.0 → 0dB
            }
            gain.setValue(Math.max(min, Math.min(max, db)));
        } catch (Throwable ignore) {
            // 音量控制不可用则按默认播放
        }
    }

    private void beep(int repeat) {
        int n = Math.max(1, Math.min(5, repeat));
        for (int i = 0; i < n && !cancel.get() && !inactive; i++) {
            try {
                Toolkit.getDefaultToolkit().beep();
            } catch (Throwable t) {
                // 无 AWT/无声环境：忽略
            }
            if (i < n - 1) {
                try {
                    Thread.sleep(220L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void logQuiet(String detail, String opCode) {
        try {
            LogManager.log(detail, opCode);
        } catch (Throwable ignore) {
            // 忽略
        }
    }
}
