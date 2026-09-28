package com.aiops.module.system.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.system.annotation.OperLog;
import com.aiops.module.system.entity.SysUser;
import com.aiops.module.system.entity.SysUserRole;
import com.aiops.module.system.mapper.SysUserMapper;
import com.aiops.module.system.mapper.SysUserRoleMapper;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Operation(summary = "分页列表")
    @RequirePerm("system:user:list")
    @GetMapping("/page")
    public Result<Page<SysUser>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) String keyword) {
        Page<SysUser> page = sysUserMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<SysUser>()
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(SysUser::getUsername, keyword)
                                        .or().like(SysUser::getNickname, keyword))
                        .orderByDesc(SysUser::getCreateTime));
        page.getRecords().forEach(u -> u.setPassword(null));
        return Result.ok(page);
    }

    @Data
    public static class UserSaveRequest {
        private Long id;
        @NotBlank(message = "用户名不能为空")
        private String username;
        @Size(min = 6, max = 64, message = "密码长度 6~64")
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
        Long cnt = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername()));
        if (cnt > 0) {
            throw new BizException("用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        sysUserMapper.insert(user);
        saveRoles(user.getId(), req.getRoleIds());
        return Result.ok();
    }

    @Operation(summary = "修改用户")
    @RequirePerm("system:user:update")
    @OperLog(module = "system", operation = "修改用户")
    @PutMapping
    public Result<Void> update(@RequestBody @Validated UserSaveRequest req) {
        SysUser user = new SysUser();
        user.setId(req.getId());
        user.setNickname(req.getNickname());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setStatus(req.getStatus());
        // 密码非空才更新
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }
        sysUserMapper.updateById(user);
        if (req.getRoleIds() != null) {
            sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, req.getId()));
            saveRoles(req.getId(), req.getRoleIds());
        }
        return Result.ok();
    }

    @Operation(summary = "删除用户")
    @RequirePerm("system:user:delete")
    @OperLog(module = "system", operation = "删除用户")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (id.equals(1L)) {
            throw new BizException("内置管理员不可删除");
        }
        sysUserMapper.deleteById(id);
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        return Result.ok();
    }

    private void saveRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null) {
            return;
        }
        for (Long roleId : roleIds) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            sysUserRoleMapper.insert(ur);
        }
    }
}
