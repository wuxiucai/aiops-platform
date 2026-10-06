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
        String step = req.getStep() == null ? "5m" : req.getStep();
        // 关键修复：分桶用独立的表达式参数，不再套 DATE_FORMAT，否则 CONCAT 会被 MySQL 当字面量
        // rawFormat=true 表示 expression 是 SQL 表达式（CONCAT/FLOOR），
        //               false 表示是 DATE_FORMAT 的 format 字符串。
        String expression;
        boolean rawFormat;
        switch (step) {
            case "1s"  -> { expression = "%Y-%m-%d %H:%i:%s"; rawFormat = false; }
            case "10s" -> { expression = secondBucketExpr(10); rawFormat = true; }
            case "30s" -> { expression = secondBucketExpr(30); rawFormat = true; }
            case "1m"  -> { expression = "%Y-%m-%d %H:%i:00"; rawFormat = false; }
            case "5m"  -> { expression = minuteBucketExpr(5); rawFormat = true; }
            case "1h"  -> { expression = "%Y-%m-%d %H:00:00"; rawFormat = false; }
            case "1d"  -> { expression = "%Y-%m-%d 00:00:00"; rawFormat = false; }
            default -> throw new BizException("step 仅支持 1s/10s/30s/1m/5m/1h/1d");
        }
        return Result.ok(metricDataMapper.querySeries(req.getTargetIds(), req.getMetricKeys(),
                start, end, expression, rawFormat, agg));
    }

    @Operation(summary = "指标元数据")
    @RequirePerm("monitor:metric:query")
    @GetMapping("/definitions")
    public Result<List<MetricDefinition>> definitions() {
        return Result.ok(metricDefinitionMapper.selectList(null));
    }

    /** 按 mod 分钟向下取整的 SQL 表达式（直接作为 SELECT 列，不再嵌 DATE_FORMAT） */
    private static String minuteBucketExpr(int mod) {
        return "CONCAT(DATE_FORMAT(collect_time, '%Y-%m-%d %H:'), LPAD(FLOOR(MINUTE(collect_time)/" + mod + ")*" + mod + ", 2, '0'), ':00')";
    }

    /** 按 mod 秒向下取整的 SQL 表达式 */
    private static String secondBucketExpr(int mod) {
        return "CONCAT(DATE_FORMAT(collect_time, '%Y-%m-%d %H:%i:'), LPAD(FLOOR(SECOND(collect_time)/" + mod + ")*" + mod + ", 2, '0'))";
    }
}
