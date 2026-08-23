package com.multind.bitpongo.notification;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class BarkEventPolicy {

    public Policy policy(NotificationEvent event) {
        Objects.requireNonNull(event, "event");
        Policy base = policy(event.type());
        if (event.type() != NotificationEventType.SYSTEM_RECOVERED
                || event.attributes() == null) {
            return base;
        }

        Object originalValue = event.attributes().get("originalEventType");
        NotificationEventType originalType = originalEventType(originalValue);
        if (originalType == null || originalType == NotificationEventType.SYSTEM_RECOVERED) {
            return base;
        }
        return new Policy(base.level(), base.volume(), base.call(), base.sound(),
                policy(originalType).group());
    }

    public Policy policy(NotificationEventType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case SCHEDULER_FATAL, ORDER_MANUAL_REVIEW ->
                    new Policy("critical", 10, true, "alarm", "Bitpongo·紧急");
            case TRADE_FAILED ->
                    new Policy("timeSensitive", null, false, "alarm", "Bitpongo·交易异常");
            case MARKET_OUTAGE ->
                    new Policy("timeSensitive", null, false, "alarm", "Bitpongo·行情");
            case PLAN_EXECUTION_SKIPPED ->
                    new Policy("timeSensitive", null, false, null, "Bitpongo·计划");
            case TRADE_SUCCEEDED ->
                    new Policy("active", null, false, "minuet", "Bitpongo·交易");
            case ASSET_SNAPSHOT_FAILED ->
                    new Policy("active", null, false, null, "Bitpongo·资产");
            case SYSTEM_RECOVERED ->
                    new Policy("passive", null, false, null, null);
            case SERVICE_STARTED ->
                    new Policy("passive", null, false, null, "Bitpongo·系统");
            case BARK_TEST ->
                    new Policy("active", null, false, "minuet", "Bitpongo·测试");
        };
    }

    public record Policy(
            String level,
            Integer volume,
            boolean call,
            String sound,
            String group) {
    }

    private static NotificationEventType originalEventType(Object value) {
        if (value instanceof NotificationEventType type) {
            return type;
        }
        if (value instanceof String name) {
            try {
                return NotificationEventType.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
