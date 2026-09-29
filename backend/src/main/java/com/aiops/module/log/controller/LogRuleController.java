package com.aiops.module.log.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.log.entity.LogDetectRule;
import com.aiops.module.log.mapper.LogDetectRuleMapper;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "日志检测规则")
@RestController
@RequestMapping("/api/log/rule")
@RequiredArgsConstructor
public class LogRuleController {

    private final LogDetectRuleMapper logDetectRuleMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Operation(summary = "分页列表")
    @RequirePerm("log:search:list")
    @GetMapping("/page")
    public Result<Page<LogDetectRule>> page(@RequestParam(defaultValue = "1") long current,
                                            @RequestParam(defaultValue = "20") long size,
                                            @RequestParam(required = false) Integer enabled) {
        return Result.ok(logDetectRuleMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<LogDetectRule>()
                        .eq(enabled != null, LogDetectRule::getEnabled, enabled)
                        .orderByDesc(LogDetectRule::getId)));
    }

    @Operation(summary = "新增")
    @RequirePerm("log:rule:update")
    @PostMapping
    public Result<Void> add(@RequestBody LogDetectRule rule) {
        validate(rule);
        rule.setId(null);
        if (rule.getEnabled() == null) rule.setEnabled(1);
        if (rule.getLevel() == null) rule.setLevel("WARN");
        logDetectRuleMapper.insert(rule);
        log.info("[LogRule] add id={} name={} type={}", rule.getId(), rule.getName(), rule.getRuleType());
        return Result.ok();
    }

    @Operation(summary = "修改")
    @RequirePerm("log:rule:update")
    @PutMapping
    public Result<Void> update(@RequestBody LogDetectRule rule) {
        if (rule.getId() == null) {
            throw new BizException("id 缺失");
        }
        validate(rule);
        logDetectRuleMapper.updateById(rule);
        return Result.ok();
    }

    @Operation(summary = "删除")
    @RequirePerm("log:rule:update")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        logDetectRuleMapper.deleteById(id);
        return Result.ok();
    }

    @Operation(summary = "启停")
    @RequirePerm("log:rule:update")
    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam Integer enabled) {
        LogDetectRule r = new LogDetectRule();
        r.setId(id);
        r.setEnabled(enabled);
        logDetectRuleMapper.updateById(r);
        return Result.ok();
    }

    private void validate(LogDetectRule rule) {
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new BizException("规则名必填");
        }
        if (rule.getRuleType() == null || rule.getRuleType().isBlank()) {
            throw new BizException("rule_type 必填");
        }
        if (!"new_template|rare_template|spike|error_rate".contains(rule.getRuleType())) {
            throw new BizException("rule_type 仅允许 new_template|rare_template|spike|error_rate");
        }
        if (rule.getDatasourceId() == null) rule.setDatasourceId(1L);
        if (rule.getIndexConfigId() == null) rule.setIndexConfigId(1L);
        if (rule.getParams() != null && !rule.getParams().isBlank()) {
            try {
                objectMapper.readTree(rule.getParams());
            } catch (Exception e) {
                throw new BizException("params 不是合法 JSON：" + e.getMessage());
            }
        }
    }
}
