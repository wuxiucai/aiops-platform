package com.aiops.module.system.aspect;

import com.aiops.module.system.entity.SysOperLog;
import com.aiops.module.system.mapper.SysOperLogMapper;
import com.aiops.module.system.annotation.OperLog;
import com.aiops.security.LoginUser;
import com.aiops.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;

/**
 * 操作日志切面：记录方法名、URI、耗时、参数（脱敏 password/apiKey）、结果到 sys_oper_log。
 * 异常路径也记录 status=0 + error_msg，然后原样抛出。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperLogAspect {

    private final SysOperLogMapper sysOperLogMapper;
    private final ObjectMapper objectMapper;

    @Around("@annotation(operLog)")
    public Object around(ProceedingJoinPoint pjp, OperLog operLog) throws Throwable {
        long start = System.currentTimeMillis();
        SysOperLog logEntity = new SysOperLog();
        logEntity.setModule(operLog.module());
        logEntity.setOperation(operLog.operation());
        logEntity.setMethod(pjp.getSignature().getDeclaringTypeName() + "#" + pjp.getSignature().getName());
        logEntity.setCreateTime(LocalDateTime.now());

        LoginUser user = UserContext.get();
        if (user != null) {
            logEntity.setUserId(user.getUserId());
            logEntity.setUsername(user.getUsername());
        }
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            logEntity.setRequestUri(request.getRequestURI());
            logEntity.setIp(request.getRemoteAddr());
            logEntity.setMethod(request.getMethod());
        }
        logEntity.setParams(desensitize(pjp.getArgs()));

        try {
            Object result = pjp.proceed();
            logEntity.setStatus(1);
            return result;
        } catch (Throwable e) {
            logEntity.setStatus(0);
            logEntity.setErrorMsg(truncate(e.getMessage(), 2000));
            throw e;
        } finally {
            logEntity.setCostTime(System.currentTimeMillis() - start);
            try {
                sysOperLogMapper.insert(logEntity);
            } catch (Exception ex) {
                log.error("[OperLog] 操作日志写入失败", ex);
            }
        }
    }

    /** 参数序列化 + 脱敏 password/apiKey 字段 */
    private String desensitize(Object[] args) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                if (arg == null || arg instanceof MultipartFile
                        || arg instanceof HttpServletRequest || arg instanceof HttpServletResponse) {
                    continue;
                }
                String json = objectMapper.writeValueAsString(arg);
                json = json.replaceAll("(\"(?:password|api[Kk]ey|apiKey|pwd)\"\\s*:\\s*\")[^\"]*(\")", "$1***$2");
                sb.append(json.length() > 2000 ? json.substring(0, 2000) : json);
            }
            return truncate(sb.toString(), 2000);
        } catch (Exception e) {
            return "参数序列化失败";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
