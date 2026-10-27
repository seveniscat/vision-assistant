package com.bz.va.web;

/** VA 业务错误（携带期望的 HTTP 状态码），由 GlobalExceptionHandler 转为统一错误体。 */
public class ApiException extends RuntimeException {

    private final String code;
    private final int status;

    public ApiException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public int getStatus() {
        return status;
    }
}
