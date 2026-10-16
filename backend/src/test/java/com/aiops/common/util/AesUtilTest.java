package com.aiops.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AES 加解密单测（M1 验收项；V5 起为 GCM，兼容读取早期 ECB 密文）
 */
class AesUtilTest {

    private static final String KEY = "16bytes-1234abcd"; // 16 字节

    /** 早期 AES/ECB 实现对 "sk-it-provider-secret" 的加密结果（改造前代码生成，作为兼容性测试向量） */
    private static final String LEGACY_ECB_CIPHER = "dgZ49zJ/so667cy2U5VEH/D/ZqsAyIIxX2xuZVevPBc=";

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
        assertThrows(IllegalStateException.class, () -> AesUtil.decrypt(cipher, "wrong-key-123456"));
    }

    @Test
    void 同一明文两次加密结果不同() {
        String a = AesUtil.encrypt("same", KEY);
        String b = AesUtil.encrypt("same", KEY);
        assertNotEquals(a, b, "ECB 下相同明文得到相同密文，会泄露'两处用的是同一个密钥'");
        assertTrue(a.startsWith("v2:"));
        assertFalse(AesUtil.isLegacy(a));
    }

    @Test
    void 密文被篡改时解密失败() {
        String c = AesUtil.encrypt("secret", KEY);
        char[] chars = c.toCharArray();
        int i = chars.length - 5;
        chars[i] = chars[i] == 'A' ? 'B' : 'A';
        assertThrows(IllegalStateException.class, () -> AesUtil.decrypt(new String(chars), KEY));
    }

    @Test
    void 仍能读取早期ECB密文() {
        assertTrue(AesUtil.isLegacy(LEGACY_ECB_CIPHER));
        assertEquals("sk-it-provider-secret", AesUtil.decrypt(LEGACY_ECB_CIPHER, KEY));
    }

    @Test
    void 密钥长度不合法时明确报错() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AesUtil.checkKey("short"));
        assertTrue(e.getMessage().contains("16/24/32"));
        AesUtil.checkKey("0123456789abcdef0123456789abcdef");
    }
}
