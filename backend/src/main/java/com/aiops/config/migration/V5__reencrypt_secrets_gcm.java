package com.aiops.config.migration;

import com.aiops.common.util.AesUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Flyway V5：存量密文 AES/ECB → AES/GCM 重加密（Spring Boot 会把 JavaMigration bean 交给 Flyway 执行）。
 * <p>
 * 涉及 llm_provider.api_key、es_datasource.password_enc / api_key、sys_mail_config.password_enc，
 * 含逻辑删除的行。只处理无 "v2:" 前缀的旧密文，用当前 aiops.security.aes-key 解密后重新加密，与迁移同一事务。
 * 任何一条解不开（例如密钥已更换）即整体失败回滚，不会留下新旧混杂的数据——
 * 此时需先用原密钥启动一次完成迁移，再更换密钥。
 */
@Component
public class V5__reencrypt_secrets_gcm extends BaseJavaMigration {

    private static final String[][] COLUMNS = {
            {"llm_provider", "api_key"},
            {"es_datasource", "password_enc"},
            {"es_datasource", "api_key"},
            {"sys_mail_config", "password_enc"},
    };

    private final String aesKey;

    public V5__reencrypt_secrets_gcm(@Value("${aiops.security.aes-key}") String aesKey) {
        this.aesKey = aesKey;
    }

    @Override
    public void migrate(Context context) throws Exception {
        AesUtil.checkKey(aesKey);
        Connection conn = context.getConnection();
        for (String[] tc : COLUMNS) {
            reencrypt(conn, tc[0], tc[1]);
        }
    }

    private void reencrypt(Connection conn, String table, String column) throws Exception {
        List<Long> ids = new ArrayList<>();
        List<String> values = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, " + column + " FROM " + table
                        + " WHERE " + column + " IS NOT NULL AND " + column + " <> ''");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (AesUtil.isLegacy(rs.getString(2))) {
                    ids.add(rs.getLong(1));
                    values.add(rs.getString(2));
                }
            }
        }
        try (PreparedStatement up = conn.prepareStatement(
                "UPDATE " + table + " SET " + column + " = ? WHERE id = ?")) {
            for (int i = 0; i < ids.size(); i++) {
                String plain;
                try {
                    plain = AesUtil.decrypt(values.get(i), aesKey);
                } catch (IllegalStateException e) {
                    throw new IllegalStateException(table + "." + column + " id=" + ids.get(i)
                            + " 无法用当前 aes-key 解密，迁移中止（密钥是否已更换？）", e);
                }
                up.setString(1, AesUtil.encrypt(plain, aesKey));
                up.setLong(2, ids.get(i));
                up.addBatch();
            }
            up.executeBatch();
        }
    }
}
