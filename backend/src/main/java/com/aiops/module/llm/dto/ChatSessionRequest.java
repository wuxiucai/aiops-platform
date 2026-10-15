package com.aiops.module.llm.dto;

import lombok.Data;

/** 新建 / 重命名会话 */
@Data
public class ChatSessionRequest {
    private String title;
}
