package com.multind.bitpongo.exchange;

public class RetryableExchangeException extends RuntimeException {
    public RetryableExchangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
