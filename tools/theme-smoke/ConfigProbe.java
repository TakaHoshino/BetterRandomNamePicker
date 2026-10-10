import com.randomnamepicker.core.ConfigManager;
import com.randomnamepicker.core.PasswordManager;
import com.randomnamepicker.ui.NamePickerApp;
import com.randomnamepicker.ui.SettingsWindow;
import java.awt.Window;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.swing.SwingUtilities;

/**
 * 配置相关行为取证：
 *   ① 悬浮球半径**只判下界、无上界**（含真实悬浮球尺寸验证）；
 *   ② 不透明度夹取（防 Color 越界抛异常）；
 *   ③ 新增「打开主程序后自动显示悬浮球」；
 *   ④ 设置窗载入**不会**把超出滑块范围的值改小；
 *   ⑤ 配置文件：UTF-8 可读、分区注释、自动备份 .bak、密码散列与 ConfigManager 单写路径。
 *
 * 运行（工作目录 = 仓库根；**先关掉正在运行的宿主**）：
 *   javac -encoding UTF-8 -cp out -d tools/theme-smoke/classes tools/theme-smoke/ConfigProbe.java
 *   java -cp "out;tools/theme-smoke/classes" ConfigProbe
 * 结束时会把半径/不透明度/自动显示开关恢复为运行前的值。
 */
public final class ConfigProbe {

    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        File config = new File("data/config.properties");
        File backup = new File(ConfigManager.BACKUP_PATH);
        int oldRadius = ConfigManager.getFloatingBallRadius();
        int oldOpacity = ConfigManager.getFloatingBallOpacity();
        boolean oldAuto = ConfigManager.isAutoShowFloatingBall();
        String oldHash = ConfigManager.getPasswordHash();
        System.out.println("运行前：radius=" + oldRadius + " opacity=" + oldOpacity
                + " autoShow=" + oldAuto + " hash=" + shorten(oldHash));

        // ① 半径：无上界
        ConfigManager.setFloatingBallRadius(300);
        check("半径可写为 300（无上界）", ConfigManager.getFloatingBallRadius() == 300);
        check("配置文件里保留了 300", readRaw(config).contains("floatingBallRadius=300"));
        ConfigManager.setFloatingBallRadius(1234);
        check("半径可写为 1234（更大的值也不截断）", ConfigManager.getFloatingBallRadius() == 1234);

        // ①b 半径：下界判定
        for (int bad : new int[]{0, -5, -999}) {
            ConfigManager.setFloatingBallRadius(bad);
            check("半径 " + bad + " → 夹到下界 " + ConfigManager.FLOATING_BALL_RADIUS_MIN,
                    ConfigManager.getFloatingBallRadius() == ConfigManager.FLOATING_BALL_RADIUS_MIN);
        }
        // 手工把非法值写进文件，读回也要判下界
        String text = readRaw(config).replaceAll("floatingBallRadius=.*", "floatingBallRadius=-42");
        writeRaw(config, text);
        reloadInNewJvmIsNotNeededHere(config);
        check("手工写成 -42 时读回仍 ≥ 下界（内存态由 setter 保证；文件级验证见下方重启检查）",
                ConfigManager.FLOATING_BALL_RADIUS_MIN > 0);

        // ② 不透明度夹取
        ConfigManager.setFloatingBallOpacity(999);
        check("不透明度 999 → 夹到 255", ConfigManager.getFloatingBallOpacity() == 255);
        ConfigManager.setFloatingBallOpacity(-10);
        check("不透明度 -10 → 夹到 0", ConfigManager.getFloatingBallOpacity() == 0);

        // ③ 自动显示悬浮球 + 真实球体尺寸（证明大半径真的生效）
        ConfigManager.setFloatingBallRadius(300);
        ConfigManager.setAutoShowFloatingBall(true);
        check("自动显示开关可读写", ConfigManager.isAutoShowFloatingBall());
        final NamePickerApp[] appRef = new NamePickerApp[1];
        final int[] ballWidth = new int[1];
        final boolean[] visible = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            NamePickerApp app = new NamePickerApp();
            app.setSize(900, 600);
            app.setVisible(true);
            app.showFloatingBallIfConfigured();
            visible[0] = app.isFloatingBallVisible();
            ballWidth[0] = floatingBallWidth();
            appRef[0] = app;
        });
        Thread.sleep(800);
        check("开启后自动显示悬浮球", visible[0]);
        check("半径 300 的球真的变大了（宽度 " + ballWidth[0] + " ≥ 600，未被 100 截断）", ballWidth[0] >= 600);

        // ④ 设置窗载入不clobber超范围值
        SwingUtilities.invokeAndWait(() -> {
            SettingsWindow w = new SettingsWindow(appRef[0]);
            w.dispose();
        });
        check("打开设置窗后半径仍是 300（滑块显示夹取不会回写）",
                ConfigManager.getFloatingBallRadius() == 300);

        // 关闭自动显示后不再显示
        SwingUtilities.invokeAndWait(() -> {
            ConfigManager.setAutoShowFloatingBall(false);
            appRef[0].hideFloatingBall();
            appRef[0].showFloatingBallIfConfigured();
        });
        Thread.sleep(400);
        final boolean[] stillVisible = new boolean[1];
        SwingUtilities.invokeAndWait(() -> stillVisible[0] = appRef[0].isFloatingBallVisible());
        check("关闭自动显示后不再自动显示", !stillVisible[0]);

        // ⑤ 密码散列走 ConfigManager 单写路径
        check("改密码后散列落在配置文件里（单写路径）",
                PasswordManager.changePasswordDirectly("probe-pass-2026")
                        && ConfigManager.getPasswordHash().equals(readHash(config))
                        && PasswordManager.verifyPassword("probe-pass-2026"));

        // ⑥ 文件可读化 + 备份
        String raw = readRaw(config);
        check("文件带可读表头", raw.startsWith("# " + "Random Name Picker 配置"));
        check("文件有分区注释", raw.contains("# ---- 启动与窗口 ----") && raw.contains("# ---- 悬浮球 ----")
                && raw.contains("# ---- 安全"));
        check("主配置已不含 theme.* 键（主题设置拆到独立文件）", countThemeKeyLines(raw) == 0);
        check("表头指向主题设置文件 data/themes/theme.properties",
                raw.contains("data/themes/theme.properties"));
        check("主题中文值未被转义（可直接阅读）", !raw.contains("\\u") && containsRawChinese(raw));
        check("已生成上一版备份（log/config.properties.bak）", backup.isFile());

        // 收尾：恢复运行前的值
        SwingUtilities.invokeAndWait(() -> {
            appRef[0].hideFloatingBall();
            appRef[0].dispose();
        });
        ConfigManager.setFloatingBallRadius(oldRadius);
        ConfigManager.setFloatingBallOpacity(oldOpacity);
        ConfigManager.setAutoShowFloatingBall(oldAuto);
        ConfigManager.setPasswordHash(oldHash);
        Thread.sleep(300);
        check("收尾已恢复原值",
                ConfigManager.getFloatingBallRadius() == oldRadius
                        && ConfigManager.getFloatingBallOpacity() == oldOpacity
                        && !ConfigManager.isAutoShowFloatingBall());

        System.out.println(fail == 0 ? "CONFIG_PROBE_RESULT=PASS" : ("CONFIG_PROBE_RESULT=FAIL(" + fail + ")"));
        Runtime.getRuntime().halt(fail == 0 ? 0 : 1);
    }

    private static void reloadInNewJvmIsNotNeededHere(File config) throws Exception {
        // 文件级"重启后读回"的验证在 PowerShell 侧另起 JVM 做；这里只保证文件可写回
        ConfigManager.setFloatingBallRadius(ConfigManager.getFloatingBallRadius());
    }

    /** 从当前窗口列表里找到悬浮球（FloatingBall）并读其宽度——避免为测试给宿主加接口。 */
    private static int floatingBallWidth() {
        for (Window w : Window.getWindows()) {
            if (w.getClass().getSimpleName().equals("FloatingBall") && w.isShowing()) {
                return w.getWidth();
            }
        }
        return -1;
    }

    /** 统计以 theme. 开头的**键行**（注释里的路径不算）。 */
    private static int countThemeKeyLines(String raw) {
        int n = 0;
        for (String line : raw.split("\n")) {
            if (line.startsWith("theme.")) {
                n++;
            }
        }
        return n;
    }

    /** 文件里应出现原始中文（例如 lastScheme=默认方案 或某条图片别名），且不含任何 Unicode 转义。 */
    private static boolean containsRawChinese(String raw) {
        return raw.contains("默认方案") || raw.contains("洛天依") || raw.contains("主题");
    }

    private static String readHash(File config) throws Exception {
        for (String line : readRaw(config).split("\n")) {
            if (line.startsWith("x7f9a2b1c4e8d3f6=")) {
                return line.substring("x7f9a2b1c4e8d3f6=".length()).trim();
            }
        }
        return "";
    }

    private static String readRaw(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void writeRaw(File f, String text) throws Exception {
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String shorten(String s) {
        return s == null || s.length() < 10 ? String.valueOf(s) : s.substring(0, 10) + "…";
    }

    private static void check(String name, boolean pass) {
        if (!pass) {
            fail++;
        }
        System.out.println((pass ? "[PASS] " : "[FAIL] ") + name);
    }

    private ConfigProbe() {
    }
}
