package com.aiops.module.kb.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.kb.entity.KbFaultCase;
import com.aiops.module.kb.mapper.KbFaultCaseMapper;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 知识库 - 案例 CRUD（S2 kb/Case）
 * 与 KbCaseController（同步嵌入 / 相似检索）解耦：本类只做基础 CRUD。
 */
@Slf4j
@Tag(name = "知识库 案例")
@RestController
@RequestMapping("/api/kb/case")
@RequiredArgsConstructor
public class KbCaseCrudController {

    private final KbFaultCaseMapper kbFaultCaseMapper;

    @Operation(summary = "分页列表")
    @RequirePerm("kb:case:list")
    @GetMapping
    public Result<Page<KbFaultCase>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String keyword) {
        return Result.ok(kbFaultCaseMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<KbFaultCase>()
                        .like(keyword != null && !keyword.isBlank(), KbFaultCase::getTitle, keyword)
                        .orderByDesc(KbFaultCase::getId)));
    }

    @Operation(summary = "详情")
    @RequirePerm("kb:case:list")
    @GetMapping("/{id}")
    public Result<KbFaultCase> detail(@PathVariable Long id) {
        KbFaultCase c = kbFaultCaseMapper.selectById(id);
        if (c == null) throw new BizException("案例不存在");
        return Result.ok(c);
    }

    @Operation(summary = "新建")
    @RequirePerm("kb:case:list")
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody KbFaultCase body) {
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            throw new BizException("title 必填");
        }
        body.setId(null);
        body.setDeleted(0);
        body.setCreateTime(LocalDateTime.now());
        if (body.getEmbeddingStatus() == null) body.setEmbeddingStatus("pending");
        if (body.getSource() == null) body.setSource("manual");
        if (body.getOccurredTime() == null) body.setOccurredTime(LocalDateTime.now());
        kbFaultCaseMapper.insert(body);
        Map<String, Object> r = new HashMap<>();
        r.put("id", body.getId());
        return Result.ok(r);
    }

    @Operation(summary = "编辑（重置 embedding_status 让后台重新嵌入）")
    @RequirePerm("kb:case:list")
    @PutMapping
    public Result<Void> update(@RequestBody KbFaultCase body) {
        if (body.getId() == null) throw new BizException("id 必填");
        KbFaultCase exist = kbFaultCaseMapper.selectById(body.getId());
        if (exist == null) throw new BizException("案例不存在");
        // 任一关键字段变化 → 重置 pending 让 sync-embeddings 重跑
        body.setEmbeddingStatus("pending");
        kbFaultCaseMapper.updateById(body);
        return Result.ok();
    }

    @Operation(summary = "删除（软删）")
    @RequirePerm("kb:case:list")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        KbFaultCase exist = kbFaultCaseMapper.selectById(id);
        if (exist == null) throw new BizException("案例不存在");
        kbFaultCaseMapper.deleteById(id);
        return Result.ok();
    }
}
