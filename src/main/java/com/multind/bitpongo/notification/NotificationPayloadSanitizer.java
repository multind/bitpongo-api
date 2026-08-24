package com.multind.bitpongo.notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class NotificationPayloadSanitizer {

    private static final Set<String> ERROR_FIELDS = Set.of(
            "status", "resultStatus", "error", "errorSummary");
    private static final Set<String> TRADE_FIELDS = Set.of(
            "symbol", "status", "resultStatus", "error", "errorSummary");
    private static final Set<String> SUCCESS_FIELDS = Set.of(
            "symbol", "status", "resultStatus");
    private static final Set<String> RECOVERY_FIELDS = Set.of(
            "status", "resultStatus");
    private static final Set<NotificationEventType> RECOVERABLE_FAULT_TYPES = Set.of(
            NotificationEventType.SCHEDULER_FATAL, NotificationEventType.MARKET_OUTAGE);

    Map<String, Object> sanitize(NotificationEvent event) {
        Objects.requireNonNull(event, "event");
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfNotNull(payload, "userId", event.userId());
        putIfNotNull(payload, "planId", event.planId());
        putIfNotNull(payload, "intentId", event.intentId());
        putIfNotNull(payload, "occurredAt",
                event.occurredAt() == null ? null : event.occurredAt().toString());
        payload.put("attributes", sanitizedAttributes(event));
        return Collections.unmodifiableMap(payload);
    }

    private static Map<String, Object> sanitizedAttributes(NotificationEvent event) {
        Map<String, Object> source = event.attributes() == null
                ? Map.of()
                : event.attributes();
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (String field : allowedFields(event.type())) {
            Object value = source.get(field);
            if (value != null) {
                sanitized.put(field, NotificationMessageRenderer.sanitizeError(
                        String.valueOf(value)));
            }
        }
        if (event.type() == NotificationEventType.SYSTEM_RECOVERED) {
            NotificationEventType originalType = recoverableFaultType(
                    source.get("originalEventType"));
            if (originalType != null) {
                sanitized.put("originalEventType", originalType.name());
            }
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private static NotificationEventType recoverableFaultType(Object value) {
        NotificationEventType type;
        if (value instanceof NotificationEventType eventType) {
            type = eventType;
        } else if (value instanceof String name) {
            try {
                type = NotificationEventType.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        } else {
            return null;
        }
        return RECOVERABLE_FAULT_TYPES.contains(type) ? type : null;
    }

    private static Set<String> allowedFields(NotificationEventType type) {
        return switch (type) {
            case SCHEDULER_FATAL, MARKET_OUTAGE, PLAN_EXECUTION_SKIPPED,
                    ASSET_SNAPSHOT_FAILED -> ERROR_FIELDS;
            case ORDER_MANUAL_REVIEW, TRADE_FAILED -> TRADE_FIELDS;
            case TRADE_SUCCEEDED -> SUCCESS_FIELDS;
            case SYSTEM_RECOVERED -> RECOVERY_FIELDS;
            case SERVICE_STARTED, BARK_TEST -> Set.of();
        };
    }

    private static void putIfNotNull(Map<String, Object> payload, String name, Object value) {
        if (value != null) {
            payload.put(name, value);
        }
    }
}
