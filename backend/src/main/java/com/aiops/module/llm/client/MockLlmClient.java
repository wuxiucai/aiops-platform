package com.aiops.module.llm.client;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * M5 补充约束 2：Mock 实现用于单测覆盖"可靠性保障"章节。
 * <p>
 * 用法（顺序敏感）：
 *   new MockLlmClient()
 *       .enqueueOnce("{\"a\":1}")    // 第 1 次返回固定 JSON
 *       .enqueueError("LLM 500")     // 第 2 次抛错
 *       .enqueueDefault("{\"ok\":1}");  // 脚本空后的惰性兜底
 * 覆盖场景：固定 JSON / 500 / 超时 / markdown 围栏 / 缺字段 / 兜底
 */
public class MockLlmClient implements LlmClient {

    private final Queue<Object> script = new ArrayDeque<>();
    private Object defaultResp = "{\"summary\":\"mock-summary\",\"likelyCause\":\"mock-cause\",\"suggestion\":\"mock-sugg\",\"confidence\":0.9}";
    private long simulatedLatencyMs = 20L;

    /* ================== 配置 ================== */

    public MockLlmClient enqueueOnce(String fixedJson) {
        script.offer(fixedJson);
        return this;
    }

    public MockLlmClient enqueueError(String message) {
        script.offer(new IllegalStateException(message));
        return this;
    }

    public MockLlmClient enqueueTimeout(long ms) {
        script.offer(new IllegalStateException("Read timed out after " + ms + "ms"));
        return this;
    }

    public MockLlmClient enqueueMarkdownWrapped(String innerJson) {
        script.offer("```json\n" + innerJson + "\n```");
        return this;
    }

    public MockLlmClient enqueueDefault(String fixed) {
        this.defaultResp = fixed;
        return this;
    }

    public MockLlmClient latency(long ms) {
        this.simulatedLatencyMs = Math.max(0, ms);
        return this;
    }

    /* ================== LlmClient ================== */

    @Override
    public LlmResponse chat(LlmRequest req) {
        sleep(simulatedLatencyMs);
        Object next = script.isEmpty() ? defaultResp : script.poll();
        if (next instanceof IllegalStateException e) throw e;
        String content = String.valueOf(next);
        int completion = Math.max(1, content.length() / 4);
        int prompt = req.messages() == null ? 0
                : req.messages().stream()
                        .mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum() / 4;
        return new LlmResponse(content, prompt, completion,
                prompt + completion, simulatedLatencyMs);
    }

    @Override
    public void chatStream(LlmRequest req, Consumer<String> onToken, Runnable onDone, Consumer<Throwable> onError) {
        try {
            LlmResponse resp = chat(req);
            String full = resp.content() == null ? "" : resp.content();
            for (int i = 0; i < full.length(); i += 32) {
                onToken.accept(full.substring(i, Math.min(i + 32, full.length())));
                sleep(5);
            }
            onDone.run();
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    @Override
    public List<Float> embed(String text) {
        return List.of((float) text.hashCode(), (float) text.length(), 0f, 0f, 0f, 0f, 0f, 0f);
    }

    @Override
    public boolean testConnection() {
        return true;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
