package com.multind.zhitoubao.exchange;

public class BinanceClientException extends RuntimeException {
    private final int httpStatus;
    private final int errorCode;
    private final boolean timeout;

    public BinanceClientException(int httpStatus, int errorCode, String message, boolean timeout) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.timeout = timeout;
    }

    public int httpStatus() { return httpStatus; }
    public int errorCode() { return errorCode; }
    public boolean timeout() { return timeout; }
}
