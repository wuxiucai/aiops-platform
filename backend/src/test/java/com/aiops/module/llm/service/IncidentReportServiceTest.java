package com.aiops.module.llm.service;

import com.aiops.common.BizException;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.llm.builder.IncidentReportContextBuilder;
import com.aiops.module.llm.client.MockLlmClient;
import com.aiops.module.llm.entity.LlmCallLog;
import com.aiops.module.llm.entity.LlmPromptTemplate;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmCallLogMapper;
import com.aiops.module.llm.mapper.LlmPromptTemplateMapper;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * M5-10：MockLlmClient / mock mapper 编排 6 个场景：
 *   A. 首次生成 → attempts=1，cached=false，写库一次 llm_report + llm_call_log
 *   B. 二次调用 → cached=true，不调 LLM，不写新 llm_call_log
 *   C. 首次缺 "五、" → 二次补 hint 通过 → attempts=2
 *   D. 二次仍缺 → BizException，含 "五"/"缺"，落 fail 日志
 *   E. 长度 < 500 → 视作缺段，触发重试
 *   F. validate 接受 # 变体 + "五、恢复过程" 别名
 */
class IncidentReportServiceTest {

    private AlertIncidentMapper incidentMapper;
    private IncidentReportContextBuilder contextBuilder;
    private LlmPromptTemplateMapper tplMapper;
    private LlmProviderMapper providerMapper;
    private LlmProviderService providerService;
    private LlmCallLogMapper callLogMapper;

    private IncidentReportService svc;

    private static final String GOOD_REPORT = buildGoodReport();
    private static final String MISSING_FIVE_REPORT = buildMissingFiveReport();

    @BeforeEach
    void setUp() {
        incidentMapper = mock(AlertIncidentMapper.class);
        contextBuilder = mock(IncidentReportContextBuilder.class);
        tplMapper = mock(LlmPromptTemplateMapper.class);
        providerMapper = mock(LlmProviderMapper.class);
        providerService = mock(LlmProviderService.class);
        callLogMapper = mock(LlmCallLogMapper.class);

        svc = new IncidentReportService(
                incidentMapper, contextBuilder, tplMapper,
                providerMapper, providerService, callLogMapper);

        // 默认 incident：无 llm_report
        AlertIncident inc = new AlertIncident();
        inc.setId(100L);
        inc.setTitle("支付服务 CPU 飙高");
        inc.setLevel("P1");
        inc.setStatus("resolved");
        inc.setStartTime(LocalDateTime.now().minusHours(1));
        inc.setEndTime(LocalDateTime.now());
        inc.setAlertCount(3);
        when(incidentMapper.selectById(100L)).thenReturn(inc);

        // 上下文
        IncidentReportContextBuilder.ReportContext ctx = new IncidentReportContextBuilder.ReportContext(
                "标题=X 级别=P1 状态=resolved",
                "[2026-09-30 10:00:00] target=1 metric=cpu value=95 status=resolved",
                "[2026-09-30 10:00:00] trigger: 触发",
                "【日志概况】总量：100 模板数：5",
                "指标 cpu.usage（10点,10min）：min=10 max=95 avg=40",
                "(知识库暂空)");
        when(contextBuilder.build(any(AlertIncident.class))).thenReturn(ctx);

        // prompt 模板
        LlmPromptTemplate tpl = new LlmPromptTemplate();
        tpl.setId(7L);
        tpl.setSceneCode("report");
        tpl.setSystemPrompt("你是 SRE");
        tpl.setUserPromptTpl("【故障事件】${incidentInfo}\n【告警列表】${alertList}\n【时间线】${timeline}\n【日志】${logSummary}\n【指标】${metricsSummary}");
        tpl.setEnabled(1);
        when(tplMapper.selectOne(Mockito.<LambdaQueryWrapper<LlmPromptTemplate>>any())).thenReturn(tpl);

        // provider
        LlmProvider provider = new LlmProvider();
        provider.setId(1L);
        provider.setStatus(1);
        provider.setModelName("mock-model");
        when(providerMapper.selectOne(Mockito.<LambdaQueryWrapper<LlmProvider>>any()))
                .thenReturn(provider);
    }

    /* ============== A. 首次生成：成功一次 ============== */

    @Test
    void firstCallGeneratesAndCachesReport() {
        MockLlmClient llm = new MockLlmClient().enqueueOnce(GOOD_REPORT);
        when(providerService.buildClient(any(LlmProvider.class))).thenReturn(llm);
        when(incidentMapper.updateById(any(AlertIncident.class))).thenReturn(1);
        when(callLogMapper.insert(any(LlmCallLog.class))).thenAnswer(inv -> {
            LlmCallLog l = inv.getArgument(0);
            l.setId(42L);
            return 1;
        });

        IncidentReportService.ReportResult r = svc.generateReport(100L);

        assertFalse(r.cached());
        assertEquals(1, r.attempts());
        assertTrue(r.markdown().contains("一、故障概述"));
        assertTrue(r.markdown().contains("六、改进措施"));
        assertTrue(r.markdown().length() >= IncidentReportService.MIN_MARKDOWN_CHARS);
        assertEquals(42L, r.llmCallLogId());
        assertTrue(r.totalTokens() > 0);
        // 写了一次 llm_report，且值匹配
        verify(incidentMapper, times(1)).updateById(argThat((AlertIncident a) ->
                GOOD_REPORT.equals(a.getLlmReport())));
        // 写了一次 llm_call_log
        verify(callLogMapper, times(1)).insert(any(LlmCallLog.class));
    }

    /* ============== B. 二次调用：命中缓存 ============== */

    @Test
    void secondCallReturnsCachedNoLlmNoLog() {
        // 已经被写入过 llm_report
        AlertIncident cached = new AlertIncident();
        cached.setId(100L);
        cached.setLlmReport(GOOD_REPORT);
        when(incidentMapper.selectById(100L)).thenReturn(cached);

        IncidentReportService.ReportResult r = svc.generateReport(100L);

        assertTrue(r.cached());
        assertEquals(0, r.attempts());
        assertEquals(GOOD_REPORT, r.markdown());
        // 不 build client
        verify(providerService, never()).buildClient(any(LlmProvider.class));
        // 不写新 call_log
        verify(callLogMapper, never()).insert(any(LlmCallLog.class));
        // 不更新 incident
        verify(incidentMapper, never()).updateById(any(AlertIncident.class));
    }

    /* ============== C. 首次缺 "五、"，重试补 hint 后成功 ============== */

    @Test
    void retriesWithHintWhenSectionMissing() {
        MockLlmClient llm = new MockLlmClient()
                .enqueueOnce(MISSING_FIVE_REPORT)   // 第 1 次漏 五、
                .enqueueOnce(GOOD_REPORT);          // 第 2 次补齐
        when(providerService.buildClient(any(LlmProvider.class))).thenReturn(llm);
        when(incidentMapper.updateById(any(AlertIncident.class))).thenReturn(1);
        when(callLogMapper.insert(any(LlmCallLog.class))).thenReturn(1);

        IncidentReportService.ReportResult r = svc.generateReport(100L);

        assertFalse(r.cached());
        assertEquals(2, r.attempts());
        assertEquals(GOOD_REPORT, r.markdown());
        verify(callLogMapper, times(1)).insert(any(LlmCallLog.class));
    }

    /* ============== D. 两次都缺 → BizException 含 "五"/"缺" ============== */

    @Test
    void throwsWhenSecondAttemptStillMissing() {
        MockLlmClient llm = new MockLlmClient()
                .enqueueOnce(MISSING_FIVE_REPORT)
                .enqueueOnce(MISSING_FIVE_REPORT);   // 第 2 次还漏
        when(providerService.buildClient(any(LlmProvider.class))).thenReturn(llm);
        when(callLogMapper.insert(any(LlmCallLog.class))).thenReturn(1);

        BizException ex = assertThrows(BizException.class,
                () -> svc.generateReport(100L));
        String msg = ex.getMessage();
        assertTrue(msg.contains("五") || msg.contains("缺"),
                "失败消息应包含缺失章节提示: " + msg);
        // 仍然落了 fail call_log
        verify(callLogMapper, atLeastOnce()).insert(argThat((LlmCallLog l) ->
                "fail".equals(l.getStatus())));
        // 不写 llm_report
        verify(incidentMapper, never()).updateById(any(AlertIncident.class));
    }

    /* ============== E. 长度不足 500 → 视作缺段，触发重试 ============== */

    @Test
    void treatsShortMarkdownAsMissing() {
        String tiny = "## 一、故障概述\nx\n## 二、影响范围\nx\n## 三、时间线\nx\n## 四、根因分析\nx\n## 五、处置过程\nx\n## 六、改进措施\nx";
        assertTrue(tiny.length() < IncidentReportService.MIN_MARKDOWN_CHARS);

        MockLlmClient llm = new MockLlmClient()
                .enqueueOnce(tiny)
                .enqueueOnce(GOOD_REPORT);
        when(providerService.buildClient(any(LlmProvider.class))).thenReturn(llm);
        when(incidentMapper.updateById(any(AlertIncident.class))).thenReturn(1);
        when(callLogMapper.insert(any(LlmCallLog.class))).thenReturn(1);

        IncidentReportService.ReportResult r = svc.generateReport(100L);
        assertEquals(2, r.attempts());
    }

    /* ============== F. 直接 validate 校验：变体标题识别 ============== */

    @Test
    void validateAcceptsHashVariantsAndRecoveryAlias() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 一、故障概述\n").append(pad()).append('\n');
        sb.append("# 二、影响范围\n").append(pad()).append('\n');
        sb.append("### 三、时间线\n").append(pad()).append('\n');
        sb.append("#### 四、根因分析\n").append(pad()).append('\n');
        sb.append("五、恢复过程\n").append(pad()).append('\n');  // 恢复也接受
        sb.append("## 六、改进措施\n").append(pad()).append('\n');
        List<String> missing = IncidentReportService.validate(sb.toString());
        assertTrue(missing.isEmpty(), "应接受 # 变体与 恢复过程，缺失：" + missing);
    }

    /* ============== 样本构造 ============== */

    private static String pad() {
        return "本次故障对上游支付链路造成了显著影响，值班同学迅速介入，初步定位为资源热点，"
                + "并联动 SRE、DBA、业务三方对核心链路做了复盘，确认了告警阈值的有效性。"
                + "后续会通过横向扩容与限流组合策略消除单点瓶颈，并对容量评估加入业务侧的纳入，请见下文分解。";
    }

    private static String buildGoodReport() {
        return """
                ## 一、故障概述
                2026-09-30 10:00 起，支付服务 primary-target-1 因 CPU 利用率持续高于 90%% 触发 P1 级告警。值班 SRE 介入后定位为正常业务高峰 + 慢查询叠加。%s
                ## 二、影响范围
                上游 order-service 调用支付接口的 P99 延迟从 80ms 上升到 1.2s；无资金损失，无数据丢失，仅影响延迟敏感场景。%s
                ## 三、时间线
                10:00 触发告警 → 10:03 聚合为 incident → 10:05 值班介入 → 10:20 限流上线 → 10:35 恢复。%s
                ## 四、根因分析
                (1) 业务高峰流量 3x；(2) 单条 SELECT 缺索引造成 CPU 飙升；(3) 自动扩容未启动。%s
                ## 五、处置过程
                临时限流 + 热点 key 缓存 + 紧急添加索引，21 分钟内恢复。%s
                ## 六、改进措施
                1. 对慢 SQL 增加每日离线分析；2. 把自动扩容从 5min 缩到 1min；3. 增加 P99 维度告警。%s
                """.formatted(pad(), pad(), pad(), pad(), pad(), pad());
    }

    private static String buildMissingFiveReport() {
        return """
                ## 一、故障概述
                2026-09-30 支付服务 CPU 飙高触发 P1 告警，影响范围涉及订单链路。%s
                ## 二、影响范围
                P99 延迟升至 1.2s。%s
                ## 三、时间线
                10:00 → 10:35 处理完毕。%s
                ## 四、根因分析
                慢 SQL + 业务高峰叠加。%s
                ## 六、改进措施
                加索引 + 自动扩容。%s
                """.formatted(pad(), pad(), pad(), pad(), pad());
    }
}
