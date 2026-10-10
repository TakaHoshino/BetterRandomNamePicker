package com.randomnamepicker.theme;

import com.randomnamepicker.core.LogManager;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 主题存储（宿主内建主题）：设置存于**独立文件** {@code data/themes/theme.properties}
 * （经 {@link ThemeConfig} 读写：UTF-8 + 分区注释 + 自动备份；旧版主配置里的 theme.* 首次运行自动迁移过来），
 * 图片复制入库 {@code data/themes/images/}（不引用原路径）。仅 EDT 使用。
 * <p>
 * 全局背景 + 按窗口类别单独指定（未指定继承全局）。类别 ID 用 ASCII，显示名供设置 UI 使用。
 * </p>
 */
public final class ThemeStore {

    public static final String DATA_ROOT = "data";
    public static final String THEME_DIR = DATA_ROOT + "/themes";
    public static final String IMAGES_DIR = THEME_DIR + "/images";

    public static final String CATEGORY_MAIN = "main";
    public static final String CATEGORY_CONFIG = "config";
    public static final String CATEGORY_SCHEME = "scheme";
    public static final String CATEGORY_NUMBER = "number";
    public static final String CATEGORY_SEAT = "seat";
    public static final String CATEGORY_SETTINGS = "settings";
    public static final String CATEGORY_OTHER = "other";

    /** 类别顺序（设置 UI 展示序）。 */
    public static final String[] CATEGORY_IDS = {
            CATEGORY_MAIN, CATEGORY_CONFIG, CATEGORY_SCHEME,
            CATEGORY_NUMBER, CATEGORY_SEAT, CATEGORY_SETTINGS, CATEGORY_OTHER
    };

    /** 类别显示名（与 CATEGORY_IDS 同序，主题独立页/设置窗分节共用同一来源）。 */
    private static final String[] CATEGORY_LABELS = {
            "主窗口", "配置名单", "方案管理", "数字设置", "座位设置", "系统设置", "其它对话框"
    };

    /** 引用位置标记：全局背景（其余为类别 ID）。 */
    public static final String USAGE_GLOBAL = "g";

    public static final int MASK_DEFAULT = 40;
    public static final int MASK_MIN = 0;
    public static final int MASK_MAX = 180;
    public static final int TABLE_TIER_DEFAULT = 1; // T2：0=原生 1=浅 2=中（默认浅，人工核验后定档）

    /**
     * 常用图片格式（按偏好顺序）。**只有运行环境确实存在对应解码器时才会生效**：
     * JDK 内置为 JPEG/PNG/BMP/GIF/TIFF/WBMP；WebP 等需第三方 ImageIO 插件（放 {@code lib/} 即可，见 {@link ThemeImageIo}）。
     */
    private static final String[] PREFERRED_EXT = {
            "jpg", "jpeg", "jpe", "jfif", "png", "bmp", "gif", "tif", "tiff", "webp", "wbmp"
    };

    /** 生效后缀白名单（惰性构建并缓存）：常用格式 ∩ 可解码，再并入运行环境注册的其它后缀。 */
    private static volatile Set<String> supportedExt = null;

    /** 图片尺寸缓存（键=路径|大小|修改时间；值 null 表示已确认不可解码）——避免列表每次重绘都重新解码。 */
    private static final int META_CACHE_MAX = 64;
    private static final Map<String, Dimension> SIZE_CACHE =
            new LinkedHashMap<String, Dimension>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Dimension> eldest) {
                    return size() > META_CACHE_MAX;
                }
            };

    /** 头部可解码缓存（同上键策略）：样式解析/预览用，避免每次 reapply 都新建 ImageReader。 */
    private static final Map<String, Boolean> HEADER_CACHE =
            new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > META_CACHE_MAX;
                }
            };

    private ThemeStore() {
    }

    // ===================== 类别显示名 =====================

    /** 类别显示名（未知/空入参回退“全局”）。 */
    public static String categoryLabel(String category) {
        if (category == null || category.isEmpty()) {
            return "全局";
        }
        for (int i = 0; i < CATEGORY_IDS.length; i++) {
            if (CATEGORY_IDS[i].equals(category)) {
                return CATEGORY_LABELS[i];
            }
        }
        return category;
    }

    /** 全部类别显示名副本（与 CATEGORY_IDS 同序）。 */
    public static String[] categoryLabels() {
        return CATEGORY_LABELS.clone();
    }

    // ===================== 总开关 / 全局参数 =====================

    public static boolean isEnabled() {
        return Boolean.parseBoolean(ThemeConfig.get("enabled", "false"));
    }

    public static void setEnabled(boolean on) {
        ThemeConfig.set("enabled", String.valueOf(on));
    }

    public static int getMask() {
        return clampInt(ThemeConfig.get("mask", String.valueOf(MASK_DEFAULT)),
                MASK_MIN, MASK_MAX, MASK_DEFAULT);
    }

    public static void setMask(int mask) {
        ThemeConfig.set("mask",
                String.valueOf(clampInt(String.valueOf(mask), MASK_MIN, MASK_MAX, MASK_DEFAULT)));
    }

    /** 表格浅透档（T2，报告 3）：0=原生/1=浅/2=中；默认浅（人工核验反馈后定档）。 */
    public static int getTableTier() {
        return clampInt(ThemeConfig.get("tableTier", "1"), 0, 2, TABLE_TIER_DEFAULT);
    }

    public static void setTableTier(int tier) {
        ThemeConfig.set("tableTier", String.valueOf(clampInt(String.valueOf(tier), 0, 2, 0)));
    }

    // ===================== 全局 / 类别样式 =====================

    private static String globalKey(String suffix) {
        return "g." + suffix;
    }

    private static String catKey(String category, String suffix) {
        return "cat." + category + "." + suffix;
    }

    public static void setGlobalImage(String imageId) {
        ThemeConfig.set(globalKey("image"),
                (imageId == null || imageId.isEmpty()) ? null : imageId);
    }

    public static String getGlobalImage() {
        return ThemeConfig.get(globalKey("image"), "");
    }

    public static void setGlobalMode(BackdropMode mode) {
        ThemeConfig.set(globalKey("mode"),
                mode == null ? BackdropMode.FILL.name() : mode.name());
    }

    public static void setGlobalCrop(Rectangle2D.Double crop) {
        ThemeConfig.set(globalKey("crop"), cropToString(crop));
    }

    public static void setCategoryImage(String category, String imageId) {
        ThemeConfig.set(catKey(category, "image"),
                (imageId == null || imageId.isEmpty()) ? null : imageId);
    }

    public static void setCategoryMode(String category, BackdropMode mode) {
        ThemeConfig.set(catKey(category, "mode"),
                mode == null ? BackdropMode.FILL.name() : mode.name());
    }

    public static void setCategoryCrop(String category, Rectangle2D.Double crop) {
        ThemeConfig.set(catKey(category, "crop"), cropToString(crop));
    }

    /** 解析某类别当前生效样式：类别未指定则继承全局；未启用或无可用图 → inactive。 */
    public static ThemeStyle resolve(String category) {
        return buildStyle(category, true);
    }

    /**
     * 预览用解析：**忽略总开关**（主题未启用时也出效果图），其余继承/回退规则与 {@link #resolve} 完全一致。
     * 仅供主题独立页的预览画布使用，不参与真实窗口装配。
     */
    public static ThemeStyle resolvePreview(String category) {
        return buildStyle(category, false);
    }

    /** 按给定图片 id + 参数直接组装样式（预览“试贴图库选中图”用；图缺失/损坏 → inactive）。 */
    public static ThemeStyle styleFor(String imageId, BackdropMode mode, Rectangle2D.Double crop, int mask) {
        File imageFile = imageFile(imageId);
        if (imageId == null || imageId.isEmpty() || !isUsableImage(imageFile)) {
            return ThemeStyle.NONE;
        }
        return new ThemeStyle(true, imageFile,
                mode != null ? mode : globalMode(),
                crop != null ? crop : (Rectangle2D.Double) ThemeStyle.FULL_CROP.clone(),
                mask);
    }

    /**
     * 解析样式。优先级（2026-10 动态背景口径）：
     * <ol>
     *   <li>该窗口类别**单独指定**的静态图片（有效）→ 静态图；</li>
     *   <li>否则若设置了**全局动态背景**（动图 GIF / 帧序列 ZIP，可用）→ 动画（帧由 {@link MediaClock} 提供）；</li>
     *   <li>否则用全局静态图片（含类别继承）。</li>
     * </ol>
     * 都不可用 → inactive（真实窗口零变化）。
     */
    private static ThemeStyle buildStyle(String category, boolean requireEnabled) {
        if (requireEnabled && !isEnabled()) {
            return ThemeStyle.NONE;
        }
        BackdropMode mode = modeFor(category);
        Rectangle2D.Double crop = cropFor(category);

        // 1) 类别单独指定的静态图片优先
        if (category != null && categoryHasOwnImage(category)) {
            File own = imageFile(categoryImageId(category));
            if (isUsableImage(own)) {
                return new ThemeStyle(true, own, mode, crop, getMask());
            }
        }
        // 2) 全局动态背景
        String mediaId = MediaLibrary.getMediaId();
        if (!mediaId.isEmpty() && MediaLibrary.isUsableAnimation(mediaId)) {
            File mediaFile = MediaLibrary.mediaFile(mediaId);
            if (mediaFile != null) {
                return new ThemeStyle(true, mediaFile, mode, crop, getMask(), mediaId);
            }
        }
        // 3) 全局静态图片（含类别继承）
        File imageFile = imageFile(categoryImageId(category));
        if (!isUsableImage(imageFile)) {
            return ThemeStyle.NONE;
        }
        return new ThemeStyle(true, imageFile, mode, crop, getMask());
    }

    /** 该类别生效的适配模式（类别未指定=继承全局）。 */
    private static BackdropMode modeFor(String category) {
        if (category == null) {
            return globalMode();
        }
        String per = ThemeConfig.get(catKey(category, "mode"), "").trim();
        return per.isEmpty() ? globalMode() : BackdropMode.parse(per);
    }

    /** 该类别生效的裁剪框（类别未指定=继承全局）。 */
    private static Rectangle2D.Double cropFor(String category) {
        if (category == null) {
            return globalCrop();
        }
        String per = ThemeConfig.get(catKey(category, "crop"), "").trim();
        return per.isEmpty() ? globalCrop() : parseCrop(per);
    }

    private static String categoryImageId(String category) {
        if (category != null) {
            String per = ThemeConfig.get(catKey(category, "image"), "").trim();
            if (!per.isEmpty()) {
                return per;
            }
        }
        return getGlobalImage().trim();
    }

    private static BackdropMode globalMode() {
        return BackdropMode.parse(ThemeConfig.get(globalKey("mode"), ""));
    }

    private static Rectangle2D.Double globalCrop() {
        return parseCrop(ThemeConfig.get(globalKey("crop"), ""));
    }

    // ===================== 可解码格式（2026-10 媒体扩展批次） =====================

    /**
     * 生效后缀白名单：{@link #PREFERRED_EXT} 中**运行环境确有解码器**者 ∪ 运行环境注册的其它读取器后缀。
     * 首次调用会触发 {@link ThemeImageIo#ensurePluginsLoaded()}（扫描 {@code lib/} 下的 ImageIO 插件）。
     */
    private static Set<String> supportedExtSet() {
        Set<String> cached = supportedExt;
        if (cached != null) {
            return cached;
        }
        synchronized (ThemeStore.class) {
            if (supportedExt != null) {
                return supportedExt;
            }
            ThemeImageIo.ensurePluginsLoaded();
            Set<String> set = new LinkedHashSet<>();
            for (String ext : PREFERRED_EXT) {
                if (hasReaderFor(ext)) {
                    set.add(ext);
                }
            }
            try {
                for (String ext : ImageIO.getReaderFileSuffixes()) {
                    if (ext == null) {
                        continue;
                    }
                    String e = ext.trim().toLowerCase(Locale.ROOT);
                    if (e.matches("[a-z0-9]{1,8}")) {
                        set.add(e);
                    }
                }
            } catch (Throwable ignore) {
                // 读取器列表异常：至少保留上面探测到的常用格式
            }
            Set<String> result = Collections.unmodifiableSet(set);
            supportedExt = result;
            LogManager.log("主题-可解码图片格式: " + String.join("/", result)
                    + "（" + ThemeImageIo.scanNote() + "）", "THEME_IMPORT");
            return result;
        }
    }

    private static boolean hasReaderFor(String ext) {
        try {
            return ImageIO.getImageReadersBySuffix(ext).hasNext();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前环境支持的后缀（排序小写），供文件选择器与提示文案使用。 */
    public static String[] supportedExtensions() {
        List<String> list = new ArrayList<>(supportedExtSet());
        Collections.sort(list);
        return list.toArray(new String[0]);
    }

    /** 当前环境支持的后缀一行文案：“bmp / gif / jpeg / …”。 */
    public static String supportedExtensionsText() {
        return String.join(" / ", supportedExtensions());
    }

    /** 该文件名后缀是否在白名单内（大小写不敏感）。 */
    public static boolean isSupportedExtension(String fileName) {
        return extensionOf(fileName) != null;
    }

    /** 能否整图解码（导入前置校验；损坏文件/无解码器的格式返回 false，不抛异常）。 */
    public static boolean canDecode(File file) {
        return readImageQuietly(file) != null;
    }

    /** 静默整图解码（失败返回 null；异常不外抛）。导入校验与尺寸读取用。 */
    public static BufferedImage readImageQuietly(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 轻量可解码判定（只嗅探头部，不做整图解码，带缓存）：图库列出/排序等"大概能用"的场合。
     * 注意：**样式解析不要用这个**——头部合法但数据截断的文件会通过头部嗅探，
     * 那种情况请用 {@link #isUsableImage(File)}（整图解码，结果同样带缓存）。
     */
    public static boolean looksDecodable(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String key = file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
        synchronized (HEADER_CACHE) {
            Boolean cached = HEADER_CACHE.get(key);
            if (cached != null) {
                return cached.booleanValue();
            }
        }
        boolean ok = canonicalExtensionOf(file) != null;
        synchronized (HEADER_CACHE) {
            HEADER_CACHE.put(key, Boolean.valueOf(ok));
        }
        return ok;
    }

    /**
     * 由**内容**嗅探出的规范后缀（jpeg→jpg、tiff→tif）；无法识别返回 null。
     * 用于接受后缀不在白名单但内容可解码的文件（如 {@code .jfif} 的 JPEG）。
     */
    public static String canonicalExtensionOf(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        ImageInputStream in = null;
        ImageReader reader = null;
        try {
            in = ImageIO.createImageInputStream(file);
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return null;
            }
            reader = readers.next();
            return normalizeExt(reader.getFormatName());
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                    // 忽略
                }
            }
        }
    }

    private static String normalizeExt(String formatName) {
        if (formatName == null || formatName.trim().isEmpty()) {
            return null;
        }
        String e = formatName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (e.isEmpty()) {
            return null;
        }
        if ("jpeg".equals(e)) {
            return "jpg";
        }
        if ("tiff".equals(e)) {
            return "tif";
        }
        return e;
    }

    /** 图片显示用格式标签（如 PNG / JPEG / TIFF），未知返回“未知格式”。 */
    public static String imageFormatText(File file) {
        if (file == null) {
            return "未知格式";
        }
        String ext = extensionOf(file.getName());
        if (ext == null) {
            ext = canonicalExtensionOf(file);
        }
        if (ext == null) {
            return "未知格式";
        }
        if ("jpg".equals(ext) || "jpeg".equals(ext) || "jpe".equals(ext) || "jfif".equals(ext)) {
            return "JPEG";
        }
        if ("tif".equals(ext) || "tiff".equals(ext)) {
            return "TIFF";
        }
        return ext.toUpperCase(Locale.ROOT);
    }

    /** 图片是否可以真正使用（**整图解码**校验，结果进尺寸缓存；截断/损坏文件在此被正确判否）。 */
    public static boolean isUsableImage(File file) {
        return imageDimension(file) != null;
    }

    /** 图片像素尺寸（带缓存的整图解码；不可解码返回 null）。 */
    public static Dimension imageDimension(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        String key = file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
        synchronized (SIZE_CACHE) {
            if (SIZE_CACHE.containsKey(key)) {
                return SIZE_CACHE.get(key);
            }
        }
        BufferedImage img = readImageQuietly(file);
        Dimension dim = img == null ? null : new Dimension(img.getWidth(), img.getHeight());
        synchronized (SIZE_CACHE) {
            SIZE_CACHE.put(key, dim);
        }
        return dim;
    }

    // ===================== 图库 =====================

    /**
     * 复制导入图片到图库，返回图库 id（失败返回 null）。别名默认取源文件名（去扩展名）。
     * <p>校验口径（2026-10 媒体扩展）：先**按内容解码校验**，解不出来一律拒收（避免"能导入却贴不上"，
     * 例如缺 WebP 解码器时的 .webp）；后缀不在白名单但内容可解码者，一律按内容嗅探到的规范后缀入库
     * （如 {@code .jfif} 的 JPEG → {@code <uuid>.jpg}）。</p>
     * <p>兜底（2026-10 WebP 修复）：本机存在 ffmpeg 时，对解不出来的文件（WebP/AVIF/HEIC…）
     * 先转成 PNG 再入库；可用 {@code theme.media.ffmpeg}=off 关闭。</p>
     */
    public static String importImage(File source) {
        if (source == null || !source.isFile()) {
            return null;
        }
        BufferedImage probe = readImageQuietly(source);
        File toCopy = source;
        String ext = extensionOf(source.getName());

        if (probe == null) {
            // 可选兜底：本机 ffmpeg 转码（WebP/AVIF/HEIC 等 JDK 解不了的格式）
            if (ThemeImageIo.ffmpegEnabled() && ThemeImageIo.ffmpegAvailable()) {
                File converted = ThemeImageIo.convertToPng(source);
                if (converted != null) {
                    BufferedImage convertedImage = readImageQuietly(converted);
                    if (convertedImage != null) {
                        probe = convertedImage;
                        toCopy = converted;
                        ext = "png";
                    } else {
                        converted.delete();
                    }
                }
            }
            if (probe == null) {
                LogManager.log("主题-图片不可解码，已拒收: " + source.getAbsolutePath()
                        + "（当前支持: " + supportedExtensionsText()
                        + "；可放 lib/ 插件或安装 ffmpeg 以便自动转换）", "THEME_IMPORT");
                return null;
            }
        } else if (ext == null) {
            ext = canonicalExtensionOf(source);
        }
        if (ext == null) {
            ext = "png";
        }
        ensureDirs();
        String id = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        File dest = new File(IMAGES_DIR, id);
        try {
            Files.copy(toCopy.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            setImageName(id, baseNameOf(source.getName()));
            // 导入时已解码：顺手写尺寸缓存，列表首屏不必再解一次
            synchronized (SIZE_CACHE) {
                SIZE_CACHE.put(dest.getAbsolutePath() + "|" + dest.length() + "|" + dest.lastModified(),
                        new Dimension(probe.getWidth(), probe.getHeight()));
            }
            return id;
        } catch (IOException e) {
            LogManager.log("主题-图片导入失败: " + source.getAbsolutePath() + "（" + e + "）", "PLUGIN_LOAD_ERROR");
            return null;
        } finally {
            if (toCopy != source) {
                toCopy.delete(); // 清理 ffmpeg 转码的临时文件
            }
        }
    }

    public static boolean deleteImage(String imageId) {
        if (imageId == null || imageId.isEmpty()) {
            return false;
        }
        File f = imageFile(imageId);
        boolean ok = f != null && f.delete();
        WindowBackdrop.purgeCache(f);
        return ok;
    }

    /**
     * 删除图库图片并清理全部引用（D9）：全局/各类别对它的指定被清空（类别回继承全局、全局回无图）、
     * 别名键移除、文件与解码缓存清理。返回是否删除成功（文件不存在也算已清理，返回 false）。
     */
    public static boolean deleteImageAndCleanup(String imageId) {
        if (imageId == null || imageId.isEmpty()) {
            return false;
        }
        clearImageReferences(imageId);
        setImageName(imageId, null);
        return deleteImage(imageId);
    }

    /** 把替换用的新图接替旧图：全局与全部类别引用重定向 + 继承别名（旧图清理由调用方决定）。 */
    public static String replaceImage(String oldImageId, File source) {
        if (oldImageId == null || oldImageId.isEmpty() || source == null) {
            return null;
        }
        String newId = importImage(source);
        if (newId == null) {
            return null;
        }
        String alias = getImageName(oldImageId);
        setImageName(newId, alias != null && !alias.isEmpty() ? alias : baseNameOf(source.getName()));

        if (oldImageId.equals(getGlobalImage())) {
            setGlobalImage(newId);
        }
        for (String category : CATEGORY_IDS) {
            String per = ThemeConfig.get(catKey(category, "image"), "").trim();
            if (oldImageId.equals(per)) {
                setCategoryImage(category, newId);
            }
        }
        deleteImageAndCleanup(oldImageId);
        return newId;
    }

    /** 清空全局/各类别对某图片的直接引用（别名与文件不动）。 */
    private static void clearImageReferences(String imageId) {
        if (imageId.equals(getGlobalImage())) {
            setGlobalImage(null);
        }
        for (String category : CATEGORY_IDS) {
            String per = ThemeConfig.get(catKey(category, "image"), "").trim();
            if (imageId.equals(per)) {
                setCategoryImage(category, null);
            }
        }
    }

    /**
     * 图库全部 id（文件名升序）。列出条件：后缀在白名单内，**或**后缀不在白名单但内容确实可解码
     * （后者覆盖手工放进 {@code data/themes/images/} 的 {@code .jfif} 等文件）。
     */
    public static List<String> listImages() {
        File dir = new File(IMAGES_DIR);
        if (!dir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = dir.listFiles(f -> f != null && f.isFile() && isLibraryImage(f));
        if (files == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (File f : files) {
            out.add(f.getName());
        }
        Collections.sort(out);
        return out;
    }

    /** 图库成员判定：白名单后缀直接收；未知后缀再看内容能否解码（成本仅一次头部嗅探）。 */
    private static boolean isLibraryImage(File f) {
        if (extensionOf(f.getName()) != null) {
            return true;
        }
        return canonicalExtensionOf(f) != null;
    }

    /** 图库全部 id，按“别名 → id”排序（图片列表管理默认展示序，别名大小写不敏感）。 */
    public static List<String> listImageIdsSorted() {
        List<String> out = new ArrayList<>(listImages());
        out.sort((a, b) -> {
            int c = imageDisplayName(a).compareToIgnoreCase(imageDisplayName(b));
            return c != 0 ? c : a.compareTo(b);
        });
        return out;
    }

    // ===================== 图片别名 / 元信息 / 引用情况 =====================

    private static String imageNameKey(String imageId) {
        return "name." + imageId;
    }

    /** 图片别名（无别名返回空串）。 */
    public static String getImageName(String imageId) {
        if (imageId == null || imageId.isEmpty()) {
            return "";
        }
        return ThemeConfig.get(imageNameKey(imageId), "").trim();
    }

    /** 设置图片别名（空/null 移除别名键；**不改磁盘文件名、不动任何引用**）。 */
    public static void setImageName(String imageId, String name) {
        if (imageId == null || imageId.isEmpty()) {
            return;
        }
        String v = name == null ? "" : name.trim();
        ThemeConfig.set(imageNameKey(imageId), v.isEmpty() ? null : v);
    }

    /** id 的短标识（去扩展名后前 8 位），用于 UI 在别名重名时消歧。 */
    public static String shortId(String imageId) {
        if (imageId == null || imageId.isEmpty()) {
            return "";
        }
        int dot = imageId.lastIndexOf('.');
        String core = dot > 0 ? imageId.substring(0, dot) : imageId;
        return core.length() <= 8 ? core : core.substring(0, 8);
    }

    /** 列表/下拉展示名：有别名为“别名(短id)”，无别名退回“短id”。 */
    public static String imageDisplayName(String imageId) {
        String alias = getImageName(imageId);
        String shortTag = shortId(imageId);
        if (alias.isEmpty()) {
            return shortTag;
        }
        return alias + "(" + shortTag + ")";
    }

    /** 该图片被**直接指定**的位置（{@link #USAGE_GLOBAL} 或类别 ID）；未使用返回空列表。 */
    public static List<String> imageUsage(String imageId) {
        List<String> out = new ArrayList<>();
        if (imageId == null || imageId.isEmpty()) {
            return out;
        }
        if (imageId.equals(getGlobalImage())) {
            out.add(USAGE_GLOBAL);
        }
        for (String category : CATEGORY_IDS) {
            String per = ThemeConfig.get(catKey(category, "image"), "").trim();
            if (imageId.equals(per)) {
                out.add(category);
            }
        }
        return out;
    }

    /** 引用情况文案：“全局、主窗口” / “未使用”。 */
    public static String usageText(String imageId) {
        List<String> usage = imageUsage(imageId);
        if (usage.isEmpty()) {
            return "未使用";
        }
        StringBuilder sb = new StringBuilder();
        for (String u : usage) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(categoryLabel(USAGE_GLOBAL.equals(u) ? null : u));
        }
        return sb.toString();
    }

    /** 图片像素尺寸文案（带缓存解码；无法解码返回“无法解码”）。 */
    public static String imageSizeText(File file) {
        if (file == null || !file.isFile()) {
            return "图片丢失";
        }
        Dimension d = imageDimension(file);
        return d == null ? "无法解码" : d.width + "×" + d.height;
    }

    /** 文件大小文案（KB，<1KB 显示 B）。 */
    public static String fileSizeText(File file) {
        if (file == null || !file.isFile()) {
            return "-";
        }
        long bytes = file.length();
        if (bytes < 1024) {
            return bytes + " B";
        }
        return (bytes / 1024) + " KB";
    }

    /** 文件名去扩展名（别名默认值）。 */
    public static String baseNameOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public static File imageFile(String imageId) {
        if (imageId == null || imageId.isEmpty()) {
            return null;
        }
        File f = new File(IMAGES_DIR, imageId);
        return f.isFile() ? f : null;
    }

    public static void ensureDirs() {
        new File(IMAGES_DIR).mkdirs();
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return supportedExtSet().contains(ext) ? ext : null;
    }

    // ===================== 裁剪框 =====================

    public static Rectangle2D.Double parseCrop(String s) {
        if (s == null || s.trim().isEmpty()) {
            return (Rectangle2D.Double) ThemeStyle.FULL_CROP.clone();
        }
        try {
            String[] p = s.trim().split(",");
            if (p.length == 4) {
                double x = Double.parseDouble(p[0].trim());
                double y = Double.parseDouble(p[1].trim());
                double w = Double.parseDouble(p[2].trim());
                double h = Double.parseDouble(p[3].trim());
                return new Rectangle2D.Double(
                        Math.max(0, Math.min(1, x)),
                        Math.max(0, Math.min(1, y)),
                        Math.max(0.001, Math.min(1, w)),
                        Math.max(0.001, Math.min(1, h)));
            }
        } catch (NumberFormatException ignore) {
            // 回退整图
        }
        return (Rectangle2D.Double) ThemeStyle.FULL_CROP.clone();
    }

    public static String cropToString(Rectangle2D.Double crop) {
        if (crop == null) {
            crop = ThemeStyle.FULL_CROP;
        }
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", crop.x, crop.y, crop.width, crop.height);
    }

    // ===================== 类别清空 / 恢复默认 =====================

    /** 设置 UI 读取辅助：类别当前生效模式（未指定=继承全局模式）。 */
    public static BackdropMode categoryModeNow(String category) {
        if (category == null) {
            return globalMode();
        }
        String per = ThemeConfig.get(catKey(category, "mode"), "").trim();
        return per.isEmpty() ? globalMode() : BackdropMode.parse(per);
    }

    /** 设置 UI 读取辅助：全局当前模式。 */
    public static BackdropMode globalModeNow() {
        return globalMode();
    }

    /** 设置 UI 读取辅助：类别当前图片 id（未指定=继承全局图片；可能为空串）。 */
    public static String categoryImageIdNow(String category) {
        return categoryImageId(category);
    }

    /** 该类别是否**单独指定**了图片（false = 继承全局；供预览/列表标注“继承全局”）。 */
    public static boolean categoryHasOwnImage(String category) {
        if (category == null) {
            return false;
        }
        return !ThemeConfig.get(catKey(category, "image"), "").trim().isEmpty();
    }

    /** 别名：类别实际生效图片 id（含继承全局）。 */
    public static String categoryResolvedImageId(String category) {
        return categoryImageId(category);
    }

    /** 设置 UI 读取辅助：全局或类别当前裁剪配置串（可能为空串 → 视作整图）。 */
    public static String configCropString(String category) {
        return category == null
                ? ThemeConfig.get(globalKey("crop"), "")
                : ThemeConfig.get(catKey(category, "crop"), "");
    }

    /** 清空某类别全部指定（图片/模式/裁剪 → 图片回继承全局、模式回默认、裁剪回整图）。 */
    public static void clearCategory(String category) {
        if (category == null) {
            return;
        }
        ThemeConfig.set(catKey(category, "image"), null);
        ThemeConfig.set(catKey(category, "mode"), null);
        ThemeConfig.set(catKey(category, "crop"), null);
    }

    /** 恢复出厂：关闭启用、遮罩/表格档回默认、全局空图（整图/填充）、全部类别清空、**动态背景与背景音清空**。 */
    public static void resetToDefaults() {
        setEnabled(false);
        setMask(MASK_DEFAULT);
        setTableTier(TABLE_TIER_DEFAULT);
        setGlobalImage(null);
        setGlobalMode(BackdropMode.FILL);
        ThemeConfig.set(globalKey("crop"), null);
        MediaLibrary.clearAllReferences();
        for (String c : CATEGORY_IDS) {
            clearCategory(c);
        }
    }

    private static int clampInt(String v, int min, int max, int def) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (Exception e) {
            return def;
        }
    }
}
