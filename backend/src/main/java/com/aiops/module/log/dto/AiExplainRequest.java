package com.aiops.module.log.dto;

import lombok.Data;

/**
 * AI 解读请求，兼容两套字段：
 * 新契约 { templateId, sceneCode }，老契约 { refId, scene }（前端 api/log.js 目前用老契约）。
 */
@Data
public class AiExplainRequest {
    private Long templateId;
    private Long refId;
    private String sceneCode;
    private String scene;

    /** templateId 优先 */
    public Long resolvedRefId() {
        return templateId != null ? templateId : refId;
    }

    /** sceneCode 优先，缺省 template_explain */
    public String resolvedScene() {
        String s = sceneCode != null ? sceneCode : scene;
        return s == null ? "template_explain" : s;
    }
}
