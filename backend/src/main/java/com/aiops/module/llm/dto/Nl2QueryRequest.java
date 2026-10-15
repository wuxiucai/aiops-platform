package com.aiops.module.llm.dto;

import lombok.Data;

/** 自然语言 → 结构化查询 */
@Data
public class Nl2QueryRequest {
    private String question;
}
