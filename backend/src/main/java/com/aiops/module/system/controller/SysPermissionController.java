package com.aiops.module.system.controller;

import com.aiops.common.Result;
import com.aiops.module.system.annotation.OperLog;
import com.aiops.module.system.entity.SysPermission;
import com.aiops.module.system.mapper.SysPermissionMapper;
import com.aiops.module.system.service.SysPermissionService;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 权限（菜单树）管理
 */
@Tag(name = "权限管理")
@RestController
@RequestMapping("/api/system/permission")
@RequiredArgsConstructor
public class SysPermissionController {

    private final SysPermissionMapper sysPermissionMapper;
    private final SysPermissionService sysPermissionService;

    @Operation(summary = "权限树")
    @RequirePerm("system:permission:list")
    @GetMapping("/tree")
    public Result<List<SysPermission>> tree() {
        return Result.ok(sysPermissionService.tree());
    }

    @Operation(summary = "新增权限")
    @RequirePerm("system:permission:add")
    @OperLog(module = "system", operation = "新增权限")
    @PostMapping
    public Result<Void> add(@RequestBody SysPermission perm) {
        perm.setId(null);
        sysPermissionMapper.insert(perm);
        return Result.ok();
    }

    @Operation(summary = "修改权限")
    @RequirePerm("system:permission:update")
    @OperLog(module = "system", operation = "修改权限")
    @PutMapping
    public Result<Void> update(@RequestBody SysPermission perm) {
        sysPermissionMapper.updateById(perm);
        return Result.ok();
    }

    @Operation(summary = "删除权限")
    @RequirePerm("system:permission:delete")
    @OperLog(module = "system", operation = "删除权限")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysPermissionMapper.deleteById(id);
        return Result.ok();
    }
}
