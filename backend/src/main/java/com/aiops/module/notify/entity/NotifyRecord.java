package com.aiops.module.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知发送记录
 */
@Data
@TableName("notify_record")
public class NotifyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("ref_type")
    private String refType;

    @TableField("ref_id")
    private Long refId;

    @TableField("channel_id")
    private Long channelId;

    @TableField("receiver")
    private String receiver;

    @TableField("content")
    private String content;

    /** success|fail */
    @TableField("status")
    private String status;

    @TableField("send_time")
    private LocalDateTime sendTime;

    @TableField("error_msg")
    private String errorMsg;
}
