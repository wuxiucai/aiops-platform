package com.aiops.config.migration;

import com.aiops.common.util.AesUtil;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V5 重加密在真实 MySQL 上的行为：旧 ECB 密文 → GCM，明文不变；密钥不对时整体回滚。
 * 直接调用迁移的 migrate()，不依赖 Flyway 版本状态，可重复执行。
 */
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${it.db.url}",
        "aiops.scheduling.enabled=false"
})
class SecretsReencryptIT {

    private static final String KEY = "16bytes-1234abcd";
    /** 改造前 ECB 实现生成的密文（与 AesUtilTest 同一组测试向量） */
    private static final String ECB_PROVIDER = "dgZ49zJ/so667cy2U5VEH/D/ZqsAyIIxX2xuZVevPBc=";   // sk-it-provider-secret
    private static final String ECB_ES = "t4wSIHMtMvyYC8k5IA9lVw==";                         // es-it-password
    private static final String ECB_SMTP = "AlbLi9+7erSc4Va0NQahAQ4iOG74w0dAcBUVTZThGTQ=";    // smtp-it-authcode

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;

    @BeforeEach
    void seed() {
        String db = jdbc.queryForObject("SELECT DATABASE()", String.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(db != null && db.endsWith("_it"), "非测试库，拒绝执行：" + db);
        clean();
        jdbc.update("INSERT INTO llm_provider(id,name,provider_type,base_url,api_key,model_name,deleted) "
                + "VALUES (9901,'it-p','openai','http://x',?,'m',0),(9902,'it-p-del','openai','http://x',?,'m',1)",
                ECB_PROVIDER, ECB_PROVIDER);
        jdbc.update("INSERT INTO es_datasource(id,name,es_host,password_enc,deleted) VALUES (9901,'it-es','h',?,0)", ECB_ES);
        jdbc.update("INSERT INTO sys_mail_config(id,name,smtp_host,username,password_enc,deleted) "
                + "VALUES (9901,'it-smtp','h','u',?,0)", ECB_SMTP);
    }

    @AfterEach
    void clean() {
        String db = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (db == null || !db.endsWith("_it")) {
            return;
        }
        jdbc.update("DELETE FROM llm_provider WHERE id IN (9901,9902)");
        jdbc.update("DELETE FROM es_datasource WHERE id=9901");
        jdbc.update("DELETE FROM sys_mail_config WHERE id=9901");
    }

    @Test
    void 旧密文重加密为GCM_明文不变_含逻辑删除行() throws Exception {
        String alreadyV2 = AesUtil.encrypt("already-v2", KEY);
        jdbc.update("UPDATE es_datasource SET api_key=? WHERE id=9901", alreadyV2);

        runMigration(KEY);

        assertDecryptsTo("SELECT api_key FROM llm_provider WHERE id=9901", "sk-it-provider-secret");
        assertDecryptsTo("SELECT api_key FROM llm_provider WHERE id=9902", "sk-it-provider-secret");
        assertDecryptsTo("SELECT password_enc FROM es_datasource WHERE id=9901", "es-it-password");
        assertDecryptsTo("SELECT password_enc FROM sys_mail_config WHERE id=9901", "smtp-it-authcode");
        assertEquals(alreadyV2, one("SELECT api_key FROM es_datasource WHERE id=9901"), "已是新格式的不重复加密");
        // 两行相同明文，重加密后密文不同（随机 IV）
        assertNotEquals(one("SELECT api_key FROM llm_provider WHERE id=9901"),
                one("SELECT api_key FROM llm_provider WHERE id=9902"));

        runMigration(KEY);   // 再跑一次无变化（幂等）
        assertDecryptsTo("SELECT api_key FROM llm_provider WHERE id=9901", "sk-it-provider-secret");
    }

    @Test
    void 密钥不对时整体回滚_不留半截数据() throws Exception {
        assertThrows(IllegalStateException.class, () -> runMigration("wrong-key-123456"));
        assertEquals(ECB_PROVIDER, one("SELECT api_key FROM llm_provider WHERE id=9901"));
        assertEquals(ECB_ES, one("SELECT password_enc FROM es_datasource WHERE id=9901"));
        assertEquals(ECB_SMTP, one("SELECT password_enc FROM sys_mail_config WHERE id=9901"));
    }

    /** 与 Flyway 一样在单个事务里执行：失败回滚 */
    private void runMigration(String key) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            Context ctx = mock(Context.class);
            when(ctx.getConnection()).thenReturn(conn);
            try {
                new V5__reencrypt_secrets_gcm(key).migrate(ctx);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private void assertDecryptsTo(String sql, String plain) {
        String c = one(sql);
        assertTrue(c.startsWith("v2:"), "应为新格式: " + c);
        assertFalse(AesUtil.isLegacy(c));
        assertEquals(plain, AesUtil.decrypt(c, KEY));
    }

    private String one(String sql) {
        List<String> r = jdbc.queryForList(sql, String.class);
        return r.isEmpty() ? null : r.get(0);
    }
}
