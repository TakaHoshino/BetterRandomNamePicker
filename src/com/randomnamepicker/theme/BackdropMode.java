package com.randomnamepicker.theme;

import java.util.Locale;

/**
 * 背景适配模式（宿主内建主题，报告 2/3）。
 * CENTER=居中原尺寸；FIT=适应（等比完整显示）；FILL=填充（等比裁切填满，默认）；
 * STRETCH=拉伸（非等比铺满）；CROP=自由裁切（按归一化裁剪框显示）。
 */
public enum BackdropMode {

    CENTER,
    FIT,
    FILL,
    STRETCH,
    CROP;

    /** 解析配置串（大小写不敏感），失败回退 FILL。 */
    public static BackdropMode parse(String value) {
        if (value != null) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignore) {
                // 回退默认
            }
        }
        return FILL;
    }

    /** 中文显示名（主题独立页/设置窗/预览共用同一来源）。 */
    public String label() {
        switch (this) {
            case CENTER:
                return "居中";
            case FIT:
                return "适应（完整显示）";
            case STRETCH:
                return "拉伸";
            case CROP:
                return "自由裁切";
            case FILL:
            default:
                return "填充（裁切填满）";
        }
    }

    /** 全部显示名（与 {@link #values()} 同序）。 */
    public static String[] labels() {
        BackdropMode[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].label();
        }
        return out;
    }
}
