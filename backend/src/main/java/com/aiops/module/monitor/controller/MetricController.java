package com.aiops.module.monitor.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.aiops.module.monitor.mapper.MetricDefinitionMapper;
import com.aiops.module.monitor.entity.MetricDefinition;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标查询与定义
 */
@Tag(name = "指标")
@RestController
@RequestMapping("/api/monitor/metric")
@RequiredArgsConstructor
public class MetricController {

    private final MetricDataMapper metricDataMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> AGGREGATIONS = Set.of("avg", "max", "min", "sum", "count");

    @Data
    public static class MetricQueryRequest {
        @NotEmpty(message = "targetIds 不能为空")
        private List<Long> targetIds;
        @NotEmpty(message = "metricKeys 不能为空")
        private List<String> metricKeys;
        @NotNull(message = "startTime 必填")
        private String startTime;
        @NotNull(message = "endTime 必填")
        private String endTime;
        /** avg|max|min|sum|count */
        private String aggregation = "avg";
        /** 1m/5m/1h/1d */
        private String step = "5m";
    }

    @Operation(summary = "时序查询（返回按时间升序的点数组）")
    @RequirePerm("monitor:metric:query")
    @PostMapping("/query")
    public Result<List<Map<String, Object>>> query(@RequestBody MetricQueryRequest req) {
        LocalDateTime start = LocalDateTime.parse(req.getStartTime(), FMT);
        LocalDateTime end = LocalDateTime.parse(req.getEndTime(), FMT);
        String agg = req.getAggregation() == null ? "avg" : req.getAggregation().toLowerCase();
        if (!AGGREGATIONS.contains(agg)) {
            throw new BizException("不支持的聚合方式: " + agg);
        }
        String format = switch (req.getStep() == null ? "5m" : req.getStep()) {
            case "1m" -> "%Y-%m-%d %H:%i:00";
            case "5m" -> concatMinuteBucket(5);
            case "1h" -> "%Y-%m-%d %H:00:00";
            case "1d" -> "%Y-%m-%d 00:00:00";
            default -> throw new BizException("step 仅支持 1m/5m/1h/1d");
        };
        return Result.ok(metricDataMapper.querySeries(req.getTargetIds(), req.getMetricKeys(),
                start, end, format, agg));
    }

    @Operation(summary = "指标元数据")
    @RequirePerm("monitor:metric:query")
    @GetMapping("/definitions")
    public Result<List<MetricDefinition>> definitions() {
        return Result.ok(metricDefinitionMapper.selectList(null));
    }

    /** 5 分钟桶：按 (minute div 5) 分组 */
    private static String concatMinuteBucket(int mod) {
        return "CONCAT(DATE_FORMAT(collect_time, '%%Y-%%m-%%d %%H:'), LPAD(FLOOR(MINUTE(collect_time)/" + mod + ")*" + mod + ", 2, '0'), ':00')";
    }
}
