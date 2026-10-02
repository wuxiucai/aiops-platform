package com.aiops.module.system.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.common.util.AesUtil;
import com.aiops.module.notify.service.MailService;
import com.aiops.module.system.entity.SysMailConfig;
import com.aiops.module.system.mapper.SysMailConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件 SMTP 配置管理。
 * - 密钥字段 password_enc 不返回明文，列表接口回显脱敏
 * - is_default 互斥 （同时只能一个）
 */
@Slf4j
@Tag(name = "邮件 SMTP 配置")
@RestController
@RequestMapping("/api/system/mail-config")
@RequiredArgsConstructor
public class MailConfigController {

    private final SysMailConfigMapper sysMailConfigMapper;
    private final MailService mailService;

    @org.springframework.beans.factory.annotation.Value("${aiops.security.aes-key:16bytes-1234abcd}")
    private String aesKey;

    /* ================== 列表 ================== */

    @Operation(summary = "列表（password_enc 脱敏）")
    @com.aiops.security.RequirePerm("system:mail:list")
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        List<SysMailConfig> rows = sysMailConfigMapper.selectList(
                new LambdaQueryWrapper<SysMailConfig>()
                        .orderByDesc(SysMailConfig::getIsDefault)
                        .orderByDesc(SysMailConfig::getId));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (SysMailConfig c : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("smtpHost", c.getSmtpHost());
            m.put("smtpPort", c.getSmtpPort());
            m.put("username", c.getUsername());
            m.put("passwordEnc", c.getPasswordEnc() == null ? null : "****");  // 明文不回
            m.put("fromName", c.getFromName());
            m.put("ssl", c.getSsl());
            m.put("enabled", c.getEnabled());
            m.put("isDefault", c.getIsDefault());
            m.put("remark", c.getRemark());
            m.put("createTime", c.getCreateTime());
            m.put("updateTime", c.getUpdateTime());
            out.add(m);
        }
        return Result.ok(out);
    }

    /* ================== 新增 ================== */

    @Operation(summary = "新增")
    @com.aiops.security.RequirePerm("system:mail:add")
    @PostMapping
    public Result<SysMailConfig> add(@RequestBody SysMailConfig req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException("name 必填");
        }
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            throw new BizException("username （邮箱账号） 必填");
        }
        if (req.getPasswordEnc() == null || req.getPasswordEnc().isBlank()) {
            throw new BizException("passwordEnc （授权码） 必填");
        }
        // 加密存储
        req.setPasswordEnc(AesUtil.encrypt(req.getPasswordEnc(), aesKey));
        if (req.getSmtpPort() == null) req.setSmtpPort(465);
        if (req.getSsl() == null) req.setSsl(1);
        if (req.getEnabled() == null) req.setEnabled(1);
        if (req.getIsDefault() == null) req.setIsDefault(0);
        req.setDeleted(0);
        sysMailConfigMapper.insert(req);
        log.info("[MailCfg] 新增 id={} name={}", req.getId(), req.getName());
        return Result.ok(req);
    }

    /* ================== 修改 ================== */

    @Operation(summary = "修改")
    @com.aiops.security.RequirePerm("system:mail:update")
    @PutMapping
    public Result<SysMailConfig> update(@RequestBody SysMailConfig req) {
        if (req.getId() == null) throw new BizException("id 必填");
        SysMailConfig old = sysMailConfigMapper.selectById(req.getId());
        if (old == null) throw new BizException("配置不存在");
        // 密码未填则沿用旧值
        if (req.getPasswordEnc() == null || req.getPasswordEnc().isBlank() || "****".equals(req.getPasswordEnc())) {
            req.setPasswordEnc(old.getPasswordEnc());
        } else {
            req.setPasswordEnc(AesUtil.encrypt(req.getPasswordEnc(), aesKey));
        }
        req.setUpdateTime(java.time.LocalDateTime.now());
        sysMailConfigMapper.updateById(req);
        return Result.ok(req);
    }

    /* ================== 删除 ================== */

    @Operation(summary = "删除")
    @com.aiops.security.RequirePerm("system:mail:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysMailConfigMapper.deleteById(id);
        return Result.ok();
    }

    /* ================== 设为默认 ================== */

    @Operation(summary = "设为默认 （互斥）")
    @com.aiops.security.RequirePerm("system:mail:update")
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        // 先清默认
        sysMailConfigMapper.selectList(new LambdaQueryWrapper<SysMailConfig>())
                .forEach(c -> {
                    if (c.getIsDefault() != null && c.getIsDefault() == 1) {
                        c.setIsDefault(0);
                        sysMailConfigMapper.updateById(c);
                    }
                });
        // 设置新的默认
        SysMailConfig target = sysMailConfigMapper.selectById(id);
        if (target == null) throw new BizException("配置不存在");
        target.setIsDefault(1);
        target.setUpdateTime(java.time.LocalDateTime.now());
        sysMailConfigMapper.updateById(target);
        return Result.ok();
    }

    /* ================== 测试发送 ================== */

    @Operation(summary = "向指定邮箱发测试邮件")
    @com.aiops.security.RequirePerm("system:mail:list")
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> testSend(@PathVariable Long id,
                                                @RequestBody Map<String, Object> body) {
        Object to = body.get("to");
        if (to == null) {
            throw new BizException("to 接收邮箱必填");
        }
        String recipient = String.valueOf(to);
        SysMailConfig cfg = sysMailConfigMapper.selectById(id);
        if (cfg == null) {
            throw new BizException("配置不存在");
        }
        boolean ok = mailService.sendTest(id, recipient);
        Map<String, Object> out = new HashMap<>();
        out.put("success", ok);
        out.put("to", recipient);
        out.put("subject", "[AIOps] SMTP 配置连通性测试");
        out.put("smtp", cfg.getSmtpHost() + ":" + cfg.getSmtpPort());
        return Result.ok(out);
    }
}
