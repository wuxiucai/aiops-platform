package com.aiops.security;

import com.aiops.common.Result;
import com.aiops.common.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;

/**
 * @RequirePerm 权限拦截器：校验当前用户 perms 是否包含注解值。
 * 权限数据在 JwtAuthFilter 之后由 system 模块加载（见 AuthInterceptor 注册顺序）。
 */
@Component
@RequiredArgsConstructor
public class RequirePermInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        Method method = handlerMethod.getMethod();
        RequirePerm requirePerm = method.getAnnotation(RequirePerm.class);
        if (requirePerm == null) {
            return true;
        }
        LoginUser user = UserContext.get();
        if (user == null) {
            writeJson(response, ResultCode.UNAUTHORIZED, "未登录");
            return false;
        }
        // perms 由 system 模块在登录/过滤时填充；此处仅校验
        if (!user.hasPerm(requirePerm.value())) {
            writeJson(response, ResultCode.FORBIDDEN, "无权限：" + requirePerm.value());
            return false;
        }
        return true;
    }

    private void writeJson(HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, msg)));
    }
}
