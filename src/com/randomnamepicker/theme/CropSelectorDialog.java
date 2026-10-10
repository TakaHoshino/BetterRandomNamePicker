package com.randomnamepicker.theme;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/**
 * 自由裁切对话框（宿主内建主题）。**2026-10 重做**，修掉"拖动时看不到实时框"的老问题并补齐裁剪手感：
 * <ul>
 *   <li><b>实时显示</b>：拖动过程中就画当前选区（旧实现里 committed crop 恒非 null，导致拖动中的框从不绘制，
 *       松手才跳变）；选区外做暗化遮罩，配三分辅助线；</li>
 *   <li>八向手柄 + 框内拖动：可反复微调、整体平移，而不是每次拖拽都重画；</li>
 *   <li>实时读数据：裁切像素尺寸、比例、占全图百分比（按**源图像素**换算）；</li>
 *   <li><b>应用后预览</b>：右侧小窗用真实 {@link WindowBackdrop}（CROP 模式 + 当前遮罩）渲染，
 *       即"裁完贴到窗口上是什么样"，与真实窗口同一渲染路径；</li>
 *   <li>常用比例（16:9 / 4:3 / 1:1）与「整图」一键重置；ESC 取消、Enter 确定；</li>
 *   <li>选区严格夹在图片范围内，并保证不小于 {@value #MIN_SRC_PX} 源像素。</li>
 * </ul>
 * 生产入口仍是 {@link #showDialog(Window, File, Rectangle2D.Double)}（模态）；{@link #createDetached} 供自动化测试使用。
 */
public class CropSelectorDialog extends JDialog {

    private static final int CANVAS_W = 620;
    private static final int CANVAS_H = 420;
    private static final int HANDLE = 9;
    private static final int HIT_TOL = 7;
    /** 最小裁切尺寸（源图像素）。 */
    private static final int MIN_SRC_PX = 12;

    /**
     * 预设比例：标签与比值（0 = 全框整图）。点击后在**整图内取该比例的最大矩形**，
     * 并**保留当前选区的中心**（例如先把框挪到人物脸上，再点 16:9 → 以该处为中心取最大 16:9）。
     */
    private static final String[] PRESET_LABELS = {
            "全框", "16:9", "4:3", "1:1", "9:16", "3:2", "16:10", "21:9", "2:1", "4:5", "3:4"
    };
    private static final double[] PRESET_RATIOS = {
            0, 16 / 9.0, 4 / 3.0, 1, 9 / 16.0, 3 / 2.0, 16 / 10.0, 21 / 9.0, 2, 4 / 5.0, 3 / 4.0
    };

    private static final int NONE = 0;
    private static final int NEW = 1;
    private static final int MOVE = 2;
    private static final int N = 3;
    private static final int S = 4;
    private static final int W = 5;
    private static final int E = 6;
    private static final int NW = 7;
    private static final int NE = 8;
    private static final int SW = 9;
    private static final int SE = 10;

    private final BufferedImage image;
    private final File imageFile;
    private final PreviewPane canvas = new PreviewPane();
    private final WindowBackdrop resultPreview = new WindowBackdrop();
    private final JLabel sizeLabel = new JLabel();
    private final JLabel infoLabel = new JLabel();

    /** 已确定的裁切框（归一化）；拖动过程中由 {@link #live} 覆盖显示。 */
    private Rectangle2D.Double crop;
    /** 拖动过程中的实时框（松手后提交到 crop）。 */
    private Rectangle2D.Double live;
    private int dragMode = NONE;
    private Point pressPoint;
    private Rectangle2D.Double pressCrop;

    private CropSelectorDialog(Window owner, File imageFile, BufferedImage image,
                               Rectangle2D.Double initial, boolean modal) {
        super(owner, "自由裁切", modal ? ModalityType.APPLICATION_MODAL : ModalityType.MODELESS);
        this.imageFile = imageFile;
        this.image = image;
        this.crop = initial != null ? clampCrop(initial) : (Rectangle2D.Double) ThemeStyle.FULL_CROP.clone();

        setLayout(new BorderLayout(8, 8));
        canvas.setPreferredSize(new Dimension(CANVAS_W, CANVAS_H));
        add(canvas, BorderLayout.CENTER);
        add(buildSidePanel(), BorderLayout.EAST);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        JButton ok = new JButton("确定");
        ok.addActionListener(e -> dispose());
        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> {
            crop = null;
            dispose();
        });
        bottom.add(ok);
        bottom.add(cancel);
        add(bottom, BorderLayout.SOUTH);

        getRootPane().registerKeyboardAction(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                crop = null;
                dispose();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setMinimumSize(new Dimension(760, 520));
        setLocationRelativeTo(owner);
        refresh();
    }

    /** 打开裁剪框；返回新的归一化裁剪框；取消返回 null。 */
    public static Rectangle2D.Double showDialog(Window owner, File imageFile, Rectangle2D.Double initial) {
        BufferedImage img = readImage(imageFile);
        if (img == null) {
            JOptionPane.showMessageDialog(owner, "无法读取该图片，请重新导入。", "提示",
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }
        CropSelectorDialog dlg = new CropSelectorDialog(owner, imageFile, img, initial, true);
        dlg.setVisible(true);
        return dlg.crop;
    }

    /** 非模态实例（供自动化测试/预览使用；生产路径用 {@link #showDialog}）。 */
    public static CropSelectorDialog createDetached(Window owner, File imageFile, Rectangle2D.Double initial) {
        BufferedImage img = readImage(imageFile);
        if (img == null) {
            return null;
        }
        return new CropSelectorDialog(owner, imageFile, img, initial, false);
    }

    /** 供测试读取当前（已提交的）裁切框。 */
    public Rectangle2D.Double currentCrop() {
        return crop;
    }

    /** 供测试派发鼠标事件用的画布组件。 */
    public Component previewComponent() {
        return canvas;
    }

    private static BufferedImage readImage(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try {
            return ImageIO.read(f);
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== 侧栏：读数据 + 应用后预览 + 快捷比例 =====================

    private JPanel buildSidePanel() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        side.setPreferredSize(new Dimension(300, 0));

        JLabel title = new JLabel("裁切信息");
        title.setFont(new Font("微软雅黑", Font.BOLD, 13));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(title);

        sizeLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        sizeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(sizeLabel);
        infoLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(infoLabel);
        side.add(javax.swing.Box.createVerticalStrut(10));

        JLabel pvTitle = new JLabel("应用后预览（CROP·16:9·当前遮罩）");
        pvTitle.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        pvTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(pvTitle);
        resultPreview.setPreferredSize(new Dimension(260, 146));
        resultPreview.setMaximumSize(new Dimension(260, 146));
        resultPreview.setBorder(BorderFactory.createLineBorder(new Color(0x99, 0x99, 0x99)));
        resultPreview.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(resultPreview);
        side.add(javax.swing.Box.createVerticalStrut(10));

        JLabel tip = new JLabel("<html>在图上拖动 = 重新框选（整图状态下直接拖即可）；<br>"
                + "框内拖动 = 整体移动；拖 8 个手柄 = 微调；<br>按住 Alt 拖动 = 强制重新框选。<br>"
                + "ESC 取消 / Enter 确定。</html>");
        tip.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        tip.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(tip);
        side.add(javax.swing.Box.createVerticalStrut(8));

        JPanel ratios = new JPanel(new java.awt.GridLayout(0, 4, 4, 3));
        ratios.setAlignmentX(Component.LEFT_ALIGNMENT);
        ratios.add(sectionLabel("预设比例"));
        for (int i = 0; i < PRESET_LABELS.length; i++) {
            ratios.add(presetButton(i));
        }
        side.add(ratios);
        side.add(javax.swing.Box.createVerticalStrut(6));
        JLabel presetTip = new JLabel("<html>预设会取该比例的最大框，<br>并保留当前选区中心。</html>");
        presetTip.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        presetTip.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(presetTip);
        return side;
    }

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("微软雅黑", Font.BOLD, 11));
        return l;
    }

    private JButton presetButton(final int index) {
        JButton b = new JButton(PRESET_LABELS[index]);
        b.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        b.setMargin(new java.awt.Insets(2, 4, 2, 4));
        b.setToolTipText(PRESET_RATIOS[index] == 0
                ? "全框：整张图（等同重置）"
                : PRESET_LABELS[index] + "：在整图内取该比例最大矩形，保留当前选区中心");
        b.addActionListener(e -> applyPreset(index));
        return b;
    }

    /**
     * 应用预设：{@code 0} = 全框整图；其它 = 在整图内取该比例的**最大**矩形，
     * 并以**当前选区中心**为基准放置（越界则贴边夹取）。
     */
    private void applyPreset(int index) {
        double ratio = PRESET_RATIOS[index];
        if (ratio <= 0) {
            crop = (Rectangle2D.Double) ThemeStyle.FULL_CROP.clone();
            refresh();
            canvas.repaint();
            return;
        }
        int sw = image.getWidth();
        int sh = image.getHeight();
        double w = sw;
        double h = sw / ratio;
        if (h > sh) {
            h = sh;
            w = sh * ratio;
        }
        double cx = (crop != null ? crop.x + crop.width / 2 : 0.5) * sw;
        double cy = (crop != null ? crop.y + crop.height / 2 : 0.5) * sh;
        double x = Math.max(0, Math.min(sw - w, cx - w / 2));
        double y = Math.max(0, Math.min(sh - h, cy - h / 2));
        crop = new Rectangle2D.Double(x / sw, y / sh, w / sw, h / sh);
        refresh();
        canvas.repaint();
    }

    // ===================== 状态刷新 =====================

    private Rectangle2D.Double displayCrop() {
        return live != null ? live : crop;
    }

    private void refresh() {
        Rectangle2D.Double c = displayCrop();
        int sw = image.getWidth();
        int sh = image.getHeight();
        int cw = Math.max(1, (int) Math.round(c.width * sw));
        int ch = Math.max(1, (int) Math.round(c.height * sh));
        sizeLabel.setText("裁切 " + cw + " × " + ch + " px　（源图 " + sw + " × " + sh + "）");
        int g = gcd(cw, ch);
        String ratioText = (g > 0 ? (cw / g) + ":" + (ch / g) : "-");
        double pct = 100.0 * (c.width * c.height);
        String preset = matchedPreset(cw, ch, c);
        infoLabel.setText(String.format("比例 %s（%.2f）　占全图 %.1f%%%s", ratioText, cw / (double) ch, pct,
                preset.isEmpty() ? "" : "　预设：" + preset));

        // 应用后预览：与真实窗口同一渲染路径（CROP 模式 + 当前遮罩）
        if (imageFile != null) {
            resultPreview.setStyle(new ThemeStyle(true, imageFile, BackdropMode.CROP, c, ThemeStore.getMask()));
        }
    }

    private static int gcd(int a, int b) {
        int x = Math.abs(a);
        int y = Math.abs(b);
        while (y != 0) {
            int t = x % y;
            x = y;
            y = t;
        }
        return x;
    }

    /** 命中的预设名（整图 / 比例与某预设相差 <0.5%），未命中返回空串。 */
    private static String matchedPreset(int cw, int ch, Rectangle2D.Double c) {
        if (isFullImageCrop(c)) {
            return "全框";
        }
        double ratio = cw / (double) ch;
        for (int i = 1; i < PRESET_LABELS.length; i++) {
            if (Math.abs(ratio - PRESET_RATIOS[i]) / PRESET_RATIOS[i] < 0.005) {
                return PRESET_LABELS[i];
            }
        }
        return "";
    }

    // ===================== 几何换算 / 夹取 =====================

    /** 图片在画布内的等比适配区域（contain 居中）。 */
    private Rectangle fitRect() {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        if (w <= 0 || h <= 0 || image == null) {
            return new Rectangle(0, 0, Math.max(1, w), Math.max(1, h));
        }
        int iw = image.getWidth();
        int ih = image.getHeight();
        double scale = Math.min((double) w / iw, (double) h / ih);
        int dw = Math.max(1, (int) Math.round(iw * scale));
        int dh = Math.max(1, (int) Math.round(ih * scale));
        return new Rectangle((w - dw) / 2, (h - dh) / 2, dw, dh);
    }

    private Rectangle toScreen(Rectangle2D.Double c) {
        Rectangle area = fitRect();
        return new Rectangle(
                area.x + (int) Math.round(c.x * area.width),
                area.y + (int) Math.round(c.y * area.height),
                Math.max(1, (int) Math.round(c.width * area.width)),
                Math.max(1, (int) Math.round(c.height * area.height)));
    }

    /** 屏幕矩形 → 归一化并夹在图片内，保证不小于最小尺寸。 */
    private Rectangle2D.Double normalize(Rectangle screenRect) {
        Rectangle area = fitRect();
        double x = (screenRect.x - area.x) / (double) Math.max(1, area.width);
        double y = (screenRect.y - area.y) / (double) Math.max(1, area.height);
        double w = screenRect.width / (double) Math.max(1, area.width);
        double h = screenRect.height / (double) Math.max(1, area.height);
        return clampCrop(new Rectangle2D.Double(x, y, w, h));
    }

    /** 夹到 [0,1] 且不小于最小源像素尺寸。 */
    private Rectangle2D.Double clampCrop(Rectangle2D.Double c) {
        int sw = image.getWidth();
        int sh = image.getHeight();
        double minW = Math.min(1, MIN_SRC_PX / (double) Math.max(1, sw));
        double minH = Math.min(1, MIN_SRC_PX / (double) Math.max(1, sh));
        double w = Math.max(minW, Math.min(1, c.width));
        double h = Math.max(minH, Math.min(1, c.height));
        double x = Math.max(0, Math.min(1 - w, c.x));
        double y = Math.max(0, Math.min(1 - h, c.y));
        return new Rectangle2D.Double(x, y, w, h);
    }

    /** 是否"整幅选中"（此时拖动应理解为重新框选，而不是移动）。 */
    private static boolean isFullImageCrop(Rectangle2D.Double c) {
        return c != null && c.x <= 0.001 && c.y <= 0.001 && c.width >= 0.999 && c.height >= 0.999;
    }

    private static int cursorFor(int mode) {
        switch (mode) {
            case N: case S:
                return Cursor.N_RESIZE_CURSOR;
            case W: case E:
                return Cursor.W_RESIZE_CURSOR;
            case NW: case SE:
                return Cursor.NW_RESIZE_CURSOR;
            case NE: case SW:
                return Cursor.NE_RESIZE_CURSOR;
            case MOVE:
                return Cursor.MOVE_CURSOR;
            default:
                return Cursor.CROSSHAIR_CURSOR;
        }
    }

    // ===================== 画布：实时绘制 + 拖拽交互 =====================

    private final class PreviewPane extends JPanel {

        PreviewPane() {
            setBackground(new Color(48, 48, 48));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (crop == null) {
                        crop = (Rectangle2D.Double) ThemeStyle.FULL_CROP.clone();
                    }
                    pressPoint = e.getPoint();
                    pressCrop = (Rectangle2D.Double) crop.clone();
                    dragMode = hitTest(e.getPoint());
                    // 交互修正：
                    // ① 初始/重置后裁切框=整图，"框内拖动=移动"没有意义（无处可移，用户会觉得"拖不动"）→ 直接重新框选；
                    // ② 任何时候按住 Alt 拖动 = 强制重新框选。
                    boolean altDown = (e.getModifiersEx() & MouseEvent.ALT_DOWN_MASK) != 0;
                    if (altDown || (dragMode == MOVE && isFullImageCrop(crop))) {
                        dragMode = NEW;
                    }
                    if (dragMode == NONE) {
                        dragMode = NEW;
                        live = normalize(new Rectangle(pressPoint.x, pressPoint.y, 1, 1));
                    } else {
                        live = (Rectangle2D.Double) crop.clone();
                    }
                    refresh();
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragMode == NONE) {
                        return;
                    }
                    if (dragMode == NEW) {
                        Rectangle r = new Rectangle(
                                Math.min(pressPoint.x, e.getX()), Math.min(pressPoint.y, e.getY()),
                                Math.abs(e.getX() - pressPoint.x), Math.abs(e.getY() - pressPoint.y));
                        live = normalize(r);
                    } else {
                        live = adjust(dragMode, e.getPoint());
                    }
                    refresh();
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (live != null) {
                        crop = live;
                    }
                    live = null;
                    dragMode = NONE;
                    pressPoint = null;
                    pressCrop = null;
                    refresh();
                    repaint();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(cursorFor(hitTest(e.getPoint()))));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setCursor(Cursor.getDefaultCursor());
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        /** 命中测试：先手柄、再框内（移动）、否则新画。 */
        private int hitTest(Point p) {
            if (crop == null) {
                return NONE;
            }
            Rectangle s = toScreen(crop);
            int x = s.x;
            int y = s.y;
            int r = s.x + s.width;
            int b = s.y + s.height;
            boolean left = Math.abs(p.x - x) <= HIT_TOL;
            boolean right = Math.abs(p.x - r) <= HIT_TOL;
            boolean top = Math.abs(p.y - y) <= HIT_TOL;
            boolean bottom = Math.abs(p.y - b) <= HIT_TOL;
            boolean inX = p.x >= x - HIT_TOL && p.x <= r + HIT_TOL;
            boolean inY = p.y >= y - HIT_TOL && p.y <= b + HIT_TOL;
            if (left && top) {
                return NW;
            }
            if (right && top) {
                return NE;
            }
            if (left && bottom) {
                return SW;
            }
            if (right && bottom) {
                return SE;
            }
            if (left && inY) {
                return W;
            }
            if (right && inY) {
                return E;
            }
            if (top && inX) {
                return N;
            }
            if (bottom && inX) {
                return S;
            }
            if (s.contains(p)) {
                return MOVE;
            }
            return NONE;
        }

        /** 依据按下的模式与当前鼠标位置，算出新的归一化裁切框（以按下时的框为基准）。 */
        private Rectangle2D.Double adjust(int mode, Point p) {
            Rectangle area = fitRect();
            double dx = (p.x - pressPoint.x) / (double) Math.max(1, area.width);
            double dy = (p.y - pressPoint.y) / (double) Math.max(1, area.height);
            if (mode == MOVE) {
                return clampCrop(new Rectangle2D.Double(pressCrop.x + dx, pressCrop.y + dy,
                        pressCrop.width, pressCrop.height));
            }
            double x1 = pressCrop.x;
            double y1 = pressCrop.y;
            double x2 = pressCrop.x + pressCrop.width;
            double y2 = pressCrop.y + pressCrop.height;
            if (mode == W || mode == NW || mode == SW) {
                x1 = pressCrop.x + dx;
            }
            if (mode == E || mode == NE || mode == SE) {
                x2 = pressCrop.x + pressCrop.width + dx;
            }
            if (mode == N || mode == NW || mode == NE) {
                y1 = pressCrop.y + dy;
            }
            if (mode == S || mode == SW || mode == SE) {
                y2 = pressCrop.y + pressCrop.height + dy;
            }
            double nx = Math.min(x1, x2);
            double ny = Math.min(y1, y2);
            double nw = Math.abs(x2 - x1);
            double nh = Math.abs(y2 - y1);
            return clampCrop(new Rectangle2D.Double(nx, ny, nw, nh));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) {
                return;
            }
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            Rectangle area = fitRect();
            g2d.drawImage(image, area.x, area.y, area.width, area.height, null);

            // 图片之外的区域压暗，边界清楚
            g2d.setColor(new Color(0, 0, 0, 140));
            if (area.x > 0) {
                g2d.fillRect(0, 0, area.x, getHeight());
            }
            if (area.y > 0) {
                g2d.fillRect(area.x, 0, area.width, area.y);
            }
            if (area.x + area.width < getWidth()) {
                g2d.fillRect(area.x + area.width, 0, getWidth() - area.x - area.width, getHeight());
            }
            if (area.y + area.height < getHeight()) {
                g2d.fillRect(area.x, area.y + area.height, area.width, getHeight() - area.y - area.height);
            }

            Rectangle2D.Double c = displayCrop();
            if (c == null) {
                g2d.dispose();
                return;
            }
            Rectangle sel = toScreen(c);

            // 选区外压暗（"只见选区"效果）
            Area outside = new Area(new Rectangle(area.x, area.y, area.width, area.height));
            outside.subtract(new Area(sel));
            g2d.setColor(new Color(0, 0, 0, 120));
            g2d.fill(outside);

            // 三分辅助线
            g2d.setColor(new Color(255, 255, 255, 70));
            g2d.setStroke(new BasicStroke(1f));
            for (int i = 1; i <= 2; i++) {
                int gx = sel.x + sel.width * i / 3;
                int gy = sel.y + sel.height * i / 3;
                g2d.drawLine(gx, sel.y, gx, sel.y + sel.height);
                g2d.drawLine(sel.x, gy, sel.x + sel.width, gy);
            }

            // 选区边框（白描边 + 蓝内线，保证深浅背景都看得清）
            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(2f));
            g2d.drawRect(sel.x, sel.y, sel.width, sel.height);
            g2d.setColor(new Color(0x1E, 0x88, 0xE5));
            g2d.setStroke(new BasicStroke(1f));
            g2d.drawRect(sel.x, sel.y, sel.width, sel.height);

            // 8 个手柄
            for (Point h : handlePoints(sel)) {
                g2d.setColor(Color.WHITE);
                g2d.fillRect(h.x - HANDLE / 2, h.y - HANDLE / 2, HANDLE, HANDLE);
                g2d.setColor(new Color(0x1E, 0x88, 0xE5));
                g2d.drawRect(h.x - HANDLE / 2, h.y - HANDLE / 2, HANDLE, HANDLE);
            }

            // 拖动中的尺寸提示（贴着选区内侧显示，避免看侧栏）
            if (live != null) {
                int cw = Math.max(1, (int) Math.round(live.width * image.getWidth()));
                int ch = Math.max(1, (int) Math.round(live.height * image.getHeight()));
                String txt = cw + " × " + ch;
                g2d.setFont(new Font("微软雅黑", Font.BOLD, 12));
                int tw = g2d.getFontMetrics().stringWidth(txt);
                int bx = Math.max(area.x, Math.min(sel.x + 6, area.x + area.width - tw - 12));
                int by = Math.max(area.y, sel.y + 6);
                g2d.setColor(new Color(0, 0, 0, 160));
                g2d.fillRect(bx - 4, by - 2, tw + 8, 20);
                g2d.setColor(Color.WHITE);
                g2d.drawString(txt, bx, by + 14);
            }
            g2d.dispose();
        }

        private java.util.List<Point> handlePoints(Rectangle s) {
            java.util.List<Point> pts = new java.util.ArrayList<>();
            int cx = s.x + s.width / 2;
            int cy = s.y + s.height / 2;
            int r = s.x + s.width;
            int b = s.y + s.height;
            pts.add(new Point(s.x, s.y));
            pts.add(new Point(cx, s.y));
            pts.add(new Point(r, s.y));
            pts.add(new Point(r, cy));
            pts.add(new Point(r, b));
            pts.add(new Point(cx, b));
            pts.add(new Point(s.x, b));
            pts.add(new Point(s.x, cy));
            return pts;
        }
    }
}
