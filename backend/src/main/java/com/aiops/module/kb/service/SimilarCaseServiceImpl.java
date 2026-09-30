package com.aiops.module.kb.service;

import com.aiops.common.BizException;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.kb.entity.KbFaultCase;
import com.aiops.module.kb.entity.KbSimilarityLog;
import com.aiops.module.kb.mapper.KbFaultCaseMapper;
import com.aiops.module.kb.mapper.KbSimilarityLogMapper;
import com.aiops.module.kb.util.EmbeddingUtil;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.aiops.module.llm.service.LlmProviderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * M5-11 相似案例检索（相似度最高的 kb_fault_case 返回 TopK）。
 * <p>
 * 流程摘要：
 *  1) 拿 incident 拼 seed text（title + symptom 摘取前 2 行）
 *  2) 计算 query embedding（LLM embed）
 *  3) 全表 kb_fault_case 遍历计算 cosine → score 排序
 *  4) score < 0.75 丢弃；TopK → 落 kb_similarity_log
 *  5) 兜底：LLM embed 失败 / provider 无嵌入能力 → 改 LIKE 关键词检索
 * <p>
 * 兜底标记：`data[0].matchType = "embedding" | "like_fallback"`
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarCaseServiceImpl implements SimilarCaseService {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final double SCORE_THRESHOLD = 0.75;

    private final KbFaultCaseMapper kbFaultCaseMapper;
    private final KbSimilarityLogMapper kbSimilarityLogMapper;
    private final AlertIncidentMapper alertIncidentMapper;
    private final LlmProviderMapper llmProviderMapper;
    private final LlmProviderService llmProviderService;

    @Override
    public Map<String, Object> findSimilarCases(Long incidentId, int topK) {
        AlertIncident inc = alertIncidentMapper.selectById(incidentId);
        if (inc == null) {
            throw new BizException("事件不存在：" + incidentId);
        }
        String seedText = buildSeedText(inc);
        log.info("[SimilarCase] incidentId={}, seedText={}", incidentId, seedText);

        // 1) embed query
        List<Float> queryEmbedding = null;
        String matchType = "embedding";
        try {
            queryEmbedding = embedText(seedText);
        } catch (Exception e) {
            log.warn("[SimilarCase] embedding fail, fall back to LIKE: {}", e.getMessage());
        }

        List<Map<String, Object>> results;
        if (queryEmbedding != null && !queryEmbedding.isEmpty()) {
            results = findByEmbedding(incidentId, seedText, queryEmbedding, topK);
        } else {
            log.warn("[SimilarCase] provider 无嵌入能力或调用失败 → 改用 LIKE 兜底");
            matchType = "like_fallback";
            results = findByLike(incidentId, seedText, topK);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("incidentId", incidentId);
        out.put("seedText", seedText);
        out.put("matchType", matchType);
        out.put("cases", results);
        return out;
    }

    /* ================== embed ================== */

    private List<Float> embedText(String text) {
        LlmProvider provider = llmProviderMapper.selectOne(
                new LambdaQueryWrapper<LlmProvider>()
                        .eq(LlmProvider::getIsDefault, 1)
                        .last("LIMIT 1"));
        if (provider == null || provider.getStatus() == null || provider.getStatus() != 1) {
            return null;
        }
        LlmClient client = llmProviderService.buildClient(provider);
        List<Float> vec = client.embed(text);
        return vec;
    }

    /* ================== 检索 ================== */

    /**
     * 遍历所有 kb_fault_case（毕设尺度千级以下没问题），算 cosine(query, case)。
     */
    private List<Map<String, Object>> findByEmbedding(Long incidentId,
                                                      String seedText,
                                                      List<Float> query,
                                                      int topK) {
        List<KbFaultCase> cases = kbFaultCaseMapper.selectList(
                new LambdaQueryWrapper<KbFaultCase>()
                        .eq(KbFaultCase::getDeleted, 0)
                        .eq(KbFaultCase::getEmbeddingStatus, "done"));
        log.info("[SimilarCase] embedding 路径：候选 case={} 条", cases.size());

        List<Map<String, Object>> scored = new ArrayList<>();
        for (KbFaultCase c : cases) {
            List<Float> v = EmbeddingUtil.parseEmbedding(c.getEmbedding());
            if (v == null || v.isEmpty()) continue;
            double score = EmbeddingUtil.cosine(query, v);
            if (score < SCORE_THRESHOLD) continue;
            scored.add(buildCaseRow(c, score, "embedding"));
        }
        scored.sort(Comparator.comparingDouble(m -> -((Number) m.get("similarity")).doubleValue()));
        List<Map<String, Object>> topKList = scored.stream().limit(Math.max(topK, 1)).toList();

        // 落 log（每个命中一条）
        for (Map<String, Object> row : topKList) {
            KbSimilarityLog l = new KbSimilarityLog();
            l.setIncidentId(incidentId);
            l.setCaseId(((Number) row.get("caseId")).longValue());
            l.setScore(BigDecimal.valueOf((Double) row.get("similarity")).setScale(4, RoundingMode.HALF_UP));
            l.setIsAdopted(0);
            l.setCreateTime(LocalDateTime.now());
            kbSimilarityLogMapper.insert(l);
        }
        return topKList;
    }

    /** LIKE 兜底：基于 title/symptom 中提取的关键词做 LIKE。 */
    private List<Map<String, Object>> findByLike(Long incidentId, String seedText, int topK) {
        // 提取关键词：split by 空格/逗号/冒号，挑中文 ≥2 字 + 字母组合 ≥4 字
        String[] parts = seedText.split("[\\s,，:：、。.!！]+");
        List<String> keywords = new ArrayList<>();
        for (String p : parts) {
            p = p.trim();
            if (p.length() >= 2 && p.length() <= 20 && !p.matches("[0-9.%]+")) {
                keywords.add(p);
            }
            if (keywords.size() >= 4) break;
        }

        List<KbFaultCase> all = kbFaultCaseMapper.selectList(
                new LambdaQueryWrapper<KbFaultCase>()
                        .eq(KbFaultCase::getDeleted, 0));
        log.info("[SimilarCase] LIKE 路径：keywords={}，候选 case={} 条", keywords, all.size());

        List<Map<String, Object>> results = new ArrayList<>();
        for (KbFaultCase c : all) {
            int hit = 0;
            String text = (c.getTitle() == null ? "" : c.getTitle()) + " "
                    + (c.getSymptom() == null ? "" : c.getSymptom()) + " "
                    + (c.getRootCause() == null ? "" : c.getRootCause());
            for (String kw : keywords) {
                if (!kw.isBlank() && text.contains(kw)) hit++;
            }
            if (hit > 0) {
                double score = Math.min(1.0, 0.5 + hit * 0.1); // LIKE 给 0.5-0.9 区间
                Map<String, Object> row = buildCaseRow(c, score, "like_fallback");
                row.put("hitKeywords", hit);
                results.add(row);
            }
        }
        results.sort(Comparator.comparingDouble(m -> -((Number) m.get("similarity")).doubleValue()));
        List<Map<String, Object>> topKList = results.stream().limit(Math.max(topK, 1)).toList();

        for (Map<String, Object> row : topKList) {
            KbSimilarityLog l = new KbSimilarityLog();
            l.setIncidentId(incidentId);
            l.setCaseId(((Number) row.get("caseId")).longValue());
            l.setScore(BigDecimal.valueOf((Double) row.get("similarity")).setScale(4, RoundingMode.HALF_UP));
            l.setIsAdopted(0);
            l.setCreateTime(LocalDateTime.now());
            kbSimilarityLogMapper.insert(l);
        }
        return topKList;
    }

    private String buildSeedText(AlertIncident inc) {
        StringBuilder sb = new StringBuilder();
        if (inc.getTitle() != null) sb.append(inc.getTitle()).append(" ");
        // 故障事件本身不含 description 字段，用 llmSummary / llmRootCause / llmLogEvidence 凑出语义线索
        if (inc.getLlmSummary() != null && !inc.getLlmSummary().isBlank()) {
            sb.append(inc.getLlmSummary()).append(" ");
        }
        if (inc.getLlmRootCause() != null && !inc.getLlmRootCause().isBlank()) {
            sb.append(inc.getLlmRootCause()).append(" ");
        }
        if (inc.getLlmLogEvidence() != null && !inc.getLlmLogEvidence().isBlank()) {
            sb.append(inc.getLlmLogEvidence()).append(" ");
        }
        String out = sb.toString().trim();
        if (out.isEmpty()) out = "未知事件 " + (inc.getIncidentNo() == null ? "" : inc.getIncidentNo());
        return abbreviate(out, 200);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private Map<String, Object> buildCaseRow(KbFaultCase c, double score, String matchType) {
        Map<String, Object> m = new HashMap<>();
        m.put("caseId", c.getId());
        m.put("title", c.getTitle());
        m.put("symptom", c.getSymptom());
        m.put("rootCause", c.getRootCause());
        m.put("solution", c.getSolution());
        m.put("similarity", score);
        m.put("matchType", matchType);
        return m;
    }
}
