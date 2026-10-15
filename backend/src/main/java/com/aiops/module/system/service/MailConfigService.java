package com.aiops.module.system.service;

import com.aiops.common.BizException;
import com.aiops.common.util.AesUtil;
import com.aiops.module.system.entity.SysMailConfig;
import com.aiops.module.system.mapper.SysMailConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件 SMTP 配置。授权码 AES 加密存储、列表脱敏；is_default 全表互斥。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailConfigService {

    /** 前端回显的脱敏占位，提交回来表示"不改密码" */
    static final String MASK = "****";

    private final SysMailConfigMapper sysMailConfigMapper;

    @Value("${aiops.security.aes-key:16bytes-1234abcd}")
    private String aesKey;

    public List<Map<String, Object>> list() {
        List<SysMailConfig> rows = sysMailConfigMapper.selectList(new LambdaQueryWrapper<SysMailConfig>()
                .orderByDesc(SysMailConfig::getIsDefault)
                .orderByDesc(SysMailConfig::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysMailConfig c : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("smtpHost", c.getSmtpHost());
            m.put("smtpPort", c.getSmtpPort());
            m.put("username", c.getUsername());
            m.put("passwordEnc", c.getPasswordEnc() == null ? null : MASK);  // 明文不回
            m.put("fromName", c.getFromName());
            m.put("ssl", c.getSsl());
            m.put("enabled", c.getEnabled());
            m.put("isDefault", c.getIsDefault());
            m.put("remark", c.getRemark());
            m.put("createTime", c.getCreateTime());
            m.put("updateTime", c.getUpdateTime());
            out.add(m);
        }
        return out;
    }

    public SysMailConfig get(Long id) {
        SysMailConfig cfg = sysMailConfigMapper.selectById(id);
        if (cfg == null) throw new BizException("配置不存在");
        return cfg;
    }

    public SysMailConfig add(SysMailConfig req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException("name 必填");
        }
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            throw new BizException("username （邮箱账号） 必填");
        }
        if (req.getPasswordEnc() == null || req.getPasswordEnc().isBlank()) {
            throw new BizException("passwordEnc （授权码） 必填");
        }
        req.setPasswordEnc(AesUtil.encrypt(req.getPasswordEnc(), aesKey));
        if (req.getSmtpPort() == null) req.setSmtpPort(465);
        if (req.getSsl() == null) req.setSsl(1);
        if (req.getEnabled() == null) req.setEnabled(1);
        if (req.getIsDefault() == null) req.setIsDefault(0);
        req.setDeleted(0);
        sysMailConfigMapper.insert(req);
        log.info("[MailCfg] 新增 id={} name={}", req.getId(), req.getName());
        return req;
    }

    /** 密码留空或为脱敏占位时沿用旧值 */
    public SysMailConfig update(SysMailConfig req) {
        if (req.getId() == null) throw new BizException("id 必填");
        SysMailConfig old = get(req.getId());
        String pwd = req.getPasswordEnc();
        if (pwd == null || pwd.isBlank() || MASK.equals(pwd)) {
            req.setPasswordEnc(old.getPasswordEnc());
        } else {
            req.setPasswordEnc(AesUtil.encrypt(pwd, aesKey));
        }
        req.setUpdateTime(LocalDateTime.now());
        sysMailConfigMapper.updateById(req);
        return req;
    }

    public void delete(Long id) {
        sysMailConfigMapper.deleteById(id);
    }

    /** 设为默认：先清其他默认再置位，同一事务，中途失败不会出现"没有默认" */
    @Transactional
    public void setDefault(Long id) {
        get(id);
        sysMailConfigMapper.update(null, new LambdaUpdateWrapper<SysMailConfig>()
                .eq(SysMailConfig::getIsDefault, 1)
                .set(SysMailConfig::getIsDefault, 0));
        SysMailConfig upd = new SysMailConfig();
        upd.setId(id);
        upd.setIsDefault(1);
        upd.setUpdateTime(LocalDateTime.now());
        sysMailConfigMapper.updateById(upd);
    }
}
