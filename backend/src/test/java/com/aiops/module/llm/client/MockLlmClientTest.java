package com.aiops.module.llm.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5-1：Mock 实现 + 异常场景覆盖（论文"可靠性保障"章节实现）。
 */
class MockLlmClientTest {

    @Test
    void chatReturnsEnqueuedJson() {
        MockLlmClient c = new MockLlmClient().enqueueOnce("{\"a\":1}");
        LlmClient.LlmResponse r = c.chat(new LlmClient.LlmRequest("m", List.of(), 0.3, 100, false));
        assertEquals("{\"a\":1}", r.content());
        assertTrue(r.totalTokens() > 0);
    }

    @Test
    void chatUsesDefaultWhenScriptEmpty() {
        MockLlmClient c = new MockLlmClient();
        LlmClient.LlmResponse r = c.chat(new LlmClient.LlmRequest("m", List.of(), 0.3, 100, false));
        assertNotNull(r.content());
        assertTrue(r.content().contains("summary"));
    }

    @Test
    void chatThrowsOnError() {
        MockLlmClient c = new MockLlmClient().enqueueError("LLM 500");
        assertThrows(IllegalStateException.class,
                () -> c.chat(new LlmClient.LlmRequest("m", List.of(), 0.3, 100, false)));
    }

    @Test
    void chatThrowsOnTimeout() {
        MockLlmClient c = new MockLlmClient().enqueueTimeout(5000);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> c.chat(new LlmClient.LlmRequest("m", List.of(), 0.3, 100, false)));
        assertTrue(ex.getMessage().contains("timed out"));
    }

    @Test
    void chatMarkdownWrappedReturnsFencedContent() {
        MockLlmClient c = new MockLlmClient().enqueueMarkdownWrapped("{\"x\":2}");
        LlmClient.LlmResponse r = c.chat(new LlmClient.LlmRequest("m", List.of(), 0.3, 100, false));
        assertTrue(r.content().startsWith("```"));
        assertTrue(r.content().contains("{\"x\":2}"));
    }

    @Test
    void chatSequenceConsumesInOrder() {
        MockLlmClient c = new MockLlmClient()
                .enqueueOnce("first")
                .enqueueOnce("second")
                .enqueueDefault("default");
        LlmClient.LlmRequest req = new LlmClient.LlmRequest("m", List.of(), 0.3, 100, false);
        assertEquals("first", c.chat(req).content());
        assertEquals("second", c.chat(req).content());
        assertEquals("default", c.chat(req).content());
        assertEquals("default", c.chat(req).content());
    }

    @Test
    void chatStreamSplitsTokens() {
        String payload = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        MockLlmClient c = new MockLlmClient().enqueueOnce(payload);
        StringBuilder sb = new StringBuilder();
        AtomicInteger done = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<>();
        c.chatStream(new LlmClient.LlmRequest("m", List.of(), 0.3, 100, true),
                sb::append, done::incrementAndGet, err::set);
        assertNull(err.get());
        assertEquals(1, done.get());
        assertEquals(payload, sb.toString());
    }

    @Test
    void embedReturnsVector() {
        MockLlmClient c = new MockLlmClient();
        List<Float> v = c.embed("hello");
        assertEquals(8, v.size());
    }

    @Test
    void testConnectionAlwaysTrue() {
        assertTrue(new MockLlmClient().testConnection());
    }
}
