import com.randomnamepicker.core.ConfigManager;
import com.randomnamepicker.plugin.PluginManager;
import com.randomnamepicker.theme.BackdropManager;
import com.randomnamepicker.theme.BackdropMode;
import com.randomnamepicker.theme.MediaClock;
import com.randomnamepicker.theme.MediaLibrary;
import com.randomnamepicker.theme.ThemeAudio;
import com.randomnamepicker.theme.ThemeImageIo;
import com.randomnamepicker.theme.ThemeStore;
import com.randomnamepicker.theme.ThemeWindow;
import com.randomnamepicker.theme.WindowBackdrop;
import com.randomnamepicker.ui.NamePickerApp;
import com.randomnamepicker.ui.SettingsWindow;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

/**
 * 主题独立页 / 图片列表管理 / 预览窗口 —— UI 冒烟与截图取证（跑在宿主 out/ 之上，不改宿主源码）。
 *
 * 运行（工作目录 = 仓库根）：
 *   javac -encoding UTF-8 -cp out -d tools/theme-smoke/classes tools/theme-smoke/ThemeUiSmoke.java
 *   java -cp "out;tools/theme-smoke/classes" ThemeUiSmoke
 *
 * 取证方式：**离屏渲染**（{@code Window.printAll}）而不是 Robot 截屏——不抓取用户桌面内容、不注入鼠标事件，
 * 因此截图里只有被测窗口自身，且与用户桌面是否在前台无关。
 *
 * 产出：theme_ui_shots/*.png + theme_ui_shots/smoke_report.txt。
 * 结束时恢复 data/config.properties 备份、删除备份副本与本次导入的测试图片。
 */
public class ThemeUiSmoke {

    private static final File SHOT_DIR = new File("theme_ui_shots");
    private static final List<String> imported = new ArrayList<>();
    /** 运行前图库快照（文件名 → 大小|修改时间）：用于**保护用户已有文件**并检测"被悄悄删除"。 */
    private static final java.util.Map<String, String> libraryAtStart = new java.util.LinkedHashMap<>();
    private static File configBackup;
    private static File themeConfigBackup;
    private static File configBakBackup;
    private static File themeBakBackup;

    /** 有快照则还原，无快照则删除该文件（保证"运行前不存在"的状态也被还原）。 */
    private static void restoreOrDelete(File snapshot, File target) throws IOException {
        if (snapshot != null && snapshot.isFile()) {
            Files.copy(snapshot.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else if (target.isFile()) {
            target.delete();
        }
    }
    private static File brokenSample;
    private static PrintStream report;
    private static boolean failed = false;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[smoke] HEADLESS：无图形环境，跳过 UI 冒烟（编译验证仍有效）");
            System.exit(3);
        }
        SHOT_DIR.mkdirs();
        report = new PrintStream(new File(SHOT_DIR, "smoke_report.txt"), "UTF-8");

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(180000);
            } catch (InterruptedException ignore) {
                // 正常结束
            }
            System.out.println("[smoke] WATCHDOG 超时，强制退出");
            Runtime.getRuntime().halt(2);
        });
        watchdog.setDaemon(true);
        watchdog.start();

        backupConfig();
        snapshotLibrary();
        boolean ok = false;
        try {
            prepareTheme();
            runFormatChecks();
            runMediaChecks();
            ok = runUiChecks();
        } catch (Throwable t) {
            log("EXCEPTION " + t);
            t.printStackTrace(report);
        } finally {
            cleanup();
        }
        log("SMOKE_RESULT=" + (ok ? "PASS" : "FAIL"));
        report.flush();
        report.close();
        System.out.println("[smoke] " + (ok ? "PASS" : "FAIL") + " —— 详见 theme_ui_shots/smoke_report.txt");
        Runtime.getRuntime().halt(ok ? 0 : 1);
    }

    // ===================== 准备：测试图 + 主题配置 =====================

    private static void prepareTheme() throws Exception {
        File inputDir = new File(SHOT_DIR, "input");
        inputDir.mkdirs();
        File a = new File(inputDir, "wallpaper_a.png");
        File b = new File(inputDir, "wallpaper_b.png");
        File c = new File(inputDir, "wallpaper_c.png");
        writeTestImage(a, 1600, 1000, new Color(0x1E, 0x63, 0xC8), new Color(0x27, 0xC0, 0x8A), "A");
        writeTestImage(b, 1280, 900, new Color(0xB0, 0x2A, 0x2A), new Color(0xE8, 0xB4, 0x2A), "B");
        writeTestImage(c, 800, 1200, new Color(0x5B, 0x2A, 0xB0), new Color(0xC8, 0x4A, 0xC8), "C");

        for (File f : new File[]{a, b, c}) {
            String id = ThemeStore.importImage(f);
            if (id == null) {
                throw new IllegalStateException("导入测试图失败: " + f);
            }
            imported.add(id);
        }
        log("imported=" + imported);

        ThemeStore.setEnabled(true);
        ThemeStore.setMask(40);
        ThemeStore.setTableTier(1);
        ThemeStore.setGlobalImage(imported.get(0));
        ThemeStore.setGlobalMode(BackdropMode.FILL);
        ThemeStore.setCategoryImage(ThemeStore.CATEGORY_CONFIG, imported.get(1));
        ThemeStore.setCategoryMode(ThemeStore.CATEGORY_CONFIG, BackdropMode.FILL);
        ThemeStore.setCategoryImage(ThemeStore.CATEGORY_NUMBER, imported.get(2));
        ThemeStore.setCategoryMode(ThemeStore.CATEGORY_NUMBER, BackdropMode.FIT);
        log("图库（别名序）=" + ThemeStore.listImageIdsSorted());
        log("全局展示名=" + ThemeStore.imageDisplayName(imported.get(0))
                + " 引用=" + ThemeStore.usageText(imported.get(0)));
        check("图库别名取自源文件名", ThemeStore.getImageName(imported.get(0)).equals("wallpaper_a"));
        check("引用查询含全局与类别", ThemeStore.usageText(imported.get(0)).contains("全局")
                && ThemeStore.usageText(imported.get(1)).contains("配置名单"));
        check("预览解析忽略启用位（未启用也能预览）",
                ThemeStore.resolvePreview(ThemeStore.CATEGORY_MAIN).isActive());
        ThemeStore.setEnabled(false);
        check("真实解析在未启用时不生效", !ThemeStore.resolve(ThemeStore.CATEGORY_MAIN).isActive());
        ThemeStore.setEnabled(true);
    }

    private static void writeTestImage(File file, int w, int h, Color c1, Color c2, String tag)
            throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, c1, w, h, c2));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 60));
        for (int i = 0; i < 6; i++) {
            g.fillOval(w / 10 + i * w / 8, h / 6 + (i % 3) * h / 5, w / 6, w / 6);
        }
        g.setColor(Color.WHITE);
        g.setFont(new java.awt.Font("微软雅黑", java.awt.Font.BOLD, Math.max(48, w / 8)));
        g.drawString(tag, w / 12, h / 2);
        g.dispose();
        ImageIO.write(img, "png", file);
    }

    // ===================== 解码格式检查（2026-10 媒体扩展批次） =====================

    /** 生成各格式样本 → 逐项校验"白名单/按内容校验/按内容规范后缀/损坏文件处理"。 */
    private static void runFormatChecks() throws Exception {
        File dir = new File(SHOT_DIR, "input/formats");
        dir.mkdirs();
        File png = writeTestImage(new File(dir, "fmt_png.png"), "png", 320, 200);
        File jpg = writeTestImage(new File(dir, "fmt_jpg.jpg"), "jpg", 320, 200);
        File bmp = writeTestImage(new File(dir, "fmt_bmp.bmp"), "bmp", 320, 200);
        File gif = writeTestImage(new File(dir, "fmt_gif.gif"), "gif", 320, 200);
        File tif = writeTestImage(new File(dir, "fmt_tif.tif"), "tif", 320, 200);
        // 白名单外后缀但内容确为 JPEG → 应能导入，并按内容规范后缀入库为 .jpg
        File jfif = new File(dir, "fmt_alias.jfif");
        Files.copy(jpg.toPath(), jfif.toPath(), StandardCopyOption.REPLACE_EXISTING);
        // 假 webp：内容不是图片 → 必须拒收（这正是旧口径"能导入却贴不上"的坑）
        File fakeWebp = new File(dir, "fmt_fake.webp");
        Files.write(fakeWebp.toPath(), "definitely not an image".getBytes("UTF-8"));

        String[] supported = ThemeStore.supportedExtensions();
        log("supportedExtensions=" + Arrays.toString(supported)
                + " ；插件扫描=" + ThemeImageIo.scanNote());
        check("白名单含 jpg/jpeg/png/bmp/gif/tif/tiff",
                containsAll(supported, "jpg", "jpeg", "png", "bmp", "gif", "tif", "tiff"));
        check("白名单不含无解码器的 webp（本机 JDK 无 WebP 读取器）", !contains(supported, "webp"));

        for (File f : new File[]{png, jpg, bmp, gif, tif}) {
            String id = ThemeStore.importImage(f);
            check("导入 " + extOf(f) + " 成功", id != null);
            if (id != null) {
                imported.add(id);
                check("  └ 入库后缀保持 " + extOf(f), id.endsWith("." + extOf(f)));
                check("  └ 尺寸可读 320×200",
                        "320×200".equals(ThemeStore.imageSizeText(ThemeStore.imageFile(id))));
            }
        }
        String jfifId = ThemeStore.importImage(jfif);
        check(".jfif（白名单外后缀）按内容导入成功", jfifId != null);
        if (jfifId != null) {
            imported.add(jfifId);
            check("  └ 按内容规范化为 .jpg", jfifId.endsWith(".jpg"));
        }
        check("假 .webp 被拒收（importImage 返回 null）", ThemeStore.importImage(fakeWebp) == null);
        check("canDecode(假 .webp)=false", !ThemeStore.canDecode(fakeWebp));
        check("格式标签正确（TIFF/PNG）",
                "TIFF".equals(ThemeStore.imageFormatText(tif))
                        && "PNG".equals(ThemeStore.imageFormatText(png)));

        // ffmpeg 兜底转码（2026-10 WebP 修复）：用 ffmpeg 现造一个 WebP，验证"导入即自动转 PNG"
        if (ThemeImageIo.ffmpegAvailable()) {
            File webp = writeWebpWithFfmpeg(png, new File(dir, "fmt_real.webp"));
            if (webp != null && webp.isFile()) {
                check("ffmpeg 能造出 .webp 测试样本", true);
                check("真 .webp 不在可解码白名单内", !ThemeStore.isSupportedExtension(webp.getName()));
                String convertedId = ThemeStore.importImage(webp);
                check("真 .webp 经 ffmpeg 兜底导入成功", convertedId != null);
                if (convertedId != null) {
                    imported.add(convertedId);
                    check("  └ 入库为 PNG", convertedId.endsWith(".png"));
                    check("  └ 尺寸与源一致 320×200",
                            "320×200".equals(ThemeStore.imageSizeText(ThemeStore.imageFile(convertedId))));
                    check("  └ 别名沿用原文件名", "fmt_real".equals(ThemeStore.getImageName(convertedId)));
                }
                ThemeImageIo.setFfmpegEnabled(false);
                check("关闭 ffmpeg 兜底后同文件被拒收", ThemeStore.importImage(webp) == null);
                ThemeImageIo.setFfmpegEnabled(true);
                File tmpDir = new File(ThemeStore.THEME_DIR + "/tmp");
                String[] tmpLeft = tmpDir.list();
                check("转码临时文件已清理", tmpLeft == null || tmpLeft.length == 0);
            } else {
                log("[SKIP] ffmpeg 未能生成 webp 测试样本（本机 ffmpeg 缺 webp 编码器？）");
            }
        } else {
            log("[SKIP] 本机无 ffmpeg：跳过兜底转码断言（lib/ 插件路线不受影响）");
        }

        // 手工丢进图库目录的"白名单外后缀但可解码"文件 → 应被列出（头部嗅探）
        File manual = new File(ThemeStore.IMAGES_DIR, "manual_alias.jfif");
        Files.copy(jpg.toPath(), manual.toPath(), StandardCopyOption.REPLACE_EXISTING);
        check("手工放入的 .jfif 会被图库列出", ThemeStore.listImages().contains("manual_alias.jfif"));
        manual.delete();

        // 损坏文件（扩展名合法、内容不是图片）→ 列出并标注"无法解码"，且不参与样式解析
        File broken = new File(ThemeStore.IMAGES_DIR, "broken_sample.png");
        Files.write(broken.toPath(), "not a png at all".getBytes("UTF-8"));
        brokenSample = broken;
        String keepGlobal = ThemeStore.getGlobalImage();
        check("损坏 png 仍在图库列出（便于用户发现）",
                ThemeStore.listImages().contains("broken_sample.png"));
        check("损坏 png 尺寸显示“无法解码”",
                "无法解码".equals(ThemeStore.imageSizeText(broken)));
        check("损坏 png 头部判定为不可解码", !ThemeStore.looksDecodable(broken));
        ThemeStore.setGlobalImage("broken_sample.png");
        check("损坏图片被引用时解析为 inactive（真实窗口零变化）",
                !ThemeStore.resolve(ThemeStore.CATEGORY_MAIN).isActive());

        // 「仅头部完整」的文件：头部嗅探会通过，必须靠**整图解码**判否（否则会"显示已启用却画不出"）
        File headOnlyJpg = new File(ThemeStore.IMAGES_DIR, "headonly.jpg");
        Files.write(headOnlyJpg.toPath(), Arrays.copyOf(Files.readAllBytes(jpg.toPath()), 400));
        File headOnlyPng = new File(ThemeStore.IMAGES_DIR, "headonly.png");
        Files.write(headOnlyPng.toPath(), Arrays.copyOf(Files.readAllBytes(png.toPath()), 400));
        File deepTruncJpg = new File(ThemeStore.IMAGES_DIR, "truncated_deep.jpg");
        Files.write(deepTruncJpg.toPath(), Arrays.copyOf(Files.readAllBytes(jpg.toPath()), 4000));
        check("仅头部完整的 jpg：嗅探通过但整图解码判否",
                ThemeStore.looksDecodable(headOnlyJpg) && !ThemeStore.isUsableImage(headOnlyJpg));
        check("仅头部完整的 png：嗅探通过但整图解码判否",
                ThemeStore.looksDecodable(headOnlyPng) && !ThemeStore.isUsableImage(headOnlyPng));
        // 契约：解析结果必须与"整图可解码"一致（不许出现 active 却画不出）
        // 注意：必须用**图库内**文件比对——解析是按图库 id 找文件的，库外路径不参与解析
        File okJpg = new File(ThemeStore.IMAGES_DIR, "contract_ok.jpg");
        Files.copy(jpg.toPath(), okJpg.toPath(), StandardCopyOption.REPLACE_EXISTING);
        File okPng = new File(ThemeStore.IMAGES_DIR, "contract_ok.png");
        Files.copy(png.toPath(), okPng.toPath(), StandardCopyOption.REPLACE_EXISTING);
        boolean contractOk = true;
        int contractSamples = 0;
        for (File f : new File[]{broken, headOnlyJpg, headOnlyPng, deepTruncJpg, okJpg, okPng}) {
            contractSamples++;
            boolean usable = ThemeStore.isUsableImage(f);
            ThemeStore.setGlobalImage(f.getName());
            boolean resolved = ThemeStore.resolve(ThemeStore.CATEGORY_MAIN).isActive();
            if (usable != resolved) {
                contractOk = false;
                log("  契约不符: " + f.getName() + " usable=" + usable + " resolved=" + resolved);
            }
        }
        check("解析结果与整图解码判定始终一致（" + contractSamples + " 个库内样本）", contractOk);
        log("截断深度记录：400B jpg 判否 / 4000B jpg 整图解码=" + ThemeStore.isUsableImage(deepTruncJpg)
                + "（ImageIO 容忍较深的截断，会画出残缺图——已按实测记录，不当失败）");
        ThemeStore.setGlobalImage(keepGlobal);
        headOnlyJpg.delete();
        headOnlyPng.delete();
        deepTruncJpg.delete();
        okJpg.delete();
        okPng.delete();
    }

    private static File writeTestImage(File file, String format, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x20, 0x60, 0xC0), w, h, new Color(0xE0, 0xA0, 0x20)));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.setFont(new java.awt.Font("微软雅黑", java.awt.Font.BOLD, 28));
        g.drawString(format.toUpperCase(Locale.ROOT), 16, h / 2);
        g.dispose();
        if (!ImageIO.write(img, format, file)) {
            throw new IOException("no ImageIO writer for " + format);
        }
        return file;
    }

    private static String extOf(File f) {
        String n = f.getName();
        return n.substring(n.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /** 用本机 ffmpeg 把一张 PNG 编成 WebP（测试"JDK 解不了、ffmpeg 能转"的真实样本）。 */
    private static File writeWebpWithFfmpeg(File pngSource, File out) {
        String ff = ThemeImageIo.ffmpegPath();
        if (ff == null) {
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(ff, "-hide_banner", "-loglevel", "error",
                    "-y", "-i", pngSource.getAbsolutePath(), "-frames:v", "1", out.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (java.io.InputStream in = p.getInputStream()) {
                byte[] buf = new byte[2048];
                while (in.read(buf) != -1) {
                    // 排空管道
                }
            }
            return p.waitFor() == 0 && out.isFile() ? out : null;
        } catch (Throwable t) {
            log("ffmpeg 生成 webp 失败: " + t);
            return null;
        }
    }

    private static boolean contains(String[] arr, String v) {
        return Arrays.asList(arr).contains(v);
    }

    private static boolean containsAll(String[] arr, String... values) {
        for (String v : values) {
            if (!contains(arr, v)) {
                return false;
            }
        }
        return true;
    }

    // ===================== 动态背景检查（路线 A：动图/帧序列/背景音） =====================

    private static String mediaGifId;
    private static String mediaSeqId;
    private static String mediaAudioId;

    private static void runMediaChecks() throws Exception {
        File dir = new File(SHOT_DIR, "input/media");
        dir.mkdirs();

        // 多帧 GIF（12 帧，每帧 10/100 秒）+ 单帧 GIF（应被拒）
        File gif = writeAnimatedGif(new File(dir, "anim12.gif"), 12, 10);
        File single = writeAnimatedGif(new File(dir, "single1.gif"), 1, 10);
        // 帧序列目录（含 f2/f10 之类的命名，检验自然序）
        File seqDir = new File(dir, "seq");
        seqDir.mkdirs();
        int[] order = {1, 2, 3, 10, 11, 12};
        for (int i : order) {
            writeTestImage(new File(seqDir, "f" + i + ".png"), "png", 240, 160);
        }
        File seqTooFew = new File(dir, "seq_one");
        seqTooFew.mkdirs();
        writeTestImage(new File(seqTooFew, "only.png"), "png", 240, 160);
        // WAV（0.4s 正弦，8kHz 16bit 单声道 PCM）
        File wav = writeSineWav(new File(dir, "bgm.wav"), 8000, 0.4);
        File fakeAudio = new File(dir, "not_audio.wav");
        Files.write(fakeAudio.toPath(), "definitely not audio".getBytes("UTF-8"));

        mediaGifId = MediaLibrary.importAnimation(gif);
        check("导入多帧 GIF 成功", mediaGifId != null && mediaGifId.endsWith(".gif"));
        if (mediaGifId != null) {
            log("GIF 元信息=" + MediaLibrary.mediaInfoText(mediaGifId));
            check("GIF 帧数识别为 12", MediaLibrary.mediaInfoText(mediaGifId).contains("12 帧"));
        }
        check("单帧 GIF 被拒（需多帧）", MediaLibrary.importAnimation(single) == null);

        mediaSeqId = MediaLibrary.importFrameSequence(seqDir);
        check("导入帧序列目录成功（打包为 zip）", mediaSeqId != null && mediaSeqId.endsWith(".zip"));
        if (mediaSeqId != null) {
            log("帧序列元信息=" + MediaLibrary.mediaInfoText(mediaSeqId));
            check("帧序列帧数为 6", MediaLibrary.mediaInfoText(mediaSeqId).contains("6 帧"));
        }
        check("单帧目录被拒（需 ≥2 帧）", MediaLibrary.importFrameSequence(seqTooFew) == null);

        mediaAudioId = MediaLibrary.importAudio(wav);
        check("导入 WAV 背景音成功", mediaAudioId != null && mediaAudioId.endsWith(".wav"));
        if (mediaAudioId != null) {
            log("背景音元信息=" + MediaLibrary.mediaInfoText(mediaAudioId));
        }
        check("非音频文件被拒", MediaLibrary.importAudio(fakeAudio) == null);

        // 优先级：类别单独指定静态图 > 全局动态背景 > 全局静态图
        MediaLibrary.setMediaId(mediaGifId);
        ThemeStore.setCategoryImage(ThemeStore.CATEGORY_CONFIG, imported.get(1));
        BackdropManager.getInstance().reapplyAll();
        check("全局动态背景对未指定类别生效（main → 动画）",
                ThemeStore.resolve(ThemeStore.CATEGORY_MAIN).isAnimated());
        check("  └ 动态背景 id 正确",
                mediaGifId.equals(ThemeStore.resolve(ThemeStore.CATEGORY_MAIN).getMediaId()));
        check("类别单独指定的静态图优先于全局动态背景（config → 静态）",
                !ThemeStore.resolve(ThemeStore.CATEGORY_CONFIG).isAnimated()
                        && ThemeStore.resolve(ThemeStore.CATEGORY_CONFIG).isActive());

        // 播放时钟真的在推进
        MediaClock clock = MediaClock.getInstance();
        int before = clock.currentIndex();
        sleep(600);
        int after = clock.currentIndex();
        log("MediaClock: frames=" + clock.frameCount() + " index " + before + " → " + after
                + " running=" + clock.isRunning());
        check("播放时钟帧数=12 且正在运行", clock.frameCount() == 12 && clock.isRunning());
        check("播放时钟在推进帧号（动画真实播放）", after != before);

        // 背景音：有音频设备才断言"在播"，否则如实记为 SKIP（不误判为失败）
        MediaLibrary.setAudioId(mediaAudioId);
        MediaLibrary.setVolume(80);
        MediaLibrary.setMuted(false);
        ThemeAudio.getInstance().syncFromStore();
        sleep(500); // Clip 起播是异步的，等它就位再判定
        if (ThemeAudio.getInstance().isPlaying()) {
            check("背景音正在播放（未静音）", true);
            MediaLibrary.setMuted(true);
            ThemeAudio.getInstance().syncFromStore();
            sleep(300);
            check("勾选静音后停止播放", !ThemeAudio.getInstance().isPlaying());
        } else {
            log("[SKIP] 本机无可用音频输出设备 —— 背景音仅做“可解析/可导入”断言");
        }
        MediaLibrary.setMuted(true);
        check("音量/静音配置可读写", volumeRoundTrip());

        // 非循环：播到末帧后自行停表
        MediaLibrary.setLoop(false);
        MediaLibrary.setFps(30);
        MediaLibrary.setMediaId(mediaSeqId);
        clock.syncFromStore();
        sleep(900);
        log("非循环序列: index=" + clock.currentIndex() + "/" + clock.frameCount()
                + " running=" + clock.isRunning());
        check("非循环序列播到末帧后停止", !clock.isRunning()
                && clock.currentIndex() == clock.frameCount() - 1);
        MediaLibrary.setLoop(true);
        MediaLibrary.setMediaId(mediaGifId);
        clock.syncFromStore();
    }

    private static boolean volumeRoundTrip() {
        int v = MediaLibrary.getVolume();
        boolean muted = MediaLibrary.isMuted();
        MediaLibrary.setVolume(33);
        MediaLibrary.setMuted(false);
        boolean ok = MediaLibrary.getVolume() == 33 && !MediaLibrary.isMuted();
        MediaLibrary.setVolume(v);
        MediaLibrary.setMuted(muted);
        return ok;
    }

    /** 写多帧 GIF（每帧 delayHundredths 个 1/100 秒；帧数=1 时即单帧 GIF）。 */
    private static File writeAnimatedGif(File out, int frames, int delayHundredths) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames; i++) {
                BufferedImage img = gifFrame(200, 140, i, frames);
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata meta = writer.getDefaultImageMetadata(
                        new ImageTypeSpecifier(img), param);
                String format = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", String.valueOf(delayHundredths));
                gce.setAttribute("transparentColorIndex", "0");
                root.appendChild(gce);
                if (i == 0) {
                    IIOMetadataNode appExts = new IIOMetadataNode("ApplicationExtensions");
                    IIOMetadataNode appExt = new IIOMetadataNode("ApplicationExtension");
                    appExt.setAttribute("applicationID", "NETSCAPE");
                    appExt.setAttribute("authenticationCode", "2.0");
                    appExt.setUserObject(new byte[]{1, 0, 0});
                    appExts.appendChild(appExt);
                    root.appendChild(appExts);
                }
                meta.setFromTree(format, root);
                writer.writeToSequence(new javax.imageio.IIOImage(img, null, meta), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return out;
    }

    private static BufferedImage gifFrame(int w, int h, int index, int total) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(30, 90, 200), w, h,
                new Color(240, 180, 40)));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        int x = (int) (w * (index / (double) Math.max(1, total)));
        g.fillOval(Math.max(0, x - 30), h / 2 - 30, 60, 60);
        g.setFont(new java.awt.Font("微软雅黑", java.awt.Font.BOLD, 20));
        g.drawString("#" + index, 12, h - 12);
        g.dispose();
        return img;
    }

    /** 写一段正弦 WAV（PCM 16bit 单声道）。 */
    private static File writeSineWav(File out, float sampleRate, double seconds) throws IOException {
        int frames = (int) (sampleRate * seconds);
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 8000);
            pcm[i * 2] = (byte) (v & 0xFF);
            pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, out);
        }
        return out;
    }

    // ===================== UI 检查 =====================

    private static boolean runUiChecks() throws Exception {
        NamePickerApp app = onEdtGet(() -> {
            NamePickerApp frame = new NamePickerApp();
            PluginManager.getInstance().init(frame);
            frame.setSize(900, 620);
            frame.setLocation(40, 40);
            frame.setVisible(true);
            return frame;
        });
        sleep(1500);

        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        log("screen=" + screen.width + "x" + screen.height);

        BackdropManager.getInstance().reapplyAll();
        sleep(800);
        capture(app, "1_main_themed");
        check("主窗已装配背景垫层", countIn(app, "WindowBackdrop") >= 1);

        onEdt(() -> app.setSize(1120, 700));
        sleep(800);
        capture(app, "2_main_resized");
        check("主窗缩放后仍装配背景垫层", countIn(app, "WindowBackdrop") >= 1);

        // 动态背景：真实主窗两次渲染必须不同（证明动画在真实窗口里播放，不只是配置生效）
        check("主窗动态背景在播放（两次渲染不同）", animationAdvances(app, "11_main_anim", 700));

        // ---- 独立页（主窗菜单入口） ----
        onEdt(() -> app.showThemeWindow());
        sleep(1300);
        Window themeWindow = findWindow("ThemeWindow");
        check("独立页已打开（主窗菜单入口）", themeWindow != null && themeWindow.isShowing());
        if (themeWindow != null) {
            onEdt(() -> themeWindow.setSize(
                    Math.min(1040, screen.width - 80), Math.min(700, screen.height - 120)));
            sleep(600);
            capture(themeWindow, "3_theme_window");
            check("独立页自身无背景垫层（D8 不贴图）", !rootLayeredHasBackdrop(themeWindow));
            check("独立页含图库列表面板", countIn(themeWindow, "ThemeGalleryPanel") >= 1);
            check("独立页含预览画布", countIn(themeWindow, "ThemePreviewPanel") >= 1);
            check("预览画布内含真实 WindowBackdrop（同一渲染路径）",
                    countIn(themeWindow, "WindowBackdrop") >= 1);

            // ---- 独立预览窗 ----
            boolean pressed = pressButton(themeWindow, "独立预览窗口…");
            check("触发“独立预览窗口…”", pressed);
            sleep(1300);
            Window previewWindow = findWindow("ThemePreviewWindow");
            check("独立预览窗已打开", previewWindow != null && previewWindow.isShowing());
            if (previewWindow != null) {
                check("预览窗自身无背景垫层（D8）", !rootLayeredHasBackdrop(previewWindow));
                check("预览窗动态背景在播放（两次渲染不同）",
                        animationAdvances(previewWindow, "13_preview_anim", 700));
                capture(previewWindow, "4_preview_window_main");
                JComboBox<?> combo = findCombo(previewWindow, "配置名单");
                check("预览窗含类别下拉", combo != null);
                if (combo != null) {
                    onEdt(() -> combo.setSelectedItem("配置名单"));
                    sleep(800);
                    capture(previewWindow, "5_preview_config_table");
                }
                onEdt(() -> previewWindow.setSize(920, 720));
                sleep(800);
                capture(previewWindow, "6_preview_resized");
                onEdt(previewWindow::dispose);
                sleep(400);
            }
            onEdt(themeWindow::dispose);
            sleep(500);
            check("独立页已关闭（单实例复位）", findWindow("ThemeWindow") == null);
        }

        // ---- 设置窗（模态）→ 打开独立页 ----
        final SettingsWindow[] settingsRef = new SettingsWindow[1];
        final Throwable[] settingsError = new Throwable[1];
        Thread settingsThread = new Thread(() -> {
            try {
                SettingsWindow w = onEdtGet(() -> {
                    SettingsWindow s = new SettingsWindow(app);
                    settingsRef[0] = s;
                    return s;
                });
                w.setVisible(true); // 模态：阻塞本线程直到关闭
            } catch (Throwable t) {
                settingsError[0] = t;
            }
        }, "settings-modal");
        settingsThread.start();
        sleep(1500);
        check("设置窗已打开（模态）", settingsRef[0] != null && settingsRef[0].isShowing());
        check("设置窗内锚定“背景主题”入口分节",
                settingsRef[0] != null && countIn(settingsRef[0], "ThemeSettingsPanel") >= 1);

        if (settingsRef[0] != null) {
            onEdt(() -> ThemeWindow.openWindow(settingsRef[0]));
            sleep(1400);
            Window themeFromSettings = findWindow("ThemeWindow");
            check("由模态设置窗打开的独立页可见", themeFromSettings != null && themeFromSettings.isShowing());
            if (themeFromSettings != null) {
                check("独立页 owner = 设置窗",
                        themeFromSettings.getOwner() == settingsRef[0]);
                capture(themeFromSettings, "8_theme_window_from_settings");
                boolean closed = pressButton(themeFromSettings, "关闭");
                sleep(600);
                check("独立页“关闭”按钮生效（程序化）",
                        closed && !themeFromSettings.isDisplayable());
            }
            capture(settingsRef[0], "7_settings_entry");
            onEdt(settingsRef[0]::dispose);
        }
        settingsThread.join(10000);
        check("设置窗已正常关闭", !settingsThread.isAlive());
        if (settingsError[0] != null) {
            log("settings thread error: " + settingsError[0]);
        }

        // ---- 图库列表管理：别名 / 替换重定向 / 删除清理 ----
        onEdt(() -> app.showThemeWindow());
        sleep(1100);
        Window galleryWindow = findWindow("ThemeWindow");
        check("独立页可再次打开（单实例复用）", galleryWindow != null);
        if (galleryWindow != null) {
            String before = ThemeStore.imageDisplayName(imported.get(1));
            ThemeStore.setImageName(imported.get(1), "名单背景-别名");
            log("重命名: " + before + " → " + ThemeStore.imageDisplayName(imported.get(1)));
            check("别名生效且引用未变",
                    ThemeStore.imageDisplayName(imported.get(1)).startsWith("名单背景-别名")
                            && ThemeStore.categoryImageIdNow(ThemeStore.CATEGORY_CONFIG).equals(imported.get(1)));

            File replacement = new File(SHOT_DIR, "input/wallpaper_d.png");
            writeTestImage(replacement, 1400, 900, new Color(0x11, 0x77, 0x55), new Color(0xEE, 0xEE, 0x22), "D");
            String newId = ThemeStore.replaceImage(imported.get(1), replacement);
            check("替换返回新 id", newId != null && !newId.equals(imported.get(1)));
            if (newId != null) {
                imported.add(newId);
                check("替换后引用重定向到新图",
                        ThemeStore.categoryImageIdNow(ThemeStore.CATEGORY_CONFIG).equals(newId));
                check("替换后别名继承", ThemeStore.getImageName(newId).equals("名单背景-别名"));
                check("替换后旧文件已删除", ThemeStore.imageFile(imported.get(1)) == null);
            }
            BackdropManager.getInstance().reapplyAll();
            sleep(700);
            // 重新打开独立页（等价用户关掉再开）：让图库列表按最新 store 重建，截图才反映重命名/替换结果
            final Window beforeOps = galleryWindow;
            onEdt(beforeOps::dispose);
            sleep(400);
            onEdt(() -> app.showThemeWindow());
            sleep(1000);
            galleryWindow = findWindow("ThemeWindow");
            capture(galleryWindow, "9_gallery_after_ops");

            String victim = imported.get(2);
            ThemeStore.deleteImageAndCleanup(victim);
            check("删除后类别不再单独指定（回继承全局）",
                    !ThemeStore.categoryHasOwnImage(ThemeStore.CATEGORY_NUMBER));
            check("删除后该类解析结果回落到全局图",
                    ThemeStore.categoryImageIdNow(ThemeStore.CATEGORY_NUMBER)
                            .equals(ThemeStore.getGlobalImage()));
            check("删除后文件消失", ThemeStore.imageFile(victim) == null);
            check("删除后别名键移除", ThemeStore.getImageName(victim).isEmpty());
            BackdropManager.getInstance().reapplyAll();
            sleep(700);
            final Window afterOps = galleryWindow;
            onEdt(afterOps::dispose);
            sleep(400);
            onEdt(() -> app.showThemeWindow());
            sleep(1000);
            galleryWindow = findWindow("ThemeWindow");
            capture(galleryWindow, "10_after_delete");
        }

        onEdt(() -> {
            BackdropManager.getInstance().shutdown();
            for (Window w : Window.getWindows()) {
                w.dispose();
            }
        });
        sleep(600);
        return !failed;
    }

    // ===================== 断言与取证工具 =====================

    private static void check(String name, boolean pass) {
        if (!pass) {
            failed = true;
        }
        log((pass ? "[PASS] " : "[FAIL] ") + name);
    }

    private static void log(String s) {
        System.out.println("[smoke] " + s);
        if (report != null) {
            report.println(s);
        }
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** 离屏渲染取证：把窗口自身画进 BufferedImage（不含用户桌面内容、无需窗口在最前）。 */
    private static void capture(Window w, String name) throws Exception {
        if (w == null || !w.isShowing()) {
            log("capture skipped (window not showing): " + name);
            return;
        }
        sleep(250);
        BufferedImage shot = renderWindow(w);
        File out = new File(SHOT_DIR, name + ".png");
        ImageIO.write(shot, "png", out);
        log("capture " + name + " " + shot.getWidth() + "x" + shot.getHeight() + " → " + out.getPath()
                + " distinctColors=" + distinctColors(shot));
    }

    /** 渲染窗口为图像（EDT 上 validate + printAll）。 */
    private static BufferedImage renderWindow(Window w) throws Exception {
        return onEdtGet(() -> {
            w.validate();
            int width = Math.max(1, w.getWidth());
            int height = Math.max(1, w.getHeight());
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            w.printAll(g);
            g.dispose();
            return img;
        });
    }

    private static byte[] pngBytes(BufferedImage img) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /**
     * 动态背景证据：间隔 gapMs 渲染两次，落两张图并返回"是否发生变化"。
     * 用于证明**真实窗口/预览**里动画确实在播放（而不是只改了配置）。
     */
    private static boolean animationAdvances(Window w, String prefix, long gapMs) throws Exception {
        if (w == null || !w.isShowing()) {
            log("anim check skipped (window not showing): " + prefix);
            return false;
        }
        BufferedImage a = renderWindow(w);
        sleep(gapMs);
        BufferedImage b = renderWindow(w);
        ImageIO.write(a, "png", new File(SHOT_DIR, prefix + "_a.png"));
        ImageIO.write(b, "png", new File(SHOT_DIR, prefix + "_b.png"));
        boolean changed = !Arrays.equals(pngBytes(a), pngBytes(b));
        log("anim check " + prefix + " → 两次渲染" + (changed ? "不同（动画在动）" : "完全相同（未动）"));
        return changed;
    }

    private static int distinctColors(BufferedImage img) {
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < img.getHeight(); y += 4) {
            for (int x = 0; x < img.getWidth(); x += 4) {
                colors.add(img.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return colors.size();
    }

    /** 仅检查窗口 rootPane.layeredPane 的直接子组件（区分“自身贴图”与“预览画布内的背景组件”）。 */
    private static boolean rootLayeredHasBackdrop(Window w) {
        if (!(w instanceof RootPaneContainer)) {
            return false;
        }
        JRootPane root = ((RootPaneContainer) w).getRootPane();
        if (root == null) {
            return false;
        }
        for (Component c : root.getLayeredPane().getComponents()) {
            if (c instanceof WindowBackdrop) {
                return true;
            }
        }
        return false;
    }

    private static int countIn(Component c, String simpleName) {
        int n = c.getClass().getSimpleName().equals(simpleName) ? 1 : 0;
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                n += countIn(child, simpleName);
            }
        }
        return n;
    }

    private static Window findWindow(String simpleName) {
        for (Window w : Window.getWindows()) {
            if (w.getClass().getSimpleName().equals(simpleName) && w.isShowing()) {
                return w;
            }
        }
        return null;
    }

    private static JComboBox<?> findCombo(Component root, String itemText) {
        if (root instanceof JComboBox) {
            JComboBox<?> combo = (JComboBox<?>) root;
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (itemText.equals(String.valueOf(combo.getItemAt(i)))) {
                    return combo;
                }
            }
        }
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                JComboBox<?> found = findCombo(child, itemText);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 程序化触发指定文案按钮（不注入桌面鼠标事件）。 */
    private static boolean pressButton(Window w, String text) throws Exception {
        AbstractButton button = findButton(w, text);
        if (button == null) {
            log("button not found: " + text);
            return false;
        }
        onEdt(button::doClick);
        log("press button: " + text);
        return true;
    }

    private static AbstractButton findButton(Component root, String text) {
        if (root instanceof AbstractButton && text.equals(((AbstractButton) root).getText())) {
            return (AbstractButton) root;
        }
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                AbstractButton found = findButton(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> T onEdtGet(java.util.concurrent.Callable<T> body) throws Exception {
        final Object[] holder = new Object[1];
        final Throwable[] err = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                holder[0] = body.call();
            } catch (Throwable t) {
                err[0] = t;
            }
        });
        if (err[0] != null) {
            throw new RuntimeException(err[0]);
        }
        @SuppressWarnings("unchecked")
        T out = (T) holder[0];
        return out;
    }

    private static void onEdt(Runnable body) throws Exception {
        onEdtGet(() -> {
            body.run();
            return null;
        });
    }

    // ===================== 环境准备/还原 =====================

    private static void backupConfig() throws IOException {
        File config = new File("data/config.properties");
        if (config.isFile()) {
            configBackup = new File(SHOT_DIR, "config_backup.properties");
            Files.copy(config.toPath(), configBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log("config 已备份 → " + configBackup.getPath());
        } else {
            log("config 不存在（将不还原）");
        }
        // 主题设置自 2026-10 拆到独立文件：一并快照，否则本测试会把用户的主题设置覆盖成默认值
        File themeCfg = new File(com.randomnamepicker.theme.ThemeConfig.FILE_PATH);
        if (themeCfg.isFile()) {
            themeConfigBackup = new File(SHOT_DIR, "theme_config_backup.properties");
            Files.copy(themeCfg.toPath(), themeConfigBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log("主题设置已备份 → " + themeConfigBackup.getPath());
        } else {
            log("主题设置文件不存在（运行时若生成，还原时会删除）");
        }
        // 两个 .bak（位于 log/ 下）也快照：测试期间的"测试态"快照不能留作未来的自动恢复源
        File configBak = new File(ConfigManager.BACKUP_PATH);
        if (configBak.isFile()) {
            configBakBackup = new File(SHOT_DIR, "config_bak_backup.properties");
            Files.copy(configBak.toPath(), configBakBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        File themeBak = new File(com.randomnamepicker.theme.ThemeConfig.BACKUP_PATH);
        if (themeBak.isFile()) {
            themeBakBackup = new File(SHOT_DIR, "theme_bak_backup.properties");
            Files.copy(themeBak.toPath(), themeBakBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 记录运行前图库内容（含媒体目录），供收尾时核对"是否有运行前就存在的文件消失"。 */
    private static void snapshotLibrary() {
        libraryAtStart.clear();
        for (String dirPath : new String[]{ThemeStore.IMAGES_DIR, MediaLibrary.MEDIA_DIR}) {
            File dir = new File(dirPath);
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (f.isFile()) {
                    libraryAtStart.put(f.getName(), f.length() + "|" + f.lastModified());
                }
            }
        }
        log("运行前图库/媒体库快照：" + libraryAtStart.size() + " 个文件（本次运行绝不会删除它们）");
    }

    /** 收尾核对：任何"运行前已存在"的文件消失/被改动都要显式报出来（保护用户素材）。 */
    private static void reportLibraryLosses() {
        int lost = 0;
        int changed = 0;
        for (java.util.Map.Entry<String, String> e : libraryAtStart.entrySet()) {
            File f = new File(ThemeStore.IMAGES_DIR, e.getKey());
            if (!f.isFile()) {
                f = new File(MediaLibrary.MEDIA_DIR, e.getKey());
            }
            if (!f.isFile()) {
                log("!! 数据保护告警：运行前存在的文件不见了 → " + e.getKey());
                lost++;
            } else if (!(f.length() + "|" + f.lastModified()).equals(e.getValue())) {
                log("!! 数据保护告警：运行前存在的文件被改动 → " + e.getKey());
                changed++;
            }
        }
        if (lost == 0 && changed == 0) {
            log("数据保护核对通过：运行前 " + libraryAtStart.size() + " 个文件全部原样存在");
        } else {
            log("数据保护核对失败：丢失 " + lost + " 个、改动 " + changed + " 个");
        }
    }

    private static void cleanup() {
        try {
            onEdt(() -> {
                BackdropManager.getInstance().shutdown();
                for (Window w : Window.getWindows()) {
                    w.dispose();
                }
            });
        } catch (Throwable t) {
            log("cleanup windows: " + t);
        }
        try {
            reportLibraryLosses();
            for (String id : imported) {
                if (libraryAtStart.containsKey(id)) {
                    log("!! 拒绝删除：该文件在本次运行前就存在（保护你的素材）→ " + id);
                    continue;
                }
                ThemeStore.setImageName(id, null);
                ThemeStore.deleteImage(id);
            }
            for (String id : Arrays.asList(mediaGifId, mediaSeqId, mediaAudioId)) {
                if (id != null && !id.isEmpty()) {
                    ThemeStore.setImageName(id, null);
                    MediaLibrary.deleteMediaAndCleanup(id);
                }
            }
            if (brokenSample != null && brokenSample.isFile()) {
                brokenSample.delete();
            }
            ThemeStore.resetToDefaults();
        } catch (Throwable t) {
            log("cleanup theme: " + t);
        }
        try {
            if (configBackup != null && configBackup.isFile()) {
                Files.copy(configBackup.toPath(), new File("data/config.properties").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                log("config 已从备份还原");
            }
            // 主题设置文件一并还原（运行前不存在则删除本次生成的），再同步内存态，
            // 这样即便之后还有组件调用 save()，写回的也仍是运行前的内容（避免测试污染用户设置）。
            File themeCfg = new File(com.randomnamepicker.theme.ThemeConfig.FILE_PATH);
            File themeBak = new File(com.randomnamepicker.theme.ThemeConfig.BACKUP_PATH);
            if (themeConfigBackup != null && themeConfigBackup.isFile()) {
                Files.copy(themeConfigBackup.toPath(), themeCfg.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log("主题设置已从备份还原");
            } else if (themeCfg.isFile()) {
                themeCfg.delete();
                log("主题设置文件（运行前不存在）已删除");
            }
            if (themeBak.isFile()) {
                themeBak.delete();
            }
            // 两个 .bak 还原为测试前的状态（测试期间写进去的是"测试态"快照，不能留作自动恢复源）
            restoreOrDelete(configBakBackup, new File(ConfigManager.BACKUP_PATH));
            restoreOrDelete(themeBakBackup, new File(com.randomnamepicker.theme.ThemeConfig.BACKUP_PATH));
            ConfigManager.reload();
            com.randomnamepicker.theme.ThemeConfig.reload();
            log("内存态已与还原后的文件同步（后续 save 不会写回测试值）");
            File images = new File(ThemeStore.IMAGES_DIR);
            String[] left = images.list();
            log("图库目录残留=" + (left == null ? "目录不存在" : java.util.Arrays.toString(left)));
            File media = new File(MediaLibrary.MEDIA_DIR);
            String[] leftMedia = media.list();
            log("媒体目录残留=" + (leftMedia == null ? "目录不存在" : java.util.Arrays.toString(leftMedia)));
        } catch (Throwable t) {
            log("cleanup config: " + t);
        }
        log("内存态主题键（还原后应仅剩备份中存在的键）=" + themeKeysInMemory());
        // 备份副本内含宿主口令散列等敏感键：还原后立即删除，不在工作区留副本
        if (configBackup != null && configBackup.isFile() && configBackup.delete()) {
            log("已删除 config 备份副本（避免口令散列副本残留）");
        }
        if (themeConfigBackup != null && themeConfigBackup.isFile() && themeConfigBackup.delete()) {
            log("已删除主题设置备份副本");
        }
        if (configBakBackup != null && configBakBackup.isFile()) {
            configBakBackup.delete();
        }
        if (themeBakBackup != null && themeBakBackup.isFile()) {
            themeBakBackup.delete();
        }
    }

    private static String themeKeysInMemory() {
        List<String> keys = new ArrayList<>();
        for (String k : com.randomnamepicker.theme.ThemeConfig.keys()) {
            keys.add(k);
        }
        return keys.toString();
    }
}
