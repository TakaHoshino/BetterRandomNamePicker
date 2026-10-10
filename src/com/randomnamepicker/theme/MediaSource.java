package com.randomnamepicker.theme;

import com.randomnamepicker.core.LogManager;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;

/**
 * 动态背景**帧源**（零依赖路线 A）：把"动图 GIF / 帧序列 ZIP"统一成"第 i 帧 + 该帧时长"的取帧接口。
 * <p>
 * 取帧口径：**按需解码 + 有界帧缓存（LRU，默认 32 帧）**，因此不必把整段动图读进内存；
 * 顺序播放时每次取下一帧都是一次顺序解码（本机实测 PNG 解码 ≈4.6 ms/帧 / 1280×720，见
 * 《主题媒体扩展与视频背景_方案.md》§2.5）。仅 EDT 使用。
 * </p>
 */
public abstract class MediaSource {

    /** 帧缓存上限（张）。 */
    private static final int FRAME_CACHE_MAX = 32;

    /** 帧时长下限（毫秒；GIF 里常见的"0 延时"按浏览器惯例抬到 100ms，序列帧按此下限保护）。 */
    public static final int MIN_DELAY_MS = 20;

    /** GIF 帧时长的兜底与下限（浏览器惯例：小于 20ms 的按 100ms 处理）。 */
    private static final int MIN_FRAME_DELAY_MS = MIN_DELAY_MS;
    private static final int FALLBACK_FRAME_DELAY_MS = 100;

    private final String id;
    private final File file;
    private final Map<Integer, BufferedImage> cache =
            new LinkedHashMap<Integer, BufferedImage>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, BufferedImage> eldest) {
                    return size() > FRAME_CACHE_MAX;
                }
            };

    protected MediaSource(String id, File file) {
        this.id = id;
        this.file = file;
    }

    /** 打开一个动态背景帧源；id 为空/文件缺失/不含多帧 → 返回 null（调用方按"无动态背景"处理）。 */
    public static MediaSource open(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        File file = new File(MediaLibrary.MEDIA_DIR, id);
        if (!file.isFile()) {
            return null;
        }
        String ext = extensionOf(id);
        try {
            if ("gif".equals(ext)) {
                GifSource gif = new GifSource(id, file);
                return gif.frameCount() >= 2 ? gif : null;
            }
            if ("zip".equals(ext)) {
                SequenceSource seq = new SequenceSource(id, file);
                return seq.frameCount() >= 2 ? seq : null;
            }
        } catch (Throwable t) {
            LogManager.log("主题-动态背景打开失败: " + id + "（" + t + "）", "THEME_DEBUG");
        }
        return null;
    }

    /** 内容嗅探判断是否为"可当动图用"的 GIF（多帧）。 */
    public static boolean isAnimatedGif(File f) {
        if (f == null || !f.isFile()) {
            return false;
        }
        ImageReader reader = null;
        ImageInputStream in = null;
        try {
            reader = ImageIO.getImageReadersByFormatName("gif").next();
            in = ImageIO.createImageInputStream(f);
            reader.setInput(in, false, false);
            return reader.getNumImages(true) >= 2;
        } catch (Throwable t) {
            return false;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignore) {
                    // 忽略
                }
            }
        }
    }

    private static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public final String getId() {
        return id;
    }

    public final File getFile() {
        return file;
    }

    /** 帧数（≥2 才算动态背景）。 */
    public abstract int frameCount();

    /** 是否使用统一帧率（帧序列=true，按配置 {@code theme.media.fps}）；GIF=false（用帧自带时长）。 */
    public abstract boolean useFixedFps();

    /** 第 index 帧的显示时长（毫秒，下限 {@value #MIN_FRAME_DELAY_MS}）；仅 GIF 有意义。 */
    public abstract int delayMillis(int index);

    /** 源尺寸（首帧，用于元信息展示）。 */
    public abstract Dimension sourceSize();

    /** 释放底层资源（读取器/压缩包句柄）。 */
    public abstract void dispose();

    /** 取第 index 帧（按需解码 + LRU 缓存，返回后调用方不应修改）。 */
    public final BufferedImage frame(int index) {
        int count = frameCount();
        if (count <= 0) {
            return null;
        }
        int i = ((index % count) + count) % count;
        synchronized (cache) {
            BufferedImage cached = cache.get(Integer.valueOf(i));
            if (cached != null) {
                return cached;
            }
        }
        BufferedImage img = decode(i);
        if (img != null) {
            synchronized (cache) {
                cache.put(Integer.valueOf(i), img);
            }
        }
        return img;
    }

    protected abstract BufferedImage decode(int index);

    // ===================== GIF 动图（多帧 + 每帧 delay） =====================

    private static final class GifSource extends MediaSource {

        private final ImageReader reader;
        private final ImageInputStream stream;
        private final int count;
        private final int[] delays;
        private final Dimension size;

        GifSource(String id, File file) throws IOException {
            super(id, file);
            this.reader = ImageIO.getImageReadersByFormatName("gif").next();
            this.stream = ImageIO.createImageInputStream(file);
            this.reader.setInput(stream, false, false);
            this.count = reader.getNumImages(true);
            this.delays = new int[Math.max(0, count)];
            for (int i = 0; i < count; i++) {
                delays[i] = readDelay(i);
            }
            BufferedImage first = count > 0 ? reader.read(0) : null;
            this.size = first == null ? new Dimension(0, 0)
                    : new Dimension(first.getWidth(), first.getHeight());
        }

        /** 从 GIF 元数据读帧时长（1/100 秒），缺失/异常回退默认值。 */
        private int readDelay(int index) {
            try {
                IIOMetadata meta = reader.getImageMetadata(index);
                if (meta == null) {
                    return FALLBACK_FRAME_DELAY_MS;
                }
                Node tree = meta.getAsTree("javax_imageio_gif_image_1.0");
                Node gce = findChild(tree, "GraphicControlExtension");
                if (gce == null) {
                    return FALLBACK_FRAME_DELAY_MS;
                }
                Node delay = gce.getAttributes() == null ? null
                        : gce.getAttributes().getNamedItem("delayTime");
                if (delay == null) {
                    return FALLBACK_FRAME_DELAY_MS;
                }
                int hundredths = Integer.parseInt(delay.getNodeValue().trim());
                int ms = hundredths * 10;
                return ms < MIN_FRAME_DELAY_MS ? FALLBACK_FRAME_DELAY_MS : ms;
            } catch (Throwable t) {
                return FALLBACK_FRAME_DELAY_MS;
            }
        }

        private Node findChild(Node node, String name) {
            if (node == null) {
                return null;
            }
            if (name.equals(node.getNodeName())) {
                return node;
            }
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                Node found = findChild(child, name);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        @Override
        public int frameCount() {
            return count;
        }

        @Override
        public boolean useFixedFps() {
            return false;
        }

        @Override
        public int delayMillis(int index) {
            if (delays.length == 0) {
                return FALLBACK_FRAME_DELAY_MS;
            }
            int ms = delays[((index % delays.length) + delays.length) % delays.length];
            return ms < MIN_FRAME_DELAY_MS ? FALLBACK_FRAME_DELAY_MS : ms;
        }

        @Override
        public Dimension sourceSize() {
            return size;
        }

        @Override
        protected BufferedImage decode(int index) {
            try {
                return reader.read(index);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public void dispose() {
            try {
                reader.dispose();
            } catch (Throwable ignore) {
                // 忽略
            }
            try {
                stream.close();
            } catch (Throwable ignore) {
                // 忽略
            }
        }
    }

    // ===================== 帧序列（ZIP：图片条目按名排序 + 统一帧率） =====================

    private static final class SequenceSource extends MediaSource {

        private final ZipFile zip;
        private final List<String> entries = new ArrayList<>();
        private final Dimension size;

        SequenceSource(String id, File file) throws IOException {
            super(id, file);
            this.zip = new ZipFile(file);
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && ThemeStore.isSupportedExtension(e.getName())) {
                    names.add(e.getName());
                }
            }
            names.sort(new NaturalOrder());
            entries.addAll(names);
            Dimension d = new Dimension(0, 0);
            if (!entries.isEmpty()) {
                BufferedImage first = decode(0);
                if (first != null) {
                    d = new Dimension(first.getWidth(), first.getHeight());
                }
            }
            this.size = d;
        }

        @Override
        public int frameCount() {
            return entries.size();
        }

        @Override
        public boolean useFixedFps() {
            return true;
        }

        @Override
        public int delayMillis(int index) {
            // 序列帧用统一帧率（配置 theme.media.fps），时长换算在 MediaClock 内完成
            return -1;
        }

        @Override
        public Dimension sourceSize() {
            return size;
        }

        @Override
        protected BufferedImage decode(int index) {
            if (index < 0 || index >= entries.size()) {
                return null;
            }
            try (InputStream in = zip.getInputStream(zip.getEntry(entries.get(index)))) {
                return ImageIO.read(in);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public void dispose() {
            try {
                zip.close();
            } catch (Throwable ignore) {
                // 忽略
            }
        }
    }

    /** 文件名自然序（frame2 < frame10）。 */
    static final class NaturalOrder implements Comparator<String> {
        @Override
        public int compare(String a, String b) {
            int i = 0;
            int j = 0;
            while (i < a.length() && j < b.length()) {
                char ca = a.charAt(i);
                char cb = b.charAt(j);
                if (Character.isDigit(ca) && Character.isDigit(cb)) {
                    int si = i;
                    int sj = j;
                    while (i < a.length() && Character.isDigit(a.charAt(i))) {
                        i++;
                    }
                    while (j < b.length() && Character.isDigit(b.charAt(j))) {
                        j++;
                    }
                    String na = a.substring(si, i).replaceFirst("^0+(?=\\d)", "");
                    String nb = b.substring(sj, j).replaceFirst("^0+(?=\\d)", "");
                    int cmp = Integer.compare(na.length(), nb.length());
                    if (cmp != 0) {
                        return cmp;
                    }
                    cmp = na.compareTo(nb);
                    if (cmp != 0) {
                        return cmp;
                    }
                } else {
                    int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                    if (cmp != 0) {
                        return cmp;
                    }
                    i++;
                    j++;
                }
            }
            return Integer.compare(a.length(), b.length());
        }
    }

    /** 供媒体库排序/诊断复用的自然序实例。 */
    public static Comparator<String> naturalOrder() {
        return new NaturalOrder();
    }

    /** 空帧源（无动态背景）。 */
    public static final MediaSource NONE = new MediaSource("", null) {
        @Override
        public int frameCount() {
            return 0;
        }

        @Override
        public boolean useFixedFps() {
            return false;
        }

        @Override
        public int delayMillis(int index) {
            return FALLBACK_FRAME_DELAY_MS;
        }

        @Override
        public Dimension sourceSize() {
            return new Dimension(0, 0);
        }

        @Override
        protected BufferedImage decode(int index) {
            return null;
        }

        @Override
        public void dispose() {
            // 无资源
        }
    };
}
