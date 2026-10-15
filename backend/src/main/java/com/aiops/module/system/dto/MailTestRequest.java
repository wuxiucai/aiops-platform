package com.aiops.module.system.dto;

import lombok.Data;

/** SMTP 配置测试发送 */
@Data
public class MailTestRequest {
    /** 接收邮箱 */
    private String to;
}
