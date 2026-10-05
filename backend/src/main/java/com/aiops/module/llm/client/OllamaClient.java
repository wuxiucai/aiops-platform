package com.aiops.module.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ollama 原生协议客户端：POST {baseUrl}/api/chat；embedding 走 /api/embeddings。
 */
@Slf4j
public class OllamaClient implements LlmClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OllamaClient(String baseUrl, int timeoutMs, WebClient.Builder builder) {
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.webClient = builder.baseUrl(root)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public LlmResponse chat(LlmRequest req) {
        long start = System.currentTimeMillis();
        String resp = webClient.post()
                .uri("/api/chat")
                .bodyValue(Map.of(
                        "model", req.model(),
                        "messages", req.messages().stream()
                                .map(m -> Map.of("role", m.role(), "content", m.content())).toList(),
                        "stream", false,
                        "options", Map.of("temperature", req.temperature())))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofMillis(120_000));
        long latency = System.currentTimeMillis() - start;
        try {
            JsonNode root = objectMapper.readTree(resp);
            String content = root.path("message").path("content").asText();
            int promptTokens = root.path("prompt_eval_count").asInt(0);
            int completionTokens = root.path("eval_count").asInt(0);
            log.info("[LLM] ollama chat 成功: model={}, latency={}ms", req.model(), latency);
            return new LlmResponse(content, promptTokens, completionTokens,
                    promptTokens + completionTokens, latency);
        } catch (Exception e) {
            throw new IllegalStateException("Ollama 响应解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void chatStream(LlmRequest req, Consumer<String> onToken, Runnable onDone, Consumer<Throwable> onError) {
        Map<String, Object> body = Map.of(
                "model", req.model(),
                "messages", req.messages().stream()
                        .map(m -> Map.of("role", m.role(), "content", m.content())).toList(),
                "stream", true);
        try {
            webClient.post()
                    .uri("/api/chat")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(chunk -> {
                        try {
                            JsonNode root = objectMapper.readTree(chunk);
                            String delta = root.path("message").path("content").asText("");
                            if (!delta.isEmpty()) {
                                onToken.accept(delta);
                            }
                            if (root.path("done").asBoolean(false)) {
                                onDone.run();
                            }
                        } catch (Exception ignored) {
                        }
                    }, err -> onError.accept(err), onDone);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    @Override
    public List<Float> embed(String text) {
        String resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of("model", "nomic-embed-text", "prompt", text))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(60));
        try {
            JsonNode arr = objectMapper.readTree(resp).path("embedding");
            List<Float> vec = new ArrayList<>();
            arr.forEach(n -> vec.add((float) n.asDouble()));
            return vec;
        } catch (Exception e) {
            log.error("[LLM] ollama embedding 解析失败", e);
            return null;
        }
    }

    @Override
    public boolean testConnection() {
        try {
            String resp = webClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            if (resp == null) {
                throw new IllegalStateException("响应体为 null");
            }
            if (!resp.contains("models")) {
                throw new IllegalStateException("响应缺少 models 字段: " +
                        (resp.length() > 200 ? resp.substring(0, 200) + "..." : resp));
            }
            return true;
        } catch (Exception e) {
            log.warn("[LLM] ollama 连通测试失败: {}", e.getMessage());
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
