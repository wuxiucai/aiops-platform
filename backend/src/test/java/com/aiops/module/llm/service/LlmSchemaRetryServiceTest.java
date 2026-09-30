package com.aiops.module.llm.service;

import com.aiops.common.BizException;
import com.aiops.module.llm.client.MockLlmClient;
import com.aiops.module.llm.entity.LlmPromptTemplate;
import com.aiops.module.llm.mapper.LlmPromptTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M5-6：MockLlmClient 返回非法 JSON → 验证最多 2 次重试 + 透传错误原因。
 */
class LlmSchemaRetryServiceTest {

    private LlmPromptTemplateMapper tplMapper;
    private LlmSchemaRetryService svc;

    @BeforeEach
    void setUp() {
        tplMapper = Mockito.mock(LlmPromptTemplateMapper.class);
        svc = new LlmSchemaRetryService(tplMapper);

        LlmPromptTemplate tpl = new LlmPromptTemplate();
        tpl.setSceneCode("log_explain");
        tpl.setSystemPrompt("你是 SRE");
        tpl.setUserPromptTpl("分析：\n${inputSummary}\n严格 JSON");
        tpl.setOutputSchema("{\"type\":\"object\",\"required\":[\"summary\",\"confidence\"],"
                + "\"properties\":{\"summary\":{\"type\":\"string\",\"minLength\":5},"
                + "\"confidence\":{\"type\":\"number\",\"minimum\":0,\"maximum\":1}}}");
        when(tplMapper.selectOne(Mockito.<LambdaQueryWrapper<LlmPromptTemplate>>any()))
                .thenReturn(tpl);
    }

    @Test
    void succeedsOnFirstTryWhenValidJson() {
        MockLlmClient llm = new MockLlmClient()
                .enqueueOnce("{\"summary\":\"OK summary\",\"confidence\":0.8}");
        var r = svc.callWithSchema(llm, "deepseek-chat", "log_explain", "context x");
        assertEquals(1, r.attempts());
        assertEquals("OK summary", r.parsed().get("summary").asText());
    }

    @Test
    void recoversOnSecondTryAfterMarkdown() {
        MockLlmClient llm = new MockLlmClient()
                .enqueueMarkdownWrapped("{\"summary\":\"good summary\",\"confidence\":0.8}");
        var r = svc.callWithSchema(llm, "deepseek-chat", "log_explain", "context x");
        assertEquals(1, r.attempts()); // markdown 围裹 pertama 提取
        assertEquals("good summary", r.parsed().get("summary").asText());
    }

    @Test
    void retriesOnInvalidJsonThenSucceeds() {
        MockLlmClient llm = new MockLlmClient()
                .enqueueOnce("not a json")                                    // 第 1 次：不是 JSON
                .enqueueOnce("{\"summary\":\"fixed summary\",\"confidence\":0.5}"); // 第 2 次：合法
        var r = svc.callWithSchema(llm, "deepseek-chat", "log_explain", "in");
        assertEquals(2, r.attempts());
        assertEquals("fixed summary", r.parsed().get("summary").asText());
    }

    @Test
    void retriesOnSchemaViolationThenSucceeds() {
        MockLlmClient llm = new MockLlmClient()
                .enqueueOnce("{\"wrongField\":true}") // schema 违反
                .enqueueOnce("{\"summary\":\"fixed again\",\"confidence\":0.6}");
        var r = svc.callWithSchema(llm, "deepseek-chat", "log_explain", "in");
        assertEquals(2, r.attempts());
    }

    @Test
    void failsAfterMaxRetriesWithErrorHint() {
        MockLlmClient llm = new MockLlmClient()
                .enqueueOnce("x1")
                .enqueueOnce("x2")
                .enqueueOnce("x3");
        BizException ex = assertThrows(BizException.class,
                () -> svc.callWithSchema(llm, "deepseek-chat", "log_explain", "in"));
        assertTrue(ex.getMessage().contains("schema 校验")
                || ex.getMessage().contains("未通过"), "失败消息应含错误原因：" + ex.getMessage());
    }

    @Test
    void extractJsonHandlesMarkdownFence() {
        String fenced = "```json\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", LlmSchemaRetryService.extractJson(fenced));
    }

    @Test
    void extractJsonHandlesLeadingText() {
        String s = " Sure! Here is the JSON you asked for:\n{\"a\":1} Hope that helps";
        assertEquals("{\"a\":1}", LlmSchemaRetryService.extractJson(s));
    }

    @Test
    void extractJsonReturnsNullForNoJson() {
        assertNull(LlmSchemaRetryService.extractJson("just text"));
        assertNull(LlmSchemaRetryService.extractJson(null));
    }
}
