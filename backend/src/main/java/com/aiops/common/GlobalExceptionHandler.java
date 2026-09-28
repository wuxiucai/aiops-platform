package com.aiops.common;

import com.aiops.module.system.annotation.OperLog;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 全局异常处理器。
 * 兜底 Exception 的 ERROR 日志是平台自身 ERROR 日志的重要来源，必须写清上下文。
 *
 * 注意：{@code @Validated} 校验失败的 MethodArgumentNotValidException 是在参数解析阶段抛出，
 * 早于 Spring AOP {@code @Around} 方法代理链——oper-log aspect 无法感知。因此对已标注
 * {@link OperLog} 的 controller 方法，这里补一条 status=0 的失败日志，与 aspect 异常路径保持对齐。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("[Biz] code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数错误");
        writeOperLogForValidationFailure(msg);
        return Result.fail(ResultCode.PARAM_ERROR, msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("[System] 系统异常: uri 不可得, class={}, msg={}",
                e.getClass().getSimpleName(), e.getMessage(), e);
        return Result.fail(ResultCode.SYSTEM_ERROR, "系统繁忙");
    }

    /**
     * 若当前请求命中的 controller 方法标了 @OperLog，补一条 status=0 日志。
     * 不依赖 aspect 触发（aspect 在参数校验失败时不会进入）。
     */
    private void writeOperLogForValidationFailure(String errorMsg) {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return;
            }
            HttpServletRequest request = attrs.getRequest();
            Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
            if (!(handler instanceof HandlerMethod handlerMethod)) {
                return;
            }
            OperLog operLog = handlerMethod.getMethod().getAnnotation(OperLog.class);
            if (operLog == null) {
                return;
            }
            // 委托给 aspect 同包内的静态写入器，避免重复实现一套
            com.aiops.module.system.aspect.OperLogValidationLogger
                    .writeFailure(request, handlerMethod, operLog, errorMsg);
        } catch (Exception ex) {
            log.error("[OperLog] 校验失败补写日志失败", ex);
        }
    }
}
