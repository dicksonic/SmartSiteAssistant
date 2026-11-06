package com.reg.assistant.common;

/** 业务异常：message 直接返回给前端展示 */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(String message) {
        this(400, message);
    }

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
