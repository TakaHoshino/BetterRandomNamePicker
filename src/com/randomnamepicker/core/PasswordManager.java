package com.randomnamepicker.core;

/**
 * 密码管理（宿主锁定/解锁）。
 * <p>
 * 2026-10：文件读写**统一走 {@link ConfigManager}**（单一写路径）——原来这里自己读/写 {@code data/config.properties}，
 * 存在两个隐患：① 主文件缺失时 {@code savePasswordConfig()} 直接 return，改密码会"看似成功、重启即失效"；
 * ② 自己用 {@code Properties.store/load(InputStream)} 走 ISO-8859-1，与配置文件的 UTF-8 混用会把中文键值写成乱码。
 * 现在改密码/初始化默认密码都由 ConfigManager 落盘（含自动备份与 UTF-8 可读格式）。
 * </p>
 * <p>
 * 2026-10（安全）：散列由"无盐单轮 SHA-256"升级为 **PBKDF2-HMAC-SHA256 + 随机盐 + 21 万轮**；
 * 旧值仍可验证，并在**首次验证成功后自动升级**（此时才拿得到明文，属标准做法）。
 * </p>
 */
public class PasswordManager {
    private static final String CONF_KEY_A = "x7f9a2b1c4e8d3f6";
    /** 内置默认密码（源码可见）——仅用于出厂初始化，登录成功且检测到它时会提醒尽快修改。 */
    private static final String BUILTIN_DEFAULT_PASSWORD =
            "#include<bits/stdc++.h>usingnamespacestd;intmain(){return0;}";

    private static boolean isUnlocked = false;
    private static int ctrlLCount = 0;
    private static long lastCtrlLTime = 0;
    private static boolean warnedDefaultPassword = false;

    static {
        loadPasswordConfig();
    }

    /** 由宿主配置统一加载（触发 ConfigManager 静态初始化）。 */
    private static void loadPasswordConfig() {
        ConfigManager.getPasswordHash();
    }

    private static void savePasswordConfig(String hash) {
        ConfigManager.setPasswordHash(hash);
    }

    public static void initializeDefaultPassword() {
        try {
            savePasswordConfig(EncryptionUtil.hashPasswordStrong(BUILTIN_DEFAULT_PASSWORD));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean verifyPassword(String inputPassword) {
        try {
            sanitizeInput(inputPassword);

            String storedHash = ConfigManager.getPasswordHash();

            if (storedHash == null || storedHash.isEmpty()) {
                initializeDefaultPassword();
                storedHash = ConfigManager.getPasswordHash();
            }

            if (storedHash == null) {
                return false;
            }

            boolean verified = EncryptionUtil.verifyPassword(inputPassword, storedHash);
            if (!verified) {
                return false;
            }

            isUnlocked = true;
            // 旧格式（无盐单轮 SHA-256）在首次成功验证后就地升级为 PBKDF2
            if (EncryptionUtil.needsUpgrade(storedHash)) {
                try {
                    savePasswordConfig(EncryptionUtil.hashPasswordStrong(inputPassword));
                    LogManager.log("密码散列已升级为 PBKDF2-HMAC-SHA256（"
                            + EncryptionUtil.PBKDF2_ITERATIONS + " 轮 + 随机盐）", "SECURITY_UPGRADE");
                } catch (Exception e) {
                    LogManager.log("密码散列升级失败（不影响本次验证）: " + e, "SECURITY_UPGRADE");
                }
            }
            warnIfStillDefault(inputPassword);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** 只读检查：当前是否仍是源码内置的默认密码（用于设置窗安全提示；不触发升级/写入）。 */
    public static boolean isUsingBuiltinDefaultPassword() {
        String stored = ConfigManager.getPasswordHash();
        if (stored == null || stored.isEmpty()) {
            return true;
        }
        return EncryptionUtil.verifyPassword(BUILTIN_DEFAULT_PASSWORD, stored);
    }

    /** 仍在使用源码里写死的默认密码时提醒一次（安全提示，不阻断）。 */
    private static void warnIfStillDefault(String inputPassword) {
        if (warnedDefaultPassword) {
            return;
        }
        try {
            if (inputPassword.equals(BUILTIN_DEFAULT_PASSWORD)) {
                warnedDefaultPassword = true;
                LogManager.log("安全提示：当前仍在使用内置默认密码（该密码写在源码里，等于未设防），建议在“设置 → 修改密码”中更换",
                        "SECURITY_WARN");
            }
        } catch (Throwable ignore) {
            // 忽略
        }
    }

    public static boolean changePassword(String oldPassword, String newPassword) {
        try {
            sanitizeInput(oldPassword);
            sanitizeInput(newPassword);

            if (!verifyPassword(oldPassword)) {
                return false;
            }

            if (newPassword.length() < 4) {
                return false;
            }

            savePasswordConfig(EncryptionUtil.hashPasswordStrong(newPassword));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean changePasswordDirectly(String newPassword) {
        try {
            sanitizeInput(newPassword);

            if (newPassword.length() < 4) {
                return false;
            }

            savePasswordConfig(EncryptionUtil.hashPasswordStrong(newPassword));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void sanitizeInput(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }

        if (input.length() > 100) {
            throw new IllegalArgumentException("密码长度不能超过100字符");
        }
    }

    public static boolean isLocked() {
        return !isUnlocked;
    }

    public static void lock() {
        isUnlocked = false;
    }

    public static void unlock() {
        isUnlocked = true;
    }

    public static boolean handleCtrlL() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCtrlLTime > 2000) {
            ctrlLCount = 0;
        }

        lastCtrlLTime = currentTime;
        ctrlLCount++;

        if (ctrlLCount >= 10) {
            isUnlocked = true;
            ctrlLCount = 0;
            return true;
        }
        return false;
    }

    public static void resetCtrlLCount() {
        ctrlLCount = 0;
    }
}
