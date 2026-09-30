package com.aiops.module.kb.service;

import java.util.Map;

/**
 * M5-11 相似案例检索。
 */
public interface SimilarCaseService {

    /**
     * 按 incident 检索 kb_fault_case 中语义最相似的历史故障。
     * 返回 {incidentId, seedText, matchType, cases:[{caseId,title,symptom,rootCause,solution,similarity}]}。
     */
    Map<String, Object> findSimilarCases(Long incidentId, int topK);
}
