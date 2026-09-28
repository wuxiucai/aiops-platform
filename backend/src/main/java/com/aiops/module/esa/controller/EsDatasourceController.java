package com.aiops.module.esa.controller;

import com.aiops.common.Result;
import com.aiops.datasource.log.EsFieldProbe;
import com.aiops.module.esa.entity.EsDatasource;
import com.aiops.module.esa.mapper.EsDatasourceMapper;
import com.aiops.module.esa.service.EsDatasourceService;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ES 数据源管理
 */
@Tag(name = "ES数据源")
@RestController
@RequestMapping("/api/es/datasource")
@RequiredArgsConstructor
public class EsDatasourceController {

    private final EsDatasourceService esDatasourceService;
    private final EsDatasourceMapper esDatasourceMapper;

    @Operation(summary = "列表")
    @RequirePerm("es:datasource:list")
    @GetMapping("/list")
    public Result<List<EsDatasource>> list() {
        List<EsDatasource> list = esDatasourceMapper.selectList(
                new LambdaQueryWrapper<EsDatasource>().orderByDesc(EsDatasource::getIsDefault));
        list.forEach(ds -> {
            if (ds.getPasswordEnc() != null) ds.setPasswordEnc("****");
            if (ds.getApiKey() != null) ds.setApiKey("****");
        });
        return Result.ok(list);
    }

    @Operation(summary = "新增")
    @RequirePerm("es:datasource:add")
    @PostMapping
    public Result<EsDatasource> add(@RequestBody EsDatasource ds) {
        return Result.ok(esDatasourceService.create(ds));
    }

    @Operation(summary = "修改")
    @RequirePerm("es:datasource:update")
    @PutMapping
    public Result<EsDatasource> update(@RequestBody EsDatasource ds) {
        return Result.ok(esDatasourceService.update(ds));
    }

    @Operation(summary = "删除")
    @RequirePerm("es:datasource:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        esDatasourceMapper.deleteById(id);
        return Result.ok();
    }

    @Operation(summary = "连通性测试")
    @RequirePerm("es:datasource:list")
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable Long id) {
        return Result.ok(esDatasourceService.test(id));
    }

    @Operation(summary = "设为默认")
    @RequirePerm("es:datasource:update")
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        esDatasourceService.setDefault(id);
        return Result.ok();
    }

    @Operation(summary = "索引列表")
    @RequirePerm("es:datasource:list")
    @GetMapping("/{id}/indices")
    public Result<List<Map<String, Object>>> indices(@PathVariable Long id) {
        return Result.ok(esDatasourceService.indices(id));
    }

    @Operation(summary = "字段探测")
    @RequirePerm("es:datasource:list")
    @GetMapping("/{id}/fields")
    public Result<List<EsFieldProbe.FieldInfo>> fields(@PathVariable Long id,
                                                       @RequestParam String indexPattern) {
        return Result.ok(esDatasourceService.fields(id, indexPattern));
    }
}
