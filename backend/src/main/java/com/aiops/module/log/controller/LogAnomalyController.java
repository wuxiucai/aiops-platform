package com.aiops.module.log.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.log.entity.LogAnomaly;
import com.aiops.module.log.mapper.LogAnomalyMapper;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Tag(name = "日志异常")
@RestController
@RequestMapping("/api/log/anomaly")
@RequiredArgsConstructor
public class LogAnomalyController {

    private final LogAnomalyMapper logAnomalyMapper;

    @Operation(summary = "分页列表")
    @RequirePerm("log:search:list")
    @GetMapping("/page")
    public Result<Page<LogAnomaly>> page(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "20") long size,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String anomalyType,
                                         @RequestParam(required = false) Long datasourceId) {
        return Result.ok(logAnomalyMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<LogAnomaly>()
                        .eq(status != null && !status.isBlank(), LogAnomaly::getStatus, status)
                        .eq(anomalyType != null && !anomalyType.isBlank(), LogAnomaly::getAnomalyType, anomalyType)
                        .eq(datasourceId != null, LogAnomaly::getDatasourceId, datasourceId)
                        .orderByDesc(LogAnomaly::getLastTime)));
    }

    @Operation(summary = "详情")
    @RequirePerm("log:search:list")
    @GetMapping("/{id}")
    public Result<LogAnomaly> detail(@PathVariable Long id) {
        return Result.ok(logAnomalyMapper.selectById(id));
    }

    @Operation(summary = "认领 → processing")
    @RequirePerm("log:anomaly:handle")
    @PutMapping("/{id}/claim")
    public Result<Void> claim(@PathVariable Long id) {
        updateStatus(id, "claim", "processing", null);
        return Result.ok();
    }

    @Operation(summary = "解决 → resolved")
    @RequirePerm("log:anomaly:handle")
    @PutMapping("/{id}/resolve")
    public Result<Void> resolve(@PathVariable Long id,
                                @RequestBody(required = false) Map<String, String> body) {
        String remark = body == null ? null : body.get("remark");
        updateStatus(id, "resolve", "resolved", remark);
        return Result.ok();
    }

    @Operation(summary = "标记误报 → false_positive")
    @RequirePerm("log:anomaly:handle")
    @PutMapping("/{id}/false-positive")
    public Result<Void> falsePositive(@PathVariable Long id) {
        updateStatus(id, "false_positive", "false_positive", null);
        return Result.ok();
    }

    private void updateStatus(Long id, String action, String newStatus, String remark) {
        LogAnomaly a = logAnomalyMapper.selectById(id);
        if (a == null) {
            throw new BizException("异常不存在");
        }
        LogAnomaly upd = new LogAnomaly();
        upd.setId(id);
        upd.setStatus(newStatus);
        if (remark != null && !remark.isBlank()) {
            upd.setDescription((a.getDescription() == null ? "" : a.getDescription() + " | ")
                    + "[" + action + "] " + remark);
        }
        upd.setLastTime(LocalDateTime.now());
        logAnomalyMapper.updateById(upd);
        log.info("[LogAnomaly] id={} → {} remark={}", id, newStatus, remark);
    }
}
