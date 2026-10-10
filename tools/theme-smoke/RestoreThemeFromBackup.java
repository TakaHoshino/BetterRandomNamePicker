import com.randomnamepicker.core.ConfigManager;
import com.randomnamepicker.theme.MediaLibrary;
import com.randomnamepicker.theme.ThemeConfig;
import com.randomnamepicker.theme.ThemeStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

/**
 * 从配置备份里恢复 **theme.\* 主题块**（merge，不覆盖其它键）。
 *
 * 用途：配置文件被删/被清空后（`CONFIG_CREATE` 日志即为征兆），主题的图库别名、类别指定、
 * 动态背景与遮罩等设置会一起丢失；本工具把它们从备份合并回当前配置，
 * **保留当前文件里的其它设置**（悬浮球半径、密码散列、autostart 等一律不动）。
 *
 * 引用校验：`theme.name.<id>` / `theme.cat.*.image` / `theme.g.image` / `theme.g.media` 等指向的
 * 文件若已不在图库/媒体库目录，则**跳过该键**并报告（避免恢复出悬空引用）。
 *
 * 运行（工作目录 = 仓库根；**先关掉正在运行的宿主**）：
 *   javac -encoding UTF-8 -cp out -d tools/theme-smoke/classes tools/theme-smoke/RestoreThemeFromBackup.java
 *   java -cp "out;tools/theme-smoke/classes" RestoreThemeFromBackup <备份文件> [--dry]
 */
public final class RestoreThemeFromBackup {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法: RestoreThemeFromBackup <备份文件> [--dry]");
            System.exit(1);
        }
        File source = new File(args[0]);
        boolean dry = args.length > 1 && "--dry".equalsIgnoreCase(args[1]);
        if (!source.isFile()) {
            System.out.println("备份文件不存在：" + source);
            System.exit(1);
        }

        Properties backup = new Properties();
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8)) {
            backup.load(r);
        }
        TreeSet<String> themeKeys = new TreeSet<>();
        for (String k : backup.stringPropertyNames()) {
            if (k.startsWith("theme.")) {
                themeKeys.add(k);
            }
        }
        System.out.println("备份 " + source.getName() + " 里 theme.* 键 = " + themeKeys.size());
        System.out.println("当前配置里 theme.* 键 = " + countCurrentThemeKeys());

        List<String> restored = new ArrayList<>();
        List<String> skippedMissing = new ArrayList<>();
        List<String> skippedExisting = new ArrayList<>();
        for (String key : themeKeys) {
            String value = backup.getProperty(key);
            if (ThemeConfig.get(key.substring("theme.".length()), "") != null
                    && !ThemeConfig.get(key.substring("theme.".length()), "").isEmpty()) {
                skippedExisting.add(key);
                continue;
            }
            if (!referenceExists(key, value)) {
                skippedMissing.add(key + " → " + value);
                continue;
            }
            ThemeConfig.set(key.substring("theme.".length()), value);
            restored.add(key);
        }

        System.out.println("恢复 " + restored.size() + " 个键；当前已存在跳过 " + skippedExisting.size()
                + "；引用缺失跳过 " + skippedMissing.size());
        for (String s : skippedMissing) {
            System.out.println("  × 引用缺失（未恢复）: " + s);
        }
        if (!dry) {
            System.out.println("已写入配置（其余设置未改动）");
        } else {
            System.out.println("--dry：未写入");
        }
        System.out.println("启用=" + ThemeStore.isEnabled()
                + " 全局图=" + ThemeStore.imageDisplayName(ThemeStore.getGlobalImage())
                + " 图库=" + ThemeStore.listImages().size() + " 张 媒体库=" + MediaLibrary.listAllMediaIds().size() + " 个");
        for (String c : ThemeStore.CATEGORY_IDS) {
            System.out.println("  " + ThemeStore.categoryLabel(c) + " = "
                    + (ThemeStore.categoryHasOwnImage(c) ? ThemeStore.imageDisplayName(ThemeStore.categoryImageIdNow(c)) : "（继承全局）"));
        }
        System.exit(0);
    }

    /** 引用了图库/媒体库文件的键，需校验文件是否存在。 */
    private static boolean referenceExists(String key, String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        boolean isFileRef = key.endsWith(".image") || key.endsWith(".media") || key.endsWith(".audio")
                || key.startsWith("theme.name.") || "theme.g.image".equals(key) || "theme.g.media".equals(key)
                || "theme.g.audio".equals(key);
        if (!isFileRef) {
            return true;
        }
        String id = key.startsWith("theme.name.") ? key.substring("theme.name.".length()) : value.trim();
        if (id.isEmpty()) {
            return true;
        }
        File images = new File(ThemeStore.IMAGES_DIR, id);
        File media = new File(MediaLibrary.MEDIA_DIR, id);
        return images.isFile() || media.isFile();
    }

    private static int countCurrentThemeKeys() {
        int n = 0;
        for (String k : ThemeConfig.keys()) {
            if (k != null && !k.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private RestoreThemeFromBackup() {
    }
}
