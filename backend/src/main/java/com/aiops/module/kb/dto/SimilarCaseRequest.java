package com.aiops.module.kb.dto;

import lombok.Data;

/** 按事件检索相似历史案例 */
@Data
public class SimilarCaseRequest {
    private Long incidentId;
    /** 默认 3 */
    private Integer topK;
}
