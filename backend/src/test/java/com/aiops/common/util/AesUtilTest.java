package com.aiops.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AES 加解密单测（M1 验收项）
 */
class AesUtilTest {

    private static final String KEY = "16bytes-1234abcd"; // 16 字节

    @Test
    void encryptDecryptRoundTrip() {
        String plain = "sk-abc123XYZ-中文密钥-789";
        String cipher = AesUtil.encrypt(plain, KEY);
        assertNotEquals(plain, cipher);
        assertEquals(plain, AesUtil.decrypt(cipher, KEY));
    }

    @Test
    void emptyAndSpecialChars() {
        assertEquals("", AesUtil.decrypt(AesUtil.encrypt("", KEY), KEY));
        String special = "!?@#$_&-+AQ/MQ==密";
        assertEquals(special, AesUtil.decrypt(AesUtil.encrypt(special, KEY), KEY));
    }

    @Test
    void maskNeverShowsFullKey() {
        String masked = AesUtil.mask("sk-1234567890");
        assertEquals("sk-1****", masked);
        assertEquals("****", AesUtil.mask("ab"));
        assertEquals("****", AesUtil.mask(null));
    }

    @Test
    void wrongKeyFails() {
        String cipher = AesUtil.encrypt("secret", KEY);
        assertThrows(IllegalStateException.class, () -> AesUtil.decrypt(cipher, "wrong-key-1234567"));
    }
}
