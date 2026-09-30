package com.aiops.module.llm.service;

import com.aiops.module.alert.mapper.AlertRecordMapper;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.llm.builder.Nl2QueryContextBuilder;
import com.aiops.module.llm.client.MockLlmClient;
import com.aiops.module.llm.entity.LlmPromptTemplate;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmCallLogMapper;
import com.aiops.module.llm.mapper.LlmPromptTemplateMapper;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.aiops.module.monitor.mapper.MetricDefinitionMapper;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M5-9 nl2query 单测：MockLlmClient enqueueOnce 预定义 LLM 输出；mapper 全部 Mockito 打桩。
 * 覆盖：
 *   1. metric_query：LLM 返回 ["本机","order-service"] → 名字→id 反查
 *   2. metric_query：LLM 返回数字 id
 *   3. alert_query：返回结果 list
 *   4. incident_query
 *   5. unknown：examples != 0
 *   6. 第 1 次 not-json → 第 2 次 valid（attempts=2）
 */
class Nl2QueryServiceTest {

    private LlmProviderMapper providerMapper;
    private LlmProviderService providerService;
    private LlmSchemaRetryService schemaRetryService;
    private Nl2QueryContextBuilder contextBuilder;
    private LlmPromptTemplateMapper tplMapper;
    private LlmCallLogMapper callLogMapper;
    private MetricDataMapper metricDataMapper;
    private AlertRecordMapper alertRecordMapper;
    private AlertIncidentMapper alertIncidentMapper;
    private MonitorTargetMapper monitorTargetMapper;
    private MetricDefinitionMapper metricDefinitionMapper;

    private MockLlmClient mockLlm;
    private Nl2QueryService svc;

    @BeforeEach
    void setUp() {
        providerMapper = Mockito.mock(LlmProviderMapper.class);
        providerService = Mockito.mock(LlmProviderService.class);
        tplMapper = Mockito.mock(LlmPromptTemplateMapper.class);
        callLogMapper = Mockito.mock(LlmCallLogMapper.class);
        metricDataMapper = Mockito.mock(MetricDataMapper.class);
        alertRecordMapper = Mockito.mock(AlertRecordMapper.class);
        alertIncidentMapper = Mockito.mock(AlertIncidentMapper.class);
        monitorTargetMapper = Mockito.mock(MonitorTargetMapper.class);
        metricDefinitionMapper = Mockito.mock(MetricDefinitionMapper.class);

        schemaRetryService = new LlmSchemaRetryService(tplMapper);
        contextBuilder = new Nl2QueryContextBuilder(metricDefinitionMapper, monitorTargetMapper);

        mockLlm = new MockLlmClient();

        LlmProvider provider = new LlmProvider();
        provider.setId(1L);
        provider.setName("test");
        provider.setProviderType("ollama");
        provider.setStatus(1);
        provider.setIsDefault(1);
        provider.setModelName("test-model");
        provider.setTimeoutMs(5000);
        provider.setBaseUrl("http://localhost:11434");

        when(providerMapper.selectOne(Mockito.<LambdaQueryWrapper<LlmProvider>>any()))
                .thenReturn(provider);
        when(providerService.buildClient(any())).thenReturn(mockLlm);

        // prompt template (nl2query)
        LlmPromptTemplate tpl = new LlmPromptTemplate();
        tpl.setSceneCode("nl2query");
        tpl.setEnabled(1);
        tpl.setSystemPrompt("你是运维助手");
        tpl.setUserPromptTpl("Metric:\n${metricList}\nTarget:\n${targetList}\nTime:${timeHint}\nQ:${question}");
        tpl.setOutputSchema("{\"type\":\"object\",\"required\":[\"queryType\",\"targetIds\",\"timeRange\"],"
                + "\"properties\":{\"queryType\":{\"enum\":[\"metric_query\",\"alert_query\",\"incident_query\",\"unknown\"]},"
                + "\"targetIds\":{\"type\":\"array\"},\"timeRange\":{\"type\":\"object\"},\"metricKeys\":{\"type\":\"array\"}},"
                + "\"additionalProperties\":true}");
        when(tplMapper.selectOne(Mockito.<LambdaQueryWrapper<LlmPromptTemplate>>any()))
                .thenReturn(tpl);

        // 测试 monitor_target：本机(id=1) / order-service(id=2)
        MonitorTarget t1 = new MonitorTarget();
        t1.setId(1L);
        t1.setName("本机");
        t1.setIp("127.0.0.1");
        t1.setLogServiceName("localhost");
        t1.setDeleted(0);
        MonitorTarget t2 = new MonitorTarget();
        t2.setId(2L);
        t2.setName("order-service");
        t2.setIp("10.0.0.2");
        t2.setLogServiceName("order-service");
        t2.setDeleted(0);
        when(monitorTargetMapper.selectList(Mockito.<LambdaQueryWrapper<MonitorTarget>>any()))
                .thenReturn(List.of(t1, t2));
        when(metricDefinitionMapper.selectList(Mockito.<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.aiops.module.monitor.entity.MetricDefinition>>any()))
                .thenReturn(List.of());

        svc = new Nl2QueryService(providerMapper, providerService, schemaRetryService,
                contextBuilder, tplMapper, callLogMapper,
                metricDataMapper, alertRecordMapper, alertIncidentMapper);
    }

    @Test
    void metricQueryByNameResolvesTargetIds() {
        mockLlm.enqueueOnce("{\"queryType\":\"metric_query\",\"targetIds\":[\"本机\",\"order-service\"],"
                + "\"metricKeys\":[\"cpu.usage\"],"
                + "\"timeRange\":{\"start\":\"2026-09-30 10:00:00\",\"end\":\"2026-09-30 11:00:00\"}}");
        // explain call: 第二次 chat（不需要再 enqueue，因为还有 explain fallback "见 query 字段"）
        when(metricDataMapper.selectList(Mockito.<LambdaQueryWrapper<com.aiops.module.monitor.entity.MetricData>>any()))
                .thenReturn(List.of());

        Map<String, Object> resp = svc.nl2query("cpu 最高的服务");
        assertEquals(Boolean.FALSE, resp.get("isLlmFallback"));
        Object q = resp.get("query");
        assertTrue(q instanceof com.fasterxml.jackson.databind.node.ObjectNode);
        com.fasterxml.jackson.databind.node.ObjectNode qn =
                (com.fasterxml.jackson.databind.node.ObjectNode) q;
        assertEquals("metric_query", qn.get("queryType").asText());
        // 名字解析为 [1,2]（顺序保持）
        assertEquals(2, qn.get("targetIds").size());
        assertEquals(1L, qn.get("targetIds").get(0).asLong());
        assertEquals(2L, qn.get("targetIds").get(1).asLong());
        assertEquals(1, resp.get("attempts"));
        assertNotNull(resp.get("results"));
    }

    @Test
    void metricQueryByNumericIdResolvesDirectly() {
        mockLlm.enqueueOnce("{\"queryType\":\"metric_query\",\"targetIds\":[\"1\",2],"
                + "\"metricKeys\":[\"mem.usage\"],"
                + "\"timeRange\":{\"start\":\"2026-09-30 10:00:00\",\"end\":\"2026-09-30 11:00:00\"}}");
        when(metricDataMapper.selectList(Mockito.<LambdaQueryWrapper<com.aiops.module.monitor.entity.MetricData>>any()))
                .thenReturn(List.of());

        Map<String, Object> resp = svc.nl2query("mem usage");
        com.fasterxml.jackson.databind.node.ObjectNode qn =
                (com.fasterxml.jackson.databind.node.ObjectNode) resp.get("query");
        assertEquals(2, qn.get("targetIds").size());
        assertEquals(1L, qn.get("targetIds").get(0).asLong());
        assertEquals(2L, qn.get("targetIds").get(1).asLong());
    }

    @Test
    void alertQueryReturnsResultsList() {
        mockLlm.enqueueOnce("{\"queryType\":\"alert_query\",\"targetIds\":[2],"
                + "\"timeRange\":{\"start\":\"2026-09-30 10:00:00\",\"end\":\"2026-09-30 11:00:00\"}}");
        when(alertRecordMapper.selectList(Mockito.<LambdaQueryWrapper<com.aiops.module.alert.entity.AlertRecord>>any()))
                .thenReturn(List.of());

        Map<String, Object> resp = svc.nl2query("order-service 有哪些告警");
        assertEquals(Boolean.FALSE, resp.get("isLlmFallback"));
        List<?> results = (List<?>) resp.get("results");
        assertNotNull(results);
        com.fasterxml.jackson.databind.node.ObjectNode qn =
                (com.fasterxml.jackson.databind.node.ObjectNode) resp.get("query");
        assertEquals("alert_query", qn.get("queryType").asText());
    }

    @Test
    void incidentQueryReturnsResultsList() {
        mockLlm.enqueueOnce("{\"queryType\":\"incident_query\",\"targetIds\":[],"
                + "\"timeRange\":{\"start\":\"2026-09-30 10:00:00\",\"end\":\"2026-09-30 11:00:00\"}}");
        when(alertIncidentMapper.selectList(Mockito.<LambdaQueryWrapper<AlertIncident>>any()))
                .thenReturn(List.of());

        Map<String, Object> resp = svc.nl2query("最近事件");
        assertEquals(Boolean.FALSE, resp.get("isLlmFallback"));
        com.fasterxml.jackson.databind.node.ObjectNode qn =
                (com.fasterxml.jackson.databind.node.ObjectNode) resp.get("query");
        assertEquals("incident_query", qn.get("queryType").asText());
    }

    @Test
    void unknownReturnsEmptyResultsAndExamples() {
        mockLlm.enqueueOnce("{\"queryType\":\"unknown\",\"targetIds\":[],"
                + "\"timeRange\":{\"start\":\"2026-09-30 10:00:00\",\"end\":\"2026-09-30 11:00:00\"}}");
        Map<String, Object> resp = svc.nl2query("任意无法识别问题");
        assertEquals(Boolean.FALSE, resp.get("isLlmFallback"));
        com.fasterxml.jackson.databind.node.ObjectNode qn =
                (com.fasterxml.jackson.databind.node.ObjectNode) resp.get("query");
        assertEquals("unknown", qn.get("queryType").asText());
        List<?> results = (List<?>) resp.get("results");
        assertTrue(results == null || results.isEmpty());
        assertNotNull(qn.get("examples"));
        assertTrue(qn.get("examples").size() > 0, "unknown 必须返回示例");
    }

    @Test
    void schemaFailThenSucceed_attemptsIsTwo() {
        mockLlm.enqueueOnce("not json at all")
                .enqueueOnce("{\"queryType\":\"metric_query\",\"targetIds\":[1],"
                        + "\"metricKeys\":[\"cpu.usage\"],"
                        + "\"timeRange\":{\"start\":\"2026-09-30 10:00:00\",\"end\":\"2026-09-30 11:00:00\"}}");
        when(metricDataMapper.selectList(Mockito.<LambdaQueryWrapper<com.aiops.module.monitor.entity.MetricData>>any()))
                .thenReturn(List.of());

        Map<String, Object> resp = svc.nl2query("cpu");
        assertEquals(2, resp.get("attempts"), "重试一次后第二次成功，attempts 应为 2");
        assertEquals(Boolean.FALSE, resp.get("isLlmFallback"));
    }

    @Test
    void emptyQuestionThrowsBizException() {
        assertThrows(com.aiops.common.BizException.class, () -> svc.nl2query(""));
        assertThrows(com.aiops.common.BizException.class, () -> svc.nl2query(null));
    }

    @Test
    void allRetriesFailReturnsFallback() {
        mockLlm.enqueueOnce("x").enqueueOnce("y").enqueueOnce("z");
        Map<String, Object> resp = svc.nl2query("test");
        assertEquals(Boolean.TRUE, resp.get("isLlmFallback"));
        com.fasterxml.jackson.databind.node.ObjectNode qn =
                (com.fasterxml.jackson.databind.node.ObjectNode) resp.get("query");
        assertEquals("unknown", qn.get("queryType").asText());
        List<?> results = (List<?>) resp.get("results");
        assertTrue(results == null || results.isEmpty());
        assertEquals(3, qn.get("examples").size(), "兜底必须给 3 个示例");
    }

    @Test
    void refIdIsStableForSameQuestion() {
        long a = Nl2QueryService.questionRefId("hello");
        long b = Nl2QueryService.questionRefId("hello");
        long c = Nl2QueryService.questionRefId("world");
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void timeHintIsReasonableFormat() {
        String hint = contextBuilder.buildTimeHint();
        assertNotNull(hint);
        assertTrue(hint.contains("Asia/Shanghai"));
        assertTrue(LocalDateTime.now().minusMinutes(1).isBefore(LocalDateTime.now()));
    }
}
