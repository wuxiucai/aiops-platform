package com.aiops.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器。
 * 白名单放行；SSE 问答接口（/api/ai/chat/stream）额外兼容 ?token= 查询参数（EventSource 无法带 Header 的妥协点）。
 * 解析 token 后加载用户权限码（带 60s 小缓存，毕设规模够用），权限校验由 RequirePermInterceptor 负责。
 */
@Slf4j
@Component
@Order(10)
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final com.aiops.module.system.service.SysPermissionService sysPermissionService;

    /** userId -> [perms, loadTime] 的简单缓存 */
    private final java.util.concurrent.ConcurrentHashMap<Long, Object[]> permCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final long PERM_CACHE_MS = 60_000L;

    public JwtAuthFilter(JwtUtils jwtUtils,
                         com.aiops.module.system.service.SysPermissionService sysPermissionService) {
        this.jwtUtils = jwtUtils;
        this.sysPermissionService = sysPermissionService;
    }

    /** SSE 流式接口允许 ?token= 鉴权的唯一路径 */
    public static final String SSE_CHAT_PATH = "/api/ai/chat/stream";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 仅登录/验证码/静态资源/swagger 放行（info 需要登录，不在白名单）
        return uri.equals("/api/auth/login")
                || uri.equals("/api/auth/captcha")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/v3/api-docs")
                || uri.equals("/")
                || uri.startsWith("/index.html")
                || uri.startsWith("/assets/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            write401(response, "未登录或凭证缺失");
            return;
        }
        LoginUser user = jwtUtils.parseToken(token);
        if (user == null) {
            write401(response, "凭证无效或已过期");
            return;
        }
        // 填充权限码（缓存 60s）
        Object[] cached = permCache.get(user.getUserId());
        if (cached != null && System.currentTimeMillis() - (long) cached[1] < PERM_CACHE_MS) {
            @SuppressWarnings("unchecked")
            java.util.Set<String> perms = (java.util.Set<String>) cached[0];
            user.setPerms(perms);
        } else {
            java.util.Set<String> perms = sysPermissionService.getUserPermCodes(user.getUserId());
            user.setPerms(perms);
            permCache.put(user.getUserId(), new Object[]{perms, System.currentTimeMillis()});
        }
        UserContext.set(user);
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        // 仅 SSE 问答路径兼容 query token
        if (SSE_CHAT_PATH.equals(request.getRequestURI())) {
            return request.getParameter("token");
        }
        return null;
    }

    private void write401(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"" + msg + "\",\"data\":null}");
    }
}
