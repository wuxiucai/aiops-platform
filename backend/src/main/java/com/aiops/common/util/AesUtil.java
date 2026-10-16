package com.aiops.common.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES 加解密工具，密钥来自配置 aiops.security.aes-key（16 / 24 / 32 字节）。
 * 用于 llm_provider.api_key、es_datasource.password_enc/api_key、sys_mail_config.password_enc 落库前加密。
 * <p>
 * 密文格式 {@code v2:Base64(iv[12] || 密文 || tag[16])}，AES/GCM：每次随机 IV，同一明文两次加密结果不同，
 * 并带完整性校验（密文被改动或密钥不对时解密直接失败）。
 * 早期版本用 AES/ECB（相同明文得到相同密文、无完整性校验），无前缀；{@link #decrypt} 仍可读取，
 * 存量数据由迁移 V5 统一重加密。
 */
public final class AesUtil {

    private static final String GCM = "AES/GCM/NoPadding";
    private static final String LEGACY_ECB = "AES/ECB/PKCS5Padding";
    private static final String V2_PREFIX = "v2:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesUtil() {
    }

    public static String encrypt(String plain, String key) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(GCM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(key), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return V2_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES 加密失败", e);
        }
    }

    public static String decrypt(String cipherText, String key) {
        try {
            if (!isLegacy(cipherText)) {
                byte[] raw = Base64.getDecoder().decode(cipherText.substring(V2_PREFIX.length()));
                Cipher cipher = Cipher.getInstance(GCM);
                cipher.init(Cipher.DECRYPT_MODE, keySpec(key), new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
                return new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
            }
            Cipher cipher = Cipher.getInstance(LEGACY_ECB);
            cipher.init(Cipher.DECRYPT_MODE, keySpec(key));
            return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES 解密失败", e);
        }
    }

    /** 是否为早期 ECB 格式的密文（需重加密） */
    public static boolean isLegacy(String cipherText) {
        return cipherText != null && !cipherText.startsWith(V2_PREFIX);
    }

    /** 密钥须为 16 / 24 / 32 字节（AES-128 / 192 / 256） */
    public static void checkKey(String key) {
        int n = key == null ? 0 : key.getBytes(StandardCharsets.UTF_8).length;
        if (n != 16 && n != 24 && n != 32) {
            throw new IllegalStateException("aiops.security.aes-key 须为 16/24/32 字节，当前 " + n + " 字节");
        }
    }

    /** 脱敏：返回前4位+****，任何接口响应体不得出现明文 api_key */
    public static String mask(String plain) {
        if (plain == null || plain.length() <= 4) {
            return "****";
        }
        return plain.substring(0, 4) + "****";
    }

    private static SecretKeySpec keySpec(String key) {
        checkKey(key);
        return new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
    }
}
