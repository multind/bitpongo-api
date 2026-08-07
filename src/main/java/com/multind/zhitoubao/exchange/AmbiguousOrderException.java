package com.multind.zhitoubao.exchange;

public class AmbiguousOrderException extends RuntimeException {
    public AmbiguousOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
