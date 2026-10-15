package com.aiops.module.llm.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.llm.dto.ChatMessageRequest;
import com.aiops.module.llm.dto.ChatSessionRequest;
import com.aiops.module.llm.entity.ChatMessage;
import com.aiops.module.llm.entity.ChatSession;
import com.aiops.module.llm.service.ChatSessionService;
import com.aiops.security.LoginUser;
import com.aiops.security.RequirePerm;
import com.aiops.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 运维助手会话历史：用户级的 chat session + message 落库，每个用户只能看到自己的会话。
 * 不做去重：前端需保证同一轮 SSE 完成后只调一次追加。实现见 {@link ChatSessionService}。
 */
@Tag(name = "AI 会话历史")
@RestController
@RequestMapping("/api/ai/chat")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    /** 必须登录（任何已登录用户都可访问，权限沿用 ai:chat） */
    private Long requireUserId() {
        LoginUser u = UserContext.get();
        if (u == null || u.getUserId() == null) {
            throw new BizException("未登录");
        }
        return u.getUserId();
    }

    @Operation(summary = "我的会话列表")
    @RequirePerm("ai:chat")
    @GetMapping("/sessions")
    public Result<List<ChatSession>> listSessions() {
        return Result.ok(chatSessionService.list(requireUserId()));
    }

    @Operation(summary = "新建会话")
    @RequirePerm("ai:chat")
    @PostMapping("/sessions")
    public Result<ChatSession> createSession(@RequestBody ChatSessionRequest body) {
        return Result.ok(chatSessionService.create(requireUserId(), body.getTitle()));
    }

    @Operation(summary = "重命名会话")
    @RequirePerm("ai:chat")
    @PutMapping("/sessions/{id}")
    public Result<ChatSession> renameSession(@PathVariable Long id, @RequestBody ChatSessionRequest body) {
        return Result.ok(chatSessionService.rename(requireUserId(), id, body.getTitle()));
    }

    @Operation(summary = "删除会话（级联删消息）")
    @RequirePerm("ai:chat")
    @DeleteMapping("/sessions/{id}")
    public Result<Void> deleteSession(@PathVariable Long id) {
        chatSessionService.delete(requireUserId(), id);
        return Result.ok();
    }

    @Operation(summary = "拉取会话下的所有消息（升序）")
    @RequirePerm("ai:chat")
    @GetMapping("/sessions/{id}/messages")
    public Result<List<ChatMessage>> listMessages(@PathVariable Long id) {
        return Result.ok(chatSessionService.messages(requireUserId(), id));
    }

    @Operation(summary = "追加一条消息（user 或 assistant）")
    @RequirePerm("ai:chat")
    @PostMapping("/sessions/{id}/messages")
    public Result<ChatMessage> appendMessage(@PathVariable Long id, @RequestBody ChatMessageRequest body) {
        return Result.ok(chatSessionService.append(requireUserId(), id, body));
    }

    @Operation(summary = "批量追加消息（更省请求，比如 user+assistant 一回合）")
    @RequirePerm("ai:chat")
    @PostMapping("/sessions/{id}/messages/batch")
    public Result<Integer> appendBatch(@PathVariable Long id, @RequestBody List<ChatMessageRequest> messages) {
        return Result.ok(chatSessionService.appendBatch(requireUserId(), id, messages));
    }
}
