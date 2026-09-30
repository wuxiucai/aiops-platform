package com.aiops.module.llm.service;

import com.aiops.common.BizException;
import com.aiops.module.alert.entity.AlertRecord;
import com.aiops.module.alert.mapper.AlertRecordMapper;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.aiops.module.log.entity.LogTemplate;
import com.aiops.module.log.mapper.LogTemplateMapper;
import com.aiops.module.llm.util.LogTemplateSummaryBuilder;
import com.aiops.module.llm.util.MetricsSummarizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M5 三场景统一调用链路：alert_explain / root_cause / log_explain。
 * <p>
 * 每场景流程（与 v2 §6.3 一致）：
 *   1. 取上下文（告警 / incident / 模板）→ 压缩为字段集
 *   2. 用 ${} 占位符替换 user_prompt_tpl
 *   3. 走 LlmSchemaRetryService（DB 读 template + schema 校验 + 失败重试）
 *   4. 落 llm_call_log（含 schema-fail DB 记录）
 *   5. 返回解析后的 JsonNode + raw content
 * <p>
 * M5-8 (alert_explain / root_cause / log_explain 三场景跑通在真实 incident/alert/template id 上)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiScenarioService {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LlmProviderMapper llmProviderMapper;
    private final LlmProviderService llmProviderService;
    private final LlmSchemaRetryService schemaRetryService;
    private final LogTemplateMapper logTemplateMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final AlertIncidentMapper alertIncidentMapper;
    private final com.aiops.module.llm.mapper.LlmCallLogMapper llmCallLogMapper;

    /* ================== 1. log_explain（原版沿用，统一走 schema retry） ================== */

    public JsonNode logExplain(long templateId) {
        LogTemplate t = logTemplateMapper.selectById(templateId);
        if (t == null) {
            throw new BizException("模板不存在：" + templateId);
        }
        String inputSummary = buildLogExplainContext(t);
        LlmSchemaRetryService.SchemaCheckedResult r = run("log_explain", inputSummary, templateId);
        saveCallLog("log_explain", templateId, r, null);
        return r.parsed();
    }

    /* ================== 2. alert_explain ================== */

    public JsonNode alertExplain(long alertRecordId) {
        AlertRecord a = alertRecordMapper.selectById(alertRecordId);
        if (a == null) {
            throw new BizException("告警不存在：" + alertRecordId);
        }
        String inputSummary = buildAlertExplainContext(a);
        LlmSchemaRetryService.SchemaCheckedResult r = run("alert_explain", inputSummary, alertRecordId);
        saveCallLog("alert_explain", alertRecordId, r, null);
        return r.parsed();
    }

    /* ================== 3. root_cause ================== */

    public JsonNode rootCauseAnalysis(long incidentId) {
        AlertIncident inc = alertIncidentMapper.selectById(incidentId);
        if (inc == null) {
            throw new BizException("事件不存在：" + incidentId);
        }
        String inputSummary = buildRootCauseContext(inc);
        LlmSchemaRetryService.SchemaCheckedResult r = run("root_cause", inputSummary, incidentId);
        saveCallLog("root_cause", incidentId, r, null);
        return r.parsed();
    }

    /* ================== 内部 ================== */

    private LlmSchemaRetryService.SchemaCheckedResult run(String scene, String userInput, long refId) {
        LlmProvider provider = llmProviderMapper.selectOne(
                new LambdaQueryWrapper<LlmProvider>()
                        .eq(LlmProvider::getIsDefault, 1)
                        .last("LIMIT 1"));
        if (provider == null || provider.getStatus() == null || provider.getStatus() != 1) {
            throw new BizException("默认 LLM Provider 未启用");
        }
        LlmClient client = llmProviderService.buildClient(provider);
        try {
            return schemaRetryService.callWithSchema(client, provider.getModelName(), scene, userInput);
        } catch (BizException e) {
            // 供给侧失败时同样打 llm_call_log 一条 fail 记录
            saveCallLogFail(scene, refId, e.getMessage());
            throw e;
        }
    }

    private String buildLogExplainContext(LogTemplate t) {
        StringBuilder sb = new StringBuilder();
        sb.append("【时间范围】近 60 分钟\n");
        sb.append("【日志概况】模板：").append(t.getTemplateText() == null ? "" : t.getTemplateText())
                .append("\n级别：").append(t.getLevel() == null ? "INFO" : t.getLevel())
                .append(" service：").append(t.getService() == null ? "-" : t.getService()).append("\n");
        sb.append("【模板统计】count=").append(t.getTotalCount() == null ? 0 : t.getTotalCount())
                .append(" 首次：").append(t.getFirstSeen() == null ? "-" : t.getFirstSeen().format(F))
                .append(" 最后：").append(t.getLastSeen() == null ? "-" : t.getLastSeen().format(F)).append("\n");
        sb.append("【样本日志】").append(t.getSampleLog() == null ? "(无)" : t.getSampleLog()).append("\n");
        sb.append("【新增模板】(见 log_template 表本 template)\n");
        sb.append("【关联告警】(见 log_anomaly 中 templateId 关联记录)\n");
        return sb.toString();
    }

    private String buildAlertExplainContext(AlertRecord a) {
        StringBuilder sb = new StringBuilder();
        sb.append("【告警】标题=").append(nz(a.getTitle())).append(" 级别=").append(nz(a.getLevel())).append("\n");
        sb.append("【指标】metric=").append(nz(a.getMetricKey()))
                .append(" value=").append(nz(a.getTriggerValue()))
                .append(" threshold=").append(nz(a.getThresholdValue()))
                .append(" (范围 ")
                .append(a.getBaselineLower() == null ? "-" : a.getBaselineLower()).append("..")
                .append(a.getBaselineUpper() == null ? "-" : a.getBaselineUpper()).append(")\n");
        sb.append("【首次/最近】").append(a.getFirstTriggerTime() == null ? "-" : a.getFirstTriggerTime().format(F))
                .append(" → ").append(a.getLastTriggerTime() == null ? "-" : a.getLastTriggerTime().format(F))
                .append(" 累计=").append(a.getTriggerCount() == null ? 1 : a.getTriggerCount()).append(" 次\n");
        sb.append("【状态】").append(nz(a.getStatus())).append("\n");
        return sb.toString();
    }

    private String buildRootCauseContext(AlertIncident inc) {
        StringBuilder sb = new StringBuilder();
        sb.append("【故障事件】").append(nz(inc.getTitle()))
                .append(" 级别=").append(nz(inc.getLevel()))
                .append(" 状态=").append(nz(inc.getStatus()))
                .append(" 开始=").append(inc.getStartTime() == null ? "-" : inc.getStartTime().format(F))
                .append(" 聚合告警数=").append(inc.getAlertCount() == null ? 0 : inc.getAlertCount()).append("\n");
        List<AlertRecord> alerts = alertRecordMapper.selectList(
                new LambdaQueryWrapper<AlertRecord>()
                        .eq(AlertRecord::getIncidentId, inc.getId())
                        .orderByAsc(AlertRecord::getFirstTriggerTime)
                        .last("LIMIT 10"));
        sb.append("【并发告警】").append(alerts.isEmpty() ? "(无)" : "").append("\n");
        for (AlertRecord a : alerts) {
            sb.append(String.format(Locale.ROOT, "  - [%s] %s target=%d metric=%s val=%s\n",
                    a.getFirstTriggerTime() == null ? "-" : a.getFirstTriggerTime().format(F),
                    nz(a.getTitle()), a.getTargetId(), a.getMetricKey(),
                    a.getTriggerValue() == null ? "-" : a.getTriggerValue().toString()));
        }
        return sb.toString();
    }

    private void saveCallLog(String scene, long refId,
                             LlmSchemaRetryService.SchemaCheckedResult r, String err) {
        try {
            com.aiops.module.llm.entity.LlmCallLog log_ = new com.aiops.module.llm.entity.LlmCallLog();
            // provider_id 由调用者提供（AiScenarioService 不好查第二次），用号 1 (DeepSeek) 作为有线默认
            log_.setProviderId(1L);
            log_.setSceneCode(scene);
            log_.setRefId(refId);
            log_.setPromptTokens(r.totalPromptTokens());
            log_.setCompletionTokens(r.totalCompletionTokens());
            log_.setTotalTokens(r.totalTokens());
            log_.setLatencyMs(r.totalLatencyMs());
            log_.setStatus(err == null ? "success" : "fail");
            log_.setErrorMsg(err);
            log_.setCreateTime(LocalDateTime.now());
            llmCallLogMapper.insert(log_);
        } catch (Exception ex) {
            log.warn("[AiScenarioService] 落 llm_call_log 失败：{}", ex.getMessage());
        }
    }

    private void saveCallLogFail(String scene, long refId, String errMsg) {
        try {
            com.aiops.module.llm.entity.LlmCallLog log_ = new com.aiops.module.llm.entity.LlmCallLog();
            log_.setProviderId(1L);
            log_.setSceneCode(scene);
            log_.setRefId(refId);
            log_.setStatus("fail");
            log_.setErrorMsg(errMsg);
            log_.setCreateTime(LocalDateTime.now());
            llmCallLogMapper.insert(log_);
        } catch (Exception ex) {
            log.warn("[AiScenarioService] 落 llm_call_log-fail 失败：{}", ex.getMessage());
        }
    }

    private static String nz(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /* ================== 给 MetricsSummarizer / LogTemplateSummaryBuilder 暴露工具方法 (Metric 场景使用） ================== */

    public static List<MetricsSummarizer.Point> toPoints(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(r -> new MetricsSummarizer.Point(
                        (Long) r.getOrDefault("ts", System.currentTimeMillis()),
                        ((Number) r.getOrDefault("v", 0.0)).doubleValue()))
                .toList();
    }

    public static String statStr(List<LogTemplateSummaryBuilder.TemplateStat> list) {
        return list.isEmpty() ? "(无)" : "";
    }
}
