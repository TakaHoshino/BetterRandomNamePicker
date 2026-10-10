import com.randomnamepicker.theme.MediaClock;
import com.randomnamepicker.theme.ThemeAudio;
import com.randomnamepicker.theme.ThemeImageIo;
import com.randomnamepicker.theme.ThemeStore;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 维护工具：把图库里**无法解码**的图片（典型：JDK 无解码器的 WebP）“就地修复”为可用的 PNG 条目。
 *
 * 行为：
 *   1) 扫描 data/themes/images/，挑出“文件存在但 ImageIO 解不了、且本机 ffmpeg 能转”的文件；
 *   2) 用 ffmpeg 转成 PNG → 走 ThemeStore.importImage 入库（别名 = 原名 +（PNG））；
 *   3) 把**引用**（全局背景 / 各窗口类别的单独指定）从原文件重定向到新 PNG 条目；
 *   4) **原文件保留**（不删你的素材），需要的话在独立页里自行“删除”。
 *
 * 运行（工作目录 = 仓库根；请先关掉正在运行的宿主，避免两个进程同时写 config）：
 *   javac -encoding UTF-8 -cp out -d tools/theme-smoke/classes tools/theme-smoke/ConvertUndecodableImages.java
 *   java -cp "out;tools/theme-smoke/classes" ConvertUndecodableImages [--dry]
 */
public final class ConvertUndecodableImages {

    public static void main(String[] args) throws Exception {
        boolean dry = args.length > 0 && "--dry".equalsIgnoreCase(args[0]);
        System.out.println("ffmpeg：" + ThemeImageIo.ffmpegStatusText());
        System.out.println("lib/ 插件目录：" + ThemeImageIo.libDirPath() + "（" + ThemeImageIo.scanNote() + "）");
        if (!ThemeImageIo.ffmpegAvailable()) {
            System.out.println("本机没有 ffmpeg，无法转换；请放 lib/ 插件或先手工转成 PNG/JPG。");
            return;
        }

        File dir = new File(ThemeStore.IMAGES_DIR);
        File[] files = dir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            System.out.println("图库为空，无需处理。");
            return;
        }

        int fixed = 0;
        for (File f : files) {
            if (ThemeStore.looksDecodable(f)) {
                continue;
            }
            String oldId = f.getName();
            String usage = ThemeStore.usageText(oldId);
            System.out.println("— 需修复：" + oldId + "（" + ThemeStore.fileSizeText(f) + "，引用：" + usage + "）");
            if (dry) {
                System.out.println("  （--dry 模式：只报告，不改动）");
                continue;
            }
            String newId = ThemeStore.importImage(f); // 内部走 ffmpeg 兜底转码
            if (newId == null) {
                System.out.println("  转换失败：跳过（可尝试手工另存为 PNG 后重新导入）");
                continue;
            }
            ThemeStore.setImageName(newId, ThemeStore.baseNameOf(oldId) + "（PNG）");
            // 若原条目有别名（例如"洛天依"），新条目优先沿用该别名，便于用户一眼认出
            String oldAlias = ThemeStore.getImageName(oldId);
            if (!oldAlias.isEmpty()) {
                ThemeStore.setImageName(newId, oldAlias + "（PNG）");
            }

            List<String> repointed = new ArrayList<>();
            if (oldId.equals(ThemeStore.getGlobalImage())) {
                ThemeStore.setGlobalImage(newId);
                repointed.add("全局");
            }
            for (String category : ThemeStore.CATEGORY_IDS) {
                if (oldId.equals(ThemeStore.categoryImageIdNow(category))) {
                    ThemeStore.setCategoryImage(category, newId);
                    repointed.add(ThemeStore.categoryLabel(category));
                }
            }
            System.out.println("  已生成：" + newId + " · " + ThemeStore.imageSizeText(ThemeStore.imageFile(newId))
                    + "；引用重定向：" + (repointed.isEmpty() ? "无" : String.join("、", repointed))
                    + "（原文件保留：" + oldId + "）");
            fixed++;
        }
        ThemeStore.setGlobalImage(ThemeStore.getGlobalImage()); // 触发一次落盘，确保重定向后的配置写入磁盘
        MediaClock.getInstance().shutdown();
        ThemeAudio.getInstance().shutdown();
        System.out.println(fixed == 0 ? "没有需要修复的图片。" : ("完成：修复 " + fixed + " 张。重启宿主即可看到效果。"));
        System.exit(0);
    }

    private ConvertUndecodableImages() {
    }
}
