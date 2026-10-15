package com.aiops.module.system.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.notify.service.MailService;
import com.aiops.module.system.dto.MailTestRequest;
import com.aiops.module.system.entity.SysMailConfig;
import com.aiops.module.system.service.MailConfigService;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件 SMTP 配置管理。实现见 {@link MailConfigService}。
 * - 密钥字段 password_enc 不返回明文，列表接口回显脱敏
 * - is_default 互斥 （同时只能一个）
 */
@Tag(name = "邮件 SMTP 配置")
@RestController
@RequestMapping("/api/system/mail-config")
@RequiredArgsConstructor
public class MailConfigController {

    private final MailConfigService mailConfigService;
    private final MailService mailService;

    @Operation(summary = "列表（password_enc 脱敏）")
    @RequirePerm("system:mail:list")
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(mailConfigService.list());
    }

    @Operation(summary = "新增")
    @RequirePerm("system:mail:add")
    @PostMapping
    public Result<SysMailConfig> add(@RequestBody SysMailConfig req) {
        return Result.ok(mailConfigService.add(req));
    }

    @Operation(summary = "修改")
    @RequirePerm("system:mail:update")
    @PutMapping
    public Result<SysMailConfig> update(@RequestBody SysMailConfig req) {
        return Result.ok(mailConfigService.update(req));
    }

    @Operation(summary = "删除")
    @RequirePerm("system:mail:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        mailConfigService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "设为默认 （互斥）")
    @RequirePerm("system:mail:update")
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        mailConfigService.setDefault(id);
        return Result.ok();
    }

    @Operation(summary = "向指定邮箱发测试邮件")
    @RequirePerm("system:mail:list")
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> testSend(@PathVariable Long id, @RequestBody MailTestRequest body) {
        if (body.getTo() == null || body.getTo().isBlank()) {
            throw new BizException("to 接收邮箱必填");
        }
        SysMailConfig cfg = mailConfigService.get(id);
        boolean ok = mailService.sendTest(id, body.getTo());
        Map<String, Object> out = new HashMap<>();
        out.put("success", ok);
        out.put("to", body.getTo());
        out.put("subject", "[AIOps] SMTP 配置连通性测试");
        out.put("smtp", cfg.getSmtpHost() + ":" + cfg.getSmtpPort());
        return Result.ok(out);
    }
}
