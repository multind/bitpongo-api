package com.multind.bitpongo.alerts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AlertDtos {
    private AlertDtos() {}
    public record Create(String name, String symbol, String condition, BigDecimal target,
            BigDecimal lower, BigDecimal upper, String frequency, String interval, Instant expiresAt) {}
    public record Rule(long id, String name, String symbol, String condition, BigDecimal target,
            BigDecimal lower, BigDecimal upper, String frequency, String interval, Instant expiresAt,
            boolean enabled, long triggeredCount, Instant lastTriggeredAt, Instant createdAt) {}
    public record Enabled(boolean enabled) {}
    public record Bulk(List<Long> ids, String action) {}
    public record Page<T>(List<T> items, long total, int page, int size, boolean hasMore) {}
    public record Event(long id, Long ruleId, String type, String title, String body, String symbol,
            BigDecimal price, Instant occurredAt, Instant readAt) {}
}
