package com.aiops.module.log.dto;

import lombok.Data;

/** 自然语言生成 ES DSL；数据源 / 索引映射可选，未选时用默认数据源 */
@Data
public class Nl2DslGenerateRequest {
    private String question;
    private Long datasourceId;
    private Long indexConfigId;
}
