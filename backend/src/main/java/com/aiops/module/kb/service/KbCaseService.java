package com.aiops.module.kb.service;

import com.aiops.module.kb.entity.KbFaultCase;
import com.aiops.module.kb.mapper.KbFaultCaseMapper;
import com.aiops.module.kb.util.EmbeddingUtil;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.aiops.module.llm.service.LlmProviderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * M5-11 嵌入回填 / 服务控制器。
 * 对包内所示对 kb_fault_case.embedding_status != 'done' 的行调 embed() 补齐。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbCaseService {

    private final KbFaultCaseMapper kbFaultCaseMapper;
    private final LlmProviderMapper llmProviderMapper;
    private final LlmProviderService llmProviderService;

    /**
     * 对 embedding_status='pending'（或 NULL）的行调 LLM embed 补齐。
     * 返回 {updated, failed, total}。
     */
    public Map<String, Object> syncEmbeddings() {
        List<KbFaultCase> pendings = kbFaultCaseMapper.selectList(
                new LambdaQueryWrapper<KbFaultCase>()
                        .eq(KbFaultCase::getDeleted, 0)
                        .and(q -> q.isNull(KbFaultCase::getEmbedding)
                                .or().ne(KbFaultCase::getEmbeddingStatus, "done")));
        log.info("[KbCase] syncEmbeddings: 待回填={} 条", pendings.size());
        if (pendings.isEmpty()) return Map.of("updated", 0, "failed", 0, "total", 0);

        LlmProvider provider = llmProviderMapper.selectOne(
                new LambdaQueryWrapper<LlmProvider>()
                        .eq(LlmProvider::getIsDefault, 1)
                        .last("LIMIT 1"));
        if (provider == null || provider.getStatus() == null || provider.getStatus() != 1) {
            log.warn("[KbCase] LLM Provider 不可用，无法回填");
            return Map.of("updated", 0, "failed", pendings.size(), "total", pendings.size());
        }

        // DeepSeek 等无嵌入能力 provider：embedding_model=NULL → 标 done 但留占位（LIKE 兜底用）
        if (provider.getEmbeddingModel() == null || provider.getEmbeddingModel().isBlank()) {
            int n = 0;
            for (KbFaultCase c : pendings) {
                c.setEmbeddingStatus("done");
                kbFaultCaseMapper.updateById(c);
                n++;
            }
            log.info("[KbCase] provider 无嵌入能力，标记 done 但留 LIKE 兜底 占位：{}/{}", n, pendings.size());
            return Map.of("updated", n, "failed", 0, "total", pendings.size(),
                    "note", "provider has no embedding capability, LIKE fallback ready");
        }

        LlmClient client = llmProviderService.buildClient(provider);
        String embedModel = provider.getEmbeddingModel();

        int updated = 0, failed = 0;
        for (KbFaultCase c : pendings) {
            try {
                String text = (c.getTitle() == null ? "" : c.getTitle()) + " "
                        + (c.getSymptom() == null ? "" : c.getSymptom()) + " "
                        + (c.getRootCause() == null ? "" : c.getRootCause());
                List<Float> vec = client.embed(abbreviate(text, 1000));
                if (vec == null || vec.isEmpty()) {
                    c.setEmbeddingStatus("failed");
                    failed++;
                } else {
                    c.setEmbedding(EmbeddingUtil.toJson(vec));
                    c.setEmbeddingStatus("done");
                    updated++;
                }
                kbFaultCaseMapper.updateById(c);
            } catch (Exception e) {
                log.warn("[KbCase] case#{} embed fail: {}", c.getId(), e.getMessage());
                c.setEmbeddingStatus("failed");
                kbFaultCaseMapper.updateById(c);
                failed++;
            }
        }
        log.info("[KbCase] syncEmbeddings done: updated={}, failed={}, total={}", updated, failed, pendings.size());
        return Map.of("updated", updated, "failed", failed, "total", pendings.size());
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
