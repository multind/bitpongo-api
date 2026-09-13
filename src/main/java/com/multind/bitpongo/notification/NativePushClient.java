package com.multind.bitpongo.notification;

import java.util.Map;

public interface NativePushClient {
    boolean available();
    default boolean prepare() { return available(); }
    Result send(String token, Map<String, Object> message);
    record Result(boolean accepted, boolean retryable, boolean invalidToken, long retrySeconds, String code) {}
}
