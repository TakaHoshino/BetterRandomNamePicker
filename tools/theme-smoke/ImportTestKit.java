import com.randomnamepicker.theme.MediaLibrary;
import com.randomnamepicker.theme.ThemeImageIo;
import com.randomnamepicker.theme.ThemeStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 把人工测试素材包导入主题库，并配一套"能互相区分"的窗口类别预设。
 *
 * 运行（工作目录 = 仓库根；**先关掉正在运行的宿主**）：
 *   javac -encoding UTF-8 -cp out -d tools/theme-smoke/classes tools/theme-smoke/ImportTestKit.java
 *   java -cp "out;tools/theme-smoke/classes" ImportTestKit ["素材目录"]
 *
 * 行为：
 *   1) 导入根目录全部**可解码**静图（jpg/png/bmp/tiff/jfif/jpe…）；
 *   2) WebP/AVIF 等 JDK 解不了的格式：临时打开 ffmpeg 兜底 → 转 PNG 入库；
 *   3) 导入 _动态背景\ 的动图 GIF（媒体库动图）、帧序列目录（打包 zip）、WAV（背景音）；
 *   4) 给每张图起可读别名（"洛天依 1920x1080 #1 (JPEG)" 等），便于在图库下拉里挑；
 *   5) 配窗口类别预设（**不动"全局背景"**，因此主窗仍显示你自己的壁纸）：
 *        配置名单=FILL 1920×1080 / 方案管理=FILL TIFF / 数字设置=FIT 竖图
 *        座位设置=CENTER BMP / 其它对话框=FILL alpha PNG / 系统设置=FILL WebP转PNG
 *   6) 不删除任何已有文件；重复运行会重复导入（图库里会多一份），需要清理在独立页里删即可。
 */
public final class ImportTestKit {

    private static int imported;
    private static int converted;

    public static void main(String[] args) throws Exception {
        File dir = args.length > 0 && !args[0].startsWith("--") ? new File(args[0]) : new File("C:/Users/PC/Pictures/洛天依");
        if (Arrays.asList(args).contains("--dedupe")) {
            dedupeByAlias();
            System.exit(0);
        }
        if (Arrays.asList(args).contains("--prune-old")) {
            pruneOldGeneration();
            System.exit(0);
        }
        if (Arrays.asList(args).contains("--prune-nohash")) {
            pruneAliasesWithoutToken();
            System.exit(0);
        }
        if (!dir.isDirectory()) {
            System.out.println("素材目录不存在：" + dir);
            System.exit(1);
        }
        ThemeImageIo.ensurePluginsLoaded();
        boolean ffmpegWasOn = ThemeImageIo.ffmpegEnabled();
        ThemeImageIo.setFfmpegEnabled(true); // 兜底转码：仅本次运行使用
        System.out.println("素材目录：" + dir.getAbsolutePath());
        System.out.println("ffmpeg：" + ThemeImageIo.ffmpegStatusText());
        System.out.println("当前可解码格式：" + ThemeStore.supportedExtensionsText());

        // ---------- 1) 静图 ----------
        File[] stills = dir.listFiles(f -> f.isFile() && !f.getName().toLowerCase().endsWith(".txt"));
        Arrays.sort(stills, (a, b) -> a.getName().compareTo(b.getName()));
        List<String> importedIds = new ArrayList<>();
        int idx = 0;
        for (File f : stills) {
            String ext = extOf(f.getName());
            boolean jdkOk = ThemeStore.canDecode(f);
            if (!jdkOk && !ThemeImageIo.ffmpegAvailable()) {
                System.out.println("  - 跳过（JDK 解不了且无 ffmpeg）：" + f.getName());
                continue;
            }
            String id = ThemeStore.importImage(f);
            if (id == null) {
                System.out.println("  × 导入失败：" + f.getName());
                continue;
            }
            idx++;
            imported++;
            if (!jdkOk) {
                converted++;
            }
            ThemeStore.setImageName(id, alias(f, ext, jdkOk));
            importedIds.add(id);
            System.out.println(String.format("  √ %-42s → %-9s %s", f.getName(),
                    ThemeStore.imageSizeText(ThemeStore.imageFile(id)),
                    jdkOk ? "" : "（ffmpeg 转 PNG）"));
        }

        // ---------- 2) 动态背景素材 ----------
        File dyn = new File(dir, "_动态背景");
        String gifId = null;
        String seqId = null;
        String audioId = null;
        if (dyn.isDirectory()) {
            File gif = new File(dir, "luotianyi_anim_4f.gif");
            if (gif.isFile()) {
                gifId = MediaLibrary.importAnimation(gif);
                if (gifId != null) {
                    ThemeStore.setImageName(gifId, "洛天依 动图 4 帧 (GIF)");
                    System.out.println("  √ 动图 GIF → " + MediaLibrary.mediaInfoText(gifId));
                }
            }
            File seqDir = new File(dyn, "帧序列_洛天依_8帧");
            if (seqDir.isDirectory()) {
                seqId = MediaLibrary.importFrameSequence(seqDir);
                if (seqId != null) {
                    ThemeStore.setImageName(seqId, "洛天依 帧序列 8 帧");
                    System.out.println("  √ 帧序列目录 → " + MediaLibrary.mediaInfoText(seqId));
                }
            }
            File wav = new File(dyn, "bgm_test_440hz_4s.wav");
            if (wav.isFile()) {
                audioId = MediaLibrary.importAudio(wav);
                if (audioId != null) {
                    ThemeStore.setImageName(audioId, "测试音 440Hz 4s (WAV)");
                    System.out.println("  √ 背景音 → " + MediaLibrary.mediaInfoText(audioId));
                }
            }
        }

        // ---------- 3) 窗口类别预设（不动全局背景） ----------
        System.out.println("\n窗口类别预设（全局背景保持你自己的壁纸不变）：");
        assign(dir, importedIds, ThemeStore.CATEGORY_CONFIG, "luotianyi_223_1920x1080.jpg",
                com.randomnamepicker.theme.BackdropMode.FILL, "配置名单");
        assign(dir, importedIds, ThemeStore.CATEGORY_SCHEME, "luotianyi_lzw_2000x1311.tiff",
                com.randomnamepicker.theme.BackdropMode.FILL, "方案管理（TIFF）");
        assign(dir, importedIds, ThemeStore.CATEGORY_NUMBER, "luotianyi_114_1080x1923.jpg",
                com.randomnamepicker.theme.BackdropMode.FIT, "数字设置（竖图+适应，看留白）");
        assign(dir, importedIds, ThemeStore.CATEGORY_SEAT, "luotianyi_sample_1600x900.bmp",
                com.randomnamepicker.theme.BackdropMode.CENTER, "座位设置（BMP+居中，看原尺寸）");
        assign(dir, importedIds, ThemeStore.CATEGORY_OTHER, "luotianyi_alpha_1920x1080.png",
                com.randomnamepicker.theme.BackdropMode.FILL, "其它对话框（alpha PNG）");
        assign(dir, importedIds, ThemeStore.CATEGORY_SETTINGS, "luotianyi_sample_2160x1112.webp",
                com.randomnamepicker.theme.BackdropMode.FILL, "系统设置（WebP→PNG 兜底产物）");

        ThemeImageIo.setFfmpegEnabled(ffmpegWasOn);

        System.out.println("\n=== 小结 ===");
        System.out.println("导入静图 " + imported + " 张（其中 ffmpeg 兜底转码 " + converted + " 张）");
        System.out.println("媒体库：动图=" + (gifId == null ? "无" : "有") + " 帧序列=" + (seqId == null ? "无" : "有")
                + " 背景音=" + (audioId == null ? "无" : "有") + "（均已入库但**未启用**，需要时在独立页下拉里选）");
        System.out.println("图库现有 " + ThemeStore.listImages().size() + " 张；媒体库现有 "
                + MediaLibrary.listAllMediaIds().size() + " 个");
        System.out.println("启用状态=" + ThemeStore.isEnabled() + " 全局背景=" + ThemeStore.getGlobalImage()
                + "（未改动）");
        System.out.println("窗口类别现状：");
        for (String c : ThemeStore.CATEGORY_IDS) {
            System.out.println(String.format("  %-8s %s %s", ThemeStore.categoryLabel(c),
                    ThemeStore.categoryHasOwnImage(c) ? ThemeStore.imageDisplayName(ThemeStore.categoryImageIdNow(c)) : "（继承全局）",
                    ThemeStore.categoryHasOwnImage(c) ? ThemeStore.categoryModeNow(c).label() : ""));
        }
        System.out.println("\n下一步：打开程序 → 主窗“主题 → 背景主题设置…” → 依次打开 配置名单/方案管理/数字设置/座位设置/系统设置/其它对话框");
        System.out.println("        再在“动态背景”下拉里选「洛天依 动图 4 帧 (GIF)」，用「导入帧序列目录…」旁的帧率/循环测动图与帧序列。");
        System.exit(0);
    }

    /**
     * 按**别名**去重：同名（同内容来源）只保留一个条目，其余条目的引用（全局/类别）重定向到保留者后删除。
     * 用于清掉"重复导入同一文件"造成的重复条目；不碰别名唯一的条目（例如你自己的壁纸）。
     */
    private static void dedupeByAlias() {
        java.util.Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        for (String id : ThemeStore.listImageIdsSorted()) {
            String alias = ThemeStore.getImageName(id);
            if (alias.isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(alias.replace("·兜底转换", ""), k -> new ArrayList<>()).add(id);
        }
        int removed = 0;
        int repointed = 0;
        for (java.util.Map.Entry<String, List<String>> e : groups.entrySet()) {
            List<String> ids = e.getValue();
            if (ids.size() < 2) {
                continue;
            }
            String keep = ids.get(0);
            System.out.println("重复别名 " + e.getKey() + "：" + ids.size() + " 条 → 保留 " + keep);            for (int i = 1; i < ids.size(); i++) {
                String dup = ids.get(i);
                if (dup.equals(ThemeStore.getGlobalImage())) {
                    ThemeStore.setGlobalImage(keep);
                    repointed++;
                }
                for (String c : ThemeStore.CATEGORY_IDS) {
                    if (dup.equals(ThemeStore.categoryImageIdNow(c))) {
                        ThemeStore.setCategoryImage(c, keep);
                        repointed++;
                    }
                }
                ThemeStore.deleteImageAndCleanup(dup);
                removed++;
                System.out.println("  删除重复条目 " + dup + "（引用已重定向 " + repointed + " 处）");
            }
        }
        System.out.println("去重完成：删除 " + removed + " 条重复条目，重定向引用 " + repointed + " 处；图库现有 "
                + ThemeStore.listImages().size() + " 张");
        dedupeMedia();
        System.exit(0);
    }

    /**
     * 清理"旧别名世代"的测试条目：本工具早期版本的别名形如
     * {@code 洛天依 <宽x高> <横图|竖图|超清> (<格式>)}（**不含来源编号 #**）。
     * 只删符合该模式者，因此**你的壁纸（别名"洛天依（PNG）"）与原始 webp（"洛天依"）不会被碰**；
     * 被删条目的引用会重定向到同尺寸同格式的新世代条目（若存在）。
     */
    private static void pruneOldGeneration() {
        java.util.regex.Pattern old = java.util.regex.Pattern.compile(
                "^洛天依 \\d{3,4}x\\d{3,4} (横图|竖图|超清) \\([^)]*\\)$");
        java.util.Map<String, String> replacement = new java.util.LinkedHashMap<>();
        for (String id : ThemeStore.listImages()) {
            String alias = ThemeStore.getImageName(id);
            if (alias.contains("#")) {
                String key = alias.replaceFirst("#[A-Za-z0-9]+ ", "");
                replacement.putIfAbsent(key, id);
            }
        }
        int removed = 0;
        int repointed = 0;
        for (String id : new ArrayList<>(ThemeStore.listImages())) {
            String alias = ThemeStore.getImageName(id);
            if (!old.matcher(alias).matches()) {
                continue;
            }
            String keep = replacement.get(alias.replace("·兜底转换", ""));
            if (keep != null && !keep.equals(id)) {
                if (id.equals(ThemeStore.getGlobalImage())) {
                    ThemeStore.setGlobalImage(keep);
                    repointed++;
                }
                for (String c : ThemeStore.CATEGORY_IDS) {
                    if (id.equals(ThemeStore.categoryImageIdNow(c))) {
                        ThemeStore.setCategoryImage(c, keep);
                        repointed++;
                    }
                }
            }
            ThemeStore.deleteImageAndCleanup(id);
            removed++;
        }
        System.out.println("旧别名世代清理：删除 " + removed + " 条，重定向引用 " + repointed
                + " 处；图库现有 " + ThemeStore.listImages().size() + " 张");
        System.exit(0);
    }

    /** 媒体库按别名去重（动图/帧序列/背景音各留一条，引用重定向到保留者）。 */
    private static void dedupeMedia() {
        java.util.Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        for (String id : MediaLibrary.listAllMediaIds()) {
            groups.computeIfAbsent(MediaLibrary.mediaDisplayName(id), k -> new ArrayList<>()).add(id);
        }
        int removed = 0;
        for (java.util.Map.Entry<String, List<String>> e : groups.entrySet()) {
            List<String> ids = e.getValue();
            if (ids.size() < 2) {
                continue;
            }
            String keep = ids.get(0);
            for (int i = 1; i < ids.size(); i++) {
                String dup = ids.get(i);
                if (dup.equals(MediaLibrary.getMediaId())) {
                    MediaLibrary.setMediaId(keep);
                }
                if (dup.equals(MediaLibrary.getAudioId())) {
                    MediaLibrary.setAudioId(keep);
                }
                MediaLibrary.deleteMediaAndCleanup(dup);
                removed++;
            }
            System.out.println("媒体重复别名 " + e.getKey() + "：留 " + keep + "，删 " + (ids.size() - 1) + " 条");
        }
        System.out.println("媒体库去重：删除 " + removed + " 条；媒体库现有 "
                + MediaLibrary.listAllMediaIds().size() + " 个");
    }

    /**
     * 删除"别名以『洛天依 』开头但不含来源编号 #"的条目（本工具旧版本的命名）。
     * 刻意**不匹配**：你的壁纸别名「洛天依（PNG）」（洛天依后紧跟全角括号）与原始 webp 别名「洛天依」。
     * 删除前会把指向它们的引用重定向到同尺寸同格式的新世代条目（若存在）。
     */
    private static void pruneAliasesWithoutToken() {
        java.util.Map<String, String> replacement = new java.util.LinkedHashMap<>();
        for (String id : ThemeStore.listImages()) {
            String alias = ThemeStore.getImageName(id);
            if (alias.contains("#")) {
                replacement.putIfAbsent(alias.replaceFirst("#[A-Za-z0-9]+ ", "").replace("·兜底转换", ""), id);
            }
        }
        int removed = 0;
        int repointed = 0;
        for (String id : new ArrayList<>(ThemeStore.listImages())) {
            String alias = ThemeStore.getImageName(id);
            boolean oldStyle = alias.startsWith("洛天依 ") && !alias.contains("#");
            if (!oldStyle) {
                continue;
            }
            String keep = replacement.get(alias.replace("·兜底转换", ""));
            if (keep != null && !keep.equals(id)) {
                if (id.equals(ThemeStore.getGlobalImage())) {
                    ThemeStore.setGlobalImage(keep);
                    repointed++;
                }
                for (String c : ThemeStore.CATEGORY_IDS) {
                    if (id.equals(ThemeStore.categoryImageIdNow(c))) {
                        ThemeStore.setCategoryImage(c, keep);
                        repointed++;
                    }
                }
            }
            System.out.println("  删除旧命名条目：" + alias + "（" + id + "）");
            ThemeStore.deleteImageAndCleanup(id);
            removed++;
        }
        System.out.println("旧命名清理：删除 " + removed + " 条，重定向引用 " + repointed
                + " 处；图库现有 " + ThemeStore.listImages().size() + " 张");
        System.exit(0);
    }

    private static String alias(File f, String ext, boolean jdkOk) {
        String n = f.getName();
        String dims = "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("_(\\d{3,4}x\\d{3,4})").matcher(n);
        if (m.find()) {
            dims = m.group(1);
        }
        // 来源编号：luotianyi_223_1920x1080.jpg → #223；sample/lossless/alpha 等取名 → 用该词
        // （必须唯一，否则同分辨率的不同图会被误判成"同一张"）
        String token = "";
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile("^luotianyi_([A-Za-z0-9]+)_").matcher(n);
        if (tm.find()) {
            token = "#" + tm.group(1);
        }
        String kind;
        switch (ext) {
            case "jpg": kind = "JPEG"; break;
            case "png": kind = n.contains("alpha") ? "PNG-透明" : "PNG"; break;
            case "bmp": kind = "BMP"; break;
            case "tiff": case "tif": kind = "TIFF"; break;
            case "gif": kind = "GIF"; break;
            case "webp": kind = "WebP"; break;
            case "avif": kind = "AVIF"; break;
            case "jfif": case "jpe": kind = "后缀伪装(" + ext.toUpperCase() + ")"; break;
            default: kind = ext.toUpperCase();
        }
        boolean portrait = false;
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile("_(\\d{3,4})x(\\d{3,4})").matcher(n);
        if (pm.find()) {
            portrait = Integer.parseInt(pm.group(2)) > Integer.parseInt(pm.group(1));
        }
        String tag = portrait ? "竖图" : "横图";
        if (n.matches(".*_(4\\d{3}|3\\d{3})x.*")) {
            tag = "超清";
        }
        if (n.contains("alias_ext")) {
            tag = "后缀伪装";
        }
        return "洛天依 " + token + " " + dims + " " + tag + " (" + kind + ")" + (jdkOk ? "" : "·兜底转换");
    }

    private static void assign(File dir, List<String> importedIds, String category, String sourceName,
                               com.randomnamepicker.theme.BackdropMode mode, String label) {
        File src = new File(dir, sourceName);
        if (!src.isFile()) {
            System.out.println("  - " + label + "：素材缺失，跳过（" + sourceName + "）");
            return;
        }
        String id = ThemeStore.importImage(src);
        if (id == null) {
            System.out.println("  × " + label + "：导入失败（" + sourceName + "）");
            return;
        }
        ThemeStore.setImageName(id, alias(src, extOf(src.getName()), true));
        ThemeStore.setCategoryImage(category, id);
        ThemeStore.setCategoryMode(category, mode);
        importedIds.add(id);
        System.out.println(String.format("  √ %-22s %s · %s · 源=%s", label,
                ThemeStore.imageDisplayName(id), mode.label(), sourceName));
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private ImportTestKit() {
    }
}
