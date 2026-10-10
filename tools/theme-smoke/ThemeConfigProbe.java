import com.randomnamepicker.theme.MediaLibrary;
import com.randomnamepicker.theme.ThemeConfig;
import com.randomnamepicker.theme.ThemeStore;
import com.randomnamepicker.core.ConfigManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 主题设置**拆分**取证：设置是否真的搬到了独立文件、主配置是否干净、读写是否落在正确文件。
 *
 * 运行（工作目录 = 仓库根；**先关掉正在运行的宿主**）：
 *   javac -encoding UTF-8 -cp out -d tools/theme-smoke/classes tools/theme-smoke/ThemeConfigProbe.java
 *   java -cp "out;tools/theme-smoke/classes" ThemeConfigProbe
 *
 * 注：迁移（旧 theme.* 从主配置搬走）与"删主题文件自动恢复"属**跨进程**行为，
 * 由 PowerShell 侧的临时目录实验验证（见本轮交付说明）。
 */
public final class ThemeConfigProbe {

    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        File themeFile = new File(ThemeConfig.FILE_PATH);
        File themeBak = new File(ThemeConfig.BACKUP_PATH);
        File configFile = new File("data/config.properties");

        System.out.println("主题设置文件 = " + themeFile.getAbsolutePath()
                + "（存在=" + themeFile.isFile() + "）");
        System.out.println("主配置       = " + configFile.getAbsolutePath());

        // 让主配置按新表头重写一次（表头里会注明主题设置已拆到独立文件）
        ConfigManager.save();

        // 记录运行前的键，收尾原样恢复（不改变用户当前的主题状态）
        boolean hadEnabled = ThemeConfig.has("enabled");
        String oldEnabled = ThemeConfig.get("enabled", "false");
        boolean hadMask = ThemeConfig.has("mask");
        String oldMask = ThemeConfig.get("mask", "");
        boolean hadMedia = ThemeConfig.has("g.media");
        String oldMedia = ThemeConfig.get("g.media", "");

        // ① 读写落到主题文件，主配置保持干净
        String marker = "probe-" + System.currentTimeMillis();
        ThemeConfig.set("probe.marker", marker);
        check("写入后能从 ThemeConfig 读回", marker.equals(ThemeConfig.get("probe.marker", "")));
        String themeText = read(themeFile);
        String configText = read(configFile);
        check("值写在 data/themes/theme.properties 里", themeText.contains("probe.marker=" + marker));
        check("主配置 config.properties 里没有该键", !configText.contains("probe.marker"));
        check("主配置里完全没有 theme.* 键（已彻底拆分）", countThemeKeys(configText) == 0);
        check("主配置表头指向主题文件", configText.contains("data/themes/theme.properties"));
        ThemeConfig.set("probe.marker", null);
        check("删除键后主题文件里也不再有它", !read(themeFile).contains("probe.marker"));

        // ② 文件可读化：UTF-8 + 分区注释 + 无 Unicode 转义
        //    （先写入总开关/遮罩，否则该文件只会有"其它"分区；写完再检查分区注释）
        ThemeStore.setEnabled(true);
        ThemeStore.setMask(77);
        String raw = read(themeFile);
        check("主题文件带可读表头", raw.startsWith("# Random Name Picker 主题设置"));
        check("主题文件有分区注释（总开关区）", raw.contains("# ---- 总开关与全局参数 ----"));
        check("主题文件无 Unicode 转义（中文可读）", !raw.contains("\\u"));

        // ③ 真实主题键确实在主题文件里（图库别名 / 类别 / 开关）
        check("theme.enabled 写入主题文件", raw.contains("enabled=true"));
        check("theme.mask 写入主题文件（77）", raw.contains("mask=77"));
        check("主配置仍未出现 theme.*", countThemeKeys(read(configFile)) == 0);
        check("ThemeStore 读回一致", ThemeStore.isEnabled() && ThemeStore.getMask() == 77);
        ThemeStore.setMask(ThemeStore.MASK_DEFAULT);
        ThemeStore.setEnabled(false);

        // ④ 媒体键也在主题文件（MediaLibrary 已改用 ThemeConfig）
        String gifId = MediaLibrary.listAnimationIds().isEmpty() ? null : MediaLibrary.listAnimationIds().get(0);
        if (gifId != null) {
            MediaLibrary.setMediaId(gifId);
            String withMedia = read(themeFile);
            check("动态背景 id 写入主题文件", withMedia.contains("g.media=" + gifId));
            check("主题文件出现「全局背景」分区注释", withMedia.contains("# ---- 全局背景 ----"));
            MediaLibrary.setMediaId(null);
        } else {
            log("[SKIP] 媒体库为空，跳过 g.media 落盘断言");
        }

        // ⑤ 备份存在
        check("主题设置已有备份 theme.properties.bak", themeBak.isFile());

        // 收尾：恢复运行前的键（保持你当前"干净"的主题状态）
        ThemeConfig.set("enabled", hadEnabled ? oldEnabled : null);
        ThemeConfig.set("mask", hadMask ? oldMask : null);
        ThemeConfig.set("g.media", hadMedia ? oldMedia : null);
        check("收尾已恢复运行前的主题键",
                ThemeConfig.has("enabled") == hadEnabled && ThemeConfig.has("mask") == hadMask
                        && ThemeConfig.has("g.media") == hadMedia);

        System.out.println(fail == 0 ? "THEMECONFIG_PROBE_RESULT=PASS" : ("THEMECONFIG_PROBE_RESULT=FAIL(" + fail + ")"));
        Runtime.getRuntime().halt(fail == 0 ? 0 : 1);
    }

    private static int countThemeKeys(String text) {
        int n = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith("theme.")) {
                n++;
            }
        }
        return n;
    }

    private static String read(File f) throws Exception {
        return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
    }

    private static void check(String name, boolean pass) {
        if (!pass) {
            fail++;
        }
        System.out.println((pass ? "[PASS] " : "[FAIL] ") + name);
    }

    private static void log(String s) {
        System.out.println(s);
    }

    private ThemeConfigProbe() {
    }
}
