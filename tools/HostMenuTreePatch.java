import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 宿主悬浮球右键菜单：插件项分层渲染改造（一次性工具，仓库内保留以便复核）。
 * <p>
 * 目标：插件菜单标题支持「组 ▸ 子组 ▸ 项」，宿主据此建出<b>多层级联子菜单</b>
 * （与 Windows 右键菜单同构）。无分隔符的标题仍平铺，完全向后兼容。
 * </p>
 * <p>
 * 关键修正（实测踩到）：{@code JMenu.add(JMenuItem)} 的子项由该 JMenu 的 {@code JPopupMenu} 持有，
 * 所以 {@code JMenu.getComponent()} 拿到的是弹层而不是子菜单；若只用 getComponent 找同名子菜单，
 * 每次都会"找不到"并重复创建（实测出现 13 个各自只有 1 项的「倒计时设置」子菜单）。
 * 因此查找同名子菜单时同时检查：直接子组件中的 JMenu、以及子组件 JPopupMenu 的 invoker。
 * </p>
 * <p>
 * 本工具用显式 UTF-8 读写 + 精确片段替换（本环境的 PowerShell 直接改源码会偶发字符损坏），
 * 并被设计为<b>幂等</b>：重复运行到已修正的源码上不会重复插入代码。
 * </p>
 * <pre>运行：java -cp &lt;宿主jar&gt;:. HostMenuTreePatch</pre>
 */
public final class HostMenuTreePatch {

    private HostMenuTreePatch() {
    }

    private static final String MARK_BEGIN = "    // ===== 插件项分层渲染（HostMenuTreePatch 生成，勿手改） =====";
    private static final String MARK_END = "    // ===== 插件项分层渲染结束 =====";

    /** 修正后的整段方法实现（appendPluginAction / splitPluginTitle / childMenu / menuNamed）。 */
    private static final String FIXED_BLOCK = MARK_BEGIN + "\n"
            + "    /**\n"
            + "     * 把一个插件动作按「组 ▸ 子组 ▸ 项」标题递归挂到菜单树上。\n"
            + "     * <p>\n"
            + "     * 标题含几层 {@link #ACTION_GROUP_SEPARATOR} 就建几层级联子菜单：一级=功能组（如「计时子球」）、\n"
            + "     * 二级=子组（如「倒计时设置」）、末段=命令项。无分隔符的标题直接平铺（与旧版行为一致）。\n"
            + "     * 同名子菜单复用（不同插件注册到同一组名时合并显示）。\n"
            + "     * </p>\n"
            + "     */\n"
            + "    private static void appendPluginAction(JComponent parent, String title,\n"
            + "                                           String pluginName, ActionListener action) {\n"
            + "        String[] parts = splitPluginTitle(title);\n"
            + "        JComponent container = parent;\n"
            + "        for (int i = 0; i < parts.length - 1; i++) {\n"
            + "            container = childMenu(container, parts[i]);\n"
            + "        }\n"
            + "        JMenuItem item = new JMenuItem(parts[parts.length - 1]);\n"
            + "        item.addActionListener(e -> PluginManager.getInstance()\n"
            + "                .runMenuActionSafely(pluginName, action, e));\n"
            + "        container.add(item);\n"
            + "    }\n"
            + "\n"
            + "    /** 按分隔符切分插件标题（去空白、丢弃空段；无分隔符则返回单段）。 */\n"
            + "    private static String[] splitPluginTitle(String title) {\n"
            + "        if (title == null) {\n"
            + "            return new String[]{\"\"};\n"
            + "        }\n"
            + "        String[] raw = title.split(java.util.regex.Pattern.quote(ACTION_GROUP_SEPARATOR));\n"
            + "        java.util.List<String> parts = new java.util.ArrayList<String>();\n"
            + "        for (String s : raw) {\n"
            + "            String t = s.trim();\n"
            + "            if (!t.isEmpty()) {\n"
            + "                parts.add(t);\n"
            + "            }\n"
            + "        }\n"
            + "        if (parts.isEmpty()) {\n"
            + "            parts.add(title.trim());\n"
            + "        }\n"
            + "        return parts.toArray(new String[0]);\n"
            + "    }\n"
            + "\n"
            + "    /**\n"
            + "     * 取（或新建）名为 groupName 的级联子菜单；同名已存在则复用。\n"
            + "     * <p>\n"
            + "     * 注意：{@link JMenu} 通过 {@code add(JMenuItem)} 添加的子项实际由它的 {@code JPopupMenu}\n"
            + "     * 持有，因此 {@code JMenu.getComponent()} 返回的是那个弹层而不是子菜单；只用 getComponent\n"
            + "     * 找同名子菜单会每次都找不到并重复创建（实测：13 个「倒计时设置」各自成一个只有 1 项的子菜单）。\n"
            + "     * 故这里同时检查直接子组件中的 JMenu、以及子组件 JPopupMenu 的 invoker。\n"
            + "     * </p>\n"
            + "     */\n"
            + "    private static JMenu childMenu(JComponent parent, String groupName) {\n"
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
            + MARK_END + "\n";

    public static void main(String[] args) throws IOException {
        Path file = Paths.get("src/com/randomnamepicker/floating/FloatingBall.java");
        String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String text = raw.replace("\r\n", "\n"); // 宿主源码是 CRLF：内部统一 LF 处理

        // 幂等：先移除上一轮生成的区块
        String withoutBlock = removeMarkedBlock(text);
        boolean hadBlock = !withoutBlock.equals(text);
        text = withoutBlock;

        // 移除旧版（第一次尝试时生成的）三个方法：从 appendPluginAction 的注释开始到 childMenu 结束
        text = removeLegacyMethods(text);

        // 插到 pluginMenuItem 方法之后
        String anchor = "    /** 内置模式既有右键配置项（按 modeId，行为与现版一致）。 */";
        int at = text.indexOf(anchor);
        if (at < 0) {
            throw new IOException("未找到插入锚点（appendBuiltinModeItems 注释）");
        }
        String result = text.substring(0, at) + FIXED_BLOCK + "\n" + text.substring(at);

        // 渲染循环改为调用 appendPluginAction（幂等）
        result = result.replace(
                "                popupMenu.add(pluginMenuItem(act.getTitle(), act.getPluginName(), act.getAction()));",
                "                appendPluginAction(popupMenu, act.getTitle(), act.getPluginName(), act.getAction());");
        result = result.replace(
                "                    groupMenu.add(pluginMenuItem(entry.title, entry.pluginName, entry.action));",
                "                    appendPluginAction(groupMenu, entry.title, entry.pluginName, entry.action);");

        Files.write(file, result.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("FloatingBall.java 已更新：多层级联子菜单渲染（旧区块清理=" + hadBlock + "）");
    }

    /** 移除本工具上一轮插入的标记区块（幂等）。 */
    private static String removeMarkedBlock(String text) {
        int b = text.indexOf(MARK_BEGIN);
        int e = text.indexOf(MARK_END);
        if (b < 0 || e < b) {
            return text;
        }
        int endOfLine = text.indexOf("\n", e);
        return text.substring(0, b) + (endOfLine < 0 ? "" : text.substring(endOfLine + 1));
    }

    /** 移除第一次尝试生成的旧版方法（含注解与 4 个方法体）。 */
    private static String removeLegacyMethods(String text) {
        String legacyStart = "    /**\n"
                + "     * 把一个插件动作按「组 ▸ 子组 ▸ 项」标题递归挂到菜单树上（P1 多次修订）。";
        int s = text.indexOf(legacyStart);
        if (s < 0) {
            return text;
        }
        // 旧块结尾 = childMenu 方法最后一个右括号
        String legacyEnd = "        JMenu menu = new JMenu(groupName);\n"
                + "        parent.add(menu);\n"
                + "        return menu;\n"
                + "    }\n";
        int e = text.indexOf(legacyEnd, s);
        if (e < 0) {
            return text;
        }
        return text.substring(0, s) + text.substring(e + legacyEnd.length());
    }
}
