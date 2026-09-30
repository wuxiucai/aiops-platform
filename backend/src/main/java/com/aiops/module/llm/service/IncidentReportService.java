package com.aiops.module.llm.service;

import com.aiops.common.BizException;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.llm.builder.IncidentReportContextBuilder;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.entity.LlmCallLog;
import com.aiops.module.llm.entity.LlmPromptTemplate;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmCallLogMapper;
import com.aiops.module.llm.mapper.LlmPromptTemplateMapper;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * M5-10 故障报告生成服务。
 * <p>
 * 流程：
 *   1. 读 incident → 若已有 llm_report 则命中缓存直接返回（不写 llm_call_log）
 *   2. 用 IncidentReportContextBuilder 拼接 prompt 上下文（≤4000 字符）
 *   3. 从 llm_prompt_template 读 scene='report' 的模板，替换占位符
 *   4. 直接 llmClient.chat()（不走 schema retry：报告的 output_schema 是 {markdown:string}，但 LLM 实际产纯 markdown）
 *   5. 校验六段标题 + markdown 长 ≥ 500；失败 → 透传 hint 重试 1 次
 *   6. 仍失败 → BizException，内含缺失段名
 *   7. 成功后写 alert_incident.llm_report + llm_call_log（success）
 * <p>
 * 章节定义（接受 0..4 个 # 前缀，允许前后空白）：
 *   一、故障概述 / 二、影响范围 / 三、时间线 / 四、根因分析 /
 *   五、处置过程 (或 五、恢复过程) / 六、改进措施
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentReportService {

    public static final String SCENE_CODE = "report";
    /** 最小 markdown 长度（任务硬性约束） */
    public static final int MIN_MARKDOWN_CHARS = 500;

    /** 必填章节 regex（独立行，允许 0..4 个 #） */
    private static final List<Section> REQUIRED_SECTIONS = List.of(
            new Section("一、故障概述", Pattern.compile("(?m)^\\s*#{0,4}\\s*一、故障概述")),
            new Section("二、影响范围", Pattern.compile("(?m)^\\s*#{0,4}\\s*二、影响范围")),
            new Section("三、时间线", Pattern.compile("(?m)^\\s*#{0,4}\\s*三、时间线")),
            new Section("四、根因分析", Pattern.compile("(?m)^\\s*#{0,4}\\s*四、根因分析")),
            // 五、处置过程 或 五、恢复过程：都接受
            new Section("五、处置过程", Pattern.compile("(?m)^\\s*#{0,4}\\s*五、(处置|恢复)过程")),
            new Section("六、改进措施", Pattern.compile("(?m)^\\s*#{0,4}\\s*六、改进措施"))
    );

    private final AlertIncidentMapper alertIncidentMapper;
    private final IncidentReportContextBuilder contextBuilder;
    private final LlmPromptTemplateMapper promptTemplateMapper;
    private final LlmProviderMapper llmProviderMapper;
    private final LlmProviderService llmProviderService;
    private final LlmCallLogMapper llmCallLogMapper;

    /** 返回调用结果 */
    public record ReportResult(
            String markdown,
            boolean cached,
            int attempts,
            long latencyMs,
            int totalTokens,
            Long llmCallLogId) {}

    /**
     * 生成/取缓存 故障报告。
     *
     * @param incidentId 事件 ID
     */
    public ReportResult generateReport(long incidentId) {
        AlertIncident inc = alertIncidentMapper.selectById(incidentId);
        if (inc == null) {
            throw new BizException("事件不存在：" + incidentId);
        }
        // 幂等：若已有 llm_report，直接返回缓存，不调 LLM，也不写新 call_log
        if (inc.getLlmReport() != null && !inc.getLlmReport().isBlank()) {
            log.info("[M5-10] incident={} 命中缓存 llm_report，跳过 LLM", incidentId);
            return new ReportResult(inc.getLlmReport(), true, 0, 0L, 0, null);
        }

        // 取默认 provider
        LlmProvider provider = llmProviderMapper.selectOne(
                new LambdaQueryWrapper<LlmProvider>()
                        .eq(LlmProvider::getIsDefault, 1)
                        .last("LIMIT 1"));
        if (provider == null || provider.getStatus() == null || provider.getStatus() != 1) {
            throw new BizException("默认 LLM Provider 未启用");
        }
        LlmClient client = llmProviderService.buildClient(provider);

        // 取模板
        LlmPromptTemplate tpl = promptTemplateMapper.selectOne(
                new LambdaQueryWrapper<LlmPromptTemplate>()
                        .eq(LlmPromptTemplate::getSceneCode, SCENE_CODE)
                        .eq(LlmPromptTemplate::getEnabled, 1)
                        .last("LIMIT 1"));
        if (tpl == null) {
            throw new BizException("prompt 模板不存在：scene=" + SCENE_CODE);
        }

        // 组装上下文
        IncidentReportContextBuilder.ReportContext ctx = contextBuilder.build(inc);

        String systemPrompt = tpl.getSystemPrompt() == null ? "" : tpl.getSystemPrompt();
        String userTpl = tpl.getUserPromptTpl() == null ? "" : tpl.getUserPromptTpl();
        String userPrompt = substitute(userTpl, ctx);

        long t0 = System.currentTimeMillis();
        int sumPrompt = 0, sumComp = 0, sumTotal = 0;
        String lastContent = null;
        String lastErr = null;
        int attemptsMade = 0;
        Long callLogId = null;

        for (int attempt = 1; attempt <= 2; attempt++) {
            attemptsMade = attempt;
            LlmClient.LlmResponse resp;
            try {
                resp = client.chat(new LlmClient.LlmRequest(
                        provider.getModelName(),
                        List.of(
                                new LlmClient.LlmMessage("system", systemPrompt),
                                new LlmClient.LlmMessage("user", userPrompt)),
                        0.3, 2048, false));
            } catch (Exception e) {
                lastErr = "LLM 调用失败: " + e.getMessage();
                log.warn("[M5-10] incident={} attempt={} err={}", incidentId, attempt, lastErr);
                if (attempt >= 2) {
                    saveCallLogFail(SCENE_CODE, incidentId, provider.getId(), lastErr);
                    throw new BizException("LLM 调用连续 2 次失败：" + lastErr);
                }
                continue;
            }
            if (resp != null) {
                sumPrompt += resp.promptTokens();
                sumComp += resp.completionTokens();
                sumTotal += resp.totalTokens();
            }
            String content = resp == null ? null : resp.content();
            lastContent = content;

            List<String> missing = validate(content);
            if (missing.isEmpty()) {
                long latency = System.currentTimeMillis() - t0;
                log.info("[M5-10] incident={} attempt={} success latency={}ms tokens={}",
                        incidentId, attempt, latency, sumTotal);
                // 成功：先落 llm_call_log，再写 incident.llm_report
                callLogId = saveCallLogSuccess(SCENE_CODE, incidentId, provider.getId(),
                        sumPrompt, sumComp, sumTotal, latency);
                boolean updated = updateIncidentReport(incidentId, content);
                if (!updated) {
                    log.warn("[M5-10] incident={} llm_report 写入失败", incidentId);
                }
                return new ReportResult(content, false, attempt, latency, sumTotal, callLogId);
            }

            lastErr = "缺失章节: " + String.join("/", missing);
            log.warn("[M5-10] incident={} attempt={} {}", incidentId, attempt, lastErr);
            if (attempt >= 2) {
                saveCallLogFail(SCENE_CODE, incidentId, provider.getId(), lastErr);
                throw new BizException("LLM 输出连续 2 次未通过六段校验，最后缺失："
                        + String.join(" / ", missing)
                        + "；摘要=" + abbreviate(lastContent, 200));
            }
            // 重试：透传错误原因
            userPrompt = userPrompt + "\n\n【重要】你上次漏了 " + String.join("、", missing)
                    + " 章节，请重新生成完整 Markdown 故障报告，必须包含全部六节。";
        }
        // 不会到这里（循环里要么 return 要么 throw），保险防线
        throw new BizException("故障报告生成失败：" + lastErr);
    }

    /* ================== 六段校验 ================== */

    /**
     * 校验 markdown：
     *   1. length >= MIN_MARKDOWN_CHARS
     *   2. 包含全部 REQUIRED_SECTIONS
     *
     * @return 缺失章节名（空 = 通过）
     */
    public static List<String> validate(String markdown) {
        List<String> missing = new ArrayList<>();
        if (markdown == null) {
            for (Section s : REQUIRED_SECTIONS) missing.add(s.name());
            return missing;
        }
        if (markdown.length() < MIN_MARKDOWN_CHARS) {
            // 短于 500：视为未通过六段，全部段标缺（触发重试）
            // 但若本身已有完整六段，仅返回单一“长度过短”项，让调用方判断；
            // 简化起见统一当作全缺。
            for (Section s : REQUIRED_SECTIONS) missing.add(s.name());
            return missing;
        }
        for (Section s : REQUIRED_SECTIONS) {
            if (!s.pattern().matcher(markdown).find()) {
                missing.add(s.name());
            }
        }
        return missing;
    }

    /* ================== 内部 ================== */

    private String substitute(String tpl, IncidentReportContextBuilder.ReportContext ctx) {
        String s = tpl;
        s = s.replace("${incidentInfo}", safe(ctx.incidentInfo()));
        s = s.replace("${alertList}", safe(ctx.alertList()));
        s = s.replace("${timeline}", safe(ctx.timeline()));
        s = s.replace("${rootCause}", ""); // M5-10 暂不依赖 root_cause 结果，留空
        s = s.replace("${logSummary}", safe(ctx.logSummary()));
        s = s.replace("${metricsSummary}", safe(ctx.metricsSummary()));
        s = s.replace("${similarCases}", safe(ctx.similarCases()));
        s = s.replace("${reportSchema}", "六节 Markdown，见系统提示");
        return s;
    }

    private boolean updateIncidentReport(long incidentId, String markdown) {
        try {
            AlertIncident upd = new AlertIncident();
            upd.setId(incidentId);
            upd.setLlmReport(markdown);
            upd.setUpdateTime(LocalDateTime.now());
            return alertIncidentMapper.updateById(upd) > 0;
        } catch (Exception e) {
            log.warn("[M5-10] incident={} llm_report 更新失败: {}", incidentId, e.getMessage());
            return false;
        }
    }

    private Long saveCallLogSuccess(String scene, long refId, Long providerId,
                                    int prompt, int completion, int total, long latency) {
        try {
            LlmCallLog l = new LlmCallLog();
            l.setProviderId(providerId == null ? 1L : providerId);
            l.setSceneCode(scene);
            l.setRefId(refId);
            l.setPromptTokens(prompt);
            l.setCompletionTokens(completion);
            l.setTotalTokens(total);
            l.setLatencyMs(latency);
            l.setStatus("success");
            l.setCreateTime(LocalDateTime.now());
            llmCallLogMapper.insert(l);
            return l.getId();
        } catch (Exception e) {
            log.warn("[M5-10] llm_call_log 写入失败: {}", e.getMessage());
            return null;
        }
    }

    private void saveCallLogFail(String scene, long refId, Long providerId, String errMsg) {
        try {
            LlmCallLog l = new LlmCallLog();
            l.setProviderId(providerId == null ? 1L : providerId);
            l.setSceneCode(scene);
            l.setRefId(refId);
            l.setStatus("fail");
            l.setErrorMsg(errMsg);
            l.setCreateTime(LocalDateTime.now());
            llmCallLogMapper.insert(l);
        } catch (Exception e) {
            log.warn("[M5-10] llm_call_log-fail 写入失败: {}", e.getMessage());
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** 章节定义 */
    private record Section(String name, Pattern pattern) {}
}
