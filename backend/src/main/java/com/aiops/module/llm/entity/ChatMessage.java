package com.aiops.module.llm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运维助手消息（一对多挂在 chat_session 下，不做外键依赖）
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private Long sessionId;

    /** user / assistant / system */
    @TableField("role")
    private String role;

    @TableField("content")
    private String content;

    @TableField("create_time")
    private LocalDateTime createTime;
}
