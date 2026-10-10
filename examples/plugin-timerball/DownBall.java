import com.randomnamepicker.core.LogManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 倒计时子球（贴着宿主悬浮球显示的圆形倒计时窗）。
 * <p>
 * 与工作区内正计时子球（{@code examples/plugin-timer/TimerBall.java}）交互语言保持一致：
 * </p>
 * <ul>
 *   <li><b>右键：快捷菜单</b>——合并形态下倒计时子球<b>只提供右键菜单</b>（开始/暂停、归零、
 *       切全屏专注、快捷时长、设置、收起）；</li>
 *   <li>左键拖动：移动子球位置（未跟随悬浮球时由控制器持久化）；左键单击/双击不触发
 *       任何命令（左键交互只属于正计时子球）。</li>
 * </ul>
 * <p>
 * 自绘圆形 + 剩余比例进度环：就绪=灰蓝、运行=绿、警戒=琥珀、暂停=橙、到时=红（全部可在设置窗改）。
 * 窗口置顶、不抢键盘焦点、圆外四角透明。所有公开方法仅在 EDT 调用。
 * </p>
 */
public class DownBall extends JWindow {

    /** 子球右键菜单里的快捷时长（分钟）：直接平铺，避免子菜单渲染问题。 */
    private static final int[] QUICK_MINUTES = {1, 3, 5, 10, 15, 25};

    /** 子球自身交互回调（由控制器实现）。 */
    public interface Actions {
        /** 单击（延迟判定后）：开始/暂停/继续/重开。 */
        void onPrimaryClick();

        /** 双击：归零并收起。 */
        void onResetAndHide();

        /** 切到全屏专注形态。 */
        void onSwitchToFocus();

        /** 快捷时长（分钟）。 */
        void onQuickDuration(int minutes);

        /** 打开常规设置。 */
        void onOpenSettings();

        /** 收起（保留读数）。 */
        void onHide();

        /** 用户拖动后（保存位置）。 */
        void onMoved(Point location);
    }

    private final DownSession session;
    private final TimerBallConfig config;
    private final Actions actions;
    private final BallPanel panel = new BallPanel();

    private Point dragOffset;
    private boolean dragged;

    public DownBall(DownSession session, TimerBallConfig config, Actions actions) {
        this.session = session;
        this.config = config;
        this.actions = actions;

        setAlwaysOnTop(true);
        setFocusableWindowState(false); // 不抢宿主键盘焦点
        setBackground(new Color(0, 0, 0, 0));
        Container content = getContentPane();
        if (content instanceof JComponent) {
            ((JComponent) content).setOpaque(false);
        }
        setLayout(null);
        panel.setBounds(0, 0, 1, 1);
        add(panel);
        setSize(1, 1);
        setLocation(-20000, -20000); // 初始远离屏幕（不可见）

        installMouse();
    }

    // ===================== 鼠标交互 =====================

    private void installMouse() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showContextMenu(e);
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e)) {
                    dragOffset = e.getPoint();
                    dragged = false;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragOffset == null || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                dragged = true;
                Point p = e.getLocationOnScreen();
                setLocation(p.x - dragOffset.x, p.y - dragOffset.y);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragged && dragOffset != null) {
                    dragged = false;
                    dragOffset = null;
                    if (actions != null) {
                        try {
                            actions.onMoved(getLocation());
                        } catch (Throwable t) {
                            LogManager.log("计时子球-倒计时：保存子球位置失败: " + t, "PLUGIN_LOAD_ERROR");
                        }
                    }
                }
                dragOffset = null;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                // 合并形态口径：倒计时子球不使用左键交互（左键只用于拖动窗口），
                // 一切命令走右键菜单（与宿主悬浮球右键菜单同一套命令）。
                // 因此这里刻意不实现"单击=开始/暂停、双击=归零收起"。
            }
        };
        panel.addMouseListener(mouse);
        panel.addMouseMotionListener(mouse);
    }

    private void showContextMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem primary = new JMenuItem(session.isRunning() ? "暂停" : (session.isIdle() ? "开始" : "继续"));
        primary.addActionListener(a -> {
            if (actions != null) {
                actions.onPrimaryClick();
            }
        });
        menu.add(primary);

        JMenuItem reset = new JMenuItem("归零（回到设定时长）");
        reset.addActionListener(a -> {
            if (actions != null) {
                actions.onResetAndHide();
            }
        });
        menu.add(reset);

        JMenuItem focus = new JMenuItem("全屏专注（番茄钟）");
        focus.addActionListener(a -> {
            if (actions != null) {
                actions.onSwitchToFocus();
            }
        });
        menu.add(focus);

        // 快捷时长：直接平铺（不再使用子菜单——子菜单在本环境下曾出现文字重影/乱码渲染缺陷）
        menu.addSeparator();
        for (int m : QUICK_MINUTES) {
            JMenuItem item = new JMenuItem("快捷 " + m + " 分钟");
            item.addActionListener(a -> {
                if (actions != null) {
                    actions.onQuickDuration(m);
                }
            });
            menu.add(item);
        }

        menu.addSeparator();
        JMenuItem settings = new JMenuItem("倒计时设置…");
        settings.addActionListener(a -> {
            if (actions != null) {
                actions.onOpenSettings();
            }
        });
        menu.add(settings);

        JMenuItem hide = new JMenuItem("收起");
        hide.addActionListener(a -> {
            if (actions != null) {
                actions.onHide();
            }
        });
        menu.add(hide);

        menu.show(panel, e.getX(), e.getY());
    }

    // ===================== 显示 =====================

    /** 设置直径（缩放时同步面板尺寸）。 */
    public void setDiameter(int d) {
        int n = Math.max(TimerBallConfig.FIXED_DIAMETER_MIN, d);
        if (getWidth() == n && getHeight() == n) {
            return;
        }
        setSize(n, n);
        panel.setBounds(0, 0, n, n);
        panel.repaint();
    }

    public int getDiameter() {
        return getWidth();
    }

    /** 外观/读数变更后重绘。 */
    public void refresh() {
        panel.repaint();
    }

    @Override
    public void dispose() {
        super.dispose();
    }

    // ===================== 自绘 =====================

    /** 当前状态色（就绪/运行/警戒/暂停/到时）。 */
    private Color stateColor() {
        if (session.isFinished()) {
            return config.getColorFinished();
        }
        if (session.isPaused()) {
            return config.getColorPaused();
        }
        if (session.isRunning()) {
            if (session.isWarning(config.getWarnSeconds())) {
                return config.getColorWarning();
            }
            return config.getColorRunning();
        }
        return config.getColorIdle();
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    private class BallPanel extends JPanel {

        BallPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int d = Math.min(w, h);
            Color base = stateColor();

            // 底圆
            g2.setColor(base);
            g2.fillOval(0, 0, w, h);

            // 进度环（剩余比例，12 点顺时针收缩）
            if (config.isShowProgressRing() && !session.isIdle()) {
                double left = 1.0 - session.progress();
                int inset = Math.max(2, d / 14);
                g2.setColor(alpha(Color.WHITE, 55));
                g2.setStroke(new BasicStroke(Math.max(2f, d / 16f), BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g2.drawOval(inset, inset, Math.max(1, w - inset * 2), Math.max(1, h - inset * 2));
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(Math.max(2f, d / 16f), BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                Arc2D arc = new Arc2D.Double(inset, inset, Math.max(1, w - inset * 2),
                        Math.max(1, h - inset * 2), 90, -360.0 * left, Arc2D.OPEN);
                g2.draw(arc);
            } else {
                g2.setColor(alpha(Color.WHITE, 190));
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval(1, 1, Math.max(0, w - 2), Math.max(0, h - 2));
            }

            // 到时再描一圈亮边提示
            if (session.isFinished()) {
                g2.setColor(alpha(Color.WHITE, 220));
                g2.setStroke(new BasicStroke(3f));
                g2.drawOval(1, 1, Math.max(0, w - 2), Math.max(0, h - 2));
            }

            // 居中读数
            String txt = session.displayText(config.isShowHours(),
                    session.isRunning() || session.isPaused());
            int fs = Math.max(8, (int) (d * 0.30));
            Font font = new Font("微软雅黑", Font.BOLD, fs);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics(font);
            int maxWidth = Math.max(8, w - Math.max(8, d / 5));
            while (fs > 7 && fm.stringWidth(txt) > maxWidth) {
                fs--;
                font = new Font("微软雅黑", Font.BOLD, fs);
                g2.setFont(font);
                fm = g2.getFontMetrics(font);
            }
            g2.setColor(Color.WHITE);
            int tw = fm.stringWidth(txt);
            int x = (w - tw) / 2;
            int y = (h - (fm.getAscent() + fm.getDescent())) / 2 + fm.getAscent();
            g2.drawString(txt, x, y);

            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(Math.max(1, getWidth()), Math.max(1, getHeight()));
        }
    }

    /** 供控制器判断"某屏幕坐标是否落在子球上"（用于避免自命中，可选）。 */
    public boolean containsPoint(Point screenPoint) {
        Point p = getLocation();
        return screenPoint != null && screenPoint.x >= p.x && screenPoint.y >= p.y
                && screenPoint.x < p.x + getWidth() && screenPoint.y < p.y + getHeight();
    }
}
