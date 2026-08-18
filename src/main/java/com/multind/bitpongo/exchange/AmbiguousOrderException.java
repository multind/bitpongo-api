package com.multind.bitpongo.exchange;

public class AmbiguousOrderException extends RuntimeException {
    public AmbiguousOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
