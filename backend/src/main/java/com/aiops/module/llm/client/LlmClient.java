package com.aiops.module.llm.client;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 客户端适配层接口（§7.1）
 */
public interface LlmClient {

    /** 同步对话 */
    LlmResponse chat(LlmRequest req);

    /** 流式对话：onToken 每段回调，onDone 结束，onError 失败 */
    void chatStream(LlmRequest req, Consumer<String> onToken, Runnable onDone, Consumer<Throwable> onError);

    /** 向量化；返回 null 表示不支持 */
    List<Float> embed(String text);

    /** 连通性测试 */
    boolean testConnection();

    record LlmMessage(String role, String content) {
    }

    record LlmRequest(String model, List<LlmMessage> messages, double temperature, int maxTokens, boolean stream) {
    }

    record LlmResponse(String content, int promptTokens, int completionTokens, int totalTokens, long latencyMs) {
    }
}
