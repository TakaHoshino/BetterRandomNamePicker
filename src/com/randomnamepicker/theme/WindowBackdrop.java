package com.randomnamepicker.theme;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JComponent;

/**
 * 自绘背景组件（置于窗口内容层之下，报告 2 宿主内建主题；2026-10 支持动态背景）。
 * <p>
 * 渲染链路：**源帧 → 复用的缩放/遮罩缓冲 → blit**。缓冲按
 * {@code (源, 帧号, 窗口尺寸, 模式, 裁剪框, 遮罩)} 命中即跳过重算，因此同一帧的重复重绘只花一次 blit
 * （本机实测 0.07 ms / 900×600），换帧才重算一次（≈3.5 ms）——动态背景不产生每帧新分配。
 * </p>
 * 源帧：静态图为文件解码（有界 LRU(16) 缓存）；动态背景取 {@link MediaClock} 的当前帧（全局单解码器多窗共享）。
 */
public class WindowBackdrop extends JComponent {

    /** 静态图解码缓存上限（张）。有界 LRU：图库反复换图/删图时不再无界增长。 */
    private static final int IMAGE_CACHE_MAX = 16;

    private static final ImageCache IMAGE_CACHE = new ImageCache();

    private ThemeStyle style = ThemeStyle.NONE;

    /** 复用的缩放+遮罩缓冲（尺寸变化时重建；换帧时原地重画）。 */
    private BufferedImage workImage;
    private String workKey = "";

    public WindowBackdrop() {
        setOpaque(false);
    }

    /** 访问序有界 LRU（超出上限淘汰最久未用）。 */
    private static final class ImageCache extends LinkedHashMap<String, BufferedImage> {
        ImageCache() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > IMAGE_CACHE_MAX;
        }
    }

    /** 清除某文件的解码缓存（主题独立页删除/替换图片后调用，避免残留旧解码图）。 */
    public static void purgeCache(File file) {
        if (file == null) {
            return;
        }
        synchronized (IMAGE_CACHE) {
            IMAGE_CACHE.remove(file.getAbsolutePath());
        }
    }

    public void setStyle(ThemeStyle style) {
        ThemeStyle next = style != null ? style : ThemeStyle.NONE;
        if (!next.equals(this.style)) {
            workKey = "";
        }
        this.style = next;
        repaint();
    }

    public ThemeStyle getStyle() {
        return style;
    }

    private BufferedImage loadImage(File file) {
        if (file == null) {
            return null;
        }
        String key = file.getAbsolutePath();
        synchronized (IMAGE_CACHE) {
            BufferedImage cached = IMAGE_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        try {
            BufferedImage loaded = ImageIO.read(file);
            if (loaded != null) {
                synchronized (IMAGE_CACHE) {
                    IMAGE_CACHE.put(key, loaded);
                }
                return loaded;
            }
        } catch (Exception ignore) {
            // 图片损坏/无法解码：视为无图
        }
        return null;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!style.isActive()) {
            return;
        }
        BufferedImage src = sourceImage();
        if (src == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (workImage == null || workImage.getWidth() != w || workImage.getHeight() != h) {
            workImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            workKey = "";
        }
        String key = cacheKey(src, w, h);
        if (!key.equals(workKey)) {
            renderInto(workImage, src, w, h);
            workKey = key;
        }
        g.drawImage(workImage, 0, 0, null);
    }

    /** 当前源帧：动态背景取时钟当前帧（时钟未同步到同一媒体则视为无帧），静态图取文件解码。 */
    private BufferedImage sourceImage() {
        if (style.isAnimated()) {
            MediaClock clock = MediaClock.getInstance();
            if (!clock.activeId().equals(style.getMediaId())) {
                return null;
            }
            return clock.currentFrame();
        }
        return loadImage(style.getImageFile());
    }

    private String cacheKey(BufferedImage src, int w, int h) {
        StringBuilder sb = new StringBuilder();
        if (style.isAnimated()) {
            sb.append(style.getMediaId()).append('#').append(MediaClock.getInstance().currentIndex());
        } else {
            sb.append(style.getImageFile() == null ? "-" : style.getImageFile().getAbsolutePath());
        }
        sb.append('|').append(src.getWidth()).append('x').append(src.getHeight())
                .append('|').append(w).append('x').append(h)
                .append('|').append(style.getMode())
                .append('|').append(ThemeStore.cropToString(style.getCrop()))
                .append('|').append(style.getMaskAlpha());
        return sb.toString();
    }

    /** 把源帧按 模式 + 裁剪框 + 遮罩 画进目标缓冲（透明底，FIT/CENTER 留白处透出下层）。 */
    private void renderInto(BufferedImage target, BufferedImage src, int w, int h) {
        Graphics2D g2d = target.createGraphics();
        g2d.setComposite(AlphaComposite.Src);
        g2d.setColor(new Color(0, 0, 0, 0));
        g2d.fillRect(0, 0, w, h);
        g2d.setComposite(AlphaComposite.SrcOver);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= 0 || sh <= 0) {
            g2d.dispose();
            return;
        }

        // 源矩形（先按归一化裁剪框求，再按模式缩放目标）
        Rectangle2D.Double crop = style.getCrop();
        double crx = clamp01(crop.x);
        double cry = clamp01(crop.y);
        double crw = Math.max(0.001, clamp01(crop.width));
        double crh = Math.max(0.001, clamp01(crop.height));
        if (crx + crw > 1) {
            crw = 1 - crx;
        }
        if (cry + crh > 1) {
            crh = 1 - cry;
        }
        int sx0 = (int) Math.round(crx * sw);
        int sy0 = (int) Math.round(cry * sh);
        int sx1 = (int) Math.round((crx + crw) * sw);
        int sy1 = (int) Math.round((cry + crh) * sh);
        if (sx1 - sx0 <= 0 || sy1 - sy0 <= 0) {
            g2d.dispose();
            return;
        }
        int cw = sx1 - sx0;
        int ch = sy1 - sy0;

        int dx0 = 0;
        int dy0 = 0;
        int dx1 = w;
        int dy1 = h;

        switch (style.getMode()) {
            case CENTER:
                dx0 = Math.max(0, (w - cw) / 2);
                dy0 = Math.max(0, (h - ch) / 2);
                dx1 = dx0 + cw;
                dy1 = dy0 + ch;
                break;
            case FIT: {
                double scale = Math.min((double) w / cw, (double) h / ch);
                int dw = (int) Math.round(cw * scale);
                int dh = (int) Math.round(ch * scale);
                dx0 = (w - dw) / 2;
                dy0 = (h - dh) / 2;
                dx1 = dx0 + dw;
                dy1 = dy0 + dh;
                break;
            }
            case STRETCH:
                break; // 铺满
            case CROP:
            case FILL:
            default: {
                double scale = Math.max((double) w / cw, (double) h / ch);
                int dw = (int) Math.ceil(cw * scale);
                int dh = (int) Math.ceil(ch * scale);
                dx0 = (w - dw) / 2;
                dy0 = (h - dh) / 2;
                dx1 = dx0 + dw;
                dy1 = dy0 + dh;
                break;
            }
        }

        g2d.drawImage(src, dx0, dy0, dx1, dy1, sx0, sy0, sx1, sy1, null);

        // 遮罩：背景图内半透明白混合（报告 3 决策：不做内容层之上的拦截层，不挡鼠标）
        int mask = Math.max(0, Math.min(180, style.getMaskAlpha()));
        if (mask > 0) {
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, mask / 255f));
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, w, h);
        }
        g2d.dispose();
    }

    private double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return Math.max(0, Math.min(1, v));
    }
}
