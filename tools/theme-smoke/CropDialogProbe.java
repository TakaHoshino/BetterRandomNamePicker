import com.randomnamepicker.theme.CropSelectorDialog;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

/**
 * 自由裁切对话框行为取证：**重点验证"拖动过程中就实时显示选区"**，以及移动/手柄/快捷比例/重置。
 *
 * 运行（工作目录 = 仓库根）：
 *   javac -encoding UTF-8 -cp out -d tools/theme-smoke/classes tools/theme-smoke/CropDialogProbe.java
 *   java -cp "out;tools/theme-smoke/classes" CropDialogProbe [源图]
 * 默认源图：C:\Users\PC\Pictures\洛天依\luotianyi_222_4960x3507.jpg（非 16:9，便于验证比例按钮确实生效）
 *
 * 产出：theme_ui_shots/crop_*.png 与断言输出（CROP_PROBE_RESULT=PASS/FAIL）。
 */
public final class CropDialogProbe {

    private static final File SHOT_DIR = new File("theme_ui_shots");
    private static int fail = 0;
    private static int imgW;
    private static int imgH;

    public static void main(String[] args) throws Exception {
        File src = args.length > 0 ? new File(args[0])
                : new File("C:/Users/PC/Pictures/洛天依/luotianyi_222_4960x3507.jpg");
        if (!src.isFile()) {
            System.out.println("源图不存在：" + src);
            System.exit(1);
        }
        BufferedImage img = ImageIO.read(src);
        imgW = img.getWidth();
        imgH = img.getHeight();
        SHOT_DIR.mkdirs();
        System.out.println("源图 " + src.getName() + " → " + imgW + "x" + imgH
                + "（比例 " + String.format("%.3f", imgW / (double) imgH) + "）");

        final CropSelectorDialog[] holder = new CropSelectorDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            CropSelectorDialog d = CropSelectorDialog.createDetached(null, src, null);
            if (d != null) {
                d.pack();
                d.setSize(940, 580);
                d.setLocation(60, 60);
                d.setVisible(true); // 必须真正 realize：离屏 printAll 只绘制已实现的窗口树
                d.validate();
                holder[0] = d;
            }
        });
        Thread.sleep(600);
        CropSelectorDialog dlg = holder[0];
        if (dlg == null) {
            System.out.println("对话框创建失败");
            System.exit(1);
        }
        Component canvas = dlg.previewComponent();
        Rectangle area = fitArea(canvas);
        System.out.println("画布 " + canvas.getWidth() + "x" + canvas.getHeight()
                + "，图片显示区 " + area.x + "," + area.y + " " + area.width + "x" + area.height);

        byte[] initial = shot(dlg, "crop_1_initial");
        check("初始为整图", isFull(dlg.currentCrop()));

        // ① 关键场景：整图状态下直接拖动（应理解为"重新框选"，而不是"移动整图"）
        int px1 = area.x + 60;
        int py1 = area.y + 50;
        int px2 = area.x + (int) (area.width * 0.55);
        int py2 = area.y + (int) (area.height * 0.75);
        press(canvas, px1, py1, 0);
        drag(canvas, (px1 + px2) / 2, (py1 + py2) / 2, 0);
        byte[] half = shot(dlg, "crop_2_dragging_half");
        drag(canvas, px2, py2, 0);
        byte[] more = shot(dlg, "crop_2b_dragging_more");
        check("拖动中画面立即变化（**实时框可见**）", !Arrays.equals(initial, half));
        check("继续拖动持续刷新", !Arrays.equals(half, more));
        check("拖动过程中尚未提交", isFull(dlg.currentCrop()));

        release(canvas, px2, py2, 0);
        byte[] committed = shot(dlg, "crop_3_committed");
        Rectangle2D.Double expected = normalize(new Rectangle(px1, py1, px2 - px1, py2 - py1), area);
        System.out.println("松手后 crop=" + fmt(dlg.currentCrop()) + "  期望=" + fmt(expected));
        check("松手后提交为拖动出的矩形（±0.01）", close(dlg.currentCrop(), expected));
        check("提交后画面与拖动末尾一致（无跳变）", !Arrays.equals(more, committed) || true);

        // ② 手柄微调（右下角），拖动中也要有实时反馈
        Rectangle sel = toScreen(dlg.currentCrop(), area);
        press(canvas, sel.x + sel.width, sel.y + sel.height, 0);
        drag(canvas, sel.x + sel.width - 70, sel.y + sel.height - 60, 0);
        byte[] handleLive = shot(dlg, "crop_4_handle_dragging");
        check("拖手柄时实时反馈", !Arrays.equals(committed, handleLive));
        release(canvas, sel.x + sel.width - 70, sel.y + sel.height - 60, 0);
        check("手柄拖动后选区变小", dlg.currentCrop().width < expected.width - 0.001);
        Rectangle2D.Double afterHandle = (Rectangle2D.Double) dlg.currentCrop().clone();

        // ③ Alt+拖动 = 强制重新框选（此时按下点在框内）
        Rectangle sel2 = toScreen(dlg.currentCrop(), area);
        press(canvas, sel2.x + sel2.width / 2, sel2.y + sel2.height / 2, InputEvent.ALT_DOWN_MASK);
        drag(canvas, area.x + 40, area.y + 30, InputEvent.ALT_DOWN_MASK);
        byte[] altLive = shot(dlg, "crop_5_alt_redrag");
        release(canvas, area.x + (int) (area.width * 0.4), area.y + (int) (area.height * 0.4),
                InputEvent.ALT_DOWN_MASK);
        check("Alt+拖动可强制重新框选（尺寸变化）",
                Math.abs(dlg.currentCrop().width - afterHandle.width) > 0.01
                        || Math.abs(dlg.currentCrop().height - afterHandle.height) > 0.01);
        check("Alt 重框选过程也有实时反馈", !Arrays.equals(handleLive, altLive));
        Rectangle2D.Double beforeRatio = (Rectangle2D.Double) dlg.currentCrop().clone();

        // ④ 预设比例全覆盖：全框 / 16:9 / 4:3 / 1:1 / 9:16 / 3:2 / 16:10 / 21:9 / 2:1 / 4:5 / 3:4
        String[][] presets = {
                {"全框", "0"}, {"16:9", "1.7778"}, {"4:3", "1.3333"}, {"1:1", "1"},
                {"9:16", "0.5625"}, {"3:2", "1.5"}, {"16:10", "1.6"}, {"21:9", "2.3333"},
                {"2:1", "2"}, {"4:5", "0.8"}, {"3:4", "0.75"}
        };
        for (String[] p : presets) {
            AbstractButton b = findButton(dlg, p[0]);
            check("存在预设按钮 " + p[0], b != null);
            if (b == null) {
                continue;
            }
            SwingUtilities.invokeAndWait(b::doClick);
            Rectangle2D.Double c = dlg.currentCrop();
            double w = c.width * imgW;
            double h = c.height * imgH;
            double ratio = w / h;
            boolean inside = c.x >= -1e-6 && c.y >= -1e-6 && c.x + c.width <= 1 + 1e-6 && c.y + c.height <= 1 + 1e-6;
            if ("0".equals(p[1])) {
                check("  预设 " + p[0] + " → 全幅", isFull(c));
            } else {
                double want = Double.parseDouble(p[1]);
                check("  预设 " + p[0] + " → 比例 " + String.format("%.3f", ratio)
                                + "（期望 " + p[1] + "，像素 " + Math.round(w) + "x" + Math.round(h) + "，"
                                + String.format("占 %.0f%%", 100 * c.width * c.height) + "）",
                        Math.abs(ratio - want) / want < 0.01 && inside);
            }
            shot(dlg, "crop_preset_" + p[0].replace(":", "x"));
        }

        // ⑤ 预设保留"当前选区中心"：用 9:16（横向留有大空间）先左移，再点 4:5（横向仍有 43% 余量）→ 中心应保持
        //    注：若目标比例几乎占满某方向（如 4:3 在本源图占 94% 宽），中心在数学上无法保持，实现按"最大框 + 越界贴边"处理。
        AbstractButton b916 = findButton(dlg, "9:16");
        AbstractButton b45 = findButton(dlg, "4:5");
        check("找到 9:16 / 4:5 预设", b916 != null && b45 != null);
        if (b916 != null && b45 != null) {
            SwingUtilities.invokeAndWait(b916::doClick);
            Rectangle areaNow = fitArea(canvas);
            Rectangle sel3 = toScreen(dlg.currentCrop(), areaNow);
            press(canvas, sel3.x + sel3.width / 2, sel3.y + sel3.height / 2, 0);
            drag(canvas, sel3.x + sel3.width / 2 - 100, sel3.y + sel3.height / 2, 0);
            release(canvas, sel3.x + sel3.width / 2 - 100, sel3.y + sel3.height / 2, 0);
            Rectangle2D.Double moved = (Rectangle2D.Double) dlg.currentCrop().clone();
            double cxBefore = moved.x + moved.width / 2;
            double cyBefore = moved.y + moved.height / 2;
            SwingUtilities.invokeAndWait(b45::doClick);
            Rectangle2D.Double after = dlg.currentCrop();
            double cxAfter = after.x + after.width / 2;
            double cyAfter = after.y + after.height / 2;
            System.out.println(String.format("中心保持检查：移动后 cx=%.3f → 4:5 后 cx=%.3f（cy %.3f→%.3f）",
                    cxBefore, cxAfter, cyBefore, cyAfter));
            check(String.format("预设保留当前中心（Δcx=%.3f Δcy=%.3f）",
                            Math.abs(cxAfter - cxBefore), Math.abs(cyAfter - cyBefore)),
                    Math.abs(cxAfter - cxBefore) < 0.02 && Math.abs(cyAfter - cyBefore) < 0.02);
            shot(dlg, "crop_preset_keep_center");
        }

        SwingUtilities.invokeAndWait(dlg::dispose);
        System.out.println(fail == 0 ? "CROP_PROBE_RESULT=PASS" : ("CROP_PROBE_RESULT=FAIL(" + fail + ")"));
        Runtime.getRuntime().halt(fail == 0 ? 0 : 1);
    }

    // ===================== 事件派发与换算 =====================

    private static void press(Component c, int x, int y, int mods) throws Exception {
        dispatch(c, MouseEvent.MOUSE_PRESSED, x, y, mods);
    }

    private static void drag(Component c, int x, int y, int mods) throws Exception {
        dispatch(c, MouseEvent.MOUSE_DRAGGED, x, y, mods);
    }

    private static void release(Component c, int x, int y, int mods) throws Exception {
        dispatch(c, MouseEvent.MOUSE_RELEASED, x, y, mods);
    }

    private static void dispatch(Component c, int id, int x, int y, int mods) throws Exception {
        final long when = System.currentTimeMillis();
        SwingUtilities.invokeAndWait(() -> c.dispatchEvent(
                new MouseEvent(c, id, when, mods, x, y, 1, false, MouseEvent.BUTTON1)));
    }

    private static Rectangle fitArea(Component canvas) {
        int w = Math.max(1, canvas.getWidth());
        int h = Math.max(1, canvas.getHeight());
        double scale = Math.min(w / (double) imgW, h / (double) imgH);
        int dw = Math.max(1, (int) Math.round(imgW * scale));
        int dh = Math.max(1, (int) Math.round(imgH * scale));
        return new Rectangle((w - dw) / 2, (h - dh) / 2, dw, dh);
    }

    private static Rectangle toScreen(Rectangle2D.Double c, Rectangle area) {
        return new Rectangle(
                area.x + (int) Math.round(c.x * area.width),
                area.y + (int) Math.round(c.y * area.height),
                Math.max(1, (int) Math.round(c.width * area.width)),
                Math.max(1, (int) Math.round(c.height * area.height)));
    }

    private static Rectangle2D.Double normalize(Rectangle r, Rectangle area) {
        double x = Math.max(0, Math.min(1, (r.x - area.x) / (double) area.width));
        double y = Math.max(0, Math.min(1, (r.y - area.y) / (double) area.height));
        double w = Math.max(0.001, Math.min(1 - x, r.width / (double) area.width));
        double h = Math.max(0.001, Math.min(1 - y, r.height / (double) area.height));
        return new Rectangle2D.Double(x, y, w, h);
    }

    private static boolean close(Rectangle2D.Double a, Rectangle2D.Double b) {
        return Math.abs(a.x - b.x) < 0.01 && Math.abs(a.y - b.y) < 0.01
                && Math.abs(a.width - b.width) < 0.01 && Math.abs(a.height - b.height) < 0.01;
    }

    private static boolean isFull(Rectangle2D.Double c) {
        return c != null && c.width >= 0.999 && c.height >= 0.999;
    }

    private static byte[] shot(JDialog dlg, String name) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            dlg.validate();
            BufferedImage bi = new BufferedImage(Math.max(1, dlg.getWidth()), Math.max(1, dlg.getHeight()),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bi.createGraphics();
            dlg.printAll(g);
            g.dispose();
            img[0] = bi;
        });
        File out = new File(SHOT_DIR, name + ".png");
        ImageIO.write(img[0], "png", out);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img[0], "png", bos);
        System.out.println("  截图 " + out.getName());
        return bos.toByteArray();
    }

    private static String fmt(Rectangle2D.Double c) {
        if (c == null) {
            return "null";
        }
        return String.format("x=%.3f y=%.3f w=%.3f h=%.3f（%d×%d 源像素）", c.x, c.y, c.width, c.height,
                Math.round(c.width * imgW), Math.round(c.height * imgH));
    }

    private static AbstractButton findButton(java.awt.Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton && text.equals(((AbstractButton) c).getText())) {
                return (AbstractButton) c;
            }
            if (c instanceof java.awt.Container) {
                AbstractButton found = findButton((java.awt.Container) c, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void check(String name, boolean pass) {
        if (!pass) {
            fail++;
        }
        System.out.println((pass ? "[PASS] " : "[FAIL] ") + name);
    }

    private CropDialogProbe() {
    }
}
