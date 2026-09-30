package com.aiops.module.kb.service;

import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.kb.entity.KbFaultCase;
import com.aiops.module.kb.entity.KbSimilarityLog;
import com.aiops.module.kb.mapper.KbFaultCaseMapper;
import com.aiops.module.kb.mapper.KbSimilarityLogMapper;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.aiops.module.llm.service.LlmProviderService;
import com.aiops.module.kb.service.SimilarCaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * M5-11 相似案例 service 单测（不走真实 LLM，用 stub/mocks）。
 * 关键用例：
 *   - top-K 排序正确
 *   - score < 0.75 被丢弃
 *   - LIKE 兜底路径返回合理结果
 *   - kb_similarity_log 落
 */
class SimilarCaseServiceTest {

    private KbFaultCaseMapper kbFaultCaseMapper;
    private KbSimilarityLogMapper kbSimilarityLogMapper;
    private AlertIncidentMapper alertIncidentMapper;
    private LlmProviderMapper llmProviderMapper;
    private LlmProviderService llmProviderService;
    private SimilarCaseServiceImpl svc;

    @BeforeEach
    void setUp() {
        kbFaultCaseMapper = Mockito.mock(KbFaultCaseMapper.class);
        kbSimilarityLogMapper = Mockito.mock(KbSimilarityLogMapper.class);
        alertIncidentMapper = Mockito.mock(AlertIncidentMapper.class);
        llmProviderMapper = Mockito.mock(LlmProviderMapper.class);
        llmProviderService = Mockito.mock(LlmProviderService.class);
        svc = new SimilarCaseServiceImpl(
                kbFaultCaseMapper, kbSimilarityLogMapper, alertIncidentMapper,
                llmProviderMapper, llmProviderService);
    }

    /* ============= 测试 1：Embedding-based 顶级排序 ============= */

    @Test
    void findsCasesByEmbeddingAndSortsByScore() {
        // incident: "cpu high usage"
        AlertIncident inc = new AlertIncident();
        inc.setId(1L);
        inc.setTitle("cpu high");
        when(alertIncidentMapper.selectById(1L)).thenReturn(inc);

        // LLM provider 支持嵌入（确保 embedding path 被选择）
        LlmProvider p = new LlmProvider();
        p.setId(1L);
        p.setIsDefault(1);
        p.setStatus(1);
        p.setEmbeddingModel("text-embedding-v1");
        when(llmProviderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(p);
        LlmClient client = Mockito.mock(LlmClient.class);
        // query embedding = [1,0,0]
        when(client.embed(any(String.class))).thenReturn(List.of(1f, 0f, 0f));
        when(llmProviderService.buildClient(any())).thenReturn(client);

        // 两个候选 case： c1=[0.9,0.1,0]=similar to query; c2=[0,1,0]=far → score 0
        KbFaultCase c1 = mkCase(1L, "cpu issue", 0.9f, 0.1f, 0f);
        KbFaultCase c2 = mkCase(2L, "db issue", 0f, 1f, 0f);
        when(kbFaultCaseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(c1, c2));

        var out = svc.findSimilarCases(1L, 2);

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> cases = (List<java.util.Map<String, Object>>) out.get("cases");
        assertEquals(1, cases.size() ,  "equal 1 cos score>=0.75 should rule out c2: " + cases);
        assertEquals(1L, cases.get(0).get("caseId"));
        double sim = ((Number) cases.get(0).get("similarity")).doubleValue();
        // cosine([1,0,0], [0.9,0.1,0]) = 0.9 / sqrt(0.81+0.01) ≈ 0.9938
        assertEquals(0.9938, sim, 0.005);

        // 落 log
        verify(kbSimilarityLogMapper, atLeastOnce()).insert(any(KbSimilarityLog.class));
    }

    /* ============= 测试 2：score 过滤阈值 0.75 ============= */

    @Test
    void dropsCasesBelowThreshold() {
        AlertIncident inc = new AlertIncident();
        inc.setId(2L);
        inc.setTitle("some");
        when(alertIncidentMapper.selectById(2L)).thenReturn(inc);

        LlmProvider p = new LlmProvider();
        p.setId(1L); p.setIsDefault(1); p.setStatus(1); p.setEmbeddingModel("text-embedding-v1");
        when(llmProviderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(p);
        LlmClient client = Mockito.mock(LlmClient.class);
        when(client.embed(any(String.class))).thenReturn(List.of(1f, 0f));
        when(llmProviderService.buildClient(any())).thenReturn(client);

        // cosine([1,0], [0,1])=0 → dropped; cosine([1,0],[0.5,0.5])=0.707 → dropped
        KbFaultCase far = mkCase(10L, "db", 0f, 1f);
        KbFaultCase close = mkCase(11L, "other", 0.5f, 0.5f);
        when(kbFaultCaseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(far, close));

        var out = svc.findSimilarCases(2L, 3);

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> cases = (List<java.util.Map<String, Object>>) out.get("cases");
        assertEquals(0, cases.size(), "all scores < 0.75 should be dropped: " + cases);
    }

    /* ============= 测试 3：LIKE 兜底路径 ============= */

    @Test
    void fallsBackToLIKEWhenEmbedFails() {
        AlertIncident inc = new AlertIncident();
        inc.setId(3L);
        inc.setTitle("cpu.usage high警告 50%");
        when(alertIncidentMapper.selectById(3L)).thenReturn(inc);

        // provider 没有 embedding，触发 LIKE
        LlmProvider p = new LlmProvider();
        p.setId(1L); p.setIsDefault(1); p.setStatus(1); p.setEmbeddingModel(null);
        when(llmProviderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(p);

        KbFaultCase c1 = new KbFaultCase();
        c1.setId(100L);
        c1.setTitle("CPU 高负载告警");
        c1.setSymptom("cpu.usage 突增到 90%");
        c1.setEmbeddingStatus("done");
        KbFaultCase c2 = new KbFaultCase();
        c2.setId(101L);
        c2.setTitle("DB 连接超时");
        c2.setSymptom("order-service transaction wait");
        c2.setEmbeddingStatus("done");
        when(kbFaultCaseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(c1, c2));

        var out = svc.findSimilarCases(3L, 3);

        assertEquals("like_fallback", out.get("matchType"));
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> cases = (List<java.util.Map<String, Object>>) out.get("cases");
        assertFalse(cases.isEmpty());
        // CPU 场景下 case#100 应排名第一
        assertEquals(100L, cases.get(0).get("caseId"));
    }

    /* ============= 测试 4：kb_similarity_log 落 ============= */

    @Test
    void writesSimilarityLogForEachMatch() {
        AlertIncident inc = new AlertIncident();
        inc.setId(9L);
        inc.setTitle("cpu");
        when(alertIncidentMapper.selectById(9L)).thenReturn(inc);

        LlmProvider p = new LlmProvider();
        p.setId(1L); p.setIsDefault(1); p.setStatus(1); p.setEmbeddingModel("text-embedding-v1");
        when(llmProviderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(p);
        LlmClient client = Mockito.mock(LlmClient.class);
        when(client.embed(any(String.class))).thenReturn(List.of(1f, 0f, 0f));
        when(llmProviderService.buildClient(any())).thenReturn(client);

        // High cosine: same direction as query
        KbFaultCase c1 = mkCase(200L, "match1", 1f, 0f, 0f);
        when(kbFaultCaseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(c1));

        svc.findSimilarCases(9L, 1);

        verify(kbSimilarityLogMapper, times(1)).insert(any(KbSimilarityLog.class));
    }

    /* ============= 辅助：建例 ============= */

    private KbFaultCase mkCase(Long id, String title, float x, float y) {
        KbFaultCase c = new KbFaultCase();
        c.setId(id);
        c.setTitle(title);
        c.setEmbedding("[ " + x + ", " + y + " ]");
        c.setEmbeddingStatus("done");
        return c;
    }

    private KbFaultCase mkCase(Long id, String title, float x, float y, float z) {
        KbFaultCase c = new KbFaultCase();
        c.setId(id);
        c.setTitle(title);
        c.setEmbedding("[" + x + "," + y + "," + z + "]");
        c.setEmbeddingStatus("done");
        return c;
    }
}
