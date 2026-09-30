package com.aiops.module.llm.controller;

import com.aiops.common.BizException;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.aiops.module.llm.service.LlmProviderService;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * M5-5：运维助手 SSE 问答（M6 前端会消费）。
 * <p>
 * 协议（v2 §8.3 / 任务书 §5.7）：
 *   GET /api/ai/chat/stream?question=...
 *   响应 200 + text/event-stream
 *   data: {"type":"token","content":"..."}
 *   data: {"type":"done","totalTokens":123}
 *   data: {"type":"error","message":"..."}
 */
@Slf4j
@Tag(name = "AI 运维助手")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final LlmProviderMapper llmProviderMapper;
    private final LlmProviderService llmProviderService;

    @Operation(summary = "SSE 流式问答：data: {type:token|done|error}")
    @RequirePerm("ai:chat")
    @GetMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam("question") String question,
                                 @RequestParam(value = "systemPrompt", required = false) String systemPrompt) {
        LlmProvider provider = llmProviderMapper.selectOne(
                new LambdaQueryWrapper<LlmProvider>()
                        .eq(LlmProvider::getIsDefault, 1)
                        .last("LIMIT 1"));
        if (provider == null || provider.getStatus() == null || provider.getStatus() != 1) {
            throw new BizException("默认 LLM Provider 未启用");
        }
        LlmClient client = llmProviderService.buildClient(provider);
        SseEmitter emitter = new SseEmitter(120_000L);

        String sys = systemPrompt != null && !systemPrompt.isBlank()
                ? systemPrompt
                : "你是资深 SRE。用简洁中文回答。回答长度 50-200 字。";
        List<LlmClient.LlmMessage> msgs = List.of(
                new LlmClient.LlmMessage("system", sys),
                new LlmClient.LlmMessage("user", question));

        LlmClient.LlmRequest req = new LlmClient.LlmRequest(
                provider.getModelName(), msgs, 0.3, 1024, true);

        // 异步执行，避免占住 Tomcat 线程
        new Thread(() -> {
            try {
                client.chatStream(req,
                        token -> send(emitter, "token", token),
                        () -> {
                            send(emitter, "done", Map.of("totalTokens", 0));
                            emitter.complete();
                        },
                        err -> {
                            send(emitter, "error", Map.of("message", err.getMessage() == null ? "unknown" : err.getMessage()));
                            emitter.completeWithError(err);
                        });
            } catch (Throwable t) {
                send(emitter, "error", Map.of("message", t.getMessage() == null ? "init" : t.getMessage()));
                emitter.completeWithError(t);
            }
        }, "ai-chat-sse").start();

        return emitter;
    }

    private void send(SseEmitter e, String type, Object payload) {
        try {
            Map<String, Object> data = payload instanceof String s
                    ? Map.of("type", type, "content", s)
                    : Map.of("type", type, "data", payload);
            e.send(SseEmitter.event().data(data).name(type));
        } catch (IOException ignore) {
            // 客户端断开时静默
        } catch (IllegalStateException illegalState) {
            // emitter 已 complete
        }
    }
}
