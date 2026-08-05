package com.aiinterview.exception;

import lombok.Getter;

/**
 * 业务异常
 * <p>
 * 所有业务逻辑异常统一使用此类抛出，由 GlobalExceptionHandler 统一处理返回。
 * 支持传入 ErrorCode 枚举（推荐）或自定义 code + message。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;
    private final String message;

    /**
     * 使用 ErrorCode 枚举（推荐）
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }

    /**
     * 使用 ErrorCode 枚举 + 自定义消息
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.message = message;
    }

    /**
     * 自定义 code + message
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    /**
     * 仅自定义消息（code 默认500）
     */
    public BusinessException(String message) {
        super(message);
        this.code = ErrorCode.INTERNAL_ERROR.getCode();
        this.message = message;
    }

    /**
     * 自定义消息 + 原始异常
     */
    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = ErrorCode.INTERNAL_ERROR.getCode();
        this.message = message;
    }

    /**
     * ErrorCode + 自定义消息 + 原始异常
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
        this.message = message;
    }
}
