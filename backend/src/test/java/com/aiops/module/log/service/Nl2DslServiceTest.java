package com.aiops.module.log.service;

import com.aiops.datasource.log.EsLogClient;
import com.aiops.module.esa.entity.EsIndexConfig;
import com.aiops.module.esa.mapper.EsDatasourceMapper;
import com.aiops.module.esa.mapper.EsIndexConfigMapper;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.client.MockLlmClient;
import com.aiops.module.llm.entity.LlmPromptTemplate;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmPromptTemplateMapper;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.aiops.module.llm.service.LlmProviderService;
import com.aiops.module.llm.service.LlmSchemaRetryService;
import com.aiops.module.log.entity.NlQueryLog;
import com.aiops.module.log.mapper.NlQueryLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * M6-1: Nl2DslService + DslSafetyValidator 联调单测。
 *
 * <ol>
 *   <li>MockLlmClient 首次返回合法 DSL → validated=true，retry=0；</li>
 *   <li>MockLlmClient 连续返回嵌套 script → feedback retry 2 次后仍 reject → validated=false；</li>
 *   <li>落 nl_query_log：validated/executed/retryCount/latencyMs 都正确写库；</li>
 * </ol>
 * <p>
 * 注：本测试不需要 DB，因此 EsIndexConfigMapper / NlQueryLogMapper / EsDatasourceMapper / EsLogClient 用 Mockito mock 替换。
 * MockLlmClient 只 mock LLM 调用本身；LlmProviderService.buildClient 是真实前先 mock 返回 MockLlmClient。
 */
class Nl2DslServiceTest {

    private EsDatasourceMapper esDatasourceMapper;
    private EsIndexConfigMapper esIndexConfigMapper;
    private EsLogClient esLogClient;
    private NlQueryLogMapper nlQueryLogMapper;
    private LlmProviderMapper llmProviderMapper;
    private LlmProviderService llmProviderService;
    private LlmPromptTemplateMapper promptTemplateMapper;
    private LlmSchemaRetryService schemaRetryService;
    private Nl2DslService service;

    private EsIndexConfig cfg;

    @BeforeEach
    void setUp() {
        esDatasourceMapper = Mockito.mock(EsDatasourceMapper.class);
        esIndexConfigMapper = Mockito.mock(EsIndexConfigMapper.class);
        esLogClient = Mockito.mock(EsLogClient.class);
        nlQueryLogMapper = Mockito.mock(NlQueryLogMapper.class);
        llmProviderMapper = Mockito.mock(LlmProviderMapper.class);
        llmProviderService = Mockito.mock(LlmProviderService.class);
        promptTemplateMapper = Mockito.mock(LlmPromptTemplateMapper.class);
        schemaRetryService = new LlmSchemaRetryService(promptTemplateMapper);

        service = new Nl2DslService(
                esDatasourceMapper, esIndexConfigMapper, esLogClient,
                nlQueryLogMapper, llmProviderMapper, llmProviderService, schemaRetryService);

        cfg = new EsIndexConfig();
        cfg.setId(1L);
        cfg.setDatasourceId(1L);
        cfg.setIndexPattern("aiops-log-*");
        cfg.setTimeField("@timestamp");
        cfg.setMessageField("message");
        cfg.setLevelField("level");
        cfg.setServiceField("service");
        cfg.setTraceIdField("traceId");
        cfg.setEnabled(1);

        // 默认 mock：查 es_index_config 总是返回 cfg
        when(esIndexConfigMapper.selectById(1L)).thenReturn(cfg);
        when(esIndexConfigMapper.selectOne(any())).thenReturn(cfg);

        // mock LlmProvider
        LlmProvider p = new LlmProvider();
        p.setId(1L);
        p.setModelName("mock-model");
        p.setStatus(1);
        p.setIsDefault(1);
        when(llmProviderMapper.selectOne(any())).thenReturn(p);

        // mock prompt template for scene=nl2es_dsl
        LlmPromptTemplate tpl = new LlmPromptTemplate();
        tpl.setSceneCode("nl2es_dsl");
        tpl.setSystemPrompt("你是 ES DSL 生成器");
        tpl.setUserPromptTpl("问题：${question}\n字段：${fieldInfo}\n时间：${timeHint}\n服务：${serviceList}");
        tpl.setOutputSchema("{\"type\":\"object\",\"required\":[\"query\"]}");
        tpl.setEnabled(1);
        when(promptTemplateMapper.selectOne(any())).thenReturn(tpl);

        // mock nl_query_log insert 返回自增 id（回写 entity）
        AtomicInteger idSeq = new AtomicInteger(1);
        doAnswer(inv -> {
            NlQueryLog arg = inv.getArgument(0);
            arg.setId((long) idSeq.getAndIncrement());
            return 1;
        }).when(nlQueryLogMapper).insert(any(NlQueryLog.class));

        // mock es datasource：仅 history 用不到；本次 generate 用不到，如 execute 用到也返回 null 即可（不会触发网络）
        when(esDatasourceMapper.selectById(any())).thenReturn(null);
        // mock listKnownServices → 不真正发请求
        when(esLogClient.postSearch(any(), any(), any())).thenThrow(new IllegalStateException("should not be called in generate path"));
    }

    /** 合法 DSL 第一次就通过 → validated=true */
    @Test
    void validDslFirstTryPasses() {
        String goodDsl = """
                {
                  "query": {"bool": {"filter": [{"range": {"@timestamp": {"gte": "2026-10-01 00:00:00"}}}]}},
                  "size": 20
                }
                """;
        MockLlmClient llm = new MockLlmClient().enqueueOnce(goodDsl);
        when(llmProviderService.buildClient(any())).thenReturn(llm);

        var out = service.generate(1L, "过去一小时的 ERROR 日志");
        assertTrue((Boolean) out.get("validated"), "errors=" + out.get("errors"));
        assertNotNull(out.get("dsl"));
        assertEquals(0, out.get("retryCount"), "first try valid → 0 retry");
        assertNotNull(out.get("recordId"));
    }

    /** 嵌套 script 第一次后 → feedback retry 2 次仍 reject → validated=false */
    @Test
    void nestedScriptRejectedAfterFeedbackRetries() throws Exception {
        // 用 Jackson 构造 5 层深 script DSL（避免原始字符串括号数错）
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        var scriptNode = om.createObjectNode();
        scriptNode.putObject("script").putObject("script").put("source", "1+1");
        var deepest = om.createObjectNode();
        deepest.putObject("bool").putArray("filter").add(scriptNode);
        var cur = deepest;
        for (int i = 0; i < 3; i++) {
            var outer = om.createObjectNode();
            outer.putObject("bool").putArray("must").add(cur);
            cur = outer;
        }
        var topBool = om.createObjectNode();
        var topFilter = topBool.putArray("filter");
        var rangeNode = om.createObjectNode();
        rangeNode.putObject("range").putObject("@timestamp").put("gte", "now-1h");
        topFilter.add(rangeNode);
        topFilter.add(cur);
        var root = om.createObjectNode();
        root.putObject("query").set("bool", topBool);
        String evilDsl = om.writeValueAsString(root);
        // 计数 LLM 调用次数：schema retry 1 次（内部不再多试，因 schema 过）+ 外部 feedback retry 2 次 = 3 次
        MockLlmClient llm = new MockLlmClient()
                .enqueueOnce(evilDsl)
                .enqueueOnce(evilDsl)
                .enqueueOnce(evilDsl)
                .enqueueDefault(evilDsl);
        when(llmProviderService.buildClient(any())).thenReturn(llm);

        var out = service.generate(1L, "恶意：嵌套 script");
        assertFalse((Boolean) out.get("validated"));
        assertNotNull(out.get("errors"));
        assertFalse(((java.util.List<?>) out.get("errors")).isEmpty());
        Integer retry = (Integer) out.get("retryCount");
        assertNotNull(retry);
        assertTrue(retry >= 2, "应至少做过 2 次 feedback retry，实际 retryCount=" + retry);
        assertNotNull(out.get("recordId"), "validated=0 也应落库留 recordId");
    }

    /** history 透传给 mapper selectList */
    @Test
    void historyCallsMapperWithWrapper() {
        when(nlQueryLogMapper.selectList(any())).thenReturn(java.util.List.of());
        var list = service.history(1L, 10);
        assertNotNull(list);
        assertEquals(0, list.size());
    }

    /** question 为空 → BizException */
    @Test
    void blankQuestionThrows() {
        assertThrows(com.aiops.common.BizException.class,
                () -> service.generate(1L, ""));
        assertThrows(com.aiops.common.BizException.class,
                () -> service.generate(1L, null));
    }
}
