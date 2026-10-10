import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;

/**
 * 子球落位算法（计时子球插件共用；无状态、纯计算）。
 * <p>
 * 合并形态约束（定时子球/正计时+倒计时）：两颗子球以宿主悬浮球为锚点，默认按"屏幕下半→正上方、
 * 上半→正下方"冒出，但必须<b>互不重叠</b>。故本类按候选位优先级在锚点周围搜索：
 * 同一侧 → 另一侧 → 左侧 → 右侧 → 锚点上方 → 锚点下方，首个"在屏内且不与已占用矩形相交"的候选位胜出。
 * </p>
 * <p>
 * 候选顺序取"同侧优先"是为了保持与原正计时子球完全一致的观感（单个子球时结果与旧算法逐像素相同）；
 * 当倒计时子球也需要落位时，它把正计时子球矩形作为 {@code occupied} 传入，于是自动换到另一侧/侧向。
 * 若所有候选位都被占满，则回退到"占用最少"的候选位（宁可轻微重叠也不出屏）。
 * </p>
 */
public final class BallLayout {

    /** 候选位之间的最小间隙（px）。 */
    public static final int MIN_GAP = 8;

    private BallLayout() {
    }

    /** 候选位（相对锚点矩形的方位 + 水平对齐方式）。 */
    private enum Slot {
        ABOVE, BELOW, LEFT, RIGHT
    }

    /** 水平对齐：居中 / 贴左 / 贴右。 */
    private enum Align {
        CENTER, START, END
    }

    /**
     * 计算子球位置。
     *
     * @param anchor  宿主悬浮球矩形（屏幕坐标）
     * @param size    子球直径（px）
     * @param gap     与锚点间距（配置项）
     * @param screen  所在屏幕 bounds
     * @param occupied 需要避让的矩形（其它已显示子球；可为 null/空）
     * @return 子球左上角坐标
     */
    public static Point placeAnchored(Rectangle anchor, int size, int gap,
                                      Rectangle screen, Rectangle[] occupied) {
        int d = Math.max(1, size);
        int g = Math.max(1, gap);
        boolean putAbove = (anchor.y + anchor.height / 2) > (screen.y + screen.height / 2);

        // 主候选（与原算法一致）：下半屏→上方；上半屏→下方；自带"空间不足改另一侧"的兜底
        Slot primary = putAbove ? Slot.ABOVE : Slot.BELOW;
        Slot secondary = putAbove ? Slot.BELOW : Slot.ABOVE;

        Slot[] order = {primary, secondary, Slot.LEFT, Slot.RIGHT};
        Point best = null;
        int bestOverlap = Integer.MAX_VALUE;

        for (Slot slot : order) {
            for (Align align : new Align[]{Align.CENTER, Align.START, Align.END}) {
                Point p = pointFor(anchor, d, g, screen, slot, align);
                if (p == null) {
                    continue;
                }
                Rectangle rect = new Rectangle(p.x, p.y, d, d);
                if (!inside(rect, screen)) {
                    continue;
                }
                int overlap = overlapArea(rect, occupied);
                if (overlap == 0) {
                    return p; // 首个"在屏内且不重叠"的候选位
                }
                if (overlap < bestOverlap) {
                    bestOverlap = overlap;
                    best = p;
                }
            }
        }
        if (best != null) {
            return best; // 全部候选位都会重叠：取重叠面积最小者（仍保证在屏内）
        }
        // 极端窄屏：退化为最朴素的 clamp 落位，保证一定返回可用坐标
        return pointFor(anchor, d, g, screen, primary, Align.CENTER);
    }

    /** 单一候选位坐标（不做占用判断；越界时返回已 clamp 的坐标）。 */
    private static Point pointFor(Rectangle anchor, int d, int g, Rectangle screen,
                                 Slot slot, Align align) {
        int x;
        int y;
        switch (slot) {
            case ABOVE:
                x = alignX(anchor, d, align);
                y = anchor.y - g - d;
                break;
            case BELOW:
                x = alignX(anchor, d, align);
                y = anchor.y + anchor.height + g;
                break;
            case LEFT:
                x = anchor.x - g - d;
                y = anchor.y + (anchor.height - d) / 2;
                break;
            case RIGHT:
            default:
                x = anchor.x + anchor.width + g;
                y = anchor.y + (anchor.height - d) / 2;
                break;
        }
        int maxX = screen.x + Math.max(0, screen.width - d);
        int maxY = screen.y + Math.max(0, screen.height - d);
        return new Point(clamp(x, screen.x, maxX), clamp(y, screen.y, maxY));
    }

    private static int alignX(Rectangle anchor, int d, Align align) {
        int cx = anchor.x + anchor.width / 2;
        switch (align) {
            case START:
                return anchor.x + anchor.width - d;      // 贴锚点右侧
            case END:
                return anchor.x;                          // 贴锚点左侧
            case CENTER:
            default:
                return cx - d / 2;
        }
    }

    private static boolean inside(Rectangle r, Rectangle screen) {
        return r.x >= screen.x && r.y >= screen.y
                && r.x + r.width <= screen.x + screen.width
                && r.y + r.height <= screen.y + screen.height;
    }

    /** 与全部占用矩形的重叠面积合计。 */
    public static int overlapArea(Rectangle r, Rectangle[] occupied) {
        if (occupied == null || occupied.length == 0) {
            return 0;
        }
        int sum = 0;
        for (Rectangle o : occupied) {
            if (o == null) {
                continue;
            }
            Rectangle inter = r.intersection(o);
            if (inter.width > 0 && inter.height > 0) {
                sum += inter.width * inter.height;
            }
        }
        return sum;
    }

    /** 是否与任一占用矩形重叠（供自检/调试断言使用）。 */
    public static boolean overlaps(Rectangle r, Rectangle[] occupied) {
        return overlapArea(r, occupied) > 0;
    }

    /**
     * 指定屏幕 bounds（多屏时用窗口自身所在屏；取不到则回退主屏尺寸）。
     */
    public static Rectangle screenBoundsFor(java.awt.Window window) {
        try {
            if (window != null) {
                GraphicsConfiguration gc = window.getGraphicsConfiguration();
                if (gc != null) {
                    return gc.getBounds();
                }
            }
        } catch (Throwable ignore) {
            // 回退主屏
        }
        java.awt.Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        return new Rectangle(0, 0, d.width, d.height);
    }

    private static int clamp(int v, int lo, int hi) {
        if (lo > hi) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
