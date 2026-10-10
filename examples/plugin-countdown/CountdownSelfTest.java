import com.randomnamepicker.plugin.HostApi;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

/**
 * 倒计时插件自检harness（不安装、不影响宿主运行；在插件工程目录用 java 直接运行）。
 * <p>
 * 覆盖：配置默认值/十六进制往返/时长解析/坏值回退、倒计时状态机（开始-暂停-继续-归零-到时）、
 * 读秒与到时回调、到时提醒音频兜底路径、设置对话框与两个形态窗口的构建（有显示环境时）、
 * 以及"jar 内不得含宿主类"这一强制禁令的自动核对。
 * </p>
 * <pre>
 * 运行：java -cp "..\..\RandomNamePicker.jar;build" CountdownSelfTest
 * </pre>
 * 退出码 0 = 全部通过；1 = 有失败项。
 */
public final class CountdownSelfTest {

    private static final List<String> FAILURES = new ArrayList<>();
    private static final AtomicInteger PASSED = new AtomicInteger();

    private CountdownSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 倒计时插件自检开始 ==");
        System.out.println("headless=" + GraphicsEnvironment.isHeadless()
                + " 宿主API级别=" + HostApi.PLUGIN_API_LEVEL
                + " 宿主版本=" + HostApi.HOST_VERSION);

        testConfigDefaults();
        testConfigPersistence();
        testDurationParsing();
        testSessionStateMachine();
        testSessionFinishInRealTime();
        testAlertFallback();
        testJarHasNoHostClasses();
        testUiConstructionIfPossible();

        System.out.println();
        System.out.println("通过 " + PASSED.get() + " 项，失败 " + FAILURES.size() + " 项");
        for (String f : FAILURES) {
            System.out.println("  [FAIL] " + f);
        }
        System.out.println(FAILURES.isEmpty() ? "== 自检全部通过 ==" : "== 自检存在失败 ==");
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    // ===================== 配置 =====================

    private static void testConfigDefaults() {
        CountdownConfig c = new CountdownConfig();
        check("默认时长=300秒", c.getDurationSeconds() == 300);
        check("默认形态=子球", c.getDisplayMode() == CountdownConfig.DisplayMode.BALL);
        check("默认显示即开始", c.isAutoStartOnShow());
        check("默认跟随悬浮球", c.isFollowBall());
        check("默认到时响铃", c.isAlertOnFinish());
        check("默认兜底蜂鸣", c.isSystemBeepFallback());
        check("默认自定义音频为空", c.getAlertFile().isEmpty() && c.getAlertFileObject() == null);
        check("默认警戒阈值=60秒", c.getWarnSeconds() == 60);
        check("默认警戒色=琥珀D9A406", CountdownConfig.toHex(c.getColorWarning()).equals("D9A406"));
        c.setDurationSeconds(999999);
        check("时长上限夹取(99:59)", c.getDurationSeconds() == 99 * 60 + 59);
        c.setDurationSeconds(0);
        check("时长下限夹取(1秒)", c.getDurationSeconds() == 1);
        c.setWarnSeconds(1);
        check("警戒阈值下限夹取(5秒)", c.getWarnSeconds() == CountdownConfig.WARN_SECONDS_MIN);
        c.resetToDefaults();
        check("恢复默认后时长=300秒", c.getDurationSeconds() == 300);
    }

    private static void testConfigPersistence() throws Exception {
        File tmp = File.createTempFile("countdown_selftest", ".properties");
        if (!tmp.delete()) {
            // 允许删除失败：save 会覆盖
        }
        CountdownConfig c = new CountdownConfig();
        c.setFilePath(tmp.getAbsolutePath());
        c.setAlertFile("D:/audio/ding.wav");
        c.setColorWarning(CountdownConfig.parseHexColor("FF00AA"));
        c.setDurationSeconds(1500);
        c.setDisplayMode(CountdownConfig.DisplayMode.BOTH);
        c.setBallPos(123, 456);
        c.setAlertVolume(42);
        c.save();
        check("配置文件已写出", tmp.exists() && tmp.length() > 0);

        CountdownConfig back = new CountdownConfig();
        back.setFilePath(tmp.getAbsolutePath());
        back.load();
        check("音频路径往返", "D:/audio/ding.wav".equals(back.getAlertFile()));
        check("警戒色往返", CountdownConfig.toHex(back.getColorWarning()).equals("FF00AA"));
        check("时长往返", back.getDurationSeconds() == 1500);
        check("形态往返", back.getDisplayMode() == CountdownConfig.DisplayMode.BOTH);
        check("位置往返", back.hasBallPos() && back.getBallPosX() == 123 && back.getBallPosY() == 456);
        check("音量往返", back.getAlertVolume() == 42);
        check("摘要非空", back.summary() != null && back.summary().contains("警戒"));

        // 坏值回退
        java.nio.file.Files.write(tmp.toPath(),
                java.util.Collections.singletonList("defaultDurationSeconds=abc\ncolorWarning=ZZZZZZ\n"));
        CountdownConfig bad = new CountdownConfig();
        bad.setFilePath(tmp.getAbsolutePath());
        bad.load();
        check("坏时长回退默认", bad.getDurationSeconds() == CountdownConfig.DEFAULT_DURATION_SECONDS);
        check("坏颜色回退默认", CountdownConfig.toHex(bad.getColorWarning())
                .equals(CountdownConfig.toHex(CountdownConfig.DEFAULT_COLOR_WARNING)));

        // 十六进制解析边界
        check("解析 #AABBCC", CountdownConfig.parseHexColor("#AABBCC") != null);
        check("解析 AABBCC", CountdownConfig.parseHexColor("AABBCC") != null);
        check("拒绝 ZZZZZZ", CountdownConfig.parseHexColor("ZZZZZZ") == null);
        check("拒绝 短串", CountdownConfig.parseHexColor("ABC") == null);
        check("拒绝 null", CountdownConfig.parseHexColor(null) == null);

        tmp.delete();
    }

    private static void testDurationParsing() {
        check("解析 25:00 = 1500", CountdownConfig.parseDuration("25:00") == 1500);
        check("解析 1:30:00 = 5400", CountdownConfig.parseDuration("1:30:00") == 5400);
        check("解析 90 = 90", CountdownConfig.parseDuration("90") == 90);
        check("解析 00:05 = 5", CountdownConfig.parseDuration("00:05") == 5);
        check("拒绝 空", CountdownConfig.parseDuration("") == -1);
        check("拒绝 乱码", CountdownConfig.parseDuration("abc") == -1);
        check("拒绝 null", CountdownConfig.parseDuration(null) == -1);
        check("格式化 1500 → 25:00", "25:00".equals(CountdownConfig.formatDuration(1500)));
        check("格式化 5400 → 1:30:00", "1:30:00".equals(CountdownConfig.formatDuration(5400)));
        check("格式化 5 → 00:05", "00:05".equals(CountdownConfig.formatDuration(5)));
    }

    // ===================== 会话状态机 =====================

    private static void testSessionStateMachine() {
        CountdownSession s = new CountdownSession(60);
        check("初始 IDLE", s.isIdle());
        check("初始读数 01:00", "01:00".equals(s.displayText(false, false)));
        check("初始剩余=60秒", s.remainingSecondsCeil() == 60);
        check("IDLE 无进度", s.progress() == 0.0);

        s.startOrResume();
        check("开始后 RUNNING", s.isRunning());
        s.pause();
        check("暂停后 PAUSED", s.isPaused());
        long p1 = s.remainingMs();
        sleep(60);
        check("暂停期间读数不走", s.remainingMs() == p1);
        s.startOrResume();
        check("继续后 RUNNING", s.isRunning());
        sleep(150);
        check("继续后读数下降", s.remainingMs() < p1);
        s.reset();
        check("归零后 IDLE", s.isIdle());
        check("归零后读数回到 01:00", s.remainingSecondsCeil() == 60);
        s.toggle();
        check("toggle 从 IDLE 进入运行", s.isRunning());
        s.toggle();
        check("toggle 从运行进入暂停", s.isPaused());
        s.reset();

        // 设置时长不改状态
        s.setDurationSeconds(90);
        check("改时长后读数变 01:30", s.displayText(false, false).equals("01:30"));

        // 手动结束
        final boolean[] finished = {false};
        CountdownSession s2 = new CountdownSession(30);
        s2.setFinishListener(skipped -> finished[0] = true);
        s2.finishNow();
        check("立即结束 → FINISHED", s2.isFinished());
        check("立即结束触发回调", finished[0]);
        check("结束后剩余为 0", s2.remainingSecondsCeil() == 0);
        check("结束后进度=1.0", s2.progress() >= 0.999);
        check("结束后读数 00:00", "00:00".equals(s2.displayText(false, false)));
    }

    private static void testSessionFinishInRealTime() {
        final boolean[] finished = {false};
        final List<Integer> lastSeconds = new ArrayList<>();
        CountdownSession s = new CountdownSession(1); // 1 秒
        s.setFinishListener(skipped -> finished[0] = true);
        s.setLastSecondListener(rem -> lastSeconds.add(rem));
        s.startOrResume();
        long deadline = System.currentTimeMillis() + 4000;
        while (!finished[0] && System.currentTimeMillis() < deadline) {
            s.tick();
            sleep(20);
        }
        check("1秒内到时(FINISHED)", s.isFinished());
        check("到时回调触发", finished[0]);
        check("读到最后一秒", !lastSeconds.isEmpty() && lastSeconds.get(0) == 1);
        // 到时后再 tick 不重复回调
        finished[0] = false;
        s.tick();
        check("到时后不重复回调", !finished[0]);

        // 最后 10 秒读秒回调
        final List<Integer> ticks = new ArrayList<>();
        CountdownSession s3 = new CountdownSession(3);
        s3.setLastSecondListener(rem -> ticks.add(rem));
        s3.startOrResume();
        long d3 = System.currentTimeMillis() + 5000;
        while (!s3.isFinished() && System.currentTimeMillis() < d3) {
            s3.tick();
            sleep(10);
        }
        check("3秒倒数集合=[3,2,1] 或等价递降", !ticks.isEmpty() && ticks.get(0) <= 3 && ticks.get(0) > 0
                && (ticks.size() < 2 || ticks.get(0) > ticks.get(ticks.size() - 1)));

        // 十分之一秒显示（只在最后 10 秒内切换）
        CountdownSession s4 = new CountdownSession(60);
        check(">10秒 显示 mm:ss", s4.displayText(false, true).contains(":"));
        CountdownSession s5 = new CountdownSession(9);
        String t = s5.displayText(false, true);
        check("≤10秒 显示 s.d（含小数点）", t.contains(".") && t.startsWith("9"));
        check("≤10秒 且关闭读秒时仍为 mm:ss", s5.displayText(false, false).equals("00:09"));
    }

    // ===================== 音频兜底（无自定义文件） =====================

    private static void testAlertFallback() {
        CountdownConfig c = new CountdownConfig();
        c.setAlertFile("");
        c.setAlertRepeat(1);
        CountdownAlert alert = new CountdownAlert(c);
        alert.play("自检"); // 未设置自定义音频 → 走系统提示音兜底，不应抛异常
        sleep(120);
        alert.stop();
        check("无自定义音频时播放不抛异常", true);
        String checkMsg = CountdownAlert.checkFile("");
        check("空路径提示使用系统提示音", checkMsg.contains("系统提示音"));
        String badMsg = CountdownAlert.checkFile("Z:/definitely/not/here.wav");
        check("不存在文件给出提示", badMsg.contains("不存在"));
        alert.shutdown();
    }

    // ===================== 打包禁令自检 =====================

    private static void testJarHasNoHostClasses() {
        File jar = new File("CountdownTimer.jar");
        if (!jar.exists()) {
            System.out.println("  (跳过 jar 检查：尚未打包)");
            return;
        }
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
            int hostClasses = 0;
            String first = null;
            String apiLevel = jf.getManifest() == null ? null
                    : jf.getManifest().getMainAttributes().getValue("Api-Level-Min");
            while (en.hasMoreElements()) {
                java.util.jar.JarEntry e = en.nextElement();
                String n = e.getName();
                if (n.endsWith(".class") && n.startsWith("com/randomnamepicker/")) {
                    hostClasses++;
                    if (first == null) {
                        first = n;
                    }
                }
            }
            check("jar 内无宿主类（打包禁令）", hostClasses == 0);
            if (hostClasses > 0) {
                System.out.println("    首个违规条目: " + first);
            }
            check("Manifest 声明 Api-Level-Min=2", "2".equals(apiLevel));
        } catch (Exception e) {
            fail("jar 检查异常: " + e);
        }
    }

    // ===================== UI 构建（有显示环境时） =====================

    private static void testUiConstructionIfPossible() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  (跳过 UI 构建检查：headless 环境)");
            return;
        }
        File tmp = File.createTempFile("countdown_selftest_ui", ".properties");
        tmp.delete();
        final CountdownConfig cfg = new CountdownConfig();
        cfg.setFilePath(tmp.getAbsolutePath());
        cfg.setAlertFile("");
        final CountdownSession session = new CountdownSession(60);
        final Throwable[] err = {null};
        SwingUtilities.invokeAndWait(() -> {
            try {
                CountdownBall ball = new CountdownBall(session, cfg, new CountdownBall.Actions() {
                    public void onPrimaryClick() {
                    }

                    public void onResetAndHide() {
                    }

                    public void onSwitchToFocus() {
                    }

                    public void onQuickDuration(int minutes) {
                    }

                    public void onOpenSettings() {
                    }

                    public void onHide() {
                    }

                    public void onMoved(java.awt.Point location) {
                    }
                });
                ball.setDiameter(60);
                check("子球直径设置生效", ball.getDiameter() == 60);
                ball.refresh();
                ball.dispose();

                CountdownFocus focus = new CountdownFocus(session, cfg, new CountdownFocus.Actions() {
                    public void onExit() {
                    }

                    public void onToggle() {
                    }
                });
                focus.setResultText("张三");
                focus.applyAppearance();
                focus.refresh();
                focus.hideQuiet();
                focus.dispose();
            } catch (Throwable t) {
                err[0] = t;
            }
        });
        if (err[0] != null) {
            fail("UI 构建异常: " + err[0]);
        } else {
            check("子球与全屏窗构建成功", true);
        }
        tmp.delete();
    }

    // ===================== 断言工具 =====================

    private static void check(String name, boolean ok) {
        if (ok) {
            PASSED.incrementAndGet();
            System.out.println("  [PASS] " + name);
        } else {
            fail(name);
        }
    }

    private static void fail(String name) {
        FAILURES.add(name);
        System.out.println("  [FAIL] " + name);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
