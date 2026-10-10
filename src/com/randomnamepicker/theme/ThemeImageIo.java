package com.randomnamepicker.theme;

import com.randomnamepicker.core.LogManager;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;

/**
 * 可选解码插件加载（宿主内建主题，2026-10 媒体扩展批次）：
 * JDK 自带 ImageIO 解码器之外的图片格式，**无需重新打包**即可启用——把第三方 ImageIO 插件 jar 放进
 * 运行目录的 {@code lib/} 后重启宿主，本类会把其中的 {@link ImageReaderSpi} 注册进 {@link IIORegistry}。
 * <p>
 * 典型用途：WebP（JDK 无内置解码器）。放入 TwelveMonkeys ImageIO 的 WebP 模块、或 sejda/luciad 的
 * webp-imageio 等按 ServiceLoader 规范注册的插件即可；{@link ThemeStore#supportedExtensions()}
 * 会自动把新后缀纳入白名单（因此"能导入什么格式"始终等于"运行环境真正能解码什么格式"）。
 * </p>
 * <p>
 * **另一条路（2026-10 新增，无需插件）**：若本机 PATH 里有 {@code ffmpeg}，导入时对**无法解码**的图片
 * （WebP/AVIF/HEIC…）自动转成 PNG 入库——{@link #convertToPng(File)}。这是"可选外部工具"路线，
 * 仅在导入那一刻用到，运行时零依赖；可用 {@code theme.media.ffmpeg}=off 关闭，或用
 * {@code theme.media.ffmpeg.path} 显式指定可执行文件。
 * </p>
 * <p>全部操作 try/catch 兜底：{@code lib/} 不存在、jar 损坏、插件与本机不兼容、ffmpeg 缺失，
 * 都只记日志不影响启动。</p>
 */
public final class ThemeImageIo {

    /** 可选插件目录（相对运行目录，与 plugins/ 并列）。 */
    public static final String LIB_DIR = "lib";

    private static boolean scanned = false;
    private static final List<String> loadedPlugins = new ArrayList<>();
    private static String scanNote = "未扫描";

    private ThemeImageIo() {
    }

    /**
     * 幂等：扫描类路径与 {@code lib/} 下的 ImageIO 插件并注册（可重复调用，仅首次生效）。
     * 供 UI 启动时提前调用，也可由 {@link ThemeStore} 首次使用时惰性触发。
     */
    public static synchronized void ensurePluginsLoaded() {
        if (scanned) {
            return;
        }
        scanned = true;
        try {
            // 1) 类路径内已有插件（例如用 -cp 带上插件 jar 启动）
            ImageIO.scanForPlugins();
        } catch (Throwable t) {
            LogManager.log("主题-ImageIO 类路径扫描失败: " + t, "THEME_DEBUG");
        }
        File dir = new File(LIB_DIR);
        if (!dir.isDirectory()) {
            scanNote = "无 lib/ 目录（仅用 JDK 内置解码器）";
            return;
        }
        File[] jars = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            scanNote = "lib/ 为空（仅用 JDK 内置解码器）";
            return;
        }
        URLClassLoader loader = null;
        try {
            URL[] urls = new URL[jars.length];
            for (int i = 0; i < jars.length; i++) {
                urls[i] = jars[i].toURI().toURL();
            }
            loader = new URLClassLoader(urls, ThemeImageIo.class.getClassLoader());
            int registered = 0;
            for (ImageReaderSpi spi : ServiceLoader.load(ImageReaderSpi.class, loader)) {
                try {
                    IIORegistry.getDefaultInstance().registerServiceProvider(spi);
                    registered++;
                    loadedPlugins.add(spi.getClass().getName());
                } catch (Throwable t) {
                    LogManager.log("主题-ImageIO 插件注册失败: " + spi.getClass().getName() + "（" + t + "）", "THEME_DEBUG");
                }
            }
            scanNote = "lib/ 命中 " + jars.length + " 个 jar，注册 " + registered + " 个读取器";
            LogManager.log("主题-ImageIO 插件扫描：" + scanNote, "THEME_DEBUG");
        } catch (Throwable t) {
            scanNote = "lib/ 扫描失败: " + t;
            LogManager.log("主题-" + scanNote, "THEME_DEBUG");
        } finally {
            // 插件类由 IIORegistry 持有，loader 不能在此关闭（否则后续解码可能 NoClassDefFoundError）
            if (loader != null && loadedPlugins.isEmpty()) {
                try {
                    loader.close();
                } catch (Throwable ignore) {
                    // 忽略
                }
            }
        }
    }

    /** 已注册的第三方读取器类名（诊断/自检用）。 */
    public static synchronized List<String> loadedPlugins() {
        return Collections.unmodifiableList(new ArrayList<>(loadedPlugins));
    }

    /** 扫描结论一行文案（诊断/自检用）。 */
    public static synchronized String scanNote() {
        return scanNote;
    }

    /** lib/ 目录绝对路径（UI 提示用）。 */
    public static String libDirPath() {
        return new File(LIB_DIR).getAbsolutePath();
    }

    // ===================== 可选的 ffmpeg 兜底转码（2026-10 WebP 修复批次） =====================

    /** 开关键：auto（默认，找到就用）/ on（强制要求存在）/ off（禁用）。 */
    private static final String FFMPEG_MODE_KEY = "media.ffmpeg";
    /** 可选：显式指定 ffmpeg 可执行文件路径（PATH 里没有时用）。 */
    private static final String FFMPEG_PATH_KEY = "media.ffmpeg.path";
    /** 转码临时目录（在主题数据目录内，避免依赖系统临时目录权限）。 */
    private static final String CONVERT_TMP_DIR = ThemeStore.THEME_DIR + "/tmp";

    private static boolean ffmpegProbed = false;
    private static String ffmpegPath;

    /** 是否允许用本机 ffmpeg 兜底转码（默认 auto=允许）。 */
    public static boolean ffmpegEnabled() {
        String mode = ThemeConfig.get(FFMPEG_MODE_KEY, "auto").trim().toLowerCase(Locale.ROOT);
        return !"off".equals(mode) && !"false".equals(mode);
    }

    public static void setFfmpegEnabled(boolean enabled) {
        ThemeConfig.set(FFMPEG_MODE_KEY, enabled ? "auto" : "off");
    }

    /** 探测本机 ffmpeg（结果缓存）：配置里显式指定优先，其次 PATH。找不到返回 null。 */
    public static synchronized String ffmpegPath() {
        if (ffmpegProbed) {
            return ffmpegPath;
        }
        ffmpegProbed = true;
        String configured = ThemeConfig.get(FFMPEG_PATH_KEY, "").trim();
        if (!configured.isEmpty()) {
            File f = new File(configured);
            if (f.isFile()) {
                ffmpegPath = f.getAbsolutePath();
                LogManager.log("主题-ffmpeg（配置指定）: " + ffmpegPath, "THEME_DEBUG");
                return ffmpegPath;
            }
            LogManager.log("主题-配置的 ffmpeg 路径不存在: " + configured, "THEME_DEBUG");
        }
        String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "ffmpeg.exe" : "ffmpeg";
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir == null || dir.trim().isEmpty()) {
                    continue;
                }
                File candidate = new File(dir.trim(), exe);
                if (candidate.isFile()) {
                    ffmpegPath = candidate.getAbsolutePath();
                    LogManager.log("主题-ffmpeg（PATH 命中）: " + ffmpegPath, "THEME_DEBUG");
                    return ffmpegPath;
                }
            }
        }
        ffmpegPath = null;
        LogManager.log("主题-未找到本机 ffmpeg（WebP 等格式需放 lib/ 插件，或转换后再导入）", "THEME_DEBUG");
        return null;
    }

    public static boolean ffmpegAvailable() {
        return ffmpegPath() != null;
    }

    /**
     * 用本机 ffmpeg 把**无法解码**的图片（WebP/AVIF/HEIC 等）转成 PNG。
     * 返回转换出的临时 PNG 文件（调用方负责删除），失败返回 null。
     */
    public static synchronized File convertToPng(File source) {
        String ff = ffmpegPath();
        if (ff == null || source == null || !source.isFile()) {
            return null;
        }
        File tmpDir = new File(CONVERT_TMP_DIR);
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) {
            LogManager.log("主题-无法创建转码临时目录: " + tmpDir.getAbsolutePath(), "THEME_DEBUG");
            return null;
        }
        File dest = new File(tmpDir, "conv_" + System.nanoTime() + ".png");
        try {
            ProcessBuilder pb = new ProcessBuilder(ff, "-hide_banner", "-loglevel", "error",
                    "-y", "-i", source.getAbsolutePath(), "-frames:v", "1", "-update", "1",
                    dest.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 必须把输出读干净，否则子进程可能因管道写满而阻塞
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                while (in.read(buf) != -1) {
                    // 丢弃，仅用于排空管道
                }
            }
            int code = p.waitFor();
            if (code == 0 && dest.isFile() && dest.length() > 0) {
                LogManager.log("主题-ffmpeg 转码成功: " + source.getName() + " → PNG（"
                        + (dest.length() / 1024) + " KB）", "THEME_IMPORT");
                return dest;
            }
            LogManager.log("主题-ffmpeg 转码失败（退出码 " + code + "）: " + source.getName(), "THEME_IMPORT");
        } catch (Throwable t) {
            LogManager.log("主题-ffmpeg 转码异常: " + source.getName() + "（" + t + "）", "THEME_IMPORT");
        }
        if (dest.isFile()) {
            dest.delete();
        }
        return null;
    }

    /** ffmpeg 状态一行文案（UI 提示用）。 */
    public static String ffmpegStatusText() {
        if (!ffmpegEnabled()) {
            return "已禁用（只认 lib/ 插件与 JDK 内置格式）";
        }
        String path = ffmpegPath();
        return path == null ? "未找到（WebP/AVIF/HEIC 需放 lib/ 插件或先转成 PNG/JPG）"
                : "可用：" + path;
    }
}
