package com.aiops.module.system.controller;

import com.aiops.common.Result;
import com.aiops.module.system.dto.LoginRequest;
import com.aiops.module.system.dto.LoginResponse;
import com.aiops.module.system.entity.SysPermission;
import com.aiops.module.system.entity.SysUser;
import com.aiops.module.system.mapper.SysUserMapper;
import com.aiops.module.system.service.AuthService;
import com.aiops.module.system.service.CaptchaService;
import com.aiops.module.system.service.SysPermissionService;
import com.aiops.security.LoginUser;
import com.aiops.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认证接口
 */
@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final SysPermissionService sysPermissionService;
    private final SysUserMapper sysUserMapper;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        // 无状态 JWT：前端丢弃 token 即可
        return Result.ok();
    }

    @Operation(summary = "获取验证码")
    @GetMapping("/captcha")
    public Result<Map<String, String>> captcha() {
        return Result.ok(captchaService.generate());
    }

    @Operation(summary = "当前用户信息")
    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        LoginUser user = UserContext.get();
        SysUser dbUser = sysUserMapper.selectById(user.getUserId());
        Set<String> roles = sysPermissionService.getUserRoleCodes(user.getUserId());
        Set<String> perms = sysPermissionService.getUserPermCodes(user.getUserId());
        List<SysPermission> menus = sysPermissionService.tree();
        return Result.ok(Map.of(
                "userId", user.getUserId(),
                "username", dbUser != null ? dbUser.getUsername() : user.getUsername(),
                "nickname", dbUser != null && dbUser.getNickname() != null ? dbUser.getNickname() : "",
                "avatar", dbUser != null && dbUser.getAvatar() != null ? dbUser.getAvatar() : "",
                "roles", roles,
                "perms", perms,
                "menus", menus
        ));
    }
}
