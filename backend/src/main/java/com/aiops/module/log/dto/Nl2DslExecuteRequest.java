package com.aiops.module.log.dto;

import lombok.Data;

/** 执行一条已通过校验的 NL2DSL 记录 */
@Data
public class Nl2DslExecuteRequest {
    private Long recordId;
}
