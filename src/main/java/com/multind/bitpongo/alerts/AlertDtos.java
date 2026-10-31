package com.multind.bitpongo.alerts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.annotation.JsonSerialize;

public final class AlertDtos {
    private AlertDtos() {}
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Create(String name, String symbol, String condition, BigDecimal target,
            BigDecimal lower, BigDecimal upper, String frequency, String interval, Instant expiresAt, List<String> channels) {
        public Create(String name, String symbol, String condition, BigDecimal target, BigDecimal lower, BigDecimal upper, String frequency, String interval, Instant expiresAt) {
            this(name, symbol, condition, target, lower, upper, frequency, interval, expiresAt, null);
        }
    }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Rule(long id, String name, String symbol, String condition,
            @JsonSerialize(using=DecimalStringSerializer.class) BigDecimal target,
            @JsonSerialize(using=DecimalStringSerializer.class) BigDecimal lower,
            @JsonSerialize(using=DecimalStringSerializer.class) BigDecimal upper, String frequency, String interval, Instant expiresAt,
            boolean enabled, long triggeredCount, Instant lastTriggeredAt, Instant createdAt, List<String> channels) {
        public Rule(long id, String name, String symbol, String condition, BigDecimal target, BigDecimal lower, BigDecimal upper, String frequency, String interval, Instant expiresAt, boolean enabled, long triggeredCount, Instant lastTriggeredAt, Instant createdAt) {
            this(id, name, symbol, condition, target, lower, upper, frequency, interval, expiresAt, enabled, triggeredCount, lastTriggeredAt, createdAt, null);
        }
    }
    public record Enabled(boolean enabled) {}
    public record Bulk(List<Long> ids, String action) {}
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Page<T>(List<T> items, long total, int page, int size, boolean hasMore) {}
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Event(long id, Long ruleId, String type, String title, String body, String symbol,
            @JsonSerialize(using=DecimalStringSerializer.class) BigDecimal price, Instant occurredAt, Instant readAt,
            Instant confirmedAt, boolean confirmationRequired) {
        public Event(long id, Long ruleId, String type, String title, String body, String symbol,
                BigDecimal price, Instant occurredAt, Instant readAt) {
            this(id, ruleId, type, title, body, symbol, price, occurredAt, readAt, null, false);
        }
    }
}
