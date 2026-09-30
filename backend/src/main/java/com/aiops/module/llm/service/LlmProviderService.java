package com.aiops.module.llm.service;

import com.aiops.common.BizException;
import com.aiops.common.util.AesUtil;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.client.OllamaClient;
import com.aiops.module.llm.client.OpenAiCompatibleClient;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * LLM 供应商服务：CRUD + 连通测试 + 客户端实例化。
 * api_key 落库前 AES 加密，任何响应不回明文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderService {

    private final LlmProviderMapper llmProviderMapper;
    private final org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder;

    @Value("${aiops.security.aes-key}")
    private String aesKey;

    public LlmProvider create(LlmProvider p) {
        if (p.getApiKey() != null && !p.getApiKey().isBlank()) {
            p.setApiKey(AesUtil.encrypt(p.getApiKey(), aesKey));
        }
        if (p.getIsDefault() != null && p.getIsDefault() == 1) {
            clearDefault();
        }
        llmProviderMapper.insert(p);
        return mask(p);
    }

    public LlmProvider update(LlmProvider p) {
        LlmProvider db = llmProviderMapper.selectById(p.getId());
        if (db == null) {
            throw new BizException("供应商不存在");
        }
        // api_key 未传则保留原值；传了则重新加密
        if (p.getApiKey() != null && !p.getApiKey().isBlank()) {
            p.setApiKey(AesUtil.encrypt(p.getApiKey(), aesKey));
        } else {
            p.setApiKey(db.getApiKey());
        }
        if (p.getIsDefault() != null && p.getIsDefault() == 1) {
            clearDefault();
        }
        llmProviderMapper.updateById(p);
        return mask(llmProviderMapper.selectById(p.getId()));
    }

    /** 连通性测试（§5.2 风格：结果写回 remark） */
    public Map<String, Object> test(Long id) {
        LlmProvider p = llmProviderMapper.selectById(id);
        if (p == null) {
            throw new BizException("供应商不存在");
        }
        boolean ok;
        String errMsg = null;
        try {
            ok = buildClient(p).testConnection();
        } catch (Exception e) {
            ok = false;
            errMsg = e.getMessage();
        }
        LlmProvider upd = new LlmProvider();
        upd.setId(id);
        upd.setRemark((ok ? "测试通过" : "测试失败: " + errMsg));
        llmProviderMapper.updateById(upd);
        log.info("[LLM] provider 连通测试: id={}, name={}, result={}", id, p.getName(), ok);
        return Map.of("success", ok, "error", errMsg == null ? "" : errMsg);
    }

    public void setDefault(Long id) {
        clearDefault();
        LlmProvider upd = new LlmProvider();
        upd.setId(id);
        upd.setIsDefault(1);
        llmProviderMapper.updateById(upd);
    }

    /** 取默认供应商 */
    public LlmProvider getDefault() {
        LlmProvider p = llmProviderMapper.selectOne(new LambdaQueryWrapper<LlmProvider>()
                .eq(LlmProvider::getIsDefault, 1)
                .eq(LlmProvider::getStatus, 1)
                .last("LIMIT 1"));
        if (p == null) {
            throw new BizException("未配置可用的默认 LLM 供应商");
        }
        return p;
    }

    /** 按配置实例化客户端（解密 api_key） */
    public LlmClient buildClient(LlmProvider p) {
        String apiKey = p.getApiKey() == null ? "" : AesUtil.decrypt(p.getApiKey(), aesKey);
        int timeout = p.getTimeoutMs() == null ? 60000 : p.getTimeoutMs();
        if ("ollama".equals(p.getProviderType())) {
            return new OllamaClient(p.getBaseUrl(), timeout, webClientBuilder);
        }
        return new OpenAiCompatibleClient(p.getBaseUrl(), apiKey, timeout, webClientBuilder,
                p.getEmbeddingModel());
    }

    /** 响应脱敏：api_key 只回前4位 */
    private LlmProvider mask(LlmProvider p) {
        if (p.getApiKey() != null && !p.getApiKey().isBlank()) {
            try {
                p.setApiKey(AesUtil.mask(AesUtil.decrypt(p.getApiKey(), aesKey)));
            } catch (Exception e) {
                p.setApiKey("****");
            }
        }
        return p;
    }

    private void clearDefault() {
        new LambdaUpdateChainWrapper<>(llmProviderMapper)
                .eq(LlmProvider::getIsDefault, 1)
                .set(LlmProvider::getIsDefault, 0)
                .update();
    }
}
