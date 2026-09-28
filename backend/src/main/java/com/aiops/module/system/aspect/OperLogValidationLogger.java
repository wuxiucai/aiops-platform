package com.aiops.module.system.aspect;

import com.aiops.module.system.annotation.OperLog;
import com.aiops.module.system.entity.SysOperLog;
import com.aiops.module.system.mapper.SysOperLogMapper;
import com.aiops.security.LoginUser;
import com.aiops.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.time.LocalDateTime;

/**
 * 供 GlobalExceptionHandler 在"参数校验失败"路径手动补写操作日志。
 * aspect {@code @Around} 在 @Validated 校验失败时不会进入（异常发生在方法调用之前），
 * 因此把这条失败日志的写入拆出来，让异常处理器在需要时调用。
 */
@Slf4j
@Component
public class OperLogValidationLogger {

    private static SysOperLogMapper MAPPER;

    /** Spring 启动时把 bean 注入静态引用，方便从静态工具方法访问 */
    public OperLogValidationLogger(SysOperLogMapper mapper, ApplicationContext ctx) {
        OperLogValidationLogger.MAPPER = mapper;
    }

    public static void writeFailure(HttpServletRequest request,
                                    HandlerMethod handlerMethod,
                                    OperLog operLog,
                                    String errorMsg) {
        if (MAPPER == null) {
            return;
        }
        try {
            SysOperLog logEntity = new SysOperLog();
            logEntity.setModule(operLog.module());
            logEntity.setOperation(operLog.operation());
            logEntity.setMethod(request.getMethod());
            logEntity.setRequestUri(request.getRequestURI());
            logEntity.setIp(request.getRemoteAddr());
            logEntity.setStatus(0);
            logEntity.setErrorMsg(errorMsg.length() > 2000 ? errorMsg.substring(0, 2000) : errorMsg);
            logEntity.setCostTime(0L);
            logEntity.setCreateTime(LocalDateTime.now());

            LoginUser user = UserContext.get();
            if (user != null) {
                logEntity.setUserId(user.getUserId());
                logEntity.setUsername(user.getUsername());
            }
            // 不序列化 @RequestBody —— 校验失败时 body 不可恢复；记录 handler 即可
            logEntity.setParams("handler=" + handlerMethod.getBeanType().getName()
                    + "#" + handlerMethod.getMethod().getName() + " （参数校验失败，未进入方法）");
            MAPPER.insert(logEntity);
        } catch (Exception e) {
            log.error("[OperLog] 校验失败日志写入异常", e);
        }
    }
}
