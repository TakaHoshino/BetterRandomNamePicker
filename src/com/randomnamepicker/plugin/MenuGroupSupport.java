package com.randomnamepicker.plugin;

import java.util.List;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * 插件菜单动作的通用渲染器（宿主内部工具，非插件 API 面）。
 * <p>
 * 宿主所有插件渲染点（主窗“插件”菜单、托盘 Swing 菜单、悬浮球右键菜单）统一调用本类，
 * 按 {@link PluginManager.MenuAction#getGroupPath()} 逐级创建或复用级联子菜单：
 * 例如 groupPath = {"分组A","子组B"} → 分组A → 子组B → 命令。
 * 路径为空则平铺插入（与未分组的旧插件行为完全一致）。
 * </p>
 * <p>
 * 本类<b>只按数据渲染</b>：不认识任何插件名、插件业务字样或标题分隔符约定。
 * 动作一律经 {@link PluginManager#runMenuActionSafely} 防御执行。
 * </p>
 */
public final class MenuGroupSupport {

    private MenuGroupSupport() {
    }

    /** 把一条插件菜单动作按分组路径挂到 {@code parent}（JPopupMenu 或 JMenu）上。 */
    public static void appendAction(JComponent parent, PluginManager.MenuAction act) {
        if (parent == null || act == null) {
            return;
        }
        JComponent container = parent;
        List<String> path = act.getGroupPath();
        if (path != null) {
            for (String segment : path) {
                if (segment == null || segment.trim().isEmpty()) {
                    continue;
                }
                container = childMenu(container, segment.trim());
            }
        }
        container.add(menuItem(act));
    }

    /** 按分组路径批量挂载（顺序 = 传入顺序）。 */
    public static void appendActions(JComponent parent, List<PluginManager.MenuAction> actions) {
        if (actions == null) {
            return;
        }
        for (PluginManager.MenuAction act : actions) {
            appendAction(parent, act);
        }
    }

    /** 单个动作菜单项（防御执行包装，与宿主其它插件动作同一路径）。 */
    public static JMenuItem menuItem(PluginManager.MenuAction act) {
        JMenuItem item = new JMenuItem(act.getTitle());
        item.addActionListener(e -> PluginManager.getInstance()
                .runMenuActionSafely(act.getPluginName(), act.getAction(), e));
        return item;
    }

    /**
     * 取（或新建）名为 groupName 的级联子菜单；同名已存在则复用。
     * <p>
     * 注意：{@link JMenu} 通过 {@code add(JMenuItem)} 添加的子项实际由它的 {@link JPopupMenu}
     * 持有，所以 {@code JMenu.getComponent()} 返回的是弹层而不是子菜单；只用 getComponent 查找同名
     * 子菜单会每次都“找不到”并重复创建（实测出现 13 个各自只有 1 项的同名子菜单）。
     * 故这里同时检查直接子组件中的 JMenu、以及子组件 JPopupMenu 的 invoker。
     * </p>
     */
    public static JMenu childMenu(JComponent parent, String groupName) {
        // 关键：JMenu 不是普通 Container——JMenu.add(JMenuItem) 实际把子项加进它自己的
        // JPopupMenu，而 JMenu.getComponent() 拿到的是"该弹层"而不是子菜单；因此对 JMenu 容器
        // 必须用 getPopupMenu()（菜单项列表的真身）来查找/新增同名子菜单，
        // 否则每次都会"找不到"并重复创建（实测出现 13 个各自只有 1 项的同名子菜单）。
        JComponent container = (parent instanceof JMenu) ? ((JMenu) parent).getPopupMenu() : parent;
        for (int i = 0; i < container.getComponentCount(); i++) {
            java.awt.Component c = container.getComponent(i);
            if (c instanceof JMenu && groupName.equals(((JMenu) c).getText())) {
                return (JMenu) c;
            }
        }
        JMenu menu = new JMenu(groupName);
        container.add(menu);
        return menu;
    }
}
