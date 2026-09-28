package com.aiops.module.incident.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.incident.service.IncidentService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 故障事件
 */
@Slf4j
@Tag(name = "故障事件")
@RestController
@RequestMapping("/api/incident")
@RequiredArgsConstructor
public class IncidentController {

    private final AlertIncidentMapper alertIncidentMapper;
    private final IncidentService incidentService;

    @Operation(summary = "分页列表")
    @RequirePerm("incident:list")
    @GetMapping("/page")
    public Result<Page<AlertIncident>> page(@RequestParam(defaultValue = "1") long current,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                            LocalDateTime startTime,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                            LocalDateTime endTime) {
        return Result.ok(alertIncidentMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<AlertIncident>()
                        .eq(status != null && !status.isBlank(), AlertIncident::getStatus, status)
                        .like(keyword != null && !keyword.isBlank(), AlertIncident::getTitle, keyword)
                        .ge(startTime != null, AlertIncident::getStartTime, startTime)
                        .le(endTime != null, AlertIncident::getStartTime, endTime)
                        .orderByDesc(AlertIncident::getId)));
    }

    @Operation(summary = "详情（含告警 + 时间线）")
    @RequirePerm("incident:list")
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(incidentService.detail(id));
    }

    @Operation(summary = "解决")
    @RequirePerm("incident:handle")
    @PutMapping("/{id}/resolve")
    public Result<Void> resolve(@PathVariable Long id,
                                @RequestBody(required = false) Map<String, String> body) {
        incidentService.resolve(id, currentUsername(), body == null ? null : body.get("remark"));
        return Result.ok();
    }

    @Operation(summary = "追加时间线")
    @RequirePerm("incident:handle")
    @PostMapping("/{id}/timeline")
    public Result<Void> appendTimeline(@PathVariable Long id,
                                       @RequestBody Map<String, String> body) {
        String eventType = body.get("event_type");
        String description = body.get("description");
        if (description == null || description.isBlank()) {
            throw new BizException("description 必填");
        }
        incidentService.appendTimeline(id, eventType, description, currentUsername());
        return Result.ok();
    }

    private String currentUsername() {
        return UserContext.get() == null ? "system" : UserContext.get().getUsername();
    }
}
