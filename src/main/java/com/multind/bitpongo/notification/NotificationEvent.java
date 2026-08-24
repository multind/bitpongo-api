package com.multind.bitpongo.notification;

import java.time.Duration;
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
        if (dedupeWindow != null) {
            String requiredScopePrefix;
            Duration requiredDuration;
            if (type == NotificationEventType.SCHEDULER_FATAL) {
                requiredScopePrefix = "scheduler-fatal:";
                requiredDuration = Duration.ofMinutes(10);
            } else if (type == NotificationEventType.ASSET_SNAPSHOT_FAILED) {
                requiredScopePrefix = "asset-snapshot-failed:";
                requiredDuration = Duration.ofMinutes(30);
            } else {
                throw new IllegalArgumentException(
                        "dedupe window is not valid for event type " + type);
            }
            if (!dedupeWindow.scopeKey().startsWith(requiredScopePrefix)
                    || !dedupeWindow.duration().equals(requiredDuration)) {
                throw new IllegalArgumentException(
                        "dedupe window does not match event type " + type);
            }
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
