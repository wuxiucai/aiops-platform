package com.aiops.module.system.controller;

import com.aiops.common.Result;
import com.aiops.module.system.annotation.OperLog;
import com.aiops.module.system.entity.SysRole;
import com.aiops.module.system.service.SysRoleService;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色管理
 */
@Tag(name = "角色管理")
@RestController
@RequestMapping("/api/system/role")
@RequiredArgsConstructor
@Validated
public class SysRoleController {

    private final SysRoleService sysRoleService;

    @Operation(summary = "分页列表")
    @RequirePerm("system:role:list")
    @GetMapping("/page")
    public Result<Page<SysRole>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "10") long size) {
        return Result.ok(sysRoleService.page(current, size));
    }

    @Operation(summary = "全部角色（下拉用）")
    @RequirePerm("system:role:list")
    @GetMapping("/all")
    public Result<List<SysRole>> all() {
        return Result.ok(sysRoleService.listEnabled());
    }

    @Data
    public static class RoleSaveRequest {
        private Long id;
        @NotBlank(message = "角色编码不能为空")
        private String roleCode;
        @NotBlank(message = "角色名称不能为空")
        private String roleName;
        private String description;
        private Integer status;
        private List<Long> permissionIds;
    }

    @Operation(summary = "新增角色")
    @RequirePerm("system:role:add")
    @OperLog(module = "system", operation = "新增角色")
    @PostMapping
    public Result<Void> add(@RequestBody @Validated RoleSaveRequest req) {
        sysRoleService.add(req);
        return Result.ok();
    }

    @Operation(summary = "角色的权限 ID 列表（编辑回显用）")
    @RequirePerm("system:role:list")
    @GetMapping("/{id}/permissions")
    public Result<List<Long>> getRolePermissions(@PathVariable Long id) {
        return Result.ok(sysRoleService.permissionIdsOf(id));
    }

    @Operation(summary = "修改角色")
    @RequirePerm("system:role:update")
    @OperLog(module = "system", operation = "修改角色")
    @PutMapping
    public Result<Void> update(@RequestBody @Validated RoleSaveRequest req) {
        sysRoleService.update(req);
        return Result.ok();
    }

    @Operation(summary = "删除角色")
    @RequirePerm("system:role:delete")
    @OperLog(module = "system", operation = "删除角色")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysRoleService.delete(id);
        return Result.ok();
    }
}
