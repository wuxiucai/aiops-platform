package com.aiops.module.system.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.system.annotation.OperLog;
import com.aiops.module.system.entity.SysRole;
import com.aiops.module.system.entity.SysRolePermission;
import com.aiops.module.system.mapper.SysRoleMapper;
import com.aiops.module.system.mapper.SysRolePermissionMapper;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    private final SysRoleMapper sysRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;

    @Operation(summary = "分页列表")
    @RequirePerm("system:role:list")
    @GetMapping("/page")
    public Result<Page<SysRole>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "10") long size) {
        return Result.ok(sysRoleMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId)));
    }

    @Operation(summary = "全部角色（下拉用）")
    @RequirePerm("system:role:list")
    @GetMapping("/all")
    public Result<List<SysRole>> all() {
        return Result.ok(sysRoleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getStatus, 1)));
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
        Long cnt = sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, req.getRoleCode()));
        if (cnt > 0) {
            throw new BizException("角色编码已存在");
        }
        SysRole role = new SysRole();
        role.setRoleCode(req.getRoleCode());
        role.setRoleName(req.getRoleName());
        role.setDescription(req.getDescription());
        role.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        sysRoleMapper.insert(role);
        savePerms(role.getId(), req.getPermissionIds());
        return Result.ok();
    }

    @Operation(summary = "修改角色")
    @RequirePerm("system:role:update")
    @OperLog(module = "system", operation = "修改角色")
    @PutMapping
    public Result<Void> update(@RequestBody @Validated RoleSaveRequest req) {
        SysRole role = new SysRole();
        role.setId(req.getId());
        role.setRoleName(req.getRoleName());
        role.setDescription(req.getDescription());
        role.setStatus(req.getStatus());
        sysRoleMapper.updateById(role);
        if (req.getPermissionIds() != null) {
            sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                    .eq(SysRolePermission::getRoleId, req.getId()));
            savePerms(req.getId(), req.getPermissionIds());
        }
        return Result.ok();
    }

    @Operation(summary = "删除角色")
    @RequirePerm("system:role:delete")
    @OperLog(module = "system", operation = "删除角色")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (id <= 3L) {
            throw new BizException("内置角色不可删除");
        }
        sysRoleMapper.deleteById(id);
        sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, id));
        return Result.ok();
    }

    private void savePerms(Long roleId, List<Long> permIds) {
        if (permIds == null) {
            return;
        }
        for (Long pid : permIds) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(pid);
            sysRolePermissionMapper.insert(rp);
        }
    }
}
