import com.randomnamepicker.core.ConfigManager;
import com.randomnamepicker.plugin.PluginManager;
import com.randomnamepicker.theme.BackdropManager;
import com.randomnamepicker.theme.MediaClock;
import com.randomnamepicker.theme.MediaLibrary;
import com.randomnamepicker.theme.ThemeAudio;
import com.randomnamepicker.theme.ThemeConfig;
import com.randomnamepicker.theme.ThemeImageIo;
import com.randomnamepicker.theme.ThemeStore;
import com.randomnamepicker.ui.NamePickerApp;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * 只读快照工具：用**当前配置**（你自己的主题设置）离屏渲染主窗，落一张 PNG 供核对。
 * 不修改任何配置、不写入图库、不抓取桌面（离屏 printAll），也不会留下窗口。
 *
 * 运行（工作目录 = 仓库根；请先关掉正在运行的宿主）：
 *   javac -encoding UTF-8 -cp out -d tools/theme-smoke/classes tools/theme-smoke/SnapshotMainWindow.java
 *   java -cp "out;tools/theme-smoke/classes" SnapshotMainWindow [输出文件名]
 * 默认输出：theme_ui_shots/user_wallpaper_main.png
 */
public final class SnapshotMainWindow {

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("HEADLESS：无图形环境，跳过");
            System.exit(3);
        }
        String outName = args.length > 0 ? args[0] : "theme_ui_shots/user_wallpaper_main.png";
        System.out.println("启用=" + ThemeStore.isEnabled()
                + " 全局图=" + ThemeStore.getGlobalImage()
                + "（" + ThemeStore.imageSizeText(ThemeStore.imageFile(ThemeStore.getGlobalImage())) + "）"
                + " 动态背景=" + MediaLibrary.getMediaId()
                + " 主题设置文件=" + ThemeConfig.FILE_PATH
                + " ffmpeg=" + ThemeImageIo.ffmpegStatusText());

        ThemeImageIo.ensurePluginsLoaded();
        final NamePickerApp[] holder = new NamePickerApp[1];
        SwingUtilities.invokeAndWait(() -> {
            NamePickerApp frame = new NamePickerApp();
            PluginManager.getInstance().init(frame);
            frame.setSize(1000, 660);
            frame.setLocation(40, 40);
            frame.setVisible(true);
            holder[0] = frame;
        });
        Thread.sleep(1500);

        SwingUtilities.invokeAndWait(() -> BackdropManager.getInstance().reapplyAll());
        Thread.sleep(700);

        SwingUtilities.invokeAndWait(() -> {
            Window w = holder[0];
            w.validate();
            BufferedImage img = new BufferedImage(Math.max(1, w.getWidth()),
                    Math.max(1, w.getHeight()), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            w.printAll(g);
            g.dispose();
            File out = new File(outName);
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try {
                ImageIO.write(img, "png", out);
            } catch (Exception e) {
                System.out.println("写出失败：" + e);
            }
            System.out.println("已写出 " + out.getPath() + "（" + img.getWidth() + "x" + img.getHeight() + "）"
                    + " 主题样式 active=" + ThemeStore.resolve(ThemeStore.CATEGORY_MAIN).isActive()
                    + " animated=" + ThemeStore.resolve(ThemeStore.CATEGORY_MAIN).isAnimated());
            BackdropManager.getInstance().shutdown();
            MediaClock.getInstance().shutdown();
            ThemeAudio.getInstance().shutdown();
            for (Window win : Window.getWindows()) {
                win.dispose();
            }
        });
        System.out.println("配置未改动：主题 enabled=" + ThemeStore.isEnabled()
                + "，全局图=" + ThemeStore.getGlobalImage()
                + "；主配置里 theme.* 键数=" + countLegacyThemeKeysInConfig());
        Runtime.getRuntime().halt(0);
    }

    private static int countLegacyThemeKeysInConfig() {
        int n = 0;
        for (String k : ConfigManager.properties.stringPropertyNames()) {
            if (k.startsWith("theme.")) {
                n++;
            }
        }
        return n;
    }

    private SnapshotMainWindow() {
    }
}
