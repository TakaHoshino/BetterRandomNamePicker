import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 计时子球插件合并工具（一次性开发工具，仓库内保留以便复核合并来源）。
 * <p>
 * 作用：从两个既有插件工程（{@code examples/plugin-countdown/}、{@code examples/plugin-timer/}）
 * 复制源码到 {@code examples/plugin-timerball/} 并按合并命名规则做<b>字节精确的 UTF-8</b> 文本替换，
 * 再人工/脚本叠加合并逻辑改动（正计时侧 {@code Up*}、倒计时侧 {@code Down*}）。
 * </p>
 * <p>
 * 之所以用 Java 而不是 PowerShell 批处理：本工具全程使用 {@link StandardCharsets#UTF_8} 显式读写，
 * 避免 shell 管道的编码推断把源码里的非 ASCII 字符弄坏（合并过程中曾因此出现字符损坏）。
 * </p>
 * <pre>
 * 运行： java -cp build TimerRenamer
 * </pre>
 */
public final class TimerRenamer {

    private TimerRenamer() {
    }

    /** 源文件 → 目标文件名 + 替换规则（按顺序应用；中文/长串优先）。 */
    private static final class Job {
        final String target;
        final String source;
        final String[][] rules;

        Job(String target, String source, String[][] rules) {
            this.target = target;
            this.source = source;
            this.rules = rules;
        }
    }

    // ===================== 替换规则 =====================

    /** 倒计时侧：类名 + 自持文件名（说明文本“倒计时插件/倒计时子球”保留，由插件补组前缀）。 */
    private static final String[][] DOWN_RULES = {
            {"CountdownSettingsDialog", "DownSettingsDialog"},
            {"CountdownController", "DownController"},
            {"CountdownSession", "DownSession"},
            {"CountdownConfig", "TimerBallConfig"},
            {"CountdownAlert", "TimerAlert"},
            {"CountdownBall", "DownBall"},
            {"CountdownFocus", "DownFocus"},
            {"CountdownPlugin", "TimerBallPlugin"},
    };

    /** 正计时侧：类名 + 自持文件说明 + 日志/用户可见文案。 */
    private static final String[][] UP_RULES = {
            {"TimerBallSettingsDialog", "UpSettingsDialog"},
            {"TimerBallConfig", "UpBallConfig"},
            {"FloatingBallTracker", "UpTracker"},
            {"TimerBall", "UpBall"},
            {"正计时子球", "计时子球·正计时"},
    };

    private static final Job[] JOBS = {
            new Job("DownSession.java", "examples/plugin-countdown/CountdownSession.java", DOWN_RULES),
            new Job("DownBall.java", "examples/plugin-countdown/CountdownBall.java", DOWN_RULES),
            new Job("DownFocus.java", "examples/plugin-countdown/CountdownFocus.java", DOWN_RULES),
            new Job("DownSettingsDialog.java", "examples/plugin-countdown/CountdownSettingsDialog.java", DOWN_RULES),
            new Job("TimerBallConfig.java", "examples/plugin-countdown/CountdownConfig.java", DOWN_RULES),
            new Job("TimerAlert.java", "examples/plugin-countdown/CountdownAlert.java", DOWN_RULES),
            new Job("DownController.java", "examples/plugin-countdown/CountdownController.java", DOWN_RULES),
            new Job("TimerBallPlugin.java", "examples/plugin-timer/CountUpTimerPlugin.java", UP_RULES),
            new Job("UpBall.java", "examples/plugin-timer/TimerBall.java", UP_RULES),
            new Job("UpBallConfig.java", "examples/plugin-timer/TimerBallConfig.java", UP_RULES),
            new Job("UpSettingsDialog.java", "examples/plugin-timer/TimerBallSettingsDialog.java", UP_RULES),
            new Job("UpTracker.java", "examples/plugin-timer/FloatingBallTracker.java", UP_RULES),
    };

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        Path outDir = root.resolve("examples/plugin-timerball");
        if (!Files.isDirectory(outDir)) {
            throw new IOException("找不到目标目录: " + outDir);
        }
        List<String> written = new ArrayList<>();
        for (Job job : JOBS) {
            Path src = root.resolve(job.source);
            if (!Files.isRegularFile(src)) {
                throw new IOException("找不到源文件: " + src);
            }
            String text = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
            for (String[] rule : job.rules) {
                text = text.replace(rule[0], rule[1]);
            }
            Path dst = outDir.resolve(job.target);
            Files.write(dst, text.getBytes(StandardCharsets.UTF_8));
            written.add(job.target);
        }
        System.out.println("合并重命名完成，共 " + written.size() + " 个文件 → " + outDir);
        for (String w : written) {
            System.out.println("  " + w);
        }
    }
}
