package com.aiops.module.esa.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.esa.entity.EsIndexConfig;
import com.aiops.module.esa.mapper.EsIndexConfigMapper;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ES 索引与字段映射配置
 */
@Tag(name = "ES索引配置")
@RestController
@RequestMapping("/api/es/index-config")
@RequiredArgsConstructor
public class EsIndexConfigController {

    private final EsIndexConfigMapper esIndexConfigMapper;

    @Operation(summary = "列表")
    @RequirePerm("es:indexconfig:list")
    @GetMapping("/list")
    public Result<List<EsIndexConfig>> list(@RequestParam(required = false) Long datasourceId) {
        return Result.ok(esIndexConfigMapper.selectList(
                new LambdaQueryWrapper<EsIndexConfig>()
                        .eq(datasourceId != null, EsIndexConfig::getDatasourceId, datasourceId)
                        .orderByDesc(EsIndexConfig::getId)));
    }

    @Operation(summary = "新增")
    @RequirePerm("es:indexconfig:add")
    @PostMapping
    public Result<Void> add(@RequestBody EsIndexConfig config) {
        // name + datasourceId 唯一校验
        Long cnt = esIndexConfigMapper.selectCount(new LambdaQueryWrapper<EsIndexConfig>()
                .eq(EsIndexConfig::getDatasourceId, config.getDatasourceId())
                .eq(EsIndexConfig::getName, config.getName()));
        if (cnt > 0) {
            throw new BizException("同一数据源下配置名已存在");
        }
        esIndexConfigMapper.insert(config);
        return Result.ok();
    }

    @Operation(summary = "修改")
    @RequirePerm("es:indexconfig:update")
    @PutMapping
    public Result<Void> update(@RequestBody EsIndexConfig config) {
        Long cnt = esIndexConfigMapper.selectCount(new LambdaQueryWrapper<EsIndexConfig>()
                .eq(EsIndexConfig::getDatasourceId, config.getDatasourceId())
                .eq(EsIndexConfig::getName, config.getName())
                .ne(EsIndexConfig::getId, config.getId()));
        if (cnt > 0) {
            throw new BizException("同一数据源下配置名已存在");
        }
        esIndexConfigMapper.updateById(config);
        return Result.ok();
    }

    @Operation(summary = "删除")
    @RequirePerm("es:indexconfig:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        esIndexConfigMapper.deleteById(id);
        return Result.ok();
    }
}
