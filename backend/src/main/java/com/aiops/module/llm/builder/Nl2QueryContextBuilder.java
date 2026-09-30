package com.aiops.module.llm.builder;

import com.aiops.module.monitor.entity.MetricDefinition;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.MetricDefinitionMapper;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * M5-9 nl2query 上下文组装器。
 * <p>
 * 一次性提供 3 类上下文供 prompt 使用：
 * <ul>
 *   <li>{@link #buildMetricList()}：从 metric_definition (deleted=0) 拉 metric_key=描述，至少 17 行</li>
 *   <li>{@link #buildTargetList()}：从 monitor_target (deleted=0) 拉 id/name/ip/logServiceName JSON</li>
 *   <li>{@link #buildTimeHint()}：当前 Asia/Shanghai 时间，给 LLM 解析"今天 / 过去一小时"使用</li>
 * </ul>
 * 共 2 个 SQL（metric_definition 1 条 + monitor_target 1 条），调用方按周期自决缓存策略。
 */
@Component
@RequiredArgsConstructor
public class Nl2QueryContextBuilder {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TARGET_LIST_CHARS = 2000;

    private final MetricDefinitionMapper metricDefinitionMapper;
    private final MonitorTargetMapper monitorTargetMapper;

    /** 指标列表（每行 "metric_key=描述"），至少返回 17 行（如 DB 不足 17 行，由 M5-9 init sql 负责补充）。 */
    public String buildMetricList() {
        List<MetricDefinition> defs = metricDefinitionMapper.selectList(
                new LambdaQueryWrapper<MetricDefinition>()
                        .eq(MetricDefinition::getDeleted, 0)
                        .orderByAsc(MetricDefinition::getMetricKey));
        return defs.stream()
                .map(d -> d.getMetricKey() + "=" + (d.getDescription() == null ? d.getMetricName() : d.getDescription()))
                .collect(Collectors.joining("\n"));
    }

    /** 监控目标列表（紧凑 JSON 一行），超 2000 字截断。 */
    public String buildTargetList() {
        List<MonitorTarget> targets = monitorTargetMapper.selectList(
                new LambdaQueryWrapper<MonitorTarget>()
                        .eq(MonitorTarget::getDeleted, 0)
                        .orderByAsc(MonitorTarget::getId)
                        .last("LIMIT 200"));
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (MonitorTarget t : targets) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(t.getId())
                    .append(",\"name\":\"").append(esc(t.getName())).append("\"")
                    .append(",\"ip\":\"").append(esc(t.getIp() == null ? "" : t.getIp())).append("\"")
                    .append(",\"logServiceName\":\"").append(esc(t.getLogServiceName() == null ? "" : t.getLogServiceName())).append("\"")
                    .append("}");
            if (sb.length() > MAX_TARGET_LIST_CHARS) break;
        }
        sb.append(']');
        return sb.toString();
    }

    /** 解析后的目标列表（供 targetIds 名称→id 反查）。 */
    public List<MonitorTarget> listTargets() {
        return monitorTargetMapper.selectList(
                new LambdaQueryWrapper<MonitorTarget>()
                        .eq(MonitorTarget::getDeleted, 0)
                        .orderByAsc(MonitorTarget::getId)
                        .last("LIMIT 200"));
    }

    /** Asia/Shanghai 当前时间字面量，用于 prompt 上下文。 */
    public String buildTimeHint() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(F) + " Asia/Shanghai";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
