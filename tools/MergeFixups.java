import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 合并收尾工具（一次性开发工具，仓库内保留以便复核合并来源）。
 * <p>
 * 背景：本仓库所在环境的 PowerShell 在对 UTF-8 源文件做"读-改-写"时会偶发字符损坏
 * （实测出现 {@code super→uuper}、{@code Color→oolor} 这类单字符错乱）。因此合并过程的
 * 所有文本替换统一由 Java 完成（显式 {@link StandardCharsets#UTF_8}，字节精确），
 * PowerShell 只负责调用 javac/jar。
 * </p>
 * <p>
 * 职责：① 按 {@link #DOWN_JOBS} 从 {@code examples/plugin-countdown/}（倒计时插件原始源码）
 * 重新生成"干净"的 {@code DownSettingsDialog.java}；② 对合并目录内的文件套用合并所需的
 * 文案与行为补丁（分组菜单前缀、右键专属口径、配置节名称、自检入口等）。
 * </p>
 * <pre>
 * 运行： java -cp <build> MergeFixups
 * </pre>
 */
public final class MergeFixups {

    private MergeFixups() {
    }

    private static Path dir;

    private static final String CD = "examples/plugin-countdown/CountdownSettingsDialog.java";

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        dir = root.resolve("examples/plugin-timerball");
        if (!Files.isDirectory(dir)) {
            throw new IOException("找不到合并目录: " + dir);
        }
        int changed = 0;

        // ① 倒计时设置对话框：因 PowerShell 改坏过，从原始源码重新生成"干净"版本
        changed += regenerateDownSettingsDialog(root);

        // ② 倒计时设置对话框文案/接线
        changed += patch("DownSettingsDialog.java",
                "super(owner, \"倒计时设置\", ModalityType.APPLICATION_MODAL);",
                "super(owner, \"计时子球 · 倒计时设置\", ModalityType.APPLICATION_MODAL);");
        changed += patch("DownSettingsDialog.java",
                "\"倒计时设置\", JOptionPane.WARNING_MESSAGE);",
                "\"计时子球 · 倒计时设置\", JOptionPane.WARNING_MESSAGE);");

        // ③ 两颗子球右键菜单/日志文案统一到"计时子球"
        changed += patch("DownBall.java",
                "LogManager.log(\"倒计时插件-保存子球位置失败: \" + t, \"PLUGIN_LOAD_ERROR\");",
                "LogManager.log(\"计时子球-倒计时：保存子球位置失败: \" + t, \"PLUGIN_LOAD_ERROR\");");

        // ④ DownBall：合并口径的行为补丁
        changed += patchDownBall();

        // ⑤ UpTracker：合并所需的联动/只读接口补丁
        changed += patchUpTracker();

        // ⑥ UpBallConfig：补 summary()（插件初始化日志用）
        changed += patchUpBallConfig();

        // ④ 配置文件节名称（两个配置类各自的 store 注释）
        changed += patch("UpBallConfig.java",
                "p.store(out, \"CountUpUpBall plugin config\");",
                "p.store(out, \"TimerBall plugin - count-up section\");");
        changed += patch("TimerBallConfig.java",
                "p.store(out, \"CountdownTimer plugin config\");",
                "p.store(out, \"TimerBall plugin - countdown section\");");

        System.out.println("MergeFixups 完成，改动 " + changed + " 处");
    }

    /** 从倒计时插件原始源码重新生成 DownSettingsDialog.java（类名按合并规则替换）。 */
    private static int regenerateDownSettingsDialog(Path root) throws IOException {
        Path src = root.resolve(CD);
        if (!Files.isRegularFile(src)) {
            throw new IOException("找不到原始源码: " + src);
        }
        String text = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        String[][] rules = {
                {"CountdownSettingsDialog", "DownSettingsDialog"},
                {"CountdownController", "DownController"},
                {"CountdownSession", "DownSession"},
                {"CountdownConfig", "TimerBallConfig"},
                {"CountdownAlert", "TimerAlert"},
        };
        for (String[] rule : rules) {
            text = text.replace(rule[0], rule[1]);
        }
        Files.write(dir.resolve("DownSettingsDialog.java"), text.getBytes(StandardCharsets.UTF_8));
        System.out.println("已重新生成 DownSettingsDialog.java（源自 " + CD + "）");
        return 1;
    }

    /** 单处替换（找不到即报错，避免静默漏改）。 */
    private static int patch(String file, String from, String to) throws IOException {
        Path p = dir.resolve(file);
        if (!Files.isRegularFile(p)) {
            throw new IOException("找不到文件: " + p);
        }
        String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        if (!text.contains(from)) {
            throw new IOException("未找到待替换文本（file=" + file + "): " + from);
        }
        text = text.replace(from, to);
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        System.out.println("  已改 " + file + " : " + from);
        return 1;
    }

    /** 正则单处替换（DOTALL）；匹配不到即报错。 */
    private static int patchRe(String file, String regex, String to) throws IOException {
        Path p = dir.resolve(file);
        String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex,
                java.util.regex.Pattern.DOTALL);
        if (!pattern.matcher(text).find()) {
            throw new IOException("未匹配正则（file=" + file + "): " + regex);
        }
        text = pattern.matcher(text).replaceFirst(java.util.regex.Matcher.quoteReplacement(to));
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        System.out.println("  已改(正则) " + file);
        return 1;
    }

    /**
     * DownBall：合并口径补丁——<b>倒计时子球只提供右键菜单</b>（左键仅用于拖动窗口），
     * 并加入"自检"菜单项入口与 onSelfCheck 回调。
     */
    private static int patchDownBall() throws IOException {
        int n = 0;
        // (1) Actions 接口新增 onSelfCheck
        n += patch("DownBall.java",
                "        /** 收起（保留读数）。 */\n        void onHide();",
                "        /** 打开自检报告。 */\n        void onSelfCheck();\n\n"
                        + "        /** 收起（保留读数）。 */\n        void onHide();");
        // (2) 右键菜单新增"自检…"
        n += patch("DownBall.java",
                "        menu.add(settings);\n\n        JMenuItem hide = new JMenuItem(\"收起\");",
                "        menu.add(settings);\n\n"
                        + "        JMenuItem selfCheck = new JMenuItem(\"自检…\");\n"
                        + "        selfCheck.addActionListener(a -> {\n"
                        + "            if (actions != null) {\n"
                        + "                actions.onSelfCheck();\n"
                        + "            }\n"
                        + "        });\n"
                        + "        menu.add(selfCheck);\n\n"
                        + "        JMenuItem hide = new JMenuItem(\"收起\");");
        // (3) 左键单击/双击不再触发任何命令（只保留左键拖动）
        n += patchRe("DownBall.java",
                "            @Override\\s*\\n            public void mouseClicked\\(MouseEvent e\\) \\{.*?\\n            \\}\\n",
                "            @Override\n"
                        + "            public void mouseClicked(MouseEvent e) {\n"
                        + "                // 合并形态口径：倒计时子球不使用左键交互（左键只用于拖动窗口），\n"
                        + "                // 一切命令走右键菜单（与宿主悬浮球右键菜单同一套命令）。\n"
                        + "                // 因此这里刻意不实现\"单击=开始/暂停、双击=归零收起\"。\n"
                        + "            }\n");
        // (4) 移除单击延迟判定与相关字段（不再需要）
        n += patchRe("DownBall.java",
                "    private Timer singleTimer;\\s*\\n    private int multiClickIntervalMs = 500;\\n",
                "");
        n += patch("DownBall.java",
                "        readMultiClickInterval();\n        installMouse();",
                "        installMouse();");
        n += patchRe("DownBall.java",
                "    private void readMultiClickInterval\\(\\) \\{.*?\\r?\\n    \\}\\r?\\n\\r?\\n",
                "");
        // (4b) 删除单击延迟判定两个方法（合并后不再使用）
        n += patchRe("DownBall.java",
                "    private void scheduleSingleClick\\(\\) \\{.*?\\r?\\n    \\}\\r?\\n\\r?\\n"
                        + "    private void cancelScheduledSingle\\(\\) \\{.*?\\r?\\n    \\}\\r?\\n\\r?\\n",
                "");
        n += patchRe("DownBall.java",
                "        if \\(singleTimer != null\\) \\{\\s*\\n            singleTimer\\.stop\\(\\);\\s*\\n"
                        + "            singleTimer = null;\\s*\\n        \\}\\n",
                "");
        // (5) 类 javadoc：交互说明改为右键专属
        n += patch("DownBall.java",
                " *   <li>单击：开始 / 暂停 / 继续（就绪态与到时态单击=开始；按 {@code awt.multiClickInterval} 做\n"
                        + " *       \"延迟单击、双击取消\"判定，避免与双击冲突）；</li>\n"
                        + " *   <li>双击：归零并收起（回到就绪）；</li>\n"
                        + " *   <li>拖动：移动子球位置（未跟随悬浮球时由控制器持久化）；</li>\n"
                        + " *   <li>右键：快捷菜单（开始/暂停、归零、切全屏专注、快捷时长、设置、收起）。</li>",
                " *   <li><b>右键：快捷菜单</b>——合并形态下倒计时子球<b>只提供右键菜单</b>（开始/暂停、归零、\n"
                        + " *       切全屏专注、快捷时长、设置、自检、收起）；</li>\n"
                        + " *   <li>左键拖动：移动子球位置（未跟随悬浮球时由控制器持久化）；左键单击/双击不触发\n"
                        + " *       任何命令（左键交互只属于正计时子球）。</li>");
        return n;
    }

    /**
     * UpTracker：合并所需的补丁——可见性联动回调 + 只读接口 + 走 {@link BallLayout} 落位。
     */
    private static int patchUpTracker() throws IOException {
        int n = 0;
        n += patch("UpTracker.java",
                "    private boolean childVisible = false;\n    private boolean stopped = false;",
                "    private boolean childVisible = false;\n    private boolean stopped = false;\n\n"
                        + "    /** 子球可见性变化回调（合并形态：通知倒计时子球重新排布，保证两颗子球不重叠）。 */\n"
                        + "    private Runnable visibilityListener;");
        n += patch("UpTracker.java",
                "        timerBall.setHideRequest(this::hideChildQuiet);\n    }",
                "        timerBall.setHideRequest(this::hideChildQuiet);\n    }\n\n"
                        + "    /** 注册子球可见性变化回调（构造之后、start 之前调用）。 */\n"
                        + "    public void setVisibilityListener(Runnable listener) {\n"
                        + "        this.visibilityListener = listener;\n"
                        + "    }\n\n"
                        + "    private void notifyVisibilityChanged() {\n"
                        + "        Runnable l = visibilityListener;\n"
                        + "        if (l == null) {\n"
                        + "            return;\n"
                        + "        }\n"
                        + "        try {\n"
                        + "            l.run();\n"
                        + "        } catch (Throwable t) {\n"
                        + "            LogManager.log(\"计时子球·正计时-可见性回调异常: \" + t, \"PLUGIN_LOAD_ERROR\");\n"
                        + "        }\n"
                        + "    }");
        n += patch("UpTracker.java",
                "        timerBall.toFront();\n    }",
                "        timerBall.toFront();\n        notifyVisibilityChanged();\n    }");
        n += patch("UpTracker.java",
                "            LogManager.log(\"计时子球·正计时-收回\", \"TIMER_BALL_HIDE\");\n        }\n    }",
                "            LogManager.log(\"计时子球·正计时-收回\", \"TIMER_BALL_HIDE\");\n"
                        + "            notifyVisibilityChanged();\n        }\n    }");
        n += patch("UpTracker.java",
                "            LogManager.log(\"计时子球·正计时-收回(双击归零后)\", \"TIMER_BALL_HIDE\");\n        }\n    }",
                "            LogManager.log(\"计时子球·正计时-收回(双击归零后)\", \"TIMER_BALL_HIDE\");\n"
                        + "            notifyVisibilityChanged();\n        }\n    }");
        // 落位改走 BallLayout（保持与原算法一致的候选顺序，仅加"与其它子球不重叠"约束）
        n += patchRe("UpTracker.java",
                "    private void placeRelative\\(FloatingBall anchor\\) \\{.*?\\n    \\}\\n",
                "    /**\n"
                        + "     * 冒出方向（§0 规则）：悬浮球位于所在屏幕下半部分→子球在其正上方；上半部分→正下方。\n"
                        + "     * <p>\n"
                        + "     * 合并形态（计时子球）：委托 {@link BallLayout#placeAnchored}，按\"与其它子球不重叠\"的\n"
                        + "     * 候选位搜索落位；正计时侧没有同源兄弟（{@link #occupiedRects()} 恒为空），单子球场景\n"
                        + "     * 结果与原算法一致。\n"
                        + "     * </p>\n"
                        + "     */\n"
                        + "    private void placeRelative(FloatingBall anchor) {\n"
                        + "        Rectangle ball;\n"
                        + "        Rectangle screen;\n"
                        + "        try {\n"
                        + "            ball = new Rectangle(anchor.getLocationOnScreen(), anchor.getSize());\n"
                        + "            screen = anchor.getGraphicsConfiguration().getBounds();\n"
                        + "        } catch (Throwable t) {\n"
                        + "            return; // 尚未 displayable / 已销毁，下个 tick 重试\n"
                        + "        }\n"
                        + "        int d = timerBall.getDiameter();\n"
                        + "        int gap = config.getGapPx();\n"
                        + "        Point p = BallLayout.placeAnchored(ball, d, gap, screen, occupiedRects());\n"
                        + "        if (p != null) {\n"
                        + "            timerBall.setLocation(p.x, p.y);\n"
                        + "        }\n"
                        + "    }\n\n"
                        + "    /** 需要避让的其它子球矩形（正计时侧无同源兄弟，恒为空；倒计时侧由插件提供正计时矩形）。 */\n"
                        + "    protected Rectangle[] occupiedRects() {\n"
                        + "        return new Rectangle[0];\n"
                        + "    }\n\n"
                        + "    /** 正计时子球当前是否可见。 */\n"
                        + "    public boolean isChildVisible() {\n"
                        + "        return childVisible;\n"
                        + "    }\n\n"
                        + "    /** 正计时子球当前屏幕矩形（不可见返回 null）。 */\n"
                        + "    public Rectangle getChildBounds() {\n"
                        + "        if (!childVisible) {\n"
                        + "            return null;\n"
                        + "        }\n"
                        + "        try {\n"
                        + "            Point p = timerBall.getLocation();\n"
                        + "            return new Rectangle(p.x, p.y, timerBall.getWidth(), timerBall.getHeight());\n"
                        + "        } catch (Throwable t) {\n"
                        + "            return null;\n"
                        + "        }\n"
                        + "    }\n\n"
                        + "    /** 正计时子球（供控制面板读取/操作计时状态）。 */\n"
                        + "    public UpBall getBall() {\n"
                        + "        return timerBall;\n"
                        + "    }\n\n"
                        + "    /** 正计时子球当前直径（px）。 */\n"
                        + "    public int getDiameter() {\n"
                        + "        return timerBall.getDiameter();\n"
                        + "    }\n");
        n += patch("UpTracker.java",
                "import java.awt.Component;\nimport java.awt.Rectangle;",
                "import java.awt.Component;\nimport java.awt.Point;\nimport java.awt.Rectangle;");
        return n;
    }

    /** UpBallConfig：补一个 summary()（插件初始化日志与控制面板复用；倒计时侧同类已有）。 */
    private static int patchUpBallConfig() throws IOException {
        return patch("UpBallConfig.java",
                "    /** 恢复全部默认（内存态；持久化由调用方决定）。 */",
                "    /** 配置摘要（写日志用）。 */\n"
                        + "    public String summary() {\n"
                        + "        return \"尺寸=\" + (isFixed() ? getFixedDiameter() + \"px\" : \"自动(×0.55)\")\n"
                        + "                + \" 间距=\" + getGapPx() + \"px\"\n"
                        + "                + \" 色=\" + toHex(getColorReady()) + \"/\" + toHex(getColorRunning())\n"
                        + "                + \"/\" + toHex(getColorPaused());\n"
                        + "    }\n\n"
                        + "    /** 恢复全部默认（内存态；持久化由调用方决定）。 */");
    }
}
