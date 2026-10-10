import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 动态背景"帧预算"实测探针（判断视频/动图路线可行性的证据工具，不参与宿主运行）。
 *
 * 运行（工作目录 = 仓库根）：
 *   javac -encoding UTF-8 -d tools/theme-smoke/classes tools/theme-smoke/AnimBudgetProbe.java
 *   java -cp tools/theme-smoke/classes AnimBudgetProbe [帧数] [源宽] [源高] [窗口宽] [窗口高]
 *
 * 输出三条路径的每帧耗时与可支撑帧率：
 *   1) 每帧从磁盘解码 PNG（等价"帧序列/动图逐帧解码"）
 *   2) 解码后每帧按窗口尺寸重采样绘制（等价当前 WindowBackdrop 的无缓存绘制）
 *   3) 预缩放帧缓存 + 直接 blit（等价给动态背景加"缩放缓存"后的绘制）
 */
public final class AnimBudgetProbe {

    public static void main(String[] args) throws Exception {
        int frames = args.length > 0 ? Integer.parseInt(args[0]) : 24;
        int sw = args.length > 1 ? Integer.parseInt(args[1]) : 1280;
        int sh = args.length > 2 ? Integer.parseInt(args[2]) : 720;
        int ww = args.length > 3 ? Integer.parseInt(args[3]) : 900;
        int wh = args.length > 4 ? Integer.parseInt(args[4]) : 600;

        File dir = new File("theme_ui_shots/input/anim");
        dir.mkdirs();
        File[] files = new File[frames];
        for (int i = 0; i < frames; i++) {
            files[i] = new File(dir, String.format("f%03d.png", i));
            if (!files[i].isFile()) {
                ImageIO.write(frame(sw, sh, i, frames), "png", files[i]);
            }
        }
        System.out.println("frames=" + frames + " source=" + sw + "x" + sh + " window=" + ww + "x" + wh);

        // 预热
        for (int i = 0; i < frames; i++) {
            ImageIO.read(files[i]);
        }
        BufferedImage canvas = new BufferedImage(ww, wh, BufferedImage.TYPE_INT_RGB);

        long tDecode = 0, maxDecode = 0;
        long tDrawScaled = 0, maxDrawScaled = 0;
        long tBlit = 0, maxBlit = 0;
        BufferedImage[] cached = new BufferedImage[frames];
        long tPreScale = 0;

        for (int i = 0; i < frames; i++) {
            long t0 = System.nanoTime();
            BufferedImage src = ImageIO.read(files[i]);
            long dt = System.nanoTime() - t0;
            tDecode += dt;
            maxDecode = Math.max(maxDecode, dt);

            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            t0 = System.nanoTime();
            g.drawImage(src, 0, 0, ww, wh, null);
            dt = System.nanoTime() - t0;
            tDrawScaled += dt;
            maxDrawScaled = Math.max(maxDrawScaled, dt);
            g.dispose();

            t0 = System.nanoTime();
            cached[i] = new BufferedImage(ww, wh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = cached[i].createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(src, 0, 0, ww, wh, null);
            g2.dispose();
            tPreScale += System.nanoTime() - t0;
        }
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < frames; i++) {
                Graphics2D g = canvas.createGraphics();
                long t0 = System.nanoTime();
                g.drawImage(cached[i], 0, 0, null);
                long dt = System.nanoTime() - t0;
                if (round > 0) {
                    tBlit += dt;
                    maxBlit = Math.max(maxBlit, dt);
                }
                g.dispose();
            }
        }
        int blitRounds = 2;

        report("1) 每帧 PNG 解码(磁盘)", tDecode / frames, maxDecode);
        report("2) 每帧解码+按窗口重采样", (tDecode + tDrawScaled) / frames, maxDecode + maxDrawScaled);
        report("3) 预缩放缓存 + 每帧 blit", tBlit / (frames * blitRounds), maxBlit);
        System.out.printf("预缩放一次性成本: 首帧 %.1f ms/帧（%d 帧共 %.0f ms，单窗口；多窗口按窗口数再加）%n",
                tPreScale / 1e6 / frames, frames, tPreScale / 1e6);
    }

    private static void report(String name, long avgNanos, long maxNanos) {
        double avgMs = avgNanos / 1e6;
        System.out.printf("%-28s 平均 %6.2f ms/帧  最差 %6.2f ms  → 理论上限 %5.1f fps%n",
                name, avgMs, maxNanos / 1e6, avgMs <= 0 ? 0 : 1000.0 / avgMs);
    }

    private static BufferedImage frame(int w, int h, int index, int total) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        double t = index / (double) total;
        g.setPaint(new GradientPaint(0, 0, new Color(20, 60, 140), w, h, new Color(230, 170, 40)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 70));
        for (int i = 0; i < 8; i++) {
            int x = (int) (w * ((t + i / 8.0) % 1.0));
            g.fillOval(Math.max(0, x - 90), h / 4 + (i % 3) * h / 6, 180, 180);
        }
        g.setColor(Color.WHITE);
        g.setFont(new java.awt.Font("微软雅黑", java.awt.Font.BOLD, 40));
        g.drawString("frame " + index, 30, h - 40);
        g.dispose();
        return img;
    }

    private AnimBudgetProbe() {
    }
}
