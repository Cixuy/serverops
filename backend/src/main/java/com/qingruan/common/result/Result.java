package com.qingruan.common.result;

import com.alibaba.fastjson2.JSON;
import com.qingruan.common.constant.SystemCodeEnum;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.Objects;

public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "code", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer code;

    @Schema(description = "message", requiredMode = Schema.RequiredMode.REQUIRED, example = "success")
    private String message;

    private T data;

    public Result() {
    }

    private Result(ResultCode resultCode) {
        this.code = resultCode.getCode();
        this.message = resultCode.getMsg();
    }

    private Result(ResultCode resultCode, String msg) {
        this.code = resultCode.getCode();
        this.message = msg;
    }

    private Result(int code, String msg) {
        this.code = code;
        this.message = msg;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public static <T> Result<T> noAuth() {
        return error(SystemCodeEnum.SYSTEM_NO_AUTH);
    }

    public static <T> Result<T> error(ResultCode resultCode) {
        return new Result<>(resultCode);
    }

    public static <T> Result<T> error(int code, String msg) {
        return new Result<>(code, msg);
    }

    public static <T> Result<T> error(ResultCode resultCode, String msg) {
        return new Result<>(resultCode, msg);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>(SystemCodeEnum.SYSTEM_OK);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> ok() {
        return new Result<>(SystemCodeEnum.SYSTEM_OK);
    }

    public Result<T> setData(T data) {
        this.data = data;
        return this;
    }

    public T getData() {
        return this.data;
    }

    public boolean hasSuccess() {
        return Objects.equals(SystemCodeEnum.SYSTEM_OK.getCode(), code);
    }

    public String toJSONString() {
        return JSON.toJSONString(this);
    }

    @Override
    public String toString() {
        return JSON.toJSONString(this);
    }
}