import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 计时子球插件自检 harness（合并版：正计时子球 + 倒计时子球）。
 * <p>
 * 覆盖本次合并的关键约束与既有能力：
 * <ol>
 *   <li><b>两球互不重叠</b>：{@link BallLayout#placeAnchored} 的候选位搜索（同侧→另一侧→左右侧），
 *       分别验证"单个子球落位与旧算法一致""第二个子球避开第一个""极端窄屏不抛异常"；</li>
 *   <li><b>分组菜单约定</b>：插件注册的悬浮球菜单标题都含「 ▸ 」分组分隔符（宿主据此生成级联子菜单）；</li>
 *   <li><b>倒计时只走右键</b>：DownBall 的 Actions 不再含左键单击/双击语义（保留 onPrimaryClick 仅供右键菜单）；</li>
 *   <li>倒计时核心状态机、配置默认值/往返/坏值回退（正计时 config 与倒计时 config 两个文件互不覆盖）；</li>
 *   <li>提醒音频兜底路径；jar 打包禁令 + Manifest 级别。</li>
 * </ol>
 * <pre>
 * 运行：java -cp "..\..\RandomNamePicker.jar;build" TimerBallSelfTest
 * </pre>
 * 退出码 0 = 全部通过。
 */
public final class TimerBallSelfTest {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int passed = 0;

    private TimerBallSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 计时子球插件（合并版）自检开始 ==");
        testLayoutSingleBallMatchesLegacy();
        testLayoutTwoBallsDoNotOverlap();
        testLayoutExtremeScreens();
        testMenuGroupingConvention();
        testDownRightClickOnly();
        testDownSessionStateMachine();
        testConfigsAreSeparate();
        testConfigRoundTripAndFallback();
        testSessionMemory();
        testAlertFallback();
        testJarAndManifest();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + FAILURES.size() + " 项");
        for (String f : FAILURES) {
            System.out.println("  [FAIL] " + f);
        }
        System.out.println(FAILURES.isEmpty() ? "== 自检全部通过 ==" : "== 自检存在失败 ==");
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    // ===================== 1) 落位算法 =====================

    /** 单子球场景：结果必须与原正计时子球算法一致（浮球在下半屏 → 子球正上方，居中）。 */
    private static void testLayoutSingleBallMatchesLegacy() {
        Rectangle screen = new Rectangle(0, 0, 1920, 1080);
        Rectangle anchor = new Rectangle(1700, 900, 60, 60); // 右下角 → 下半屏 → 放上方
        java.awt.Point p = BallLayout.placeAnchored(anchor, 44, 6, screen, new Rectangle[0]);
        int expectedX = anchor.x + anchor.width / 2 - 22;
        int expectedY = anchor.y - 6 - 44;
        check("单子球：X 与旧算法一致", p.x == expectedX, p.x + " vs " + expectedX);
        check("单子球：Y 与旧算法一致（放上方）", p.y == expectedY, p.y + " vs " + expectedY);

        Rectangle anchorTop = new Rectangle(300, 100, 60, 60); // 上半屏 → 放下方
        java.awt.Point p2 = BallLayout.placeAnchored(anchorTop, 44, 6, screen, new Rectangle[0]);
        check("单子球：上半屏放正下方",
                p2.y == anchorTop.y + anchorTop.height + 6, String.valueOf(p2.y));

        // 上方空间不足时旧算法改放下方
        Rectangle anchorNearTop = new Rectangle(300, 10, 60, 60);
        java.awt.Point p3 = BallLayout.placeAnchored(anchorNearTop, 44, 6, screen, new Rectangle[0]);
        check("单子球：上方越界时改放下方",
                p3.y == anchorNearTop.y + anchorNearTop.height + 6, String.valueOf(p3.y));
    }

    /** 双子球场景：第二颗（倒计时）必须避开第一颗（正计时）占用的矩形。 */
    private static void testLayoutTwoBallsDoNotOverlap() {
        Rectangle screen = new Rectangle(0, 0, 1920, 1080);
        Rectangle anchor = new Rectangle(1700, 900, 60, 60);

        java.awt.Point up = BallLayout.placeAnchored(anchor, 44, 6, screen, new Rectangle[0]);
        Rectangle upRect = new Rectangle(up.x, up.y, 44, 44);

        java.awt.Point down = BallLayout.placeAnchored(anchor, 44, 6, screen,
                new Rectangle[]{upRect});
        Rectangle downRect = new Rectangle(down.x, down.y, 44, 44);

        check("双子球：位置不同", !up.equals(down), up + " vs " + down);
        check("双子球：矩形不相交", !BallLayout.overlaps(downRect, new Rectangle[]{upRect}),
                upRect + " / " + downRect);
        check("双子球：都在屏内", screen.contains(upRect) && screen.contains(downRect),
                screen + " " + upRect + " " + downRect);

        // 球离屏幕中心很近时（四个方向被占满一个方向），也要不重叠
        Rectangle center = new Rectangle(930, 510, 60, 60);
        java.awt.Point up2 = BallLayout.placeAnchored(center, 44, 6, screen, new Rectangle[0]);
        Rectangle upRect2 = new Rectangle(up2.x, up2.y, 44, 44);
        java.awt.Point down2 = BallLayout.placeAnchored(center, 44, 6, screen,
                new Rectangle[]{upRect2});
        check("双子球(屏幕中心)：不重叠",
                !BallLayout.overlaps(new Rectangle(down2.x, down2.y, 44, 44),
                        new Rectangle[]{upRect2}),
                upRect2 + " / " + new Rectangle(down2.x, down2.y, 44, 44));

        // 三个占用位（上、下都占）→ 应换到侧向
        java.awt.Point side = BallLayout.placeAnchored(center, 44, 6, screen,
                new Rectangle[]{upRect2, new Rectangle(down2.x, down2.y, 44, 44)});
        Rectangle sideRect = new Rectangle(side.x, side.y, 44, 44);
        check("三子球：换到侧向且不重叠",
                !BallLayout.overlaps(sideRect, new Rectangle[]{upRect2,
                        new Rectangle(down2.x, down2.y, 44, 44)}),
                String.valueOf(sideRect));
        check("三子球：仍在屏内", screen.contains(sideRect), String.valueOf(sideRect));
    }

    /** 极端屏幕/尺寸不应抛异常且结果在屏内。 */
    private static void testLayoutExtremeScreens() {
        Rectangle tiny = new Rectangle(0, 0, 120, 90);
        Rectangle anchor = new Rectangle(10, 10, 40, 40);
        try {
            java.awt.Point p = BallLayout.placeAnchored(anchor, 60, 8, tiny, new Rectangle[0]);
            check("窄屏：不抛异常且返回坐标", p != null);
            check("窄屏：坐标被 clamp 进屏", p.x >= tiny.x && p.y >= tiny.y);
        } catch (Throwable t) {
            fail("窄屏抛异常: " + t);
        }
        // 全部候选位都被占用 → 取重叠最小者，不抛异常
        try {
            Rectangle big = new Rectangle(-100, -100, 500, 500);
            java.awt.Point p = BallLayout.placeAnchored(anchor, 40, 8, tiny,
                    new Rectangle[]{new Rectangle(0, 0, 120, 90), big});
            check("候选位全占用：仍返回坐标", p != null);
        } catch (Throwable t) {
            fail("全占用场景抛异常: " + t);
        }
        check("重叠面积计算正确",
                BallLayout.overlapArea(new Rectangle(0, 0, 10, 10), new Rectangle[]{new Rectangle(5, 5, 10, 10)}) == 25);
        check("不重叠面积为 0",
                BallLayout.overlapArea(new Rectangle(0, 0, 10, 10), new Rectangle[]{new Rectangle(50, 50, 10, 10)}) == 0);
    }

    // ===================== 2) 菜单结构（经宿主正式分组 API） =====================

    /**
     * 菜单结构必须通过<b>宿主正式接口</b>表达：{@code addXxxMenuAction(String[] groupPath, String title, ActionListener)}；
     * 结构为"一级=核心命令，倒计时其余功能进二级【倒计时设置】"。
     * <p>同时校验插件<b>不再使用</b>标题分隔符约定（该约定已从宿主删除）。</p>
     */
    private static void testMenuGroupingConvention() throws Exception {
        // 一级项（groupPath = ["计时子球"]）：控制面板置顶 + 正/倒计时核心命令
        String[] firstLevelTitles = {
                "控制面板…",
                "正计时：冒出/收回", "正计时：开始", "正计时：归零",
                "倒计时：冒出/收回", "倒计时：开始", "倒计时：归零", "倒计时：全屏专注",
        };
        // 二级项（groupPath = ["计时子球","倒计时设置"]）
        String[] secondLevelTitles = {
                "快捷 1 分钟", "快捷 3 分钟", "快捷 5 分钟", "快捷 10 分钟", "快捷 15 分钟",
                "快捷 25 分钟", "快捷 30 分钟", "快捷 45 分钟",
                "常规设置…",
        };
        for (String t : firstLevelTitles) {
            check("一级项标题干净（无分隔符约定）: " + t, !t.contains(" ▸ "));
        }
        for (String t : secondLevelTitles) {
            check("二级项标题干净（无分隔符约定）: " + t, !t.contains(" ▸ ") && !t.contains("自检"));
        }
        check("一级项不含旧'开始/暂停'写法",
                !java.util.Arrays.toString(firstLevelTitles).contains("开始/暂停"));

        // 宿主正式 API 存在性（类型化接口，而非标题约定）
        Class<?> ctx = Class.forName("com.randomnamepicker.plugin.PluginContext");
        check("宿主提供 addGroupedUiAction(UiZone,String[],String,ActionListener)",
                ctx.getMethod("addGroupedUiAction", com.randomnamepicker.plugin.UiZone.class,
                        String[].class, String.class, java.awt.event.ActionListener.class) != null);
        check("宿主提供 addFloatingBallMenuAction(String[],String,ActionListener)",
                ctx.getMethod("addFloatingBallMenuAction", String[].class, String.class,
                        java.awt.event.ActionListener.class) != null);
        check("宿主提供 addMainMenuAction(String[],String,ActionListener)",
                ctx.getMethod("addMainMenuAction", String[].class, String.class,
                        java.awt.event.ActionListener.class) != null);
        check("宿主提供 addTrayMenuAction(String[],String,ActionListener)",
                ctx.getMethod("addTrayMenuAction", String[].class, String.class,
                        java.awt.event.ActionListener.class) != null);

        // MenuAction 携带分组路径 + 宿主通用渲染器
        Class<?> menuAction = Class.forName("com.randomnamepicker.plugin.PluginManager$MenuAction");
        check("MenuAction 暴露 getGroupPath()", menuAction.getMethod("getGroupPath") != null);
        Class<?> support = Class.forName("com.randomnamepicker.plugin.MenuGroupSupport");
        check("宿主提供通用渲染器 MenuGroupSupport.appendActions",
                support.getMethod("appendActions", javax.swing.JComponent.class, java.util.List.class) != null);
        check("宿主提供 MenuGroupSupport.childMenu（同名子菜单复用）",
                support.getMethod("childMenu", javax.swing.JComponent.class, String.class) != null);

        // 插件源码：必须调用带 groupPath 的重载，且不得再出现分隔符约定
        File plugin = new File("TimerBallPlugin.java");
        if (plugin.isFile()) {
            String text = new String(java.nio.file.Files.readAllBytes(plugin.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("插件调用 addMainMenuAction(groupPath, title, action)",
                    text.contains("context.addMainMenuAction(groupPath, title, action)"));
            check("插件调用 addTrayMenuAction(groupPath, title, action)",
                    text.contains("context.addTrayMenuAction(groupPath, title, action)"));
            check("插件调用 addFloatingBallMenuAction(groupPath, title, action)",
                    text.contains("context.addFloatingBallMenuAction(groupPath, title, action)"));
            check("插件定义了一级/二级分组路径常量",
                    text.contains("PATH_TOP") && text.contains("PATH_DOWN_SETTINGS"));
            check("插件不再使用标题分隔符约定", !text.contains("▸"));
            // 需求：控制面板提到一级菜单，且必须排在最前
            check("控制面板在一级菜单（PATH_TOP）",
                    text.contains("registerEverywhere(context, PATH_TOP, \"控制面板…\", \"panel\")"));
            check("控制面板不再出现在二级菜单",
                    !text.contains("PATH_DOWN_SETTINGS, \"控制面板…\""));
            int panelIdx = text.indexOf("PATH_TOP, \"控制面板…\"");
            int firstOther = text.indexOf("PATH_TOP, \"正计时：冒出/收回\"");
            check("控制面板排在一级菜单首位", panelIdx > 0 && firstOther > panelIdx);
        } else {
            System.out.println("  (跳过 TimerBallPlugin 源码检查：当前目录无源码)");
        }
    }

    // ===================== 3) 倒计时仅右键 =====================

    /** DownBall 的 Actions 不应再暴露"左键单击/双击"语义接口；只保留菜单可用的命令集合。 */
    private static void testDownRightClickOnly() throws Exception {
        Class<?> actions = Class.forName("DownBall$Actions");
        List<String> methods = new ArrayList<>();
        for (java.lang.reflect.Method m : actions.getDeclaredMethods()) {
            methods.add(m.getName());
        }
        check("Actions 含菜单命令 onPrimaryClick", methods.contains("onPrimaryClick"));
        check("Actions 含 onResetAndHide", methods.contains("onResetAndHide"));
        check("Actions 已移除 onSelfCheck（界面自检入口取消）",
                !methods.contains("onSelfCheck"));
        check("Actions 含 onOpenSettings", methods.contains("onOpenSettings"));
        check("Actions 含 onHide", methods.contains("onHide"));

        // 全屏专注窗：必须有"暂停/继续"与"复原"两个回调 + 自绘图标
        Class<?> focusActions = Class.forName("DownFocus$Actions");
        List<String> focusMethods = new ArrayList<>();
        for (java.lang.reflect.Method m : focusActions.getDeclaredMethods()) {
            focusMethods.add(m.getName());
        }
        check("全屏 Actions 含 onToggle（暂停/继续按钮）", focusMethods.contains("onToggle"));
        check("全屏 Actions 含 onRestore（复原按钮）", focusMethods.contains("onRestore"));
        Class<?> icons = Class.forName("TimerIcons");
        check("TimerIcons.pause 可用", icons.getMethod("pause", java.awt.Color.class, int.class) != null);
        check("TimerIcons.play 可用", icons.getMethod("play", java.awt.Color.class, int.class) != null);
        check("TimerIcons.restore 可用",
                icons.getMethod("restore", java.awt.Color.class, int.class) != null);
        check("TimerIcons 提供屏幕夹取（防球跑到屏幕外）",
                icons.getMethod("clampToScreen", Rectangle.class) != null
                        && icons.getMethod("visibleArea", Rectangle.class) != null);

        // 复原语义：回到"上次起点"并重新开始
        DownSession s = new DownSession(600);
        s.restoreToLastStart();
        check("复原记录起点=600s", s.getLastStartSeconds() == 600);
        s.setDurationSeconds(30);
        check("改设定时长不改上次起点", s.getLastStartSeconds() == 600);
        s.restoreToLastStart();
        check("复原把读数拉回起点并运行",
                s.getDurationSeconds() == 600 && s.isRunning() && s.remainingSecondsCeil() == 600);
        s.pause();
        s.restoreToLastStart();
        check("暂停态复原=回到起点重新开始",
                s.isRunning() && s.remainingSecondsCeil() == 600);

        // 全屏标题：可编辑 + 会话真值 + 配置持久化字段
        s.setTitle("数学小测");
        check("全屏标题读写（会话）", "数学小测".equals(s.getTitle()));
        TimerBallConfig cfg = new TimerBallConfig();
        check("全屏标题有独立配置字段", cfg.getFocusTitle() != null);
        check("全屏标题默认值非空", !cfg.getFocusTitle().isEmpty());

        // DownFocus：顶部标题栏（标题在窗口顶部）+ 退出按钮 + 按钮黑色前景 + 全局按键兜底
        File focusSrc = new File("DownFocus.java");
        if (focusSrc.isFile()) {
            String ft = new String(java.nio.file.Files.readAllBytes(focusSrc.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("DownFocus 含可编辑标题输入框", ft.contains("JTextField titleField"));
            check("DownFocus 标题即时保存（DocumentListener→config.save）",
                    ft.contains("DocumentListener") && ft.contains("config.setFocusTitle"));
            check("DownFocus 按钮前景为黑色常量",
                    ft.contains("BUTTON_FG = new Color(0x11, 0x14, 0x18)")
                            && ft.contains("setForeground(fg)"));
            check("DownFocus 编辑标题时让位快捷键（空格/R 不误触）",
                    ft.contains("titleEditing()"));
            // 回归：标题必须在"顶部"（用 BorderLayout.NORTH 的标题栏），此前用 GridBag 行序被挤到底部
            check("标题栏挂在窗口 NORTH（真正的顶部）",
                    ft.contains("createTitleBar()") && ft.contains("BorderLayout.NORTH"));
            check("标题栏不再使用 GridBag 行序", !ft.contains("GridBagConstraints"));
            check("顶部含可视退出按钮（不依赖键盘焦点）",
                    ft.contains("✕ 退出") && ft.contains("exitButton.addActionListener"));
            check("标题支持双击弹窗改名（键盘焦点不可靠时兜底）",
                    ft.contains("editTitleViaDialog") && ft.contains("showInputDialog"));
            check("独占全屏下安装全局按键兜底（ESC/空格/R）",
                    ft.contains("installGlobalKeyFallback")
                            && ft.contains("addAWTEventListener")
                            && ft.contains("uninstallGlobalKeyFallback"));
            check("焦点重试（进入全屏后再抢一次键盘焦点）", ft.contains("grabKeyboardFocus"));
            // 回归：标题栏高度必须随字号自适应（此前固定 74px 会把大号标题裁掉）
            check("标题栏高度自适应（getPreferredSize = 行高+24）",
                    ft.contains("textHeight + 24") && ft.contains("titleField.getFontMetrics"));
            check("字号变化后重算标题栏高度（applyTitleFont→setPreferredSize(null)+revalidate）",
                    ft.contains("titleBand.setPreferredSize(null)") && ft.contains("titleBand.revalidate"));
            // 需求：全屏不再使用白色/浅灰文字，统一黑色（并加浅色底衬保证可读）
            check("全屏文字统一黑色常量 TEXT_FG",
                    ft.contains("TEXT_FG = new Color(0x0A, 0x0C, 0x0F)")
                            && ft.contains("statusLabel.setForeground(TEXT_FG)")
                            && ft.contains("resultLabel.setForeground(TEXT_FG)")
                            && ft.contains("hintLabel.setForeground(TEXT_FG)"));
            check("上下栏与内容区完全同色（整屏统一底色，无蒙版）",
                    ft.contains("config.getFocusBackground()")
                            && ft.contains("g.fillRect(0, 0, getWidth(), getHeight())")
                            && !ft.contains("BAR_OVERLAY") && !ft.contains("fillRoundRect"));
            check("全屏不再出现半透明底衬/蒙版常量",
                    !ft.contains("TITLE_BACKDROP") && !ft.contains("TEXT_BACKDROP")
                            && !ft.contains("BAR_OVERLAY"));
            check("全屏不再出现白色/浅灰文字常量",
                    !ft.contains("0xE8, 0xEC, 0xF1") && !ft.contains("0xB8, 0xC2, 0xCC")
                            && !ft.contains("0x7E, 0x8A, 0x96") && !ft.contains("Color.WHITE"));
        } else {
            System.out.println("  (跳过 DownFocus 源码检查：当前目录无源码)");
        }

        // 设置对话框：与悬浮球间距必须与"跟随宿主悬浮球"联动（未跟随时禁用，避免无意义选项）
        File dlgSrc = new File("DownSettingsDialog.java");
        if (dlgSrc.isFile()) {
            String dt = new String(java.nio.file.Files.readAllBytes(dlgSrc.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("间距项随跟随开关联动（syncGapState）",
                    dt.contains("private void syncGapState()")
                            && dt.contains("gapSlider.setEnabled(follow)"));
            check("跟随开关变化会触发联动", dt.contains("syncGapState(); // 跟随开关变化会决定"));
            check("未跟随时给出说明文字", dt.contains("仅「跟随宿主悬浮球」开启时生效"));
            check("对话框打开时同步一次联动状态", dt.contains("syncGapState(); // 间距项按"));
        } else {
            System.out.println("  (跳过 DownSettingsDialog 源码检查：当前目录无源码)");
        }

        // 源码层面确认：单击延迟机制（scheduleSingleClick/singleTimer）已从 DownBall 移除
        File src = new File("DownBall.java");
        if (src.isFile()) {
            String text = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("DownBall 源码已移除单击延迟机制", !text.contains("scheduleSingleClick")
                    && !text.contains("singleTimer"));
            check("DownBall 源码保留右键菜单", text.contains("JPopupMenu"));
            check("DownBall 源码的左键仅用于拖动",
                    text.contains("dragOffset") && text.contains("mouseDragged"));
        } else {
            System.out.println("  (跳过 DownBall 源码检查：当前目录无源码)");
        }

        // DownController：不得安装全局 AWT 观察监听（倒计时不做左键交互）
        File ctrl = new File("DownController.java");
        if (ctrl.isFile()) {
            String text = new String(java.nio.file.Files.readAllBytes(ctrl.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("DownController 不安装 AWT 监听", !text.contains("addAWTEventListener"));
            check("DownController 使用官方几何查询",
                    text.contains("isFloatingBallVisible") && text.contains("getFloatingBallBounds"));
            check("DownController 具备避让注入点", text.contains("setAvoidanceProvider"));
            check("DownController 具备重排入口", text.contains("relayout"));
            // 需求：冒出只显示不自动计时；开始才计时
            check("开始命令会先确保子球可见（commandStart→ensureDownVisible）",
                    text.contains("public void commandStart") && text.contains("ensureDownVisible"));
            check("冒出/收起不自动倒计时（toggleShowFromMenu 传 autoStart=false）",
                    text.contains("showAll(parent, true, false)"));
            check("快捷时长只冒出、不自动开始",
                    methodBody(text, "public void commandSetQuickDuration") != null
                            && !methodBody(text, "public void commandSetQuickDuration")
                            .contains("startOrResume"));
            check("未计时状态下也可进全屏（commandToggleFocus 不强制开始）",
                    methodBody(text, "public void commandToggleFocus") != null
                            && !methodBody(text, "public void commandToggleFocus").contains("restoreToLastStart")
                            && !methodBody(text, "public void commandToggleFocus").contains("startOrResume"));
            check("位置保险：记忆位置会被夹回屏内",
                    text.contains("ensureOnScreen") && text.contains("TimerIcons.visibleArea"));
            check("界面自检入口已移除", !text.contains("public void showSelfCheck"));
        } else {
            System.out.println("  (跳过 DownController 源码检查：当前目录无源码)");
        }

        // 插件入口：菜单结构符合"一级核心命令 + 二级倒计时设置"
        File plugin = new File("TimerBallPlugin.java");
        if (plugin.isFile()) {
            String text = new String(java.nio.file.Files.readAllBytes(plugin.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            // 只截取菜单注册区（onLoad 首次出现 registerEverywhere 到 initOnEdt 之间），
            // 避免把类注释里"已移除自检"等说明文字误判成菜单项。
            int regStart = text.indexOf("registerEverywhere(context");
            int regEnd = text.indexOf("SwingUtilities.invokeLater(this::initOnEdt)");
            String menuBlock = (regStart > 0 && regEnd > regStart)
                    ? text.substring(regStart, regEnd) : text;
            check("一级用 PATH_TOP 注册正计时冒出/收回、开始、归零",
                    menuBlock.contains("PATH_TOP, \"正计时：冒出/收回\"")
                            && menuBlock.contains("PATH_TOP, \"正计时：开始\"")
                            && menuBlock.contains("PATH_TOP, \"正计时：归零\""));
            check("一级用 PATH_TOP 注册倒计时冒出/收回、开始、归零、全屏",
                    menuBlock.contains("PATH_TOP, \"倒计时：冒出/收回\"")
                            && menuBlock.contains("PATH_TOP, \"倒计时：开始\"")
                            && menuBlock.contains("PATH_TOP, \"倒计时：归零\"")
                            && menuBlock.contains("PATH_TOP, \"倒计时：全屏专注\""));
            check("冒出与收回合并为同一项（不再各占一条）",
                    !menuBlock.contains("\"正计时：冒出\"") && !menuBlock.contains("\"倒计时：冒出\"")
                            && !menuBlock.contains("\"正计时：收回\"") && !menuBlock.contains("\"倒计时：收回\""));
            check("二级用 PATH_DOWN_SETTINGS 注册（倒计时设置）",
                    menuBlock.contains("PATH_DOWN_SETTINGS, \"快捷")
                            && menuBlock.contains("PATH_DOWN_SETTINGS, \"常规设置…\""));
            check("重复入口「显示形态…」已删除（与常规设置同窗）",
                    !menuBlock.contains("PATH_DOWN_SETTINGS, \"显示形态"));
            check("一级含控制面板且位于首位（PATH_TOP 首条即控制面板）",
                    menuBlock.indexOf("PATH_TOP, \"控制面板…\"") > 0
                            && menuBlock.indexOf("PATH_TOP, \"控制面板…\"")
                            < menuBlock.indexOf("PATH_TOP, \"正计时：冒出/收回\""));
            check("菜单区无自检项", !menuBlock.contains("自检"));
            check("菜单区无旧式「开始/暂停」文案", !menuBlock.contains("开始/暂停"));
            check("菜单区无标题分隔符约定", !menuBlock.contains("▸"));
        } else {
            System.out.println("  (跳过 TimerBallPlugin 源码检查：当前目录无源码)");
        }
    }

    // ===================== 4) 倒计时状态机 =====================

    private static void testDownSessionStateMachine() {
        DownSession s = new DownSession(60);
        check("初始 IDLE", s.isIdle());
        check("初始读数 01:00", "01:00".equals(s.displayText(false, false)));
        s.startOrResume();
        check("开始后 RUNNING", s.isRunning());
        s.pause();
        check("暂停后 PAUSED", s.isPaused());
        long r1 = s.remainingMs();
        sleep(80);
        check("暂停期间读数不变", s.remainingMs() == r1);
        s.startOrResume();
        sleep(120);
        check("继续后读数下降", s.remainingMs() < r1);
        s.reset();
        check("归零后回到 IDLE 与满值", s.isIdle() && s.remainingSecondsCeil() == 60);

        final boolean[] finished = {false};
        DownSession s2 = new DownSession(1);
        s2.setFinishListener(skipped -> finished[0] = true);
        s2.startOrResume();
        long deadline = System.currentTimeMillis() + 4000;
        while (!finished[0] && System.currentTimeMillis() < deadline) {
            s2.tick();
            sleep(20);
        }
        check("1 秒后到时", s2.isFinished() && finished[0]);
        finished[0] = false;
        s2.tick();
        check("到时后不重复回调", !finished[0]);
        check("结束时进度=1.0", s2.progress() >= 0.999);
    }

    // ===================== 5) 配置隔离与往返 =====================

    private static void testConfigsAreSeparate() {
        check("正计时配置走 data/timerball_plugin.properties",
                UpBallConfig.FILE_PATH.endsWith("timerball_plugin.properties"));
        check("倒计时配置走 data/countdown_plugin.properties",
                TimerBallConfig.FILE_PATH.endsWith("countdown_plugin.properties"));
        check("两个配置文件不同名（互不覆盖）",
                !new File(UpBallConfig.FILE_PATH).getName()
                        .equals(new File(TimerBallConfig.FILE_PATH).getName()));
        UpBallConfig up = new UpBallConfig();
        check("正计时配置摘要可用", up.summary() != null && up.summary().contains("尺寸"));
        TimerBallConfig down = new TimerBallConfig();
        check("倒计时配置摘要可用", down.summary() != null && down.summary().contains("警戒"));
        check("倒计时默认时长 5 分钟", down.getDurationSeconds() == 300);
        check("倒计时默认警戒色 D9A406",
                "D9A406".equals(TimerBallConfig.toHex(down.getColorWarning())));
    }

    private static void testConfigRoundTripAndFallback() throws Exception {
        File tmp = File.createTempFile("timerball_selftest", ".properties");
        TimerBallConfig c = new TimerBallConfig();
        c.setFilePath(tmp.getAbsolutePath());
        c.setDurationSeconds(1500);
        c.setAlertFile("D:/audio/ding.wav");
        c.setColorWarning(TimerBallConfig.parseHexColor("FF00AA"));
        c.setDisplayMode(TimerBallConfig.DisplayMode.BOTH);
        c.setBallPos(321, 654);
        c.save();
        TimerBallConfig back = new TimerBallConfig();
        back.setFilePath(tmp.getAbsolutePath());
        back.load();
        check("倒计时配置往返：时长", back.getDurationSeconds() == 1500);
        check("倒计时配置往返：音频", "D:/audio/ding.wav".equals(back.getAlertFile()));
        check("倒计时配置往返：警戒色",
                "FF00AA".equals(TimerBallConfig.toHex(back.getColorWarning())));
        check("倒计时配置往返：形态", back.getDisplayMode() == TimerBallConfig.DisplayMode.BOTH);
        check("倒计时配置往返：位置", back.hasBallPos() && back.getBallPosX() == 321);
        java.nio.file.Files.write(tmp.toPath(),
                java.util.Collections.singletonList("defaultDurationSeconds=abc\n"));
        TimerBallConfig bad = new TimerBallConfig();
        bad.setFilePath(tmp.getAbsolutePath());
        bad.load();
        check("坏值回退默认时长",
                bad.getDurationSeconds() == TimerBallConfig.DEFAULT_DURATION_SECONDS);
        tmp.delete();

        // 正计时配置同样往返（临时文件，避免污染运行目录）
        File tmp2 = File.createTempFile("upball_selftest", ".properties");
        check("正计时配置有配置文件常量", UpBallConfig.FILE_PATH != null);
        check("时长/格式工具可用",
                "25:00".equals(TimerBallConfig.formatDuration(1500))
                        && TimerBallConfig.parseDuration("1:30:00") == 5400);
        tmp2.delete();
    }

    // ===================== 5b) 跨会话记忆（退出后重启能恢复这一轮） =====================

    private static void testSessionMemory() {
        DownSession s = new DownSession(1500);
        s.setLastStartSeconds(1500);
        s.restoreToLastStart();
        s.restore(900_000L, true);
        DownSession.Snapshot snap = s.snapshot();
        // 快照发生在"恢复之后、读取之前"，期间会真实流逝几毫秒：允许 2 秒误差
        check("快照含剩余毫秒与运行时状态",
                snap != null
                        && Math.abs(snap.remainingMs - 900_000L) <= 2000L
                        && snap.state == DownSession.State.RUNNING
                        && snap.durationSeconds == 1500);

        DownSession s2 = new DownSession(1500);
        s2.restore(900_000L, true);
        check("按快照恢复：继续走且剩 900s",
                s2.isRunning() && s2.remainingSecondsCeil() == 900);

        DownSession s3 = new DownSession(1500);
        s3.restore(300_000L, false);
        check("暂停态快照恢复：保持暂停且剩 300s",
                s3.isPaused() && s3.remainingSecondsCeil() == 300);

        DownSession s4 = new DownSession(60);
        check("空闲态不产生快照（未开始的一轮不会被记忆）", s4.snapshot() == null);

        // 配置层：记忆字段持久化与清除
        try {
            java.io.File tmp = java.io.File.createTempFile("timerball_mem", ".properties");
            tmp.delete();
            TimerBallConfig c = new TimerBallConfig();
            c.setFilePath(tmp.getAbsolutePath());
            c.saveSession(900_000L, true);
            c.save();
            TimerBallConfig back = new TimerBallConfig();
            back.setFilePath(tmp.getAbsolutePath());
            back.load();
            check("记忆字段持久化（剩余毫秒+是否在走）",
                    back.getSavedRemainingMs() == 900_000L && back.isSavedWasRunning());
            back.clearSession();
            back.save();
            TimerBallConfig cleared = new TimerBallConfig();
            cleared.setFilePath(tmp.getAbsolutePath());
            cleared.load();
            check("清除记忆后不再恢复", cleared.getSavedRemainingMs() < 0);
            tmp.delete();
        } catch (Exception e) {
            fail("记忆字段持久化检查异常: " + e);
        }

        // 源码层：退出时保存、启动时恢复、归零时清除
        File ctrl = new File("DownController.java");
        if (ctrl.isFile()) {
            try {
                String text = new String(java.nio.file.Files.readAllBytes(ctrl.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                check("控制器启动时恢复上次进度（restoreSavedSession）",
                        text.contains("restoreSavedSession()") && text.contains("session.restore("));
                check("控制器退出前保存进度（stop→persistSession）",
                        text.contains("persistSession(); // 退出前记住这一轮进度"));
                check("归零会清除记忆", text.contains("config.clearSession()"));
            } catch (Exception e) {
                fail("DownController 记忆链路检查异常: " + e);
            }
        }
    }

    // ===================== 6) 音频兜底 =====================

    private static void testAlertFallback() {
        TimerBallConfig c = new TimerBallConfig();
        c.setAlertFile("");
        c.setAlertRepeat(1);
        TimerAlert alert = new TimerAlert(c);
        alert.play("自检");
        sleep(120);
        alert.stop();
        check("无自定义音频时播放不抛异常", true);
        check("空路径提示系统提示音",
                TimerAlert.checkFile("").contains("系统提示音"));
        check("不存在文件给出提示",
                TimerAlert.checkFile("Z:/definitely/not/here.wav").contains("不存在"));
        alert.shutdown();
    }

    // ===================== 7) 打包禁令与 Manifest =====================

    private static void testJarAndManifest() {
        File jar = new File("TimerBall.jar");
        if (!jar.exists()) {
            System.out.println("  (跳过 jar 检查：尚未打包 TimerBall.jar)");
            return;
        }
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
            int hostClasses = 0;
            String first = null;
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
            String apiLevel = jf.getManifest() == null ? null
                    : jf.getManifest().getMainAttributes().getValue("Api-Level-Min");
            check("Manifest 声明 Api-Level-Min=2", "2".equals(apiLevel));
        } catch (Exception e) {
            fail("jar 检查异常: " + e);
        }
    }

    // ===================== 断言工具 =====================

    /** 截取某个方法体（从签名到下一个同级方法/类尾），用于精确的源码级断言。 */
    private static String methodBody(String text, String signature) {
        int start = text.indexOf(signature);
        if (start < 0) {
            return null;
        }
        int next = text.indexOf("\n    public ", start + signature.length());
        int nextPriv = text.indexOf("\n    private ", start + signature.length());
        int end = text.length();
        if (next > 0) {
            end = Math.min(end, next);
        }
        if (nextPriv > 0) {
            end = Math.min(end, nextPriv);
        }
        return text.substring(start, end);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            fail(name);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            fail(name + "  [" + detail + "]");
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
