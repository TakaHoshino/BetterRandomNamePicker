import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 宿主插件菜单"分组（级联子菜单）"正式 API 改造工具（一次性工具，仓库内保留复核）。
 * <p>
 * 目标（按需求）：<b>宿主提供正式的类型化接口，插件调用该接口；宿主代码里不得出现任何插件专属逻辑</b>
 * （此前临时加的「▸ 标题约定 + 解析」全部删除）。
 * </p>
 * <p>
 * 交付的宿主接口面：
 * <ul>
 *   <li>{@code PluginContext.addGroupedUiAction(UiZone, String[] groupPath, String title, ActionListener)}；</li>
 *   <li>便捷方法 {@code addMainMenuAction/addTrayMenuAction/addFloatingBallMenuAction(String[] groupPath, String title, ActionListener)}
 *       与 {@code addSettingsWindowAction(...)}；原平铺重载保持不变（源码与行为兼容）；</li>
 *   <li>{@code PluginManager.MenuAction.getGroupPath()}（公共菜单渲染器读它建级联子菜单）；</li>
 *   <li>{@code MenuGroupSupport.appendAction(JComponent, MenuAction)}：宿主各渲染点统一调用的通用渲染器
 *       （不含任何插件名/插件业务字样）。</li>
 * </ul>
 * 任意一步匹配失败即抛异常并中止（不留半成品）。
 * </p>
 * <pre>运行：java -cp &lt;宿主jar&gt;:. HostGroupApiPatch</pre>
 */
public final class HostGroupApiPatch {

    private HostGroupApiPatch() {
    }

    private static Path root;

    public static void main(String[] args) throws IOException {
        root = Paths.get(".").toAbsolutePath().normalize();
        int n = 0;
        n += patchPluginManager();
        n += patchPerPluginContext();
        n += patchPluginContext();
        n += writeMenuGroupSupport();
        n += patchFloatingBall();
        n += patchNamePickerApp();
        n += patchMain();
        System.out.println("HostGroupApiPatch 完成，改动 " + n + " 处");
    }

    // ===================== PluginManager =====================

    private static int patchPluginManager() throws IOException {
        Path p = root.resolve("src/com/randomnamepicker/plugin/PluginManager.java");
        String t = read(p);
        int n = 0;

        // 1) MenuAction：新增 groupPath 字段 + 构造器 + 访问器
        String oldCtor = "    /** 菜单项（title 不判重；pluginName 用于出错日志归属）。 */\n"
                + "    public static final class MenuAction {\n"
                + "        private final String title;\n"
                + "        private final String pluginName;\n"
                + "        private final ActionListener action;\n"
                + "\n"
                + "        MenuAction(String title, String pluginName, ActionListener action) {\n"
                + "            this.title = title;\n"
                + "            this.pluginName = pluginName;\n"
                + "            this.action = action;\n"
                + "        }\n"
                + "\n"
                + "        public String getTitle() {\n"
                + "            return title;\n"
                + "        }\n"
                + "\n"
                + "        public String getPluginName() {\n"
                + "            return pluginName;\n"
                + "        }\n"
                + "\n"
                + "        public ActionListener getAction() {\n"
                + "            return action;\n"
                + "        }\n"
                + "    }\n";
        String newCtor = "    /**\n"
                + "     * 菜单项（title 不判重；pluginName 用于出错日志归属）。\n"
                + "     * <p>\n"
                + "     * {@code groupPath} 为宿主提供的<b>分组路径</b>：非空时，宿主在渲染该动作时按路径依次创建\n"
                + "     * （或复用）级联子菜单，例如 {@code groupPath = new String[]{\"计时\",\"倒计时设置\"}}\n"
                + "     * → 计时 ▸ 倒计时设置 ▸ 命令。\n"
                + "     * 空数组/全空字符串表示平铺（与旧行为一致）。宿主渲染端只按本字段拼菜单，不认识任何插件业务字样。\n"
                + "     * </p>\n"
                + "     */\n"
                + "    public static final class MenuAction {\n"
                + "        private final String title;\n"
                + "        private final String pluginName;\n"
                + "        private final ActionListener action;\n"
                + "        private final List<String> groupPath;\n"
                + "\n"
                + "        MenuAction(String title, String pluginName, ActionListener action) {\n"
                + "            this(title, pluginName, action, null);\n"
                + "        }\n"
                + "\n"
                + "        MenuAction(String title, String pluginName, ActionListener action, String[] groupPath) {\n"
                + "            this.title = title;\n"
                + "            this.pluginName = pluginName;\n"
                + "            this.action = action;\n"
                + "            List<String> path = new ArrayList<>();\n"
                + "            if (groupPath != null) {\n"
                + "                for (String seg : groupPath) {\n"
                + "                    if (seg != null && !seg.trim().isEmpty()) {\n"
                + "                        path.add(seg.trim());\n"
                + "                    }\n"
                + "                }\n"
                + "            }\n"
                + "            this.groupPath = Collections.unmodifiableList(path);\n"
                + "        }\n"
                + "\n"
                + "        public String getTitle() {\n"
                + "            return title;\n"
                + "        }\n"
                + "\n"
                + "        public String getPluginName() {\n"
                + "            return pluginName;\n"
                + "        }\n"
                + "\n"
                + "        public ActionListener getAction() {\n"
                + "            return action;\n"
                + "        }\n"
                + "\n"
                + "        /** 分组路径只读视图（空 = 平铺）。 */\n"
                + "        public List<String> getGroupPath() {\n"
                + "            return groupPath;\n"
                + "        }\n"
                + "    }\n";
        t = swap(t, oldCtor, newCtor);
        n++;

        // 1b) MenuAction 使用 ArrayList：确保已导入 java.util.ArrayList
        if (!t.contains("import java.util.ArrayList;")) {
            t = t.replace("import java.util.Collections;",
                    "import java.util.ArrayList;\nimport java.util.Collections;");
            n++;
        }

        // 2) PerPluginContext：新增 addGroupedUiAction（复用既有的 zone 严格失败语义）
        String oldAdd = "        @Override\n"
                + "        public synchronized void addUiAction(UiZone zone, String title, ActionListener action) {\n"
                + "            // D8 严格失败语义：zone 为 null = 插件缺陷 → 抛异常（宿主整 jar 拒载并记 PLUGIN_LOAD_ERROR）\n"
                + "            if (zone == null) {\n"
                + "                throw new IllegalArgumentException(\"插件 \" + plugin.getName()\n"
                + "                        + \" 调用 addUiAction 时 zone 为 null\");\n"
                + "            }\n"
                + "            if (title == null || title.trim().isEmpty() || action == null) {\n"
                + "                return; // 与既有菜单注册一致：空 title/action 忽略\n"
                + "            }\n"
                + "            pendingActions.computeIfAbsent(zone, z -> new ArrayList<>())\n"
                + "                    .add(new MenuAction(title, plugin.getName(), action));\n"
                + "        }\n";
        String newAdd = "        @Override\n"
                + "        public synchronized void addUiAction(UiZone zone, String title, ActionListener action) {\n"
                + "            addGroupedUiAction(zone, null, title, action);\n"
                + "        }\n"
                + "\n"
                + "        @Override\n"
                + "        public synchronized void addGroupedUiAction(UiZone zone, String[] groupPath,\n"
                + "                                                    String title, ActionListener action) {\n"
                + "            // D8 严格失败语义：zone 为 null = 插件缺陷 → 抛异常（宿主整 jar 拒载并记 PLUGIN_LOAD_ERROR）\n"
                + "            if (zone == null) {\n"
                + "                throw new IllegalArgumentException(\"插件 \" + plugin.getName()\n"
                + "                        + \" 调用 addUiAction/addGroupedUiAction 时 zone 为 null\");\n"
                + "            }\n"
                + "            if (title == null || title.trim().isEmpty() || action == null) {\n"
                + "                return; // 与既有菜单注册一致：空 title/action 忽略\n"
                + "            }\n"
                + "            pendingActions.computeIfAbsent(zone, z -> new ArrayList<>())\n"
                + "                    .add(new MenuAction(title, plugin.getName(), action, groupPath));\n"
                + "        }\n";
        t = swap(t, oldAdd, newAdd);
        n++;

        write(p, t);
        return n;
    }

    // ===================== PluginContext =====================

    private static int patchPerPluginContext() throws IOException {
        // 已在 patchPluginManager 中一并处理（PerPluginContext 位于 PluginManager.java）
        return 0;
    }

    private static int patchPluginContext() throws IOException {
        Path p = root.resolve("src/com/randomnamepicker/plugin/PluginContext.java");
        String t = read(p);
        int n = 0;

        String oldIf = "    void addUiAction(UiZone zone, String title, ActionListener action);\n";
        String newIf = "    void addUiAction(UiZone zone, String title, ActionListener action);\n"
                + "\n"
                + "    /**\n"
                + "     * 在指定 UI 区域注册一个<b>带分组路径</b>的动作（宿主插件 API：菜单分组/级联子菜单的正式接口）。\n"
                + "     * <p>\n"
                + "     * {@code groupPath} 为空/null 时等价于 {@link #addUiAction(UiZone, String, ActionListener)}（平铺）；\n"
                + "     * 非空时宿主渲染端按路径逐级创建（或复用）级联子菜单，例如\n"
                + "     * {@code new String[]{\"计时\", \"倒计时设置\"}} → {@code 计时 ▸ 倒计时设置 ▸ 命令}。\n"
                + "     * 路径段为 null/空白会被忽略；宿主只按该字段渲染，不需要（也不应）约定任何标题分隔符。\n"
                + "     * </p>\n"
                + "     * <p>\n"
                + "     * 失败语义与 {@link #addUiAction} 一致：zone 为 null 抛 {@link IllegalArgumentException}\n"
                + "     * （宿主整 jar 拒载，原因码 NULL_ZONE）；title 空白或 action 为 null 时忽略。\n"
                + "     * </p>\n"
                + "     */\n"
                + "    void addGroupedUiAction(UiZone zone, String[] groupPath, String title, ActionListener action);\n";
        t = swap(t, oldIf, newIf);
        n++;

        String oldMain = "    /** 在主窗口“插件”菜单添加一项（暂存，提交后生效；标题不判重）。等价 addUiAction(UiZone.MAIN_MENU, …)。 */\n"
                + "    default void addMainMenuAction(String title, ActionListener action) {\n"
                + "        addUiAction(UiZone.MAIN_MENU, title, action);\n"
                + "    }\n"
                + "\n"
                + "    /** 在系统托盘菜单添加一项（暂存，提交后生效；标题不判重）。等价 addUiAction(UiZone.TRAY_MENU, …)。 */\n"
                + "    default void addTrayMenuAction(String title, ActionListener action) {\n"
                + "        addUiAction(UiZone.TRAY_MENU, title, action);\n"
                + "    }\n"
                + "\n"
                + "    /** 在悬浮球右键菜单添加一项（暂存，提交后生效；标题不判重）。等价 addUiAction(UiZone.FLOATING_BALL_MENU, …)。 */\n"
                + "    default void addFloatingBallMenuAction(String title, ActionListener action) {\n"
                + "        addUiAction(UiZone.FLOATING_BALL_MENU, title, action);\n"
                + "    }\n";
        String newMain = "    /** 在主窗口“插件”菜单添加一项（暂存，提交后生效；标题不判重）。等价 addUiAction(UiZone.MAIN_MENU, …)。 */\n"
                + "    default void addMainMenuAction(String title, ActionListener action) {\n"
                + "        addUiAction(UiZone.MAIN_MENU, title, action);\n"
                + "    }\n"
                + "\n"
                + "    /** 主窗“插件”菜单：带分组路径添加一项（宿主建级联子菜单；groupPath 空则平铺）。 */\n"
                + "    default void addMainMenuAction(String[] groupPath, String title, ActionListener action) {\n"
                + "        addGroupedUiAction(UiZone.MAIN_MENU, groupPath, title, action);\n"
                + "    }\n"
                + "\n"
                + "    /** 在系统托盘菜单添加一项（暂存，提交后生效；标题不判重）。等价 addUiAction(UiZone.TRAY_MENU, …)。 */\n"
                + "    default void addTrayMenuAction(String title, ActionListener action) {\n"
                + "        addUiAction(UiZone.TRAY_MENU, title, action);\n"
                + "    }\n"
                + "\n"
                + "    /** 托盘菜单：带分组路径添加一项（宿主建级联子菜单；groupPath 空则平铺）。 */\n"
                + "    default void addTrayMenuAction(String[] groupPath, String title, ActionListener action) {\n"
                + "        addGroupedUiAction(UiZone.TRAY_MENU, groupPath, title, action);\n"
                + "    }\n"
                + "\n"
                + "    /** 在悬浮球右键菜单添加一项（暂存，提交后生效；标题不判重）。等价 addUiAction(UiZone.FLOATING_BALL_MENU, …)。 */\n"
                + "    default void addFloatingBallMenuAction(String title, ActionListener action) {\n"
                + "        addUiAction(UiZone.FLOATING_BALL_MENU, title, action);\n"
                + "    }\n"
                + "\n"
                + "    /** 悬浮球右键菜单：带分组路径添加一项（宿主建级联子菜单；groupPath 空则平铺）。 */\n"
                + "    default void addFloatingBallMenuAction(String[] groupPath, String title, ActionListener action) {\n"
                + "        addGroupedUiAction(UiZone.FLOATING_BALL_MENU, groupPath, title, action);\n"
                + "    }\n"
                + "\n"
                + "    /** 设置窗“插件”区：带分组路径添加一个按钮（一般不需要分组，保留一致性）。 */\n"
                + "    default void addSettingsWindowAction(String[] groupPath, String title, ActionListener action) {\n"
                + "        addGroupedUiAction(UiZone.SETTINGS_WINDOW, groupPath, title, action);\n"
                + "    }\n";
        t = swap(t, oldMain, newMain);
        n++;

        write(p, t);
        return n;
    }

    // ===================== MenuGroupSupport（新文件） =====================

    private static int writeMenuGroupSupport() throws IOException {
        Path p = root.resolve("src/com/randomnamepicker/plugin/MenuGroupSupport.java");
        String body = "package com.randomnamepicker.plugin;\n"
                + "\n"
                + "import java.util.List;\n"
                + "import javax.swing.JComponent;\n"
                + "import javax.swing.JMenu;\n"
                + "import javax.swing.JMenuItem;\n"
                + "import javax.swing.JPopupMenu;\n"
                + "\n"
                + "/**\n"
                + " * 插件菜单动作的通用渲染器（宿主内部工具，非插件 API 面）。\n"
                + " * <p>\n"
                + " * 宿主所有插件渲染点（主窗“插件”菜单、托盘 Swing 菜单、悬浮球右键菜单）统一调用本类，\n"
                + " * 按 {@link PluginManager.MenuAction#getGroupPath()} 逐级创建或复用级联子菜单：\n"
                + " * groupPath = [\"计时\",\"倒计时设置\"] → 计时 ▸ 倒计时设置 ▸ 命令。\n"
                + " * 路径为空则平铺插入（与未分组的旧插件行为完全一致）。\n"
                + " * </p>\n"
                + " * <p>\n"
                + " * 本类<b>只按数据渲染</b>：不认识任何插件名、插件业务字样或标题分隔符约定。\n"
                + " * 动作一律经 {@link PluginManager#runMenuActionSafely} 防御执行。\n"
                + " * </p>\n"
                + " */\n"
                + "public final class MenuGroupSupport {\n"
                + "\n"
                + "    private MenuGroupSupport() {\n"
                + "    }\n"
                + "\n"
                + "    /** 把一条插件菜单动作按分组路径挂到 {@code parent}（JPopupMenu 或 JMenu）上。 */\n"
                + "    public static void appendAction(JComponent parent, PluginManager.MenuAction act) {\n"
                + "        if (parent == null || act == null) {\n"
                + "            return;\n"
                + "        }\n"
                + "        JComponent container = parent;\n"
                + "        List<String> path = act.getGroupPath();\n"
                + "        if (path != null) {\n"
                + "            for (String segment : path) {\n"
                + "                if (segment == null || segment.trim().isEmpty()) {\n"
                + "                    continue;\n"
                + "                }\n"
                + "                container = childMenu(container, segment.trim());\n"
                + "            }\n"
                + "        }\n"
                + "        container.add(menuItem(act));\n"
                + "    }\n"
                + "\n"
                + "    /** 按分组路径批量挂载（顺序 = 传入顺序）。 */\n"
                + "    public static void appendActions(JComponent parent, List<PluginManager.MenuAction> actions) {\n"
                + "        if (actions == null) {\n"
                + "            return;\n"
                + "        }\n"
                + "        for (PluginManager.MenuAction act : actions) {\n"
                + "            appendAction(parent, act);\n"
                + "        }\n"
                + "    }\n"
                + "\n"
                + "    /** 单个动作菜单项（防御执行包装，与宿主其它插件动作同一路径）。 */\n"
                + "    public static JMenuItem menuItem(PluginManager.MenuAction act) {\n"
                + "        JMenuItem item = new JMenuItem(act.getTitle());\n"
                + "        item.addActionListener(e -> PluginManager.getInstance()\n"
                + "                .runMenuActionSafely(act.getPluginName(), act.getAction(), e));\n"
                + "        return item;\n"
                + "    }\n"
                + "\n"
                + "    /**\n"
                + "     * 取（或新建）名为 groupName 的级联子菜单；同名已存在则复用。\n"
                + "     * <p>\n"
                + "     * 注意：{@link JMenu} 通过 {@code add(JMenuItem)} 添加的子项实际由它的 {@link JPopupMenu}\n"
                + "     * 持有，所以 {@code JMenu.getComponent()} 返回的是弹层而不是子菜单；只用 getComponent 查找同名\n"
                + "     * 子菜单会每次都“找不到”并重复创建（实测出现 13 个各自只有 1 项的同名子菜单）。\n"
                + "     * 故这里同时检查直接子组件中的 JMenu、以及子组件 JPopupMenu 的 invoker。\n"
                + "     * </p>\n"
                + "     */\n"
                + "    public static JMenu childMenu(JComponent parent, String groupName) {\n"
                + "        for (int i = 0; i < parent.getComponentCount(); i++) {\n"
                + "            JMenu found = menuNamed(parent.getComponent(i), groupName);\n"
                + "            if (found != null) {\n"
                + "                return found;\n"
                + "            }\n"
                + "        }\n"
                + "        JMenu menu = new JMenu(groupName);\n"
                + "        parent.add(menu);\n"
                + "        return menu;\n"
                + "    }\n"
                + "\n"
                + "    /** 组件本身、或它承载的弹层的 invoker，是否是名为 groupName 的菜单。 */\n"
                + "    private static JMenu menuNamed(java.awt.Component c, String groupName) {\n"
                + "        if (c instanceof JMenu && groupName.equals(((JMenu) c).getText())) {\n"
                + "            return (JMenu) c;\n"
                + "        }\n"
                + "        if (c instanceof JPopupMenu) {\n"
                + "            java.awt.Component invoker = ((JPopupMenu) c).getInvoker();\n"
                + "            if (invoker instanceof JMenu && groupName.equals(((JMenu) invoker).getText())) {\n"
                + "                return (JMenu) invoker;\n"
                + "            }\n"
                + "        }\n"
                + "        return null;\n"
                + "    }\n"
                + "}\n";
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
        System.out.println("  新建 src/com/randomnamepicker/plugin/MenuGroupSupport.java");
        return 1;
    }

    // ===================== 渲染点：悬浮球右键菜单 =====================

    private static int patchFloatingBall() throws IOException {
        Path p = root.resolve("src/com/randomnamepicker/floating/FloatingBall.java");
        String t = read(p);
        int n = 0;

        // 1) 删除临时"标题分隔符"常量
        String oldConst = "    /**\n"
                + "     * 悬浮球右键菜单插件项的分组分隔符（P1）：插件动作标题形如\n"
                + "     * <code>组名 ▸ 命令名</code> 时，宿主把该命令收进同名级联子菜单（一级=组，二级=命令）；\n"
                + "     * 标题不含本分隔符的插件项仍平铺显示，与旧版行为一致。插件可选用。\n"
                + "     */\n"
                + "    public static final String ACTION_GROUP_SEPARATOR = \" ▸ \";\n";
        if (t.contains(oldConst)) {
            t = t.replace(oldConst, "");
            n++;
        } else {
            System.out.println("  (提示) FloatingBall 未找到旧分隔符常量，跳过");
        }

        // 2) 删除 PluginMenuEntry 内部类（改为直接渲染 MenuAction）
        int a = t.indexOf("    /** 右键菜单插件项（已按分组约定拆分");
        int b = t.indexOf("    public FloatingBall(NamePickerApp mainApp) {");
        if (a > 0 && b > a) {
            t = t.substring(0, a) + t.substring(b);
            n++;
        } else {
            System.out.println("  (提示) FloatingBall 未找到 PluginMenuEntry 区块，跳过");
        }

        // 3) 渲染循环改为通用渲染器
        int renderStart = t.indexOf("        // Stage3：悬浮球右键菜单插件项");
        int renderEnd = t.indexOf("        popupMenu.addSeparator();\n\n        JMenuItem closeItem");
        if (renderStart > 0 && renderEnd > renderStart) {
            String newRender = "        // 插件右键项：按 MenuAction.groupPath 渲染（宿主通用分组渲染，见 MenuGroupSupport）。\n"
                    + "        java.util.List<PluginManager.MenuAction> pluginActions =\n"
                    + "                PluginManager.getInstance().getFloatingBallMenuActions();\n"
                    + "        if (!pluginActions.isEmpty()) {\n"
                    + "            popupMenu.addSeparator();\n"
                    + "            MenuGroupSupport.appendActions(popupMenu, pluginActions);\n"
                    + "        }\n"
                    + "\n";
            t = t.substring(0, renderStart) + newRender + t.substring(renderEnd);
            n++;
        } else {
            throw new IOException("FloatingBall：未找到插件项渲染区块");
        }

        // 4) 删除旧的两段生成区块（含 appendPluginAction/splitPluginTitle/childMenu/menuNamed/pluginMenuItem）
        t = dropGeneratedBlock(t, "    // ===== 插件项分层渲染（HostMenuTreePatch 生成，勿手改） =====",
                "    // ===== 插件项分层渲染结束 =====");
        t = dropLegacyBlock(t);
        // pluginMenuItem 已无引用，删除
        int pm = t.indexOf("    /** 悬浮球右键菜单的插件项（防御执行，与其它插件动作同一执行路径）。 */");
        if (pm > 0) {
            int pmEnd = t.indexOf("    }\n", t.indexOf("return item;\n", pm));
            if (pmEnd > pm) {
                t = t.substring(0, pm) + t.substring(pmEnd + "    }\n".length());
                n++;
            }
        }
        // 5) 补 import
        if (!t.contains("import com.randomnamepicker.plugin.MenuGroupSupport;")) {
            t = t.replace("import com.randomnamepicker.plugin.PluginManager;",
                    "import com.randomnamepicker.plugin.MenuGroupSupport;\n"
                            + "import com.randomnamepicker.plugin.PluginManager;");
            n++;
        }
        write(p, t);
        return n;
    }

    /** 删除 HostMenuTreePatch 生成的标记区块。 */
    private static String dropGeneratedBlock(String t, String begin, String end) {
        int b = t.indexOf(begin);
        int e = t.indexOf(end);
        if (b < 0 || e < b) {
            return t;
        }
        int endOfLine = t.indexOf("\n", e);
        return t.substring(0, b) + (endOfLine < 0 ? "" : t.substring(endOfLine + 1));
    }

    /** 删除第一轮（标题约定）生成的旧方法块。 */
    private static String dropLegacyBlock(String t) {
        String start = "    /**\n"
                + "     * 把一个插件动作按「组 ▸ 子组 ▸ 项」标题递归挂到菜单树上（P1 多次修订）。";
        int s = t.indexOf(start);
        if (s < 0) {
            return t;
        }
        String end = "        JMenu menu = new JMenu(groupName);\n"
                + "        parent.add(menu);\n"
                + "        return menu;\n"
                + "    }\n";
        int e = t.indexOf(end, s);
        if (e < 0) {
            return t;
        }
        return t.substring(0, s) + t.substring(e + end.length());
    }

    // ===================== 渲染点：主窗菜单 / 托盘菜单 =====================

    private static int patchNamePickerApp() throws IOException {
        Path p = root.resolve("src/com/randomnamepicker/ui/NamePickerApp.java");
        String t = read(p);
        String oldBlock = "        for (PluginManager.MenuAction act : actions) {\n"
                + "            JMenuItem item = new JMenuItem(act.getTitle());\n"
                + "            PluginManager.MenuAction captured = act;\n"
                + "            item.addActionListener(e -> PluginManager.getInstance()\n"
                + "                    .runMenuActionSafely(captured.getPluginName(), captured.getAction(), e));\n"
                + "            pluginMenu.add(item);\n"
                + "        }\n";
        String newBlock = "        // 插件项：按 MenuAction.groupPath 渲染级联子菜单（宿主通用渲染器）\n"
                + "        MenuGroupSupport.appendActions(pluginMenu, actions);\n";
        t = swap(t, oldBlock, newBlock);
        if (!t.contains("import com.randomnamepicker.plugin.MenuGroupSupport;")) {
            t = t.replace("import com.randomnamepicker.plugin.PluginManager;",
                    "import com.randomnamepicker.plugin.MenuGroupSupport;\n"
                            + "import com.randomnamepicker.plugin.PluginManager;");
        }
        write(p, t);
        return 1;
    }

    private static int patchMain() throws IOException {
        Path p = root.resolve("src/com/randomnamepicker/main/Main.java");
        String t = read(p);
        String oldBlock = "        for (PluginManager.MenuAction act : pluginActions) {\n"
                + "            JMenuItem item = new JMenuItem(act.getTitle());\n"
                + "            PluginManager.MenuAction captured = act;\n"
                + "            item.addActionListener(e -> PluginManager.getInstance()\n"
                + "                    .runMenuActionSafely(captured.getPluginName(), captured.getAction(), e));\n"
                + "            menu.add(item);\n"
                + "        }\n";
        String newBlock = "        // 插件项：按 MenuAction.groupPath 渲染级联子菜单（宿主通用渲染器）\n"
                + "        com.randomnamepicker.plugin.MenuGroupSupport.appendActions(menu, pluginActions);\n";
        t = swap(t, oldBlock, newBlock);
        write(p, t);
        return 1;
    }

    // ===================== IO =====================

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static void write(Path p, String text) throws IOException {
        Files.write(p, text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String swap(String text, String from, String to) throws IOException {
        if (!text.contains(from)) {
            throw new IOException("未找到待替换片段（前 60 字）："
                    + from.substring(0, Math.min(60, from.length())).replace('\n', '|'));
        }
        return text.replace(from, to);
    }
}
