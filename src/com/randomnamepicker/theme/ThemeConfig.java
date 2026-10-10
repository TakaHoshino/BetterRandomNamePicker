package com.randomnamepicker.theme;

import com.randomnamepicker.core.ConfigManager;
import com.randomnamepicker.core.LogManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

/**
 * 主题设置存储（2026-10 从主配置**拆分**出来）。
 *
 * <p>落盘位置：{@code data/themes/theme.properties}（与 {@code images/}、{@code media/} 同目录，主题相关文件集中存放），
 * 备份：{@code data/themes/theme.properties.bak}。主配置 {@code data/config.properties} 只保留宿主设置
 * （自启动/最小化到托盘/自动显示悬浮球/上次方案/悬浮球尺寸透明度/密码散列），不再夹杂几十条 {@code theme.*}。</p>
 *
 * <p>键空间与原来完全一致（不含 {@code theme.} 前缀）：{@code enabled}、{@code mask}、{@code tableTier}、
 * {@code g.image|mode|crop|media|audio}、{@code cat.<类别>.image|mode|crop}、{@code media.fps|loop|muted|volume|ffmpeg}、
 * {@code name.<图片id>}（别名）。</p>
 *
 * <p>三条保障与主配置一致：**UTF-8 + 分区注释 + 键排序**（中文可读）、**每次写入前备份上一版**、
 * **主文件缺失但备份存在时自动恢复**。另含**一次性迁移**：若旧版主配置里还有 theme.* 键，
 * 首次运行会把它们搬进本文件并从主配置移除（日志记 {@code CONFIG_MIGRATE}）。</p>
 */
public final class ThemeConfig {

    public static final String FILE_PATH = ThemeStore.THEME_DIR + "/theme.properties";
    /** 备份默认放 log/ 下（要求）：log/ 不在版本控制内，且"清空日志"只删 Modifylog.txt、不动本文件。 */
    public static final String BACKUP_PATH = com.randomnamepicker.core.LogManager.LOG_DIR + "/theme.properties.bak";
    /** 历史位置（旧版放在 data/themes/ 下）：首次运行时自动搬到 {@link #BACKUP_PATH}。 */
    private static final String LEGACY_BACKUP_PATH = ThemeStore.THEME_DIR + "/theme.properties.bak";
    private static final String PREFIX = "theme.";
    private static final String HEADER = "Random Name Picker 主题设置（UTF-8，可手工编辑）";
    private static boolean backupPathLogged = false;

    /** 分区：总开关与全局参数。 */
    private static final String[] PARAM_KEYS = {
            "enabled", "mask", "tableTier",
            "media.fps", "media.loop", "media.muted", "media.volume",
            "media.ffmpeg", "media.ffmpeg.path"
    };
    /** 分区：全局背景。 */
    private static final String[] GLOBAL_KEYS = {"g.image", "g.mode", "g.crop", "g.media", "g.audio"};

    private static final Properties properties = new Properties();
    private static boolean loaded = false;

    private ThemeConfig() {
    }

    // ===================== 读取 / 写入 =====================

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        migrateLegacyBackup();
        File file = new File(FILE_PATH);
        if (!file.isFile()) {
            File backup = new File(BACKUP_PATH);
            if (backup.isFile()) {
                try {
                    Files.copy(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    LogManager.log("主题设置缺失，已从备份恢复: " + backup.getAbsolutePath(), "CONFIG_RESTORE");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        try {
            if (file.isFile()) {
                // 容忍 UTF-8 BOM（编辑器写入 BOM 时，首行表头会变成垃圾键）
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                    text = text.substring(1);
                }
                try (Reader r = new java.io.StringReader(text)) {
                    properties.load(r);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (migrateLegacyKeys()) {
            save();
        }
    }

    /** 备份位置迁移：旧版在 {@code data/themes/theme.properties.bak}，现按要求统一到 {@code log/}。 */
    private static void migrateLegacyBackup() {
        try {
            File legacy = new File(LEGACY_BACKUP_PATH);
            if (!legacy.isFile()) {
                return;
            }
            File target = new File(BACKUP_PATH);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!target.isFile()) {
                Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LogManager.log("主题设置备份位置迁移: " + LEGACY_BACKUP_PATH + " → " + BACKUP_PATH, "CONFIG_BACKUP_MOVE");
            } else {
                Files.deleteIfExists(legacy.toPath());
                LogManager.log("已清理旧位置备份（新位置 " + BACKUP_PATH + " 已存在）", "CONFIG_BACKUP_MOVE");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 一次性迁移：把旧版主配置里的 {@code theme.*} 键搬到本文件，并从主配置移除。
     * 冲突时以**本文件现有值**为准（不覆盖新设置）。返回是否发生了迁移。
     */
    private static boolean migrateLegacyKeys() {
        List<String> legacy = new ArrayList<>();
        for (String k : ConfigManager.properties.stringPropertyNames()) {
            if (k.startsWith(PREFIX)) {
                legacy.add(k);
            }
        }
        if (legacy.isEmpty()) {
            return false;
        }
        for (String k : legacy) {
            String key = k.substring(PREFIX.length());
            if (properties.getProperty(key) == null) {
                properties.setProperty(key, ConfigManager.properties.getProperty(k));
            }
            ConfigManager.properties.remove(k);
        }
        ConfigManager.save(); // 主配置里移除这些键（备份仍可能保留旧副本，无害）
        LogManager.log("主题设置已从 config.properties 迁移到 " + FILE_PATH + "（" + legacy.size() + " 个键）",
                "CONFIG_MIGRATE");
        return true;
    }

    /** 读主题键；缺省返回 def。 */
    public static String get(String key, String def) {
        ensureLoaded();
        String v = properties.getProperty(key);
        return v != null ? v : def;
    }

    /** 写主题键；value 为 null 表示移除该键。写后落盘（含备份）。 */
    public static void set(String key, String value) {
        ensureLoaded();
        if (key == null || key.isEmpty()) {
            return;
        }
        if (value == null) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
        save();
    }

    /** 全部键（诊断/工具用）。 */
    public static java.util.Set<String> keys() {
        ensureLoaded();
        return new TreeSet<>(properties.stringPropertyNames());
    }

    /** 该键是否存在（非空）。 */
    public static boolean has(String key) {
        ensureLoaded();
        String v = properties.getProperty(key);
        return v != null && !v.isEmpty();
    }

    /** 丢弃内存态并从磁盘重读（供工具/测试在还原文件后同步内存）。 */
    public static synchronized void reload() {
        loaded = false;
        properties.clear();
        ensureLoaded();
    }

    /** 落盘：先备份上一版（到 log/ 下），再按「分区 + 键排序」写 UTF-8。 */
    public static synchronized void save() {
        File file = new File(FILE_PATH);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File backup = new File(BACKUP_PATH);
            File backupParent = backup.getParentFile();
            if (backupParent != null && !backupParent.exists()) {
                backupParent.mkdirs();
            }
            if (file.isFile()) {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (!backupPathLogged) {
                    backupPathLogged = true;
                    LogManager.log("主题设置备份位置: " + BACKUP_PATH, "CONFIG_BACKUP");
                }
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                w.write("# " + HEADER + "\n");
                w.write("# 生成时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n");
                w.write("# 本文件只放主题设置；图片与媒体在该目录下的 images/ 与 media/\n");
                w.write("# 上一版备份：" + BACKUP_PATH + "（本文件缺失时会自动从它恢复）\n");
                w.write("# 注意：删掉本文件会丢失图库别名、类别指定、遮罩与动态背景设置（图片文件本身不会删）。\n\n");
                writeSection(w, "总开关与全局参数", PARAM_KEYS);
                writeSection(w, "全局背景", GLOBAL_KEYS);
                writeCategories(w);
                writeAliases(w);
                writeOthers(w);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void writeSection(Writer w, String title, String[] keys) throws IOException {
        List<String> present = new ArrayList<>();
        for (String k : keys) {
            if (properties.getProperty(k) != null) {
                present.add(k);
            }
        }
        if (present.isEmpty()) {
            return;
        }
        w.write("# ---- " + title + " ----\n");
        for (String k : present) {
            w.write(escapeKey(k) + "=" + escapeValue(properties.getProperty(k)) + "\n");
        }
        w.write("\n");
    }

    private static void writeCategories(Writer w) throws IOException {
        TreeSet<String> keys = new TreeSet<>();
        for (String k : properties.stringPropertyNames()) {
            if (k.startsWith("cat.")) {
                keys.add(k);
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        w.write("# ---- 按窗口类别指定（未指定的类别继承全局） ----\n");
        for (String k : keys) {
            w.write(escapeKey(k) + "=" + escapeValue(properties.getProperty(k)) + "\n");
        }
        w.write("\n");
    }

    private static void writeAliases(Writer w) throws IOException {
        TreeSet<String> keys = new TreeSet<>();
        for (String k : properties.stringPropertyNames()) {
            if (k.startsWith("name.")) {
                keys.add(k);
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        w.write("# ---- 图库/媒体别名（name.<id>；<id> 即 data/themes/images 或 media 下的文件名） ----\n");
        for (String k : keys) {
            w.write(escapeKey(k) + "=" + escapeValue(properties.getProperty(k)) + "\n");
        }
        w.write("\n");
    }

    private static void writeOthers(Writer w) throws IOException {
        TreeSet<String> keys = new TreeSet<>();
        for (String k : properties.stringPropertyNames()) {
            if (k.startsWith("cat.") || k.startsWith("name.") || contains(PARAM_KEYS, k) || contains(GLOBAL_KEYS, k)) {
                continue;
            }
            keys.add(k);
        }
        if (keys.isEmpty()) {
            return;
        }
        w.write("# ---- 其它（未识别的键，原样保留） ----\n");
        for (String k : keys) {
            w.write(escapeKey(k) + "=" + escapeValue(properties.getProperty(k)) + "\n");
        }
        w.write("\n");
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) {
            if (s.equals(v)) {
                return true;
            }
        }
        return false;
    }

    private static String escapeKey(String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            switch (c) {
                case ' ': case '=': case ':': case '#': case '!': case '\\':
                    sb.append('\\').append(c);
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeValue(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
