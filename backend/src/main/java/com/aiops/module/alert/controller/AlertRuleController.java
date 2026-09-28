package com.aiops.module.alert.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.alert.entity.AlertRule;
import com.aiops.module.alert.mapper.AlertRuleMapper;
import com.aiops.module.alert.service.AlertRuleService;
import com.aiops.security.RequirePerm;
import com.aiops.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 告警规则管理 + 基线训练/回放
 */
@Slf4j
@Tag(name = "告警规则")
@RestController
@RequestMapping("/api/alert/rule")
@RequiredArgsConstructor
public class AlertRuleController {

    private final AlertRuleMapper alertRuleMapper;
    private final AlertRuleService alertRuleService;

    @Operation(summary = "分页列表")
    @RequirePerm("alert:rule:list")
    @GetMapping("/page")
    public Result<Page<AlertRule>> page(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "10") long size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String ruleType,
                                        @RequestParam(required = false) Integer enabled) {
        return Result.ok(alertRuleMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<AlertRule>()
                        .like(keyword != null && !keyword.isBlank(), AlertRule::getName, keyword)
                        .eq(ruleType != null && !ruleType.isBlank(), AlertRule::getRuleType, ruleType)
                        .eq(enabled != null, AlertRule::getEnabled, enabled)
                        .orderByDesc(AlertRule::getId)));
    }

    @Operation(summary = "详情")
    @RequirePerm("alert:rule:list")
    @GetMapping("/{id}")
    public Result<AlertRule> detail(@PathVariable Long id) {
        return Result.ok(alertRuleMapper.selectById(id));
    }

    @Operation(summary = "新增")
    @RequirePerm("alert:rule:update")
    @PostMapping
    public Result<Void> add(@RequestBody AlertRule rule) {
        validate(rule);
        rule.setId(null);
        if (UserContext.get() != null) {
            rule.setCreator(UserContext.get().getUsername());
        }
        if (rule.getEnabled() == null) rule.setEnabled(1);
        if (rule.getDurationSec() == null) rule.setDurationSec(60);
        if (rule.getLevel() == null) rule.setLevel("WARN");
        alertRuleMapper.insert(rule);
        log.info("[AlertRule] 新增 id={}, name={}", rule.getId(), rule.getName());
        return Result.ok();
    }

    @Operation(summary = "修改")
    @RequirePerm("alert:rule:update")
    @PutMapping
    public Result<Void> update(@RequestBody AlertRule rule) {
        if (rule.getId() == null) {
            throw new BizException("id 缺失");
        }
        validate(rule);
        alertRuleMapper.updateById(rule);
        log.info("[AlertRule] 修改 id={}", rule.getId());
        return Result.ok();
    }

    @Operation(summary = "删除")
    @RequirePerm("alert:rule:update")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        alertRuleService.deleteRule(id);
        log.info("[AlertRule] 删除 id={}", id);
        return Result.ok();
    }

    @Operation(summary = "启停切换")
    @RequirePerm("alert:rule:update")
    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam Integer enabled) {
        alertRuleService.toggle(id, enabled);
        log.info("[AlertRule] 切换 enabled id={}, enabled={}", id, enabled);
        return Result.ok();
    }

    @Operation(summary = "训练基线")
    @RequirePerm("alert:rule:update")
    @PostMapping("/{id}/train")
    public Result<Map<String, Object>> train(@PathVariable Long id) {
        return Result.ok(alertRuleService.trainBaseline(id));
    }

    @Operation(summary = "基线图（某一日 24h 上/下界 + 实际值）")
    @RequirePerm("alert:rule:list")
    @GetMapping("/{id}/baseline-chart")
    public Result<Map<String, Object>> baselineChart(@PathVariable Long id,
                                                     @RequestParam(required = false) Long targetId,
                                                     @RequestParam String date) {
        return Result.ok(alertRuleService.baselineChart(id, targetId, date));
    }

    @Operation(summary = "历史回放 dry-run")
    @RequirePerm("alert:rule:list")
    @PostMapping("/{id}/dry-run")
    public Result<Map<String, Object>> dryRun(@PathVariable Long id,
                                              @RequestBody Map<String, String> body) {
        String s = body.get("startTime");
        String e = body.get("endTime");
        if (s == null || e == null) {
            throw new BizException("startTime / endTime 必填");
        }
        LocalDateTime start = LocalDateTime.parse(s.replace(' ', 'T'));
        LocalDateTime end = LocalDateTime.parse(e.replace(' ', 'T'));
        if (start.isAfter(end)) {
            throw new BizException("startTime 不能晚于 endTime");
        }
        return Result.ok(alertRuleService.dryRun(id, start, end));
    }

    private void validate(AlertRule rule) {
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new BizException("规则名必填");
        }
        if (rule.getMetricKey() == null || rule.getMetricKey().isBlank()) {
            throw new BizException("metric_key 必填");
        }
        if (rule.getRuleType() == null || rule.getRuleType().isBlank()) {
            rule.setRuleType("static");
        }
        if (!"baseline".equalsIgnoreCase(rule.getRuleType()) && rule.getThreshold() == null) {
            throw new BizException("static 规则必须给阈值 threshold");
        }
    }
}
