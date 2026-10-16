package com.aiops.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretsCheckerTest {

    private static final String GOOD_JWT = "x".repeat(48);
    private static final String GOOD_AES = "0123456789abcdef0123456789abcdef";

    @Test
    void 演示默认值_开发模式只告警() {
        SecretsChecker c = assertDoesNotThrow(() -> new SecretsChecker(
                SecretsChecker.DEMO_JWT_SECRET, SecretsChecker.DEMO_AES_KEY, SecretsChecker.DEMO_DB_PASSWORD, false));
        assertEquals(List.of("JWT_SECRET", "AES_KEY", "MYSQL_PWD"), c.demoDefaultsInUse());
    }

    @Test
    void 演示默认值_生产模式拒绝启动() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new SecretsChecker(
                GOOD_JWT, SecretsChecker.DEMO_AES_KEY, "strong-db-pwd", true));
        assertTrue(e.getMessage().contains("AES_KEY"));
    }

    @Test
    void 自定义密钥_生产模式通过() {
        SecretsChecker c = assertDoesNotThrow(() -> new SecretsChecker(GOOD_JWT, GOOD_AES, "strong-db-pwd", true));
        assertTrue(c.demoDefaultsInUse().isEmpty());
    }

    @Test
    void 密钥格式错误_任何模式都拒绝() {
        assertThrows(IllegalStateException.class, () -> new SecretsChecker(GOOD_JWT, "too-short", "p", false));
        assertThrows(IllegalStateException.class, () -> new SecretsChecker("short-jwt", GOOD_AES, "p", false));
    }
}
