import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/**
 * 计时子球图标库（纯 Java2D 自绘，不引入任何图片资源，jar 体积与打包禁令都不受影响）。
 * <p>
 * 供全屏番茄钟的两个按钮使用：「暂停/继续」与「复原（回到上次设定的倒计时起点）」。
 * 图标按传入前景色绘制，透明背景，天然适配深色全屏。
 * </p>
 */
public final class TimerIcons {

    private TimerIcons() {
    }

    /** 暂停图标：两条竖条。 */
    public static Icon pause(Color color, int size) {
        return new DrawIcon(size) {
            @Override
            void paint(Graphics2D g2, int s) {
                g2.setColor(color);
                int barW = Math.max(3, s / 4);
                int h = (int) (s * 0.78);
                int y = (s - h) / 2;
                int gap = Math.max(3, s / 6);
                int x1 = s / 2 - gap / 2 - barW;
                int x2 = s / 2 + gap / 2;
                g2.fillRoundRect(x1, y, barW, h, barW / 2, barW / 2);
                g2.fillRoundRect(x2, y, barW, h, barW / 2, barW / 2);
            }
        };
    }

    /** 继续（播放）图标：右向实心三角。 */
    public static Icon play(Color color, int size) {
        return new DrawIcon(size) {
            @Override
            void paint(Graphics2D g2, int s) {
                g2.setColor(color);
                Path2D p = new Path2D.Double();
                double left = s * 0.26;
                double right = s * 0.80;
                p.moveTo(left, s * 0.16);
                p.lineTo(right, s * 0.5);
                p.lineTo(left, s * 0.84);
                p.closePath();
                g2.fill(p);
            }
        };
    }

    /**
     * 复原图标：逆时针回转箭头（箭头回到起点）——语义 = 恢复到上次设定的倒计时开始状态。
     */
    public static Icon restore(Color color, int size) {
        return new DrawIcon(size) {
            @Override
            void paint(Graphics2D g2, int s) {
                g2.setColor(color);
                g2.setStroke(new BasicStroke(Math.max(2f, s / 8f), BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                double inset = s * 0.20;
                // 缺口圆环（留出右上缺口给箭头）
                g2.draw(new Arc2D.Double(inset, inset, s - inset * 2, s - inset * 2,
                        70, -300, Arc2D.OPEN));
                // 箭头（指向缺口处，构成"回转"语义）
                Path2D arrow = new Path2D.Double();
                double cx = s * 0.5;
                double cy = s * 0.5;
                double r = (s - inset * 2) / 2.0;
                double angle = Math.toRadians(-20);
                double ax = cx + r * Math.cos(angle);
                double ay = cy - r * Math.sin(angle);
                double w = Math.max(3.0, s * 0.20);
                arrow.moveTo(ax - w * 0.15, ay - w);
                arrow.lineTo(ax + w * 0.85, ay + w * 0.15);
                arrow.lineTo(ax - w * 0.85, ay + w * 0.65);
                arrow.closePath();
                g2.fill(arrow);
            }
        };
    }

    /** 复位/归零图标：方块内一个逆时针箭头脚注（备选，未在主界面使用）。 */
    public static Icon reset(Color color, int size) {
        return new DrawIcon(size) {
            @Override
            void paint(Graphics2D g2, int s) {
                g2.setColor(color);
                int b = (int) (s * 0.42);
                g2.setStroke(new BasicStroke(Math.max(2f, s / 10f)));
                g2.drawRect((s - b) / 2, (int) (s * 0.42), b, b);
                g2.draw(new Arc2D.Double(s * 0.18, s * 0.18, s * 0.64, s * 0.64, 60, -260, Arc2D.OPEN));
            }
        };
    }

    /** 通用矢量图标基类（透明背景 + 抗锯齿）。 */
    private abstract static class DrawIcon implements Icon {

        private final int size;

        DrawIcon(int size) {
            this.size = Math.max(8, size);
        }

        abstract void paint(Graphics2D g2, int s);

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                paint(g2, size);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    // ===================== 屏幕/窗口几何工具（球落位防"跑到屏幕外"） =====================

    /**
     * 某矩形与"所有屏幕"的最大交集面积（用于判断记忆位置是否还在可视区内）。
     */
    public static long visibleArea(Rectangle r) {
        if (r == null) {
            return 0L;
        }
        long best = 0L;
        for (GraphicsConfiguration gc : screenConfigs()) {
            Rectangle inter = r.intersection(gc.getBounds());
            if (inter.width > 0 && inter.height > 0) {
                best = Math.max(best, (long) inter.width * inter.height);
            }
        }
        return best;
    }

    private static GraphicsConfiguration[] screenConfigs() {
        java.awt.GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getScreenDevices();
        GraphicsConfiguration[] configs = new GraphicsConfiguration[devices.length];
        for (int i = 0; i < devices.length; i++) {
            configs[i] = devices[i].getDefaultConfiguration();
        }
        return configs;
    }

    /** 主屏 bounds。 */
    public static Rectangle primaryScreenBounds() {
        try {
            Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            if (b != null && b.width > 0 && b.height > 0) {
                return b;
            }
        } catch (Throwable ignore) {
            // 回退
        }
        java.awt.Dimension d = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        return new Rectangle(0, 0, d.width, d.height);
    }

    /**
     * 把子球矩形修正到"完全落在某块屏幕内"：优先留在与它交集最大的屏幕，否则回主屏。
     * <p>
     * 用于修复"记忆位置落在屏幕外（换显示器/分辨率变化/曾被拖到边缘）导致子球看不见"的缺陷。
     * </p>
     */
    public static Rectangle clampToScreen(Rectangle r) {
        Rectangle best = null;
        long bestArea = -1L;
        for (GraphicsConfiguration gc : screenConfigs()) {
            Rectangle inter = r.intersection(gc.getBounds());
            long area = (inter.width > 0 && inter.height > 0)
                    ? (long) inter.width * inter.height : 0L;
            if (area > bestArea) {
                bestArea = area;
                best = gc.getBounds();
            }
        }
        if (best == null) {
            best = primaryScreenBounds();
        }
        int maxX = best.x + Math.max(0, best.width - r.width);
        int maxY = best.y + Math.max(0, best.height - r.height);
        int x = Math.max(best.x, Math.min(maxX, r.x));
        int y = Math.max(best.y, Math.min(maxY, r.y));
        return new Rectangle(x, y, r.width, r.height);
    }

    /** 点位是否在某块屏幕内。 */
    public static boolean isOnAnyScreen(Point p) {
        if (p == null) {
            return false;
        }
        for (GraphicsConfiguration gc : screenConfigs()) {
            if (gc.getBounds().contains(p)) {
                return true;
            }
        }
        return false;
    }
}
