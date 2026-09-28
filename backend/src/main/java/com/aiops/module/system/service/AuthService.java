package com.aiops.module.system.service;

import com.aiops.module.system.dto.LoginRequest;
import com.aiops.module.system.dto.LoginResponse;
import com.aiops.module.system.entity.SysUser;
import com.aiops.module.system.mapper.SysUserMapper;
import com.aiops.common.BizException;
import com.aiops.security.JwtUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * 认证服务：登录（验证码 + BCrypt）→ 签发 JWT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final CaptchaService captchaService;
    private final SysPermissionService sysPermissionService;

    public LoginResponse login(LoginRequest req) {
        if (!captchaService.verify(req.getCaptchaKey(), req.getCaptchaCode())) {
            throw new BizException("验证码错误或已过期");
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.warn("[Auth] 登录失败: username={}", req.getUsername());
            throw new BizException("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BizException("账号已禁用");
        }
        java.util.Set<String> roleCodes = new java.util.HashSet<>(sysPermissionService.getUserRoleCodes(user.getId()));
        java.util.Set<String> perms = new java.util.HashSet<>(sysPermissionService.getUserPermCodes(user.getId()));
        String token = jwtUtils.createToken(user.getId(), user.getUsername(), new ArrayList<>(roleCodes));

        // 更新最后登录时间
        SysUser upd = new SysUser();
        upd.setId(user.getId());
        upd.setLastLoginTime(LocalDateTime.now());
        sysUserMapper.updateById(upd);

        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        resp.setUserId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setNickname(user.getNickname());
        resp.setRoles(roleCodes);
        resp.setPerms(perms);
        log.info("[Auth] 登录成功: username={}, roles={}", user.getUsername(), roleCodes);
        return resp;
    }
}
