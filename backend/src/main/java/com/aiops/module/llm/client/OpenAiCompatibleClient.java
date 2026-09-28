package com.aiops.module.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议客户端：一个实现覆盖 DeepSeek / 通义 / 智谱 / OpenAI。
 * POST {baseUrl}/chat/completions；embedding POST {baseUrl}/embeddings。
 */
@Slf4j
public class OpenAiCompatibleClient implements LlmClient {

    private final WebClient webClient;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatibleClient(String baseUrl, String apiKey, int timeoutMs, WebClient.Builder builder) {
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.webClient = builder.baseUrl(root)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.apiKey = apiKey;
    }

    @Override
    public LlmResponse chat(LlmRequest req) {
        long start = System.currentTimeMillis();
        Map<String, Object> body = Map.of(
                "model", req.model(),
                "messages", req.messages().stream()
                        .map(m -> Map.of("role", m.role(), "content", m.content())).toList(),
                "temperature", req.temperature(),
                "max_tokens", req.maxTokens(),
                "stream", false);
        String resp = webClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofMillis(120_000));
        long latency = System.currentTimeMillis() - start;
        try {
            JsonNode root = objectMapper.readTree(resp);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            JsonNode usage = root.path("usage");
            log.info("[LLM] chat 调用成功: model={}, latency={}ms, tokens={}",
                    req.model(), latency, usage.path("total_tokens").asInt());
            return new LlmResponse(content,
                    usage.path("prompt_tokens").asInt(),
                    usage.path("completion_tokens").asInt(),
                    usage.path("total_tokens").asInt(),
                    latency);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 响应解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void chatStream(LlmRequest req, Consumer<String> onToken, Runnable onDone, Consumer<Throwable> onError) {
        Map<String, Object> body = Map.of(
                "model", req.model(),
                "messages", req.messages().stream()
                        .map(m -> Map.of("role", m.role(), "content", m.content())).toList(),
                "temperature", req.temperature(),
                "max_tokens", req.maxTokens(),
                "stream", true);
        try {
            Flux<String> flux = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(String.class);
            flux.subscribe(chunk -> {
                try {
                    // SSE chunk: "data: {...}"
                    String payload = chunk.startsWith("data:") ? chunk.substring(5).trim() : chunk.trim();
                    if ("[DONE]".equals(payload)) {
                        onDone.run();
                        return;
                    }
                    JsonNode root = objectMapper.readTree(payload);
                    String delta = root.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        onToken.accept(delta);
                    }
                } catch (Exception e) {
                    // 忽略单 chunk 解析失败（如注释行）
                }
            }, err -> onError.accept(err), onDone);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    @Override
    public List<Float> embed(String text) {
        String resp = webClient.post()
                .uri("/embeddings")
                .bodyValue(Map.of("model", "text-embedding-v1", "input", text))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(60));
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode arr = root.path("data").path(0).path("embedding");
            List<Float> vec = new ArrayList<>();
            arr.forEach(n -> vec.add((float) n.asDouble()));
            return vec;
        } catch (Exception e) {
            log.error("[LLM] embedding 解析失败", e);
            return null;
        }
    }

    @Override
    public boolean testConnection() {
        try {
            String resp = webClient.get()
                    .uri("/models")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            return resp != null && resp.contains("data");
        } catch (Exception e) {
            log.warn("[LLM] 连通测试失败: {}", e.getMessage());
            return false;
        }
    }
}
