package com.aiops.common;

import lombok.Data;

/**
 * 全局唯一响应包装
 */
@Data
public class Result<T> {

    /** 200 成功；400 参数错误；401 未登录；403 无权限；500 系统错误；1001 业务错误 */
    private Integer code;
    private String msg;
    private T data;

    public static <T> Result<T> ok() {
        return build(200, "success", null);
    }

    public static <T> Result<T> ok(T data) {
        return build(200, "success", data);
    }

    public static <T> Result<T> ok(String msg, T data) {
        return build(200, msg, data);
    }

    public static <T> Result<T> fail(String msg) {
        return build(1001, msg, null);
    }

    public static <T> Result<T> fail(Integer code, String msg) {
        return build(code, msg, null);
    }

    public static <T> Result<T> build(Integer code, String msg, T data) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }
}
