package com.multind.bitpongo.notification;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class NotificationRetryPolicy {

    static final int MAX_ATTEMPTS = 10;

    public boolean isDead(int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }

    public LocalDateTime nextAttemptAt(LocalDateTime now, int attempts) {
        Objects.requireNonNull(now, "now");
        if (attempts <= 0) {
            throw new IllegalArgumentException("attempts must be positive");
        }
        Duration delay = switch (attempts) {
            case 1 -> Duration.ofSeconds(30);
            case 2 -> Duration.ofMinutes(2);
            case 3 -> Duration.ofMinutes(10);
            default -> Duration.ofMinutes(30);
        };
        return now.plus(delay);
    }
}
