package com.aiops.module.llm.controller;

import com.aiops.common.BizException;
import com.aiops.module.llm.chat.ChatToolRouter;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final ChatToolRouter chatToolRouter;

    @Operation(summary = "SSE 流式问答（GET，已废弃：中文 URL 编码在 Windows 上不可靠）")
    @RequirePerm("ai:chat")
    @GetMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStreamGet(@RequestParam("question") String question,
                                    @RequestParam(value = "systemPrompt", required = false) String systemPrompt) {
        return doChatStream(question, systemPrompt);
    }

    /**
     * SSE 流式问答（POST + JSON body，**推荐**）。
     *
     * GET /chat/stream?question=... 在 Windows 中文环境下会因 Tomcat 不按 UTF-8 解码 query
     * 收到 U+FFFD 替换字符，导致中文意图识别全失败。POST + JSON body 由 Content-Type 显式
     * 声明 charset=UTF-8，是更可靠的方式。
     *
     * Body: {"question": "...", "systemPrompt": "..."?}
     */
    @Operation(summary = "SSE 流式问答（POST + JSON body，推荐）")
    @RequirePerm("ai:chat")
    @PostMapping(path = "/chat/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStreamPost(@RequestBody ChatRequest req) {
        return doChatStream(req.question(), req.systemPrompt());
    }

    /** POST body 形如 {"question": "...", "systemPrompt": "..."?} */
    public record ChatRequest(String question, String systemPrompt) {}

    private SseEmitter doChatStream(String question, String systemPrompt) {
        LlmProvider provider = llmProviderMapper.selectOne(
                new LambdaQueryWrapper<LlmProvider>()
                        .eq(LlmProvider::getIsDefault, 1)
                        .last("LIMIT 1"));
        if (provider == null || provider.getStatus() == null || provider.getStatus() != 1) {
            throw new BizException("默认 LLM Provider 未启用");
        }
        LlmClient client = llmProviderService.buildClient(provider);
        SseEmitter emitter = new SseEmitter(120_000L);

        // Tool routing：识别用户意图 → 拉取实时数据 → 注入到 system prompt
        // 命中工具：LLM 用真实数据回答；未命中：降回裸 LLM 兜底
        ChatToolRouter.ToolResult toolCtx = chatToolRouter.route(question);
        String sys = buildSystemPrompt(systemPrompt, toolCtx);
        if (toolCtx != null) {
            log.info("[AiChat] tool hit: tool={}, summary={}", toolCtx.toolName(), toolCtx.context());
        }
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

    /**
     * 构造 system prompt：
     *   - 默认精简版（保持原"资深 SRE"设置）
     *   - 工具命中时，把平台数据上下文拼进去，并硬性要求 LLM 只用中文 / 基于给定数据回答
     */
    private String buildSystemPrompt(String userSupplied, ChatToolRouter.ToolResult toolCtx) {
        String base = userSupplied != null && !userSupplied.isBlank()
                ? userSupplied
                : "你是 AIOps 平台的运维助手，懂监控指标、告警生命周期、日志分析。用简洁中文回答，不要太长。";
        if (toolCtx == null) {
            return base + "\n"
                    + "注意：如用户问的是平台内的实时数据（告警数 / 指标 / 日志条数），且你没有拿到数据，"
                    + "明确告知'当前问题未触发平台工具，无法直接回答实时数据'，并建议用户使用日志智能分析或告警中心模块。";
        }
        return base + "\n"
                + "【平台实时数据（已为你查询})】\n"
                + toolCtx.context() + "\n"
                + "工具返回的结构化数据：" + toolCtx.data() + "\n"
                + "请基于上面的真实数据回答，不要编造未提供的数字。回答中文，尽量 80 字以内。";
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
