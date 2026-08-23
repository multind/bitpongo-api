package com.multind.bitpongo.notification;

import java.time.Instant;
import java.util.Map;

public record NotificationEvent(
        NotificationEventType type,
        Long userId,
        Long planId,
        Long intentId,
        Instant occurredAt,
        String dedupeKey,
        Map<String, Object> attributes) {
}
