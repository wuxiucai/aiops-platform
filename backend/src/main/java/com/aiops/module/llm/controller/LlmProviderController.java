package com.aiops.module.llm.controller;

import com.aiops.common.Result;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.service.LlmProviderService;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * LLM 供应商配置
 */
@Tag(name = "LLM模型配置")
@RestController
@RequestMapping("/api/llm/provider")
@RequiredArgsConstructor
public class LlmProviderController {

    private final LlmProviderService llmProviderService;
    private final com.aiops.module.llm.mapper.LlmProviderMapper llmProviderMapper;

    @Operation(summary = "列表")
    @RequirePerm("llm:provider:list")
    @GetMapping("/list")
    public Result<List<LlmProvider>> list() {
        List<LlmProvider> list = llmProviderMapper.selectList(
                new LambdaQueryWrapper<LlmProvider>().orderByDesc(LlmProvider::getIsDefault));
        list.forEach(p -> {
            if (p.getApiKey() != null && !p.getApiKey().isBlank()) {
                p.setApiKey("****");
            }
        });
        return Result.ok(list);
    }

    @Operation(summary = "新增")
    @RequirePerm("llm:provider:add")
    @PostMapping
    public Result<LlmProvider> add(@RequestBody LlmProvider p) {
        return Result.ok(llmProviderService.create(p));
    }

    @Operation(summary = "修改")
    @RequirePerm("llm:provider:update")
    @PutMapping
    public Result<LlmProvider> update(@RequestBody LlmProvider p) {
        return Result.ok(llmProviderService.update(p));
    }

    @Operation(summary = "删除")
    @RequirePerm("llm:provider:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        llmProviderMapper.deleteById(id);
        return Result.ok();
    }

    @Operation(summary = "连通性测试")
    @RequirePerm("llm:provider:list")
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable Long id) {
        return Result.ok(llmProviderService.test(id));
    }

    @Operation(summary = "设为默认")
    @RequirePerm("llm:provider:update")
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        llmProviderService.setDefault(id);
        return Result.ok();
    }
}
