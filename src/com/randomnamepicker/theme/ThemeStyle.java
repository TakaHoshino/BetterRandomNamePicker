package com.randomnamepicker.theme;

import java.awt.geom.Rectangle2D;
import java.io.File;

/**
 * 窗口背景解析结果（宿主内建主题）。
 * active=false 表示不贴图（未启用/无图/图缺失或损坏）；active=true 时按 image/mode/crop/mask 渲染；
 * {@code mediaId} 非空表示这是**动态背景**（动图 GIF / 帧序列 ZIP），帧由 {@link MediaClock} 提供。
 * crop 为源图归一化 (x,y,w,h)（0..1）；mask 为背景图内半透明白混合值（0..180，默认 40，0=关闭）。
 */
public final class ThemeStyle {

    /** 默认归一化裁剪框 = 整图。 */
    public static final Rectangle2D.Double FULL_CROP = new Rectangle2D.Double(0, 0, 1, 1);

    private final boolean active;
    private final File imageFile;
    private final BackdropMode mode;
    private final Rectangle2D.Double crop;
    private final int maskAlpha;
    private final String mediaId;

    public static final ThemeStyle NONE = new ThemeStyle(false, null, BackdropMode.FILL, FULL_CROP, 0);

    public ThemeStyle(boolean active, File imageFile, BackdropMode mode,
                      Rectangle2D.Double crop, int maskAlpha) {
        this(active, imageFile, mode, crop, maskAlpha, "");
    }

    public ThemeStyle(boolean active, File imageFile, BackdropMode mode,
                      Rectangle2D.Double crop, int maskAlpha, String mediaId) {
        this.active = active;
        this.imageFile = imageFile;
        this.mode = mode != null ? mode : BackdropMode.FILL;
        this.crop = crop != null ? crop : FULL_CROP;
        this.maskAlpha = maskAlpha;
        this.mediaId = mediaId != null ? mediaId : "";
    }

    public boolean isActive() {
        return active;
    }

    public File getImageFile() {
        return imageFile;
    }

    public BackdropMode getMode() {
        return mode;
    }

    public Rectangle2D.Double getCrop() {
        return crop;
    }

    public int getMaskAlpha() {
        return maskAlpha;
    }

    /** 动态背景媒体 id（动图/帧序列）；空串=静态图模式。 */
    public String getMediaId() {
        return mediaId;
    }

    /** 是否动态背景（帧由 {@link MediaClock} 提供）。 */
    public boolean isAnimated() {
        return active && !mediaId.isEmpty();
    }
}
