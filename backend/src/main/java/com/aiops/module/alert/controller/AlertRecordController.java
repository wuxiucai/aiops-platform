package com.aiops.module.alert.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.alert.entity.AlertRecord;
import com.aiops.module.alert.mapper.AlertRecordMapper;
import com.aiops.module.alert.service.AlertRecordService;
import com.aiops.security.RequirePerm;
import com.aiops.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 告警记录查询 + 生命周期操作
 */
@Slf4j
@Tag(name = "告警记录")
@RestController
@RequestMapping("/api/alert/record")
@RequiredArgsConstructor
public class AlertRecordController {

    private final AlertRecordMapper alertRecordMapper;
    private final AlertRecordService alertRecordService;

    @Operation(summary = "分页列表")
    @RequirePerm("alert:record:list")
    @GetMapping("/page")
    public Result<Page<AlertRecord>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long ruleId,
                                          @RequestParam(required = false) Long targetId,
                                          @RequestParam(required = false)
                                          @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                          LocalDateTime startTime,
                                          @RequestParam(required = false)
                                          @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                          LocalDateTime endTime) {
        Page<AlertRecord> p = alertRecordMapper.pageWithNames(new Page<>(current, size),
                status, ruleId, targetId, startTime, endTime);
        return Result.ok(p);
    }

    @Operation(summary = "详情")
    @RequirePerm("alert:record:list")
    @GetMapping("/{id}")
    public Result<AlertRecord> detail(@PathVariable Long id) {
        return Result.ok(alertRecordMapper.selectById(id));
    }

    @Operation(summary = "认领")
    @RequirePerm("alert:record:handle")
    @PutMapping("/{id}/claim")
    public Result<Void> claim(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        alertRecordService.changeStatus(id, "claim", currentUsername(),
                body == null ? null : body.get("remark"));
        return Result.ok();
    }

    @Operation(summary = "解决")
    @RequirePerm("alert:record:handle")
    @PutMapping("/{id}/resolve")
    public Result<Void> resolve(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        alertRecordService.changeStatus(id, "resolve", currentUsername(),
                body == null ? null : body.get("remark"));
        return Result.ok();
    }

    @Operation(summary = "关闭")
    @RequirePerm("alert:record:handle")
    @PutMapping("/{id}/close")
    public Result<Void> close(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        alertRecordService.changeStatus(id, "close", currentUsername(),
                body == null ? null : body.get("remark"));
        return Result.ok();
    }

    @Operation(summary = "标记误报")
    @RequirePerm("alert:record:handle")
    @PutMapping("/{id}/false-positive")
    public Result<Void> falsePositive(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, String> body) {
        alertRecordService.changeStatus(id, "false-positive", currentUsername(),
                body == null ? null : body.get("remark"));
        return Result.ok();
    }

    @Operation(summary = "关联日志（ES）")
    @RequirePerm("alert:record:list")
    @GetMapping("/{id}/related-logs")
    public Result<Map<String, Object>> relatedLogs(@PathVariable Long id) {
        return Result.ok(alertRecordService.relatedLogs(id));
    }

    @Operation(summary = "最近 N 小时告警数（大盘）")
    @RequirePerm("monitor:overview")
    @GetMapping("/count-24h")
    public Result<Long> count24h() {
        return Result.ok(alertRecordMapper.countSince(LocalDateTime.now().minusHours(24)));
    }

    private String currentUsername() {
        return UserContext.get() == null ? "system" : UserContext.get().getUsername();
    }
}
