package com.idolradar.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 稳定的 HTTP 响应封装：成功响应仅携带 data，失败响应仅携带公开错误。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean ok, T data, ErrorBody error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(code, message));
    }

    public record ErrorBody(String code, String message) {
    }
}
