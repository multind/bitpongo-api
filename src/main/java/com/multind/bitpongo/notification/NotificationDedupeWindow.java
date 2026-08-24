package com.multind.bitpongo.notification;

import java.time.Duration;
import java.util.Objects;

public record NotificationDedupeWindow(String scopeKey, Duration duration) {

    public static final int MAX_SCOPE_KEY_LENGTH = 160;

    public NotificationDedupeWindow {
        Objects.requireNonNull(scopeKey, "scopeKey");
        Objects.requireNonNull(duration, "duration");
        if (scopeKey.isBlank()) {
            throw new IllegalArgumentException("scopeKey must not be blank");
        }
        if (scopeKey.length() > MAX_SCOPE_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "scopeKey must not exceed " + MAX_SCOPE_KEY_LENGTH + " characters");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
    }
}
