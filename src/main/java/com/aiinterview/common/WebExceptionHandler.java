package com.aiinterview.common;

import com.aiinterview.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.SocketTimeoutException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * <p>
 * 所有异常统一返回 HTTP 200 + Result.error()，
 * 通过业务错误码区分异常类型，前端无需关心 HTTP 状态码。
 */
@RestControllerAdvice
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    // ============================================================
    // 业务异常
    // ============================================================

    /**
     * 业务异常 — 正常业务流程中的可预期错误
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    // ============================================================
    // 参数校验异常
    // ============================================================

    /**
     * @Valid 校验失败
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return Result.fail(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 参数绑定失败
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数绑定失败: {}", message);
        return Result.fail(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 文件上传大小超限
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("文件上传大小超限: {}", e.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST, "文件大小超过限制");
    }

    /**
     * 非法参数
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    // ============================================================
    // AI服务调用异常
    // ============================================================

    /**
     * AI服务网络异常（SSL握手失败、连接超时等）
     */
    @ExceptionHandler(ResourceAccessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleResourceAccessException(ResourceAccessException e) {
        log.error("AI服务连接失败: {}", e.getMessage(), e);

        Throwable cause = e.getCause();
        if (cause instanceof SocketTimeoutException) {
            return Result.fail(ErrorCode.AI_SERVICE_TIMEOUT, "AI服务响应超时，请稍后重试");
        }

        String message = e.getMessage();
        if (message != null && message.contains("handshake")) {
            return Result.fail(ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "AI服务连接失败（网络不稳定），请检查网络或稍后重试");
        }

        return Result.fail(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI服务暂时不可用，请稍后重试");
    }

    /**
     * AI服务API调用异常（401/429等）
     */
    @ExceptionHandler(RestClientException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleRestClientException(RestClientException e) {
        log.error("AI服务调用失败: {}", e.getMessage(), e);

        String message = e.getMessage();
        if (message != null) {
            if (message.contains("401") || message.contains("Unauthorized")) {
                return Result.fail(ErrorCode.AI_API_KEY_INVALID, "AI服务密钥无效，请检查API Key配置");
            }
            if (message.contains("429") || message.contains("Too Many Requests")) {
                return Result.fail(ErrorCode.AI_RATE_LIMIT_EXCEEDED, "AI服务调用过于频繁，请稍后重试");
            }
        }

        return Result.fail(ErrorCode.AI_SERVICE_ERROR, "AI服务调用失败，请稍后重试");
    }

    // ============================================================
    // 兜底异常
    // ============================================================

    /**
     * 404 — API接口不存在
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleNoResourceFoundException(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.warn("资源未找到: {}", e.getResourcePath());
        return Result.fail(ErrorCode.NOT_FOUND, "API 接口不存在");
    }

    /**
     * 请求方法不支持（GET vs POST）
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleHttpRequestMethodNotSupportedException(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {} {}", e.getMethod(), e.getSupportedHttpMethods());
        return Result.fail(ErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持: " + e.getMethod());
    }

    /**
     * 未知异常 — 兜底处理
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return Result.fail(ErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试");
    }
}
