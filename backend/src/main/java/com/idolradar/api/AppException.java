package com.idolradar.api;

import org.springframework.http.HttpStatus;

/** 可预期的应用异常，包含对客户端安全的错误码、消息和 HTTP 状态。 */
public class AppException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public AppException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
