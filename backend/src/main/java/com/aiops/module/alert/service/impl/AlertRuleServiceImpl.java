package com.aiops.module.alert.service.impl;

import com.aiops.common.BizException;
import com.aiops.module.alert.entity.AlertRule;
import com.aiops.module.alert.entity.BaselineModel;
import com.aiops.module.alert.mapper.AlertRuleMapper;
import com.aiops.module.alert.mapper.BaselineModelMapper;
import com.aiops.module.alert.service.AlertRuleService;
import com.aiops.module.monitor.entity.MetricData;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 告警规则业务：CRUD 之外的训练/回放/对照逻辑。
 * 分桶键格式 EEE-HH（Locale.ENGLISH：MON-00 ~ SUN-23，168 桶）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRuleServiceImpl implements AlertRuleService {

    public static final DateTimeFormatter BUCKET_FMT =
            DateTimeFormatter.ofPattern("EEE-HH", Locale.ENGLISH);

    private final AlertRuleMapper alertRuleMapper;
    private final BaselineModelMapper baselineModelMapper;
    private final MetricDataMapper metricDataMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AlertRule getById(Long id) {
        return alertRuleMapper.selectById(id);
    }

    /** 训练：默认取最近 7 天数据，按 EEE-HH 分 168 桶，每桶 mean/std/upper/lower */
    @Override
    public Map<String, Object> trainBaseline(Long ruleId) {
        AlertRule rule = alertRuleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BizException("规则不存在");
        }
        if (rule.getTargetId() == null) {
            throw new BizException("规则未绑定 target，无法训练");
        }
        // 解析 baseline_config：{days:7, k:3}
        int days = 7;
        double k = 3.0;
        if (rule.getBaselineConfig() != null && !rule.getBaselineConfig().isBlank()) {
            try {
                JsonNode cfg = objectMapper.readTree(rule.getBaselineConfig());
                if (cfg.hasNonNull("days")) days = cfg.get("days").asInt(7);
                if (cfg.hasNonNull("k")) k = cfg.get("k").asDouble(3.0);
            } catch (Exception ignore) { /* 配置异常时用默认值 */ }
        }
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(days);
        List<MetricData> history = metricDataMapper.selectList(new LambdaQueryWrapper<MetricData>()
                .eq(MetricData::getTargetId, rule.getTargetId())
                .eq(MetricData::getMetricKey, rule.getMetricKey())
                .between(MetricData::getCollectTime, start, end)
                .orderByAsc(MetricData::getCollectTime));
        if (history.isEmpty()) {
            throw new BizException("近 " + days + " 天无指标数据，无法训练");
        }
        log.info("[Baseline] 训练 ruleId={}, target={}, metric={}, 样本={}", ruleId,
                rule.getTargetId(), rule.getMetricKey(), history.size());

        // 分桶：bucketKey -> values
        Map<String, List<Double>> buckets = new TreeMap<>();
        for (MetricData md : history) {
            String key = md.getCollectTime().format(BUCKET_FMT).toUpperCase(Locale.ENGLISH);
            buckets.computeIfAbsent(key, kk -> new ArrayList<>()).add(md.getMetricValue().doubleValue());
        }
        // 清掉旧的
        baselineModelMapper.delete(new LambdaQueryWrapper<BaselineModel>()
                .eq(BaselineModel::getRuleId, ruleId)
                .eq(BaselineModel::getTargetId, rule.getTargetId())
                .eq(BaselineModel::getMetricKey, rule.getMetricKey())
                .eq(BaselineModel::getModelType, "hour_bucket"));

        int wrote = 0;
        double kd = k;
        for (Map.Entry<String, List<Double>> e : buckets.entrySet()) {
            List<Double> vs = e.getValue();
            if (vs.size() < 3) {
                // 样本不足，跳过
                continue;
            }
            double mean = vs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = vs.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
            double std = Math.sqrt(variance);
            double upper = mean + kd * std;
            double lower = mean - kd * std;
            BaselineModel bm = new BaselineModel();
            bm.setRuleId(ruleId);
            bm.setTargetId(rule.getTargetId());
            bm.setMetricKey(rule.getMetricKey());
            bm.setModelType("hour_bucket");
            bm.setParams("{\"k\":" + kd + ",\"days\":" + days + "}");
            bm.setBucketKey(e.getKey());
            bm.setUpperBound(BigDecimal.valueOf(upper).setScale(4, RoundingMode.HALF_UP));
            bm.setLowerBound(BigDecimal.valueOf(lower).setScale(4, RoundingMode.HALF_UP));
            bm.setMeanValue(BigDecimal.valueOf(mean).setScale(4, RoundingMode.HALF_UP));
            bm.setStdValue(BigDecimal.valueOf(std).setScale(4, RoundingMode.HALF_UP));
            bm.setSampleCount(vs.size());
            bm.setTrainStart(start);
            bm.setTrainEnd(end);
            bm.setLastTrainTime(LocalDateTime.now());
            bm.setStatus(1);
            baselineModelMapper.insert(bm);
            wrote++;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("bucketCount", wrote);
        result.put("trained", true);
        result.put("sampleTotal", history.size());
        result.put("days", days);
        result.put("k", k);
        return result;
    }

    /** 返回某日的 24h 基线上/下界 + 实际值（ECharts 用） */
    @Override
    public Map<String, Object> baselineChart(Long ruleId, Long targetId, String date) {
        AlertRule rule = alertRuleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BizException("规则不存在");
        }
        Long tid = targetId != null ? targetId : rule.getTargetId();
        if (tid == null) {
            throw new BizException("targetId 缺失");
        }
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (Exception e) {
            throw new BizException("日期格式须为 yyyy-MM-dd");
        }
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();

        // 拉取该日实际值，按小时聚合
        List<MetricData> data = metricDataMapper.selectList(new LambdaQueryWrapper<MetricData>()
                .eq(MetricData::getTargetId, tid)
                .eq(MetricData::getMetricKey, rule.getMetricKey())
                .between(MetricData::getCollectTime, dayStart, dayEnd)
                .orderByAsc(MetricData::getCollectTime));
        Map<Integer, List<Double>> hourly = data.stream().collect(Collectors.groupingBy(
                md -> md.getCollectTime().getHour(),
                Collectors.mapping(md -> md.getMetricValue() == null ? 0.0 : md.getMetricValue().doubleValue(),
                        Collectors.toList())));

        // 拉取所有 baseline 桶
        List<BaselineModel> models = baselineModelMapper.selectList(new LambdaQueryWrapper<BaselineModel>()
                .eq(BaselineModel::getRuleId, ruleId)
                .eq(BaselineModel::getTargetId, tid)
                .eq(BaselineModel::getMetricKey, rule.getMetricKey())
                .eq(BaselineModel::getModelType, "hour_bucket"));
        Map<String, BaselineModel> bucketMap = models.stream()
                .collect(Collectors.toMap(BaselineModel::getBucketKey, b -> b, (a, b) -> a));

        // 该日是周几
        String dow = day.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)).toUpperCase(Locale.ENGLISH);
        List<Integer> hours = new ArrayList<>();
        List<BigDecimal> upper = new ArrayList<>();
        List<BigDecimal> lower = new ArrayList<>();
        List<BigDecimal> actual = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            hours.add(h);
            String key = dow + "-" + String.format("%02d", h);
            BaselineModel bm = bucketMap.get(key);
            upper.add(bm == null ? null : bm.getUpperBound());
            lower.add(bm == null ? null : bm.getLowerBound());
            List<Double> vs = hourly.get(h);
            if (vs == null || vs.isEmpty()) {
                actual.add(null);
            } else {
                double avg = vs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                actual.add(BigDecimal.valueOf(avg).setScale(4, RoundingMode.HALF_UP));
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("hours", hours);
        result.put("upper", upper);
        result.put("lower", lower);
        result.put("actual", actual);
        return result;
    }

    /** 干跑：在历史数据上回放检测算法，不落库 */
    @Override
    public Map<String, Object> dryRun(Long ruleId, LocalDateTime startTime, LocalDateTime endTime) {
        AlertRule rule = alertRuleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BizException("规则不存在");
        }
        if (rule.getTargetId() == null) {
            throw new BizException("规则未绑定 target");
        }
        List<MetricData> data = metricDataMapper.selectList(new LambdaQueryWrapper<MetricData>()
                .eq(MetricData::getTargetId, rule.getTargetId())
                .eq(MetricData::getMetricKey, rule.getMetricKey())
                .between(MetricData::getCollectTime, startTime, endTime)
                .orderByAsc(MetricData::getCollectTime));
        // baseline 模式拉桶
        Map<String, BaselineModel> bucketMap = new HashMap<>();
        if ("baseline".equalsIgnoreCase(rule.getRuleType())) {
            List<BaselineModel> models = baselineModelMapper.selectList(new LambdaQueryWrapper<BaselineModel>()
                    .eq(BaselineModel::getRuleId, ruleId)
                    .eq(BaselineModel::getTargetId, rule.getTargetId())
                    .eq(BaselineModel::getMetricKey, rule.getMetricKey())
                    .eq(BaselineModel::getModelType, "hour_bucket"));
            for (BaselineModel bm : models) {
                bucketMap.put(bm.getBucketKey(), bm);
            }
        }
        List<LocalDateTime> timestamps = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        int total = 0;
        for (MetricData md : data) {
            boolean hit = switch (rule.getRuleType() == null ? "static" : rule.getRuleType()) {
                case "baseline" -> {
                    String key = md.getCollectTime().format(BUCKET_FMT).toUpperCase(Locale.ENGLISH);
                    BaselineModel bm = bucketMap.get(key);
                    if (bm == null) yield false;
                    double v = md.getMetricValue().doubleValue();
                    yield v > bm.getUpperBound().doubleValue() || v < bm.getLowerBound().doubleValue();
                }
                default -> compare(md.getMetricValue().doubleValue(),
                        rule.getOperator(),
                        rule.getThreshold() == null ? null : rule.getThreshold().doubleValue());
            };
            if (hit) {
                timestamps.add(md.getCollectTime());
                values.add(md.getMetricValue());
                total++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("timestamps", timestamps);
        result.put("values", values);
        result.put("total", total);
        return result;
    }

    /** 静态阈值比较（gt|gte|lt|lte；outside/inside 需上下界，见 compareRange） */
    public static boolean compare(double value, String operator, Double threshold) {
        if (threshold == null || operator == null) {
            return false;
        }
        return switch (operator) {
            case "gt" -> value > threshold;
            case "gte" -> value >= threshold;
            case "lt" -> value < threshold;
            case "lte" -> value <= threshold;
            default -> false;
        };
    }

    /** outside/inside：value 相对 [lower, upper] 的位置判定（threshold 字段存不动双界时用 baseline 上下界） */
    public static boolean compareRange(double value, String operator, double lower, double upper) {
        return switch (operator) {
            case "outside" -> value < lower || value > upper;
            case "inside" -> value >= lower && value <= upper;
            default -> false;
        };
    }

    @Override
    public void deleteRule(Long id) {
        alertRuleMapper.deleteById(id);
        baselineModelMapper.delete(new LambdaQueryWrapper<BaselineModel>()
                .eq(BaselineModel::getRuleId, id));
    }

    @Override
    public void toggle(Long id, Integer enabled) {
        AlertRule r = new AlertRule();
        r.setId(id);
        r.setEnabled(enabled);
        alertRuleMapper.updateById(r);
    }
}
