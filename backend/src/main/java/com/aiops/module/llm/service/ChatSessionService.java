package com.aiops.module.llm.service;

import com.aiops.common.BizException;
import com.aiops.module.llm.dto.ChatMessageRequest;
import com.aiops.module.llm.entity.ChatMessage;
import com.aiops.module.llm.entity.ChatSession;
import com.aiops.module.llm.mapper.ChatMessageMapper;
import com.aiops.module.llm.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运维助手会话历史。每个用户只能读写自己的会话（{@link #requireOwned}）；
 * 追加消息与刷新会话 update_time 在同一事务，批量追加要么整轮写入要么都不写。
 */
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    public List<ChatSession> list(Long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getUpdateTime)
                .last("LIMIT 100"));
    }

    public ChatSession create(Long userId, String title) {
        ChatSession s = new ChatSession();
        s.setUserId(userId);
        s.setTitle(title == null ? "新对话" : title);
        s.setCreateTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        s.setDeleted(0);
        sessionMapper.insert(s);
        return s;
    }

    public ChatSession rename(Long userId, Long id, String title) {
        if (title == null || title.isBlank()) {
            throw new BizException("标题不能为空");
        }
        requireOwned(userId, id);
        ChatSession upd = new ChatSession();
        upd.setId(id);
        upd.setTitle(title.trim());
        upd.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(upd);
        return sessionMapper.selectById(id);
    }

    /** 会话逻辑删除（@TableLogic），消息无删除标志，物理删除 */
    @Transactional
    public void delete(Long userId, Long id) {
        requireOwned(userId, id);
        sessionMapper.deleteById(id);
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, id));
    }

    public List<ChatMessage> messages(Long userId, Long id) {
        requireOwned(userId, id);
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, id)
                .orderByAsc(ChatMessage::getCreateTime)
                .orderByAsc(ChatMessage::getId)
                .last("LIMIT 500"));
    }

    @Transactional
    public ChatMessage append(Long userId, Long id, ChatMessageRequest req) {
        requireOwned(userId, id);
        if (!isValidRole(req.getRole())) {
            throw new BizException("role 必须是 user / assistant");
        }
        LocalDateTime now = LocalDateTime.now();
        ChatMessage m = insertMessage(id, req, now);
        touch(id, now);
        return m;
    }

    /** 批量追加（一问一答一回合）。role 非法的条目跳过，返回实际写入条数 */
    @Transactional
    public int appendBatch(Long userId, Long id, List<ChatMessageRequest> reqs) {
        requireOwned(userId, id);
        if (reqs == null || reqs.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int inserted = 0;
        for (ChatMessageRequest r : reqs) {
            if (r == null || !isValidRole(r.getRole())) continue;
            insertMessage(id, r, now);
            inserted++;
        }
        touch(id, now);
        return inserted;
    }

    private void requireOwned(Long userId, Long id) {
        ChatSession s = sessionMapper.selectById(id);
        if (s == null || !userId.equals(s.getUserId())) {
            throw new BizException("会话不存在");
        }
    }

    private static boolean isValidRole(String role) {
        return "user".equals(role) || "assistant".equals(role);
    }

    private ChatMessage insertMessage(Long sessionId, ChatMessageRequest r, LocalDateTime now) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setRole(r.getRole());
        m.setContent(r.getContent() == null ? "" : r.getContent());
        m.setCreateTime(now);
        messageMapper.insert(m);
        return m;
    }

    /** 同步会话 update_time，会话列表按它排序 */
    private void touch(Long sessionId, LocalDateTime now) {
        ChatSession touch = new ChatSession();
        touch.setId(sessionId);
        touch.setUpdateTime(now);
        sessionMapper.updateById(touch);
    }
}
