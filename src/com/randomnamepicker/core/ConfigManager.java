package com.randomnamepicker.core;

import java.io.*;
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
 * 宿主配置（{@code data/config.properties}）。
 *
 * <p><b>2026-10 重做要点</b></p>
 * <ul>
 *   <li><b>可读化</b>：文件改为 **UTF-8 + 分区分组 + 注释**（中文不再被转义成 Unicode 码点形式）；
 *       未知键一律原样保留（放在「其它」区）；键值转义按 {@link Properties} 规则处理，读回无差别；</li>
 *   <li><b>防手滑</b>：每次写入前把上一版另存为 {@code data/config.properties.bak}；
 *       若主文件不存在而备份存在，**自动从备份恢复**（只记日志，不静默重置），避免误删后丢密码/主题配置；</li>
 *   <li><b>悬浮球尺寸只判下界</b>：{@link #getFloatingBallRadius()} 保证 ≥ {@link #FLOATING_BALL_RADIUS_MIN}，
 *       **不设上界**（大小 ∈ (0, +∞)）；设置窗滑块的 30–100 只是手动拖动范围，不参与内部读取判定；</li>
 *   <li>不透明度按 [0,255] 夹取（{@code new Color(..., alpha)} 越界会抛异常，属必要保护）；</li>
 *   <li>新增 {@code autoShowFloatingBall}：打开主程序后自动显示悬浮球。</li>
 * </ul>
 */
public class ConfigManager {

    private static final String CONFIG_FILE_PATH = "data/config.properties";
    /** 备份默认放 log/ 下（要求）：log/ 不在版本控制内（.gitignore 已忽略），且"清空日志"只删 Modifylog.txt、不动本文件。 */
    public static final String BACKUP_PATH = "log/config.properties.bak";
    /** 历史位置（旧版放在 data/ 下）：首次运行时自动搬到 {@link #BACKUP_PATH}。 */
    private static final String LEGACY_BACKUP_PATH = "data/config.properties.bak";
    private static final String HEADER = "Random Name Picker 配置（UTF-8，可手工编辑）";
    private static boolean backupPathLogged = false;

    /** 悬浮球半径下界（像素）；**无上界**——大小 ∈ (0, +∞)，读取时只判下界。 */
    public static final int FLOATING_BALL_RADIUS_MIN = 8;
    public static final int FLOATING_BALL_RADIUS_DEFAULT = 50;
    /** 不透明度合法区间（Color alpha）。 */
    public static final int FLOATING_BALL_OPACITY_MIN = 0;
    public static final int FLOATING_BALL_OPACITY_MAX = 255;
    public static final int FLOATING_BALL_OPACITY_DEFAULT = 200;

    /**
     * 「启动与窗口 / 悬浮球 / 安全」三个已知区的键。
     * **必须声明在静态初始化块之前**：静态字段按文本顺序初始化，若放在后面，
     * 首次运行（无配置文件 → createDefaultConfig → save）时会读到 null 并抛 ExceptionInInitializerError。
     */
    private static final java.util.Set<String> HEADER_KEYS = java.util.Set.of(
            "autoStart", "minimizeToTray", "autoShowFloatingBall", "lastScheme",
            "floatingBallRadius", "floatingBallOpacity", "x7f9a2b1c4e8d3f6");

    /** 配置内存态（对外公开：历史代码与工具会直接读它）。 */
    public static Properties properties;

    static {
        properties = new Properties();
        loadConfig();
    }

    // ===================== 读取 / 写入 =====================

    private static void loadConfig() {
        File configFile = new File(CONFIG_FILE_PATH);
        File parentDir = configFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        migrateLegacyBackup();
        try {
            if (!configFile.exists()) {
                File backup = new File(BACKUP_PATH);
                if (backup.isFile()) {
                    // 主文件被误删/被工具清掉：从备份恢复，而不是静默回到出厂默认（否则会丢密码等设置）
                    Files.copy(backup.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    LogManager.log("配置主文件缺失，已从备份恢复: " + backup.getAbsolutePath(), "CONFIG_RESTORE");
                } else {
                    createDefaultConfig(configFile);
                    return;
                }
            }
            properties = loadFrom(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 备份位置迁移：旧版把备份放在 {@code data/config.properties.bak}，现按**要求**统一放到 {@code log/} 下。
     * 新位置已存在时，旧位置文件被取代（删除），避免残留旧凭据副本。
     */
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
                LogManager.log("配置备份位置迁移: " + LEGACY_BACKUP_PATH + " → " + BACKUP_PATH, "CONFIG_BACKUP_MOVE");
            } else {
                Files.deleteIfExists(legacy.toPath());
                LogManager.log("已清理旧位置备份（新位置 " + BACKUP_PATH + " 已存在）", "CONFIG_BACKUP_MOVE");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 以 UTF-8 读入（旧文件里的 Unicode 转义序列仍能正确解析）。
     * **容忍 BOM**：记事本/PowerShell 等编辑器可能写入 UTF-8 BOM，
     * 若不去掉，首行表头会变成 `\uFEFF#...` 而被 Properties 当成一个键（产生垃圾键）。
     */
    private static Properties loadFrom(File file) throws IOException {
        Properties p = new Properties();
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        try (Reader r = new java.io.StringReader(text)) {
            p.load(r);
        }
        return p;
    }

    private static void createDefaultConfig(File configFile) throws IOException {
        properties = new Properties();
        properties.setProperty("autoStart", "false");
        properties.setProperty("minimizeToTray", "true");
        properties.setProperty("autoShowFloatingBall", "false");
        properties.setProperty("floatingBallRadius", String.valueOf(FLOATING_BALL_RADIUS_DEFAULT));
        properties.setProperty("floatingBallOpacity", String.valueOf(FLOATING_BALL_OPACITY_DEFAULT));
        try {
            String defaultPassword = "#include<bits/stdc++.h>usingnamespacestd;intmain(){return0;}";
            properties.setProperty("x7f9a2b1c4e8d3f6", EncryptionUtil.hashPasswordStrong(defaultPassword));
        } catch (Exception e) {
            e.printStackTrace();
        }
        save();
        System.out.println("配置文件已自动创建：" + configFile.getAbsolutePath());
        LogManager.log("配置文件已自动创建（出厂默认，含内置默认密码）", "CONFIG_CREATE");
    }

    /**
     * 丢弃内存态并从磁盘重读（供工具/测试在还原文件后同步内存，避免后续任何 save 把测试值写回）。
     * 文件缺失时保持当前内存不变。
     */
    public static synchronized void reload() {
        File file = new File(CONFIG_FILE_PATH);
        if (!file.isFile()) {
            return;
        }
        try {
            properties = loadFrom(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** 落盘：先备份上一版（到 log/ 下），再按「分区 + 键排序」写成 UTF-8 文本。公开入口（PasswordManager 等共用同一写路径）。 */
    public static void save() {
        File configFile = new File(CONFIG_FILE_PATH);
        File backup = new File(BACKUP_PATH);
        try {
            File backupParent = backup.getParentFile();
            if (backupParent != null && !backupParent.exists()) {
                backupParent.mkdirs();
            }
            if (configFile.isFile()) {
                Files.copy(configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (!backupPathLogged) {
                    backupPathLogged = true;
                    LogManager.log("配置备份位置: " + BACKUP_PATH, "CONFIG_BACKUP");
                }
            }
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
                w.write("# " + HEADER + "\n");
                w.write("# 生成时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n");
                w.write("# 上一版备份：" + BACKUP_PATH + "（主文件缺失时会自动从它恢复）\n");
                w.write("# 注意：删掉本文件会丢失自定义密码（回落到内置默认密码）与悬浮球/托盘等设置。\n");
                w.write("# 主题设置不在这里，位于 data/themes/theme.properties。\n\n");

                writeSection(w, "启动与窗口", new String[]{
                        "autoStart", "minimizeToTray", "autoShowFloatingBall", "lastScheme"});
                writeSection(w, "悬浮球", new String[]{
                        "floatingBallRadius", "floatingBallOpacity"});
                writeSection(w, "安全（密码散列，请勿手工修改）", new String[]{
                        "x7f9a2b1c4e8d3f6"});
                writeOtherSection(w);
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

    /** 其它/未知键：原样保留（含历史上可能残留的 theme.*，迁移后正常不会出现）。 */
    private static void writeOtherSection(Writer w) throws IOException {
        TreeSet<String> others = new TreeSet<>();
        for (String k : properties.stringPropertyNames()) {
            if (!HEADER_KEYS.contains(k)) {
                others.add(k);
            }
        }
        if (others.isEmpty()) {
            return;
        }
        w.write("# ---- 其它（未识别的键，原样保留） ----\n");
        for (String k : others) {
            w.write(escapeKey(k) + "=" + escapeValue(properties.getProperty(k)) + "\n");
        }
        w.write("\n");
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

    // ===================== 启动与窗口 =====================

    public static boolean isAutoStartEnabled() {
        return Boolean.parseBoolean(properties.getProperty("autoStart", "false"));
    }

    public static void setAutoStart(boolean enabled) {
        properties.setProperty("autoStart", String.valueOf(enabled));
        save();
    }

    public static boolean isMinimizeToTray() {
        return Boolean.parseBoolean(properties.getProperty("minimizeToTray", "true"));
    }

    public static void setMinimizeToTray(boolean minimize) {
        properties.setProperty("minimizeToTray", String.valueOf(minimize));
        save();
    }

    /** 打开主程序后自动显示悬浮球（默认 false）。 */
    public static boolean isAutoShowFloatingBall() {
        return Boolean.parseBoolean(properties.getProperty("autoShowFloatingBall", "false"));
    }

    public static void setAutoShowFloatingBall(boolean autoShow) {
        properties.setProperty("autoShowFloatingBall", String.valueOf(autoShow));
        save();
    }

    public static String getLastScheme() {
        return properties.getProperty("lastScheme", "");
    }

    public static void setLastScheme(String schemeName) {
        if (schemeName == null || schemeName.isEmpty()) {
            properties.remove("lastScheme");
        } else {
            properties.setProperty("lastScheme", schemeName);
        }
        save();
    }

    // ===================== 悬浮球 =====================

    /**
     * 悬浮球半径（像素）。**只判下界**：保证 ≥ {@link #FLOATING_BALL_RADIUS_MIN}；
     * **不设上界**（大小 ∈ (0, +∞)，可手工写成 300 之类的大球）。
     * 设置窗滑块的 30–100 只是手动拖动范围，不参与这里的判定。
     */
    public static int getFloatingBallRadius() {
        String value = properties.getProperty("floatingBallRadius",
                String.valueOf(FLOATING_BALL_RADIUS_DEFAULT));
        int radius;
        try {
            radius = Integer.parseInt(value.trim());
        } catch (Exception e) {
            radius = FLOATING_BALL_RADIUS_DEFAULT;
        }
        return Math.max(FLOATING_BALL_RADIUS_MIN, radius);
    }

    /** 写入半径：同样只判下界（不限制上界）。 */
    public static void setFloatingBallRadius(int radius) {
        properties.setProperty("floatingBallRadius",
                String.valueOf(Math.max(FLOATING_BALL_RADIUS_MIN, radius)));
        save();
    }

    /** 不透明度：夹在 [0,255]（越界会让 {@code new Color(..., alpha)} 抛异常）。 */
    public static int getFloatingBallOpacity() {
        String value = properties.getProperty("floatingBallOpacity",
                String.valueOf(FLOATING_BALL_OPACITY_DEFAULT));
        int opacity;
        try {
            opacity = Integer.parseInt(value.trim());
        } catch (Exception e) {
            opacity = FLOATING_BALL_OPACITY_DEFAULT;
        }
        return Math.max(FLOATING_BALL_OPACITY_MIN, Math.min(FLOATING_BALL_OPACITY_MAX, opacity));
    }

    public static void setFloatingBallOpacity(int opacity) {
        properties.setProperty("floatingBallOpacity",
                String.valueOf(Math.max(FLOATING_BALL_OPACITY_MIN,
                        Math.min(FLOATING_BALL_OPACITY_MAX, opacity))));
        save();
    }

    // ===================== 密码散列（与 PasswordManager 共用同一写路径） =====================

    public static String getPasswordHash() {
        return properties.getProperty("x7f9a2b1c4e8d3f6", "");
    }

    public static void setPasswordHash(String hash) {
        if (hash == null || hash.isEmpty()) {
            properties.remove("x7f9a2b1c4e8d3f6");
        } else {
            properties.setProperty("x7f9a2b1c4e8d3f6", hash);
        }
        save();
        // 备份里不能留"上一版凭据"：否则一次升级（弱哈希→强哈希）之后，
        // .bak 里仍是可快速爆破的旧哈希，等于绕过了升级。写入密码后立即把备份刷成当前版本。
        refreshBackup();
    }

    /** 用当前主文件覆盖备份（仅用于密码变更后，避免备份残留旧凭据）。 */
    private static void refreshBackup() {
        try {
            File configFile = new File(CONFIG_FILE_PATH);
            File backup = new File(BACKUP_PATH);
            File parent = backup.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (configFile.isFile()) {
                Files.copy(configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 备份刷新失败不影响主流程（下一次保存会再写备份）
            e.printStackTrace();
        }
    }

    // ===================== 主题键（已拆分到独立文件，保留转发以兼容旧代码/工具） =====================

    /**
     * @deprecated 主题设置自 2026-10 起存于 {@code data/themes/theme.properties}（见
     * {@code com.randomnamepicker.theme.ThemeConfig}）。本方法仅作**兼容转发**，请改用 {@code ThemeConfig.get}。
     */
    @Deprecated
    public static String getThemeProperty(String key, String def) {
        return com.randomnamepicker.theme.ThemeConfig.get(key, def);
    }

    /**
     * @deprecated 主题设置自 2026-10 起存于 {@code data/themes/theme.properties}（见
     * {@code com.randomnamepicker.theme.ThemeConfig}）。本方法仅作**兼容转发**，请改用 {@code ThemeConfig.set}。
     */
    @Deprecated
    public static void setThemeProperty(String key, String value) {
        com.randomnamepicker.theme.ThemeConfig.set(key, value);
    }
}
