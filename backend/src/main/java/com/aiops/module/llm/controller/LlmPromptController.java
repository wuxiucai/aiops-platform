package com.aiops.module.llm.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.llm.entity.LlmPromptTemplate;
import com.aiops.module.llm.mapper.LlmPromptTemplateMapper;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词管理（M5-4）。
 * 列表 / 详情 / 保存（自动 version+1）——7 场景 llm_prompt_template 现网已 seed。
 */
@Tag(name = "LLM提示词管理")
@RestController
@RequestMapping("/api/llm/prompt")
@RequiredArgsConstructor
public class LlmPromptController {

    private final LlmPromptTemplateMapper llmPromptTemplateMapper;

    @Operation(summary = "列表（7 场景）")
    @RequirePerm("llm:prompt:list")
    @GetMapping("/list")
    public Result<List<LlmPromptTemplate>> list() {
        return Result.ok(llmPromptTemplateMapper.selectList(
                new LambdaQueryWrapper<LlmPromptTemplate>()
                        .orderByAsc(LlmPromptTemplate::getSceneCode)));
    }

    @Operation(summary = "详情")
    @RequirePerm("llm:prompt:list")
    @GetMapping("/{id}")
    public Result<LlmPromptTemplate> detail(@PathVariable Long id) {
        LlmPromptTemplate t = llmPromptTemplateMapper.selectById(id);
        if (t == null) throw new BizException("模板不存在：" + id);
        return Result.ok(t);
    }

    /**
     * 保存：user_prompt_tpl / system_prompt / output_schema 改变时 version+1，旧版本历史其实存在 sys_permission 与
     * 现有 llm_prompt_template 表中没有较深的 history 字段（任务书 v2 §3.8)，用 version 自增即可。
     */
    @Operation(summary = "保存（自动 version+1）")
    @RequirePerm("llm:prompt:update")
    @PutMapping
    public Result<Map<String, Object>> save(@RequestBody LlmPromptTemplate req) {
        if (req.getId() == null) throw new BizException("id 必填");
        LlmPromptTemplate old = llmPromptTemplateMapper.selectById(req.getId());
        if (old == null) throw new BizException("模板不存在：" + req.getId());
        old.setSystemPrompt(req.getSystemPrompt() != null ? req.getSystemPrompt() : old.getSystemPrompt());
        old.setUserPromptTpl(req.getUserPromptTpl() != null ? req.getUserPromptTpl() : old.getUserPromptTpl());
        old.setOutputSchema(req.getOutputSchema() != null ? req.getOutputSchema() : old.getOutputSchema());
        old.setEnabled(req.getEnabled() != null ? req.getEnabled() : old.getEnabled());
        old.setVersion((old.getVersion() == null ? 1 : old.getVersion()) + 1);
        old.setUpdateTime(LocalDateTime.now());
        llmPromptTemplateMapper.updateById(old);
        Map<String, Object> out = new HashMap<>();
        out.put("id", old.getId());
        out.put("sceneCode", old.getSceneCode());
        out.put("newVersion", old.getVersion());
        return Result.ok(out);
    }
}
