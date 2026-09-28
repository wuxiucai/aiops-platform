package com.aiops.common.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES 加解密工具：AES/ECB/PKCS5Padding，密钥来自配置 aiops.security.aes-key（16字节）。
 * 用于 llm_provider.api_key 与 es_datasource.password_enc/api_key 落库前加密。
 */
public final class AesUtil {

    private static final String ALGORITHM = "AES/ECB/PKCS5Padding";

    private AesUtil() {
    }

    public static String encrypt(String plain, String key) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("AES 加密失败", e);
        }
    }

    public static String decrypt(String cipherText, String key) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
            return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES 解密失败", e);
        }
    }

    /** 脱敏：返回前4位+****，任何接口响应体不得出现明文 api_key */
    public static String mask(String plain) {
        if (plain == null || plain.length() <= 4) {
            return "****";
        }
        return plain.substring(0, 4) + "****";
    }
}
