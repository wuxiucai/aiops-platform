package com.aiops.config;

import com.aiops.common.util.AesUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时检查密钥配置。
 * <ul>
 *   <li>aes-key 长度不合法、jwt-secret 不足 32 字节（HS256 要求）：直接启动失败；</li>
 *   <li>仍在用 application.yml 里的演示默认值：
 *       aiops.security.require-custom-secrets=true（生产）时启动失败，否则只打 WARN（本地开发 / 答辩演示）。</li>
 * </ul>
 * 注意：aes-key 一经使用不能随意更换，库里的密文要用同一个密钥才能解开。
 */
@Slf4j
@Component
public class SecretsChecker {

    /** application.yml 中的演示默认值，生产环境必须通过环境变量覆盖 */
    static final String DEMO_JWT_SECRET = "aiops-platform-dev-secret-key-change-me-in-prod-2027-thesis-0123456789-abcdef";
    static final String DEMO_AES_KEY = "16bytes-1234abcd";
    static final String DEMO_DB_PASSWORD = "123456";

    private final String jwtSecret;
    private final String aesKey;
    private final String dbPassword;
    private final boolean requireCustom;

    public SecretsChecker(@Value("${aiops.security.jwt-secret}") String jwtSecret,
                          @Value("${aiops.security.aes-key}") String aesKey,
                          @Value("${spring.datasource.password:}") String dbPassword,
                          @Value("${aiops.security.require-custom-secrets:false}") boolean requireCustom) {
        this.jwtSecret = jwtSecret;
        this.aesKey = aesKey;
        this.dbPassword = dbPassword;
        this.requireCustom = requireCustom;
        check();
    }

    /** 构造时即校验：配置错误应在任何 Bean 用到密钥之前暴露 */
    final void check() {
        AesUtil.checkKey(aesKey);
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("aiops.security.jwt-secret 至少 32 字节（HS256）");
        }
        List<String> demo = demoDefaultsInUse();
        if (!demo.isEmpty() && requireCustom) {
            throw new IllegalStateException("生产模式下以下配置仍为演示默认值，请通过环境变量设置：" + String.join(", ", demo));
        }
    }

    List<String> demoDefaultsInUse() {
        List<String> out = new ArrayList<>();
        if (DEMO_JWT_SECRET.equals(jwtSecret)) out.add("JWT_SECRET");
        if (DEMO_AES_KEY.equals(aesKey)) out.add("AES_KEY");
        if (DEMO_DB_PASSWORD.equals(dbPassword)) out.add("MYSQL_PWD");
        return out;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnIfDemoDefaults() {
        List<String> demo = demoDefaultsInUse();
        if (!demo.isEmpty()) {
            log.warn("[Security] 以下配置仍为演示默认值，仅适用于本地开发 / 演示：{}。"
                    + "部署时请通过环境变量设置，并加 AIOPS_REQUIRE_CUSTOM_SECRETS=true 强制校验", demo);
        }
    }
}
