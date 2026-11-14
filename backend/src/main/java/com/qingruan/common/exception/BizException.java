package com.qingruan.common.exception;

import com.qingruan.common.result.ResultCode;

/**
 * 业务异常：携带统一响应码，由 GlobalExceptionHandler 统一转换为 Result
 */
public class BizException extends RuntimeException {

    private final int code;
    private final String msg;

    public BizException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMsg());
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
