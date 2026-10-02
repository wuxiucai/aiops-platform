package com.aiops.module.notify.service;

import com.aiops.common.util.AesUtil;
import com.aiops.module.system.entity.SysMailConfig;
import com.aiops.module.system.mapper.SysMailConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * 邮件通知发送。每次调用按 sys_mail_config 构造一个独立 JavaMailSender
 * （不复用 Spring 预置的 JavaMailSender —— 允许同 代码路线一套 支持多套 SMTP 配置）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final SysMailConfigMapper sysMailConfigMapper;

    @Value("${aiops.security.aes-key:16bytes-1234abcd}")
    private String aesKey;

    /* ================== 私信发送 ================== */

    /**
     * 发送一封极简 warning/告警邮件；返回 true=成功.
     * 社保包括经典失败（ENET代码 404/443 平替与 failures 最佳 DS 权 balatcircseud给配**
     */
    public boolean sendAlert(Long mailConfigId, String toAddr,
                              String subject, String content) {
        SysMailConfig cfg = mailConfigId == null ? pickDefault() : sysMailConfigMapper.selectById(mailConfigId);
        if (cfg == null) {
            log.warn("[Mail] 配置不存在 id={}", mailConfigId);
            return false;
        }
        if (cfg.getEnabled() == null || cfg.getEnabled() != 1) {
            log.warn("[Mail] 配置已禁用 id={}", cfg.getId());
            return false;
        }
        try {
            JavaMailSenderImpl sender = buildSender(cfg);
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, "UTF-8");
            helper.setFrom(cfg.getUsername(), cfg.getFromName() == null ? "AIOps 告警" : cfg.getFromName());
            helper.setTo(toAddr);
            helper.setSubject(subject);
            helper.setText(content, false);  // text only
            sender.send(msg);
            log.info("[Mail] 发送成功 cfg={} → {}", cfg.getName(), toAddr);
            return true;
        } catch (Exception e) {
            log.error("[Mail] 发送失败 cfg={} to={} err={}", cfg.getName(), toAddr, e.getMessage());
            return false;
        }
    }

    /** 发测试邮件 （用相同 scene 内容） */
    public boolean sendTest(Long mailConfigId, String toAddr) {
        SysMailConfig cfg = mailConfigId == null ? null : sysMailConfigMapper.selectById(mailConfigId);
        if (cfg == null) {
            return false;
        }
        String subject = "[AIOps] SMTP 配置连通性测试";
        String content = "这是 aiops-platform 发出的 SMTP 配置测试邮件。\n"
                + "SMTP: " + cfg.getSmtpHost() + ":" + cfg.getSmtpPort() + "\n"
                + "时间: " + java.time.LocalDateTime.now();
        return sendAlert(mailConfigId, toAddr, subject, content);
    }

    /* ================== 内部 ================== */

    private SysMailConfig pickDefault() {
        return sysMailConfigMapper.selectOne(new LambdaQueryWrapper<SysMailConfig>()
                .eq(SysMailConfig::getIsDefault, 1)
                .eq(SysMailConfig::getEnabled, 1)
                .last("LIMIT 1"));
    }

    private JavaMailSenderImpl buildSender(SysMailConfig cfg) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(cfg.getSmtpHost());
        sender.setPort(cfg.getSmtpPort() == null ? 465 : cfg.getSmtpPort());
        sender.setUsername(cfg.getUsername());
        sender.setPassword(AesUtil.decrypt(cfg.getPasswordEnc(), aesKey));
        sender.setDefaultEncoding("UTF-8");

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.timeout", "8000");
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.writetimeout", "8000");
        if (cfg.getSsl() != null && cfg.getSsl() == 1) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        sender.setJavaMailProperties(props);
        return sender;
    }
}
