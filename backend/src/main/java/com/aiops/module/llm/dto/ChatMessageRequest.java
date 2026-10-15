package com.aiops.module.llm.dto;

import lombok.Data;

/** 追加一条会话消息。role 只接受 user / assistant，content 为空按空串存 */
@Data
public class ChatMessageRequest {
    private String role;
    private String content;
}
