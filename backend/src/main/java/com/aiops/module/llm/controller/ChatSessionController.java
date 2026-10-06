package com.aiops.module.llm.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.llm.entity.ChatMessage;
import com.aiops.module.llm.entity.ChatSession;
import com.aiops.module.llm.mapper.ChatMessageMapper;
import com.aiops.module.llm.mapper.ChatSessionMapper;
import com.aiops.security.LoginUser;
import com.aiops.security.RequirePerm;
import com.aiops.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 运维助手会话历史：用户级的 chat session + message 落库。
 *
 * 表设计极简：会话 + 消息两张表，无外键。每个用户只能看到自己的会话。
 * 幂等：POST /messages 接收 {role, content}，逐条追加；不做去重（前端要保证同一
 *   轮 SSE 完成后只调一次追加，重复调用会产生重复行，但显示层不会去重）。
 */
@Slf4j
@Tag(name = "AI 会话历史")
@RestController
@RequestMapping("/api/ai/chat")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

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
        Long userId = requireUserId();
        List<ChatSession> list = sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getUpdateTime)
                        .last("LIMIT 100"));
        return Result.ok(list);
    }

    @Operation(summary = "新建会话")
    @RequirePerm("ai:chat")
    @PostMapping("/sessions")
    public Result<ChatSession> createSession(@RequestBody Map<String, String> body) {
        Long userId = requireUserId();
        ChatSession s = new ChatSession();
        s.setUserId(userId);
        s.setTitle(body.getOrDefault("title", "新对话"));
        s.setCreateTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        s.setDeleted(0);
        sessionMapper.insert(s);
        return Result.ok(s);
    }

    @Operation(summary = "重命名会话")
    @RequirePerm("ai:chat")
    @PutMapping("/sessions/{id}")
    public Result<ChatSession> renameSession(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Long userId = requireUserId();
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            throw new BizException("标题不能为空");
        }
        ChatSession s = sessionMapper.selectById(id);
        if (s == null || !userId.equals(s.getUserId())) {
            throw new BizException("会话不存在");
        }
        ChatSession upd = new ChatSession();
        upd.setId(id);
        upd.setTitle(title.trim());
        upd.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(upd);
        return Result.ok(sessionMapper.selectById(id));
    }

    @Operation(summary = "删除会话（级联删消息）")
    @RequirePerm("ai:chat")
    @DeleteMapping("/sessions/{id}")
    public Result<Void> deleteSession(@PathVariable Long id) {
        Long userId = requireUserId();
        ChatSession s = sessionMapper.selectById(id);
        if (s == null || !userId.equals(s.getUserId())) {
            throw new BizException("会话不存在");
        }
        sessionMapper.deleteById(id);  // @TableLogic 自动置 deleted=1
        // 物理删消息（消息没有自己的删除标志，简化处理）
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, id));
        return Result.ok();
    }

    @Operation(summary = "拉取会话下的所有消息（升序）")
    @RequirePerm("ai:chat")
    @GetMapping("/sessions/{id}/messages")
    public Result<List<ChatMessage>> listMessages(@PathVariable Long id) {
        Long userId = requireUserId();
        ChatSession s = sessionMapper.selectById(id);
        if (s == null || !userId.equals(s.getUserId())) {
            throw new BizException("会话不存在");
        }
        List<ChatMessage> list = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, id)
                        .orderByAsc(ChatMessage::getCreateTime)
                        .orderByAsc(ChatMessage::getId)
                        .last("LIMIT 500"));
        return Result.ok(list);
    }

    @Operation(summary = "追加一条消息（user 或 assistant）")
    @RequirePerm("ai:chat")
    @PostMapping("/sessions/{id}/messages")
    public Result<ChatMessage> appendMessage(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Long userId = requireUserId();
        ChatSession s = sessionMapper.selectById(id);
        if (s == null || !userId.equals(s.getUserId())) {
            throw new BizException("会话不存在");
        }
        String role = body.get("role");
        String content = body.get("content");
        if (role == null || (!role.equals("user") && !role.equals("assistant"))) {
            throw new BizException("role 必须是 user / assistant");
        }
        if (content == null) content = "";
        ChatMessage m = new ChatMessage();
        m.setSessionId(id);
        m.setRole(role);
        m.setContent(content);
        m.setCreateTime(LocalDateTime.now());
        messageMapper.insert(m);

        // 同步会话 update_time，便于列表排序
        ChatSession touch = new ChatSession();
        touch.setId(id);
        touch.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(touch);

        return Result.ok(m);
    }

    @Operation(summary = "批量追加消息（更省请求，比如 user+assistant 一回合）")
    @RequirePerm("ai:chat")
    @PostMapping("/sessions/{id}/messages/batch")
    public Result<Integer> appendBatch(@PathVariable Long id, @RequestBody List<Map<String, String>> messages) {
        Long userId = requireUserId();
        ChatSession s = sessionMapper.selectById(id);
        if (s == null || !userId.equals(s.getUserId())) {
            throw new BizException("会话不存在");
        }
        if (messages == null || messages.isEmpty()) {
            return Result.ok(0);
        }
        LocalDateTime now = LocalDateTime.now();
        int inserted = 0;
        for (Map<String, String> body : messages) {
            String role = body.get("role");
            String content = body.getOrDefault("content", "");
            if (role == null || (!role.equals("user") && !role.equals("assistant"))) continue;
            ChatMessage m = new ChatMessage();
            m.setSessionId(id);
            m.setRole(role);
            m.setContent(content);
            m.setCreateTime(now);
            messageMapper.insert(m);
            inserted++;
        }
        ChatSession touch = new ChatSession();
        touch.setId(id);
        touch.setUpdateTime(now);
        sessionMapper.updateById(touch);
        return Result.ok(inserted);
    }
}
