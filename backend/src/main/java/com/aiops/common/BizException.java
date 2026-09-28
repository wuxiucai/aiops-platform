package com.aiops.common;

import lombok.Getter;

/**
 * 业务异常
 */
@Getter
public class BizException extends RuntimeException {

    private final Integer code;

    public BizException(String message) {
        this(ResultCode.BIZ_ERROR, message);
    }

    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
