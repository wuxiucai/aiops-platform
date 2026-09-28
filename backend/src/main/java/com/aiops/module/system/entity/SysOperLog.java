package com.aiops.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志
 */
@Data
@TableName("sys_oper_log")
public class SysOperLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("username")
    private String username;

    @TableField("module")
    private String module;

    @TableField("operation")
    private String operation;

    @TableField("method")
    private String method;

    @TableField("request_uri")
    private String requestUri;

    @TableField("params")
    private String params;

    @TableField("ip")
    private String ip;

    /** 1成功 0失败 */
    @TableField("status")
    private Integer status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("cost_time")
    private Long costTime;

    @TableField("create_time")
    private LocalDateTime createTime;
}
