package com.aiops.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邮件 SMTP 配置（通知 email 渠道使用）。
 * password_enc 为 AES 加密的授权码（不是邮箱密码）。
 */
@Data
@TableName("sys_mail_config")
public class SysMailConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    /** 如 smtp.qq.com / smtp.163.com */
    @TableField("smtp_host")
    private String smtpHost;

    /** 通常 465（QQ 类） 或 25/587 (163/其他） */
    @TableField("smtp_port")
    private Integer smtpPort;

    @TableField("username")
    private String username;

    /** AES 加密的授权码，非明文密码 */
    @TableField("password_enc")
    private String passwordEnc;

    @TableField("from_name")
    private String fromName;

    @TableField("`ssl`")
    private Integer ssl;

    @TableField("enabled")
    private Integer enabled;

    @TableField("is_default")
    private Integer isDefault;

    @TableField("remark")
    private String remark;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("deleted")
    private Integer deleted;
}
