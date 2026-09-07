package com.aiinterview.common;

import com.aiinterview.common.CommonConstants;
import com.aiinterview.common.ErrorCode;
import lombok.Getter;

/**
 * 统一响应结果
 * <p>
 * 所有API统一返回此结构：{ code, message, data }。
 * HTTP状态码始终为200（业务异常通过code字段区分）。
 */
@Getter
public class Result<T> {

    private final Integer code;
    private final String message;
    private final T data;

    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ========== 成功响应 ==========

    public static <T> Result<T> ok() {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, "success", null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, "success", data);
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, message, data);
    }

    // ========== 失败响应 ==========

    public static <T> Result<T> fail(String message) {
        return new Result<>(CommonConstants.StatusCode.SERVER_ERROR, message, null);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    // ========== 辅助方法 ==========

    public boolean isSuccess() {
        return CommonConstants.StatusCode.SUCCESS == this.code;
    }
}
