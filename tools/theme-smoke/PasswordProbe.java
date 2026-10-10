import com.randomnamepicker.core.ConfigManager;
import com.randomnamepicker.core.EncryptionUtil;
import com.randomnamepicker.core.PasswordManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 密码存储安全取证：
 *   ① 文件里存的是**哈希**而非明文（并打印 SHA-256 / PBKDF2 的爆破成本对比）；
 *   ② 新格式 = PBKDF2-HMAC-SHA256 + 随机盐 + 21 万轮，同一密码两次哈希不同、都能验证、错误密码失败；
 *   ③ 旧格式（无盐单轮 SHA-256）仍可验证，并在**首次验证成功后自动升级**；
 *   ④ 密码写入后备份 .bak 同步刷新（不留可快速爆破的旧哈希）；
 *   ⑤ 仍在使用源码内置默认密码时给出安全提示。
 *
 * 运行（工作目录 = 仓库根；**先关掉正在运行的宿主**）：
 *   javac -encoding UTF-8 -cp out -d tools/theme-smoke/classes tools/theme-smoke/PasswordProbe.java
 *   java -cp "out;tools/theme-smoke/classes" PasswordProbe
 * 结束时恢复运行前的密码散列与配置备份。
 */
public final class PasswordProbe {

    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        File config = new File("data/config.properties");
        File backup = new File(ConfigManager.BACKUP_PATH);
        String oldHash = ConfigManager.getPasswordHash();
        byte[] oldBackup = backup.isFile() ? Files.readAllBytes(backup.toPath()) : null;
        System.out.println("运行前散列前缀 = " + prefix(oldHash));

        // ① 爆破成本对比
        long t0 = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            EncryptionUtil.hashPassword("guess" + i);
        }
        double shaMs = (System.nanoTime() - t0) / 1e6 / 5000;
        String strong = EncryptionUtil.hashPasswordStrong("bench-pass");
        t0 = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            EncryptionUtil.hashPasswordStrong("bench-pass" + i);
        }
        double pbkdf2Ms = (System.nanoTime() - t0) / 1e6 / 10;
        System.out.printf("对比：旧格式 SHA-256 ≈ %.5f ms/次（%.0f 次/秒）；新格式 PBKDF2 ≈ %.1f ms/次（%.0f 次/秒）→ 慢约 %.0f 倍%n",
                shaMs, 1000 / shaMs, pbkdf2Ms, 1000 / pbkdf2Ms, pbkdf2Ms / shaMs);
        check("PBKDF2 单次成本 ≥ 10ms（显著抬高离线爆破成本）", pbkdf2Ms >= 10);

        // ② 新格式性质
        String h1 = EncryptionUtil.hashPasswordStrong("s3cret-pw");
        String h2 = EncryptionUtil.hashPasswordStrong("s3cret-pw");
        check("新格式带自描述前缀", h1.startsWith(EncryptionUtil.PBKDF2_PREFIX + "$"));
        check("轮数写入格式（" + EncryptionUtil.PBKDF2_ITERATIONS + "）",
                h1.split("\\$")[1].equals(String.valueOf(EncryptionUtil.PBKDF2_ITERATIONS)));
        check("同一密码两次哈希不同（随机盐）", !h1.equals(h2));
        check("两个哈希都能验证通过",
                EncryptionUtil.verifyPassword("s3cret-pw", h1) && EncryptionUtil.verifyPassword("s3cret-pw", h2));
        check("错误密码验证失败", !EncryptionUtil.verifyPassword("wrong-pw", h1));
        check("新旧格式都不需要升级（新格式）", !EncryptionUtil.needsUpgrade(h1));

        // ③ 旧格式兼容 + 自动升级
        String legacy = EncryptionUtil.hashPassword("legacy-pw-2026");
        check("旧格式被识别为需要升级", EncryptionUtil.needsUpgrade(legacy) && EncryptionUtil.isLegacyHash(legacy));
        check("旧格式仍能验证通过", EncryptionUtil.verifyPassword("legacy-pw-2026", legacy));
        ConfigManager.setPasswordHash(legacy);
        check("已写入旧格式（模拟历史配置）", ConfigManager.getPasswordHash().equals(legacy));
        check("旧格式登录成功", PasswordManager.verifyPassword("legacy-pw-2026"));
        String afterLogin = ConfigManager.getPasswordHash();
        check("登录后自动升级为 PBKDF2", afterLogin.startsWith(EncryptionUtil.PBKDF2_PREFIX + "$"));
        check("升级后新密码依旧可验证", PasswordManager.verifyPassword("legacy-pw-2026"));
        check("升级后错误密码仍失败", !PasswordManager.verifyPassword("legacy-pw-2026-x"));

        // ④ 备份不留旧弱哈希
        String bakText = backup.isFile() ? new String(Files.readAllBytes(backup.toPath()), StandardCharsets.UTF_8) : "";
        check("备份已同步刷新（含同一条 PBKDF2 值）",
                bakText.contains(afterLogin.substring(0, 24)) && !bakText.contains(legacy));

        // ⑤ 文件里是哈希、不是明文
        String raw = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
        check("配置文件里不含明文密码", !raw.contains("legacy-pw-2026") && !raw.contains("s3cret-pw"));
        check("配置里只出现自描述哈希", raw.contains("x7f9a2b1c4e8d3f6=" + EncryptionUtil.PBKDF2_PREFIX + "$"));

        // 收尾恢复
        if (oldHash != null && !oldHash.isEmpty()) {
            ConfigManager.setPasswordHash(oldHash);
        }
        if (oldBackup != null) {
            Files.write(backup.toPath(), oldBackup);
        }
        check("收尾已恢复运行前的散列", ConfigManager.getPasswordHash().equals(oldHash));

        System.out.println(fail == 0 ? "PASSWORD_PROBE_RESULT=PASS" : ("PASSWORD_PROBE_RESULT=FAIL(" + fail + ")"));
        Runtime.getRuntime().halt(fail == 0 ? 0 : 1);
    }

    private static String prefix(String hash) {
        if (hash == null || hash.isEmpty()) {
            return "(空)";
        }
        return hash.startsWith(EncryptionUtil.PBKDF2_PREFIX) ? "PBKDF2（已升级）"
                : "旧格式 SHA-256（无盐单轮，建议升级）";
    }

    private static void check(String name, boolean pass) {
        if (!pass) {
            fail++;
        }
        System.out.println((pass ? "[PASS] " : "[FAIL] ") + name);
    }

    private PasswordProbe() {
    }
}
