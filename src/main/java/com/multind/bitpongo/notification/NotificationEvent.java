package com.multind.bitpongo.notification;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record NotificationEvent(
        NotificationEventType type,
        Long userId,
        Long planId,
        Long intentId,
        Instant occurredAt,
        String dedupeKey,
        Map<String, Object> attributes,
        NotificationAudienceContext audienceContext,
        NotificationDedupeWindow dedupeWindow) {

    public NotificationEvent {
        Objects.requireNonNull(type, "type");
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        if (audienceContext != null && type != NotificationEventType.SYSTEM_RECOVERED) {
            throw new IllegalArgumentException(
                    "audience context is only valid for SYSTEM_RECOVERED");
        }
    }

    public NotificationEvent(
            NotificationEventType type,
            Long userId,
            Long planId,
            Long intentId,
            Instant occurredAt,
            String dedupeKey,
            Map<String, Object> attributes,
            NotificationAudienceContext audienceContext) {
        this(type, userId, planId, intentId, occurredAt, dedupeKey, attributes,
                audienceContext, null);
    }

    public NotificationEvent(
            NotificationEventType type,
            Long userId,
            Long planId,
            Long intentId,
            Instant occurredAt,
            String dedupeKey,
            Map<String, Object> attributes) {
        this(type, userId, planId, intentId, occurredAt, dedupeKey, attributes,
                null, null);
    }
}
