package com.aiops.module.system.controller;

import com.aiops.common.Result;
import com.aiops.module.system.annotation.OperLog;
import com.aiops.module.system.entity.SysUser;
import com.aiops.module.system.service.SysUserService;
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
 * 用户管理
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/system/user")
@RequiredArgsConstructor
@Validated
public class SysUserController {

    private final SysUserService sysUserService;

    @Operation(summary = "分页列表")
    @RequirePerm("system:user:list")
    @GetMapping("/page")
    public Result<Page<SysUser>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) String keyword) {
        return Result.ok(sysUserService.page(current, size, keyword));
    }

    @Data
    public static class UserSaveRequest {
        private Long id;
        @NotBlank(message = "用户名不能为空")
        private String username;
        /** password 允许为 null（编辑时不改密码）；null 时跳过 @Size 校验 */
        @jakarta.validation.constraints.Size(min = 6, max = 64, message = "密码长度 6~64")
        private String password;
        private String nickname;
        private String email;
        private String phone;
        private Integer status;
        private List<Long> roleIds;
    }

    @Operation(summary = "新增用户")
    @RequirePerm("system:user:add")
    @OperLog(module = "system", operation = "新增用户")
    @PostMapping
    public Result<Void> add(@RequestBody @Validated UserSaveRequest req) {
        sysUserService.add(req);
        return Result.ok();
    }

    @Operation(summary = "修改用户")
    @RequirePerm("system:user:update")
    @OperLog(module = "system", operation = "修改用户")
    @PutMapping
    public Result<Void> update(@RequestBody @Validated UserSaveRequest req) {
        sysUserService.update(req);
        return Result.ok();
    }

    @Operation(summary = "用户的角色 ID 列表（编辑回显用）")
    @RequirePerm("system:user:list")
    @GetMapping("/{id}/roles")
    public Result<List<Long>> getUserRoles(@PathVariable Long id) {
        return Result.ok(sysUserService.roleIdsOf(id));
    }

    @Operation(summary = "删除用户")
    @RequirePerm("system:user:delete")
    @OperLog(module = "system", operation = "删除用户")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysUserService.delete(id);
        return Result.ok();
    }
}
