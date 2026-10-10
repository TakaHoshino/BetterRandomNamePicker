package com.randomnamepicker.core;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class EncryptionUtil {
    private static final String ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    
    // AES加密相关常量
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String SALT = "R@nd0mN@m3P!ck3r_S@lt_2026";

    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance(ALGORITHM);
        keyPairGen.initialize(KEY_SIZE);
        return keyPairGen.generateKeyPair();
    }

    public static String encrypt(String data, String publicKeyStr) throws Exception {
        byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyStr);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
        PublicKey publicKey = keyFactory.generatePublic(keySpec);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedBytes = cipher.doFinal(data.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    public static String decrypt(String encryptedData, String privateKeyStr) throws Exception {
        byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyStr);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedData));
        return new String(decryptedBytes, "UTF-8");
    }

    public static String getPublicKeyString(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public static String getPrivateKeyString(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    /**
     * 强密码哈希（2026-10）：**PBKDF2-HMAC-SHA256 + 每密码随机盐 + {@value #PBKDF2_ITERATIONS} 轮**。
     * 存储格式自描述：{@code pbkdf2-sha256$<迭代数>$<盐 Base64>$<派生值 Base64>}，便于日后提高轮数后仍能验证旧值。
     * <p>对比旧格式（无盐单轮 SHA-256，本机实测约 19 万次/秒、弱密码可离线秒破），
     * PBKDF2 把每次尝试的成本抬到几十毫秒量级，使暴力/字典破解不再可行。</p>
     */
    public static final String PBKDF2_PREFIX = "pbkdf2-sha256";
    public static final int PBKDF2_ITERATIONS = 210000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int PBKDF2_SALT_BYTES = 16;

    /** 旧格式（无盐 SHA-256，Base64 44 字符）——只用于**兼容验证**，不再用于写入。 */
    public static String hashPassword(String password) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(password.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(hash);
    }

    /** 写入用：PBKDF2-HMAC-SHA256（随机盐）。 */
    public static String hashPasswordStrong(String password) throws Exception {
        byte[] salt = new byte[PBKDF2_SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, PBKDF2_ITERATIONS);
        return PBKDF2_PREFIX + "$" + PBKDF2_ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    /**
     * 统一验证：自动识别新格式（PBKDF2）与旧格式（无盐 SHA-256）。
     * 比较使用**恒定时间**实现，避免通过响应时间侧信道推断哈希前缀。
     */
    public static boolean verifyPassword(String password, String stored) {
        if (password == null || stored == null || stored.isEmpty()) {
            return false;
        }
        try {
            if (stored.startsWith(PBKDF2_PREFIX + "$")) {
                String[] parts = stored.split("\\$");
                if (parts.length != 4) {
                    return false;
                }
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                byte[] derived = pbkdf2(password, salt, iterations);
                return constantTimeEquals(expected, derived);
            }
            byte[] expected = Base64.getDecoder().decode(stored);
            byte[] derived = MessageDigest.getInstance("SHA-256")
                    .digest(password.getBytes("UTF-8"));
            return constantTimeEquals(expected, derived);
        } catch (Exception e) {
            return false;
        }
    }

    /** 该存储值是否需要升级（旧格式，或轮数低于当前标准）。 */
    public static boolean needsUpgrade(String stored) {
        if (stored == null || !stored.startsWith(PBKDF2_PREFIX + "$")) {
            return true;
        }
        try {
            String[] parts = stored.split("\\$");
            return parts.length != 4 || Integer.parseInt(parts[1]) < PBKDF2_ITERATIONS;
        } catch (Exception e) {
            return true;
        }
    }

    /** 该存储值是否仍是无盐单轮 SHA-256（旧格式）。 */
    public static boolean isLegacyHash(String stored) {
        return stored != null && !stored.startsWith(PBKDF2_PREFIX + "$");
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, PBKDF2_KEY_BITS);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
    
    public static String encryptData(String data, String salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(salt.toCharArray(), SALT.getBytes(), 100000, 128);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKey secretKey = new SecretKeySpec(tmp.getEncoded(), "AES");
        
        SecureRandom random = new SecureRandom();
        byte[] iv = new byte[GCM_IV_LENGTH];
        random.nextBytes(iv);
        
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);
        
        byte[] encryptedBytes = cipher.doFinal(data.getBytes("UTF-8"));
        
        byte[] result = new byte[iv.length + encryptedBytes.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encryptedBytes, 0, result, iv.length, encryptedBytes.length);
        
        return Base64.getEncoder().encodeToString(result);
    }
    
    public static String decryptData(String encryptedData, String salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(salt.toCharArray(), SALT.getBytes(), 100000, 128);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKey secretKey = new SecretKeySpec(tmp.getEncoded(), "AES");
        
        byte[] decodedData = Base64.getDecoder().decode(encryptedData);
        
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(decodedData, 0, iv, 0, iv.length);
        
        byte[] encryptedBytes = new byte[decodedData.length - iv.length];
        System.arraycopy(decodedData, iv.length, encryptedBytes, 0, encryptedBytes.length);
        
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);
        
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
        return new String(decryptedBytes, "UTF-8");
    }
    
    public static String calculateHash(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(hash);
    }
}
