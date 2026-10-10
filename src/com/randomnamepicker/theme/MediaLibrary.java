package com.randomnamepicker.theme;

import com.randomnamepicker.core.LogManager;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioSystem;

/**
 * 动态背景**媒体库**（零依赖路线 A）：动图 GIF / 帧序列 ZIP / 背景音（WAV·AIFF·AU）的入库、列出、引用与删除。
 * <p>
 * 落盘：{@code data/themes/media/}（与图片库 {@code images/} 并列，一律"引用 id 不引用原路径"）。
 * 别名复用 {@code theme.name.<id>} 键空间（与图片同一套别名机制）。
 * 优先级口径：**某窗口类别单独指定了静态图片 → 该类用静态图；否则用全局动态背景（若设置）；再否则用全局静态图**。
 * </p>
 */
public final class MediaLibrary {

    public static final String MEDIA_DIR = ThemeStore.THEME_DIR + "/media";

    public static final int DEFAULT_FPS = 12;
    public static final int FPS_MIN = 1;
    public static final int FPS_MAX = 60;
    public static final int VOLUME_DEFAULT = 60;
    public static final int MAX_SEQ_FRAMES = 600;

    private static final String[] ANIM_EXT = {"gif", "zip"};
    private static final String[] AUDIO_EXT = {"wav", "aiff", "aif", "au"};

    /** 元信息/可用性缓存（键=路径|大小|修改时间）——避免每次刷新目录都重新开一遍 GIF/ZIP。 */
    private static final int CACHE_MAX = 64;
    private static final Map<String, String> INFO_CACHE = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_MAX;
        }
    };
    private static final Map<String, Boolean> USABLE_CACHE = new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > CACHE_MAX;
        }
    };

    private MediaLibrary() {
    }

    // ===================== 配置键 =====================

    /** 全局动态背景 id（动图 GIF 或帧序列 ZIP；空=无）。 */
    public static String getMediaId() {
        return ThemeConfig.get("g.media", "").trim();
    }

    public static void setMediaId(String mediaId) {
        ThemeConfig.set("g.media",
                mediaId == null || mediaId.isEmpty() ? null : mediaId);
    }

    /** 背景音 id（WAV/AIFF/AU；空=无）。 */
    public static String getAudioId() {
        return ThemeConfig.get("g.audio", "").trim();
    }

    public static void setAudioId(String audioId) {
        ThemeConfig.set("g.audio",
                audioId == null || audioId.isEmpty() ? null : audioId);
    }

    /** 帧序列统一帧率（GIF 用其自带帧时长，不受此值影响）。 */
    public static int getFps() {
        return clampInt(ThemeConfig.get("media.fps", String.valueOf(DEFAULT_FPS)),
                FPS_MIN, FPS_MAX, DEFAULT_FPS);
    }

    public static void setFps(int fps) {
        ThemeConfig.set("media.fps",
                String.valueOf(clampInt(String.valueOf(fps), FPS_MIN, FPS_MAX, DEFAULT_FPS)));
    }

    public static boolean isLoop() {
        return Boolean.parseBoolean(ThemeConfig.get("media.loop", "true"));
    }

    public static void setLoop(boolean loop) {
        ThemeConfig.set("media.loop", String.valueOf(loop));
    }

    /** 是否静音（默认**静音**：避免一启动就出声；取消勾选即播放）。 */
    public static boolean isMuted() {
        return Boolean.parseBoolean(ThemeConfig.get("media.muted", "true"));
    }

    public static void setMuted(boolean muted) {
        ThemeConfig.set("media.muted", String.valueOf(muted));
    }

    /** 音量 0–100。 */
    public static int getVolume() {
        return clampInt(ThemeConfig.get("media.volume", String.valueOf(VOLUME_DEFAULT)),
                0, 100, VOLUME_DEFAULT);
    }

    public static void setVolume(int volume) {
        ThemeConfig.set("media.volume",
                String.valueOf(clampInt(String.valueOf(volume), 0, 100, VOLUME_DEFAULT)));
    }

    /** 恢复默认：清空媒体与背景音的全部键（"恢复默认"按钮用）。 */
    public static void clearAllReferences() {
        setMediaId(null);
        setAudioId(null);
        ThemeConfig.set("media.fps", null);
        ThemeConfig.set("media.loop", null);
        ThemeConfig.set("media.muted", null);
        ThemeConfig.set("media.volume", null);
    }

    // ===================== 目录与列出 =====================

    public static void ensureDirs() {
        new File(MEDIA_DIR).mkdirs();
    }

    public static File mediaFile(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        File f = new File(MEDIA_DIR, id);
        return f.isFile() ? f : null;
    }

    /** 可用作动态背景的媒体 id（多帧 GIF / ≥2 帧的序列 ZIP），按别名排序。 */
    public static List<String> listAnimationIds() {
        List<String> out = new ArrayList<>();
        for (String id : listFiles(ANIM_EXT)) {
            if (isUsableAnimation(id)) {
                out.add(id);
            }
        }
        sortByAlias(out);
        return out;
    }

    /** 可用作背景音的媒体 id（WAV/AIFF/AU 且能被 AudioSystem 解析），按别名排序。 */
    public static List<String> listAudioIds() {
        List<String> out = new ArrayList<>();
        for (String id : listFiles(AUDIO_EXT)) {
            if (isUsableAudio(mediaFile(id))) {
                out.add(id);
            }
        }
        sortByAlias(out);
        return out;
    }

    /** 媒体目录下全部文件 id（含不可用文件，供管理/清理入口展示）。 */
    public static List<String> listAllMediaIds() {
        List<String> out = new ArrayList<>();
        File dir = new File(MEDIA_DIR);
        File[] files = dir.listFiles(f -> f != null && f.isFile());
        if (files != null) {
            for (File f : files) {
                out.add(f.getName());
            }
        }
        sortByAlias(out);
        return out;
    }

    /** 该 id 是否为可用动态背景（多帧 GIF / ≥2 帧 ZIP），结果带缓存。 */
    public static boolean isUsableAnimation(String id) {
        File f = mediaFile(id);
        if (f == null) {
            return false;
        }
        String ext = extOf(id);
        if (!"gif".equals(ext) && !"zip".equals(ext)) {
            return false;
        }
        String key = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        synchronized (USABLE_CACHE) {
            Boolean cached = USABLE_CACHE.get(key);
            if (cached != null) {
                return cached.booleanValue();
            }
        }
        boolean ok;
        if ("gif".equals(ext)) {
            ok = MediaSource.isAnimatedGif(f);
        } else {
            MediaSource src = MediaSource.open(id);
            ok = src != null && src.frameCount() >= 2;
            if (src != null) {
                src.dispose();
            }
        }
        synchronized (USABLE_CACHE) {
            USABLE_CACHE.put(key, Boolean.valueOf(ok));
        }
        return ok;
    }

    /** 该文件能否被 AudioSystem 解析（音频可用性判定）。用 File 重载：InputStream 重载要求流可 mark/reset。 */
    public static boolean isUsableAudio(File f) {
        if (f == null || !f.isFile()) {
            return false;
        }
        try {
            AudioSystem.getAudioFileFormat(f);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 媒体展示名（优先别名，复用 {@code theme.name.<id>}；无别名用短 id + 类型后缀）。 */
    public static String mediaDisplayName(String id) {
        if (id == null || id.isEmpty()) {
            return "（无）";
        }
        String alias = ThemeStore.getImageName(id);
        if (!alias.isEmpty()) {
            return alias;
        }
        return ThemeStore.shortId(id);
    }

    /** 媒体元信息文案：GIF/帧序列给帧数·尺寸·帧率，音频给大小与格式。 */
    public static String mediaInfoText(String id) {
        File f = mediaFile(id);
        if (f == null) {
            return "文件丢失";
        }
        String key = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        synchronized (INFO_CACHE) {
            String cached = INFO_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        String info;
        if (isUsableAudio(f) && !"gif".equals(extOf(id)) && !"zip".equals(extOf(id))) {
            info = extOf(id).toUpperCase(Locale.ROOT) + " · " + ThemeStore.fileSizeText(f);
        } else {
            MediaSource src = MediaSource.open(id);
            if (src == null) {
                info = extOf(id).toUpperCase(Locale.ROOT) + " · 不可用（需多帧 GIF 或 ≥2 帧 ZIP）";
            } else {
                Dimension d = src.sourceSize();
                StringBuilder sb = new StringBuilder();
                sb.append("zip".equals(extOf(id)) ? "帧序列" : "GIF 动图");
                sb.append(" · ").append(src.frameCount()).append(" 帧");
                if (d.width > 0) {
                    sb.append(" · ").append(d.width).append("×").append(d.height);
                }
                if (src.useFixedFps()) {
                    sb.append(" · ").append(getFps()).append(" fps");
                } else {
                    sb.append(" · 帧自带时长");
                }
                sb.append(" · ").append(ThemeStore.fileSizeText(f));
                info = sb.toString();
                src.dispose();
            }
        }
        synchronized (INFO_CACHE) {
            INFO_CACHE.put(key, info);
        }
        return info;
    }

    // ===================== 入库 =====================

    /** 导入动图：多帧 GIF 或序列 ZIP（复制入库，别名取源文件名）。失败返回 null。 */
    public static String importAnimation(File source) {
        if (source == null || !source.isFile()) {
            return null;
        }
        String ext = extOf(source.getName());
        if (!"gif".equals(ext) && !"zip".equals(ext)) {
            LogManager.log("主题-动态背景导入被拒（仅支持多帧 GIF / 序列 ZIP）: " + source.getName(), "THEME_IMPORT");
            return null;
        }
        ensureDirs();
        String id = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            Files.copy(source.toPath(), new File(MEDIA_DIR, id).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LogManager.log("主题-动态背景导入失败: " + source.getAbsolutePath() + "（" + e + "）", "THEME_IMPORT");
            return null;
        }
        if (!isUsableAnimation(id)) {
            new File(MEDIA_DIR, id).delete();
            LogManager.log("主题-动态背景不可用（GIF 需多帧 / ZIP 需 ≥2 帧图片）: " + source.getName(), "THEME_IMPORT");
            return null;
        }
        ThemeStore.setImageName(id, ThemeStore.baseNameOf(source.getName()));
        LogManager.log("主题-导入动态背景 " + source.getName() + " → " + mediaInfoText(id), "THEME_IMPORT");
        return id;
    }

    /**
     * 把**帧序列目录**打包入库为序列 ZIP（条目按文件名自然序，帧率走配置 {@code theme.media.fps}）。
     * 失败返回 null（帧数不足/过多/无可解码图片）。
     */
    public static String importFrameSequence(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles(f -> f != null && f.isFile() && ThemeStore.canDecode(f));
        if (files == null || files.length < 2) {
            LogManager.log("主题-帧序列目录可用图片不足 2 张: " + dir.getAbsolutePath(), "THEME_IMPORT");
            return null;
        }
        if (files.length > MAX_SEQ_FRAMES) {
            LogManager.log("主题-帧序列帧数过多（" + files.length + " > " + MAX_SEQ_FRAMES + "）: "
                    + dir.getAbsolutePath(), "THEME_IMPORT");
            return null;
        }
        List<File> ordered = new ArrayList<>();
        Collections.addAll(ordered, files);
        ordered.sort((a, b) -> MediaSource.naturalOrder().compare(a.getName(), b.getName()));

        ensureDirs();
        String id = UUID.randomUUID().toString().replace("-", "") + ".zip";
        File dest = new File(MEDIA_DIR, id);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(dest.toPath()))) {
            int index = 1;
            for (File f : ordered) {
                String ext = extOf(f.getName());
                String entryName = String.format(Locale.ROOT, "frame_%04d.%s", index, ext);
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(f.toPath(), zip);
                zip.closeEntry();
                index++;
            }
        } catch (IOException e) {
            LogManager.log("主题-帧序列打包失败: " + dir.getAbsolutePath() + "（" + e + "）", "THEME_IMPORT");
            dest.delete();
            return null;
        }
        ThemeStore.setImageName(id, dir.getName());
        LogManager.log("主题-导入帧序列 " + dir.getName() + "（" + ordered.size() + " 帧，"
                + getFps() + " fps）→ " + mediaInfoText(id), "THEME_IMPORT");
        return id;
    }

    /** 导入背景音（WAV/AIFF/AU，AudioSystem 能解析者）。失败返回 null。 */
    public static String importAudio(File source) {
        if (source == null || !source.isFile()) {
            return null;
        }
        String ext = extOf(source.getName());
        boolean extOk = false;
        for (String allowed : AUDIO_EXT) {
            if (allowed.equals(ext)) {
                extOk = true;
                break;
            }
        }
        if (!extOk) {
            ext = "wav";
        }
        if (!isUsableAudio(source)) {
            LogManager.log("主题-背景音不可解析（仅支持 WAV/AIFF/AU）: " + source.getName(), "THEME_IMPORT");
            return null;
        }
        ensureDirs();
        String id = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            Files.copy(source.toPath(), new File(MEDIA_DIR, id).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LogManager.log("主题-背景音导入失败: " + source.getAbsolutePath() + "（" + e + "）", "THEME_IMPORT");
            return null;
        }
        ThemeStore.setImageName(id, ThemeStore.baseNameOf(source.getName()));
        LogManager.log("主题-导入背景音 " + source.getName() + " → " + mediaInfoText(id), "THEME_IMPORT");
        return id;
    }

    // ===================== 删除 =====================

    /** 删除媒体并清理引用（全局动态背景/背景音指向它时一并清空），同时停止正在播放的实例。 */
    public static boolean deleteMediaAndCleanup(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (id.equals(getMediaId())) {
            setMediaId(null);
        }
        if (id.equals(getAudioId())) {
            setAudioId(null);
        }
        ThemeStore.setImageName(id, null);
        File f = mediaFile(id);
        boolean ok = f != null && f.delete();
        MediaClock.getInstance().invalidate();
        ThemeAudio.getInstance().stop();
        WindowBackdrop.purgeCache(f);
        LogManager.log("主题-删除媒体 " + id + "（文件删除=" + ok + "）", "THEME_IMPORT");
        return ok;
    }

    // ===================== 内部辅助 =====================

    private static List<String> listFiles(String[] exts) {
        List<String> out = new ArrayList<>();
        File dir = new File(MEDIA_DIR);
        File[] files = dir.listFiles(f -> {
            if (f == null || !f.isFile()) {
                return false;
            }
            String ext = extOf(f.getName());
            for (String allowed : exts) {
                if (allowed.equals(ext)) {
                    return true;
                }
            }
            return false;
        });
        if (files != null) {
            for (File f : files) {
                out.add(f.getName());
            }
        }
        return out;
    }

    private static void sortByAlias(List<String> ids) {
        ids.sort((a, b) -> {
            int c = mediaDisplayName(a).compareToIgnoreCase(mediaDisplayName(b));
            return c != 0 ? c : a.compareTo(b);
        });
    }

    private static String extOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static int clampInt(String v, int min, int max, int def) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (Exception e) {
            return def;
        }
    }
}
