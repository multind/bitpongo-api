package com.multind.bitpongo.notification;

final class NativePushPolicy {
    private NativePushPolicy() {}
    static String category(NotificationEventType type) {
        return switch (type) {
            case TRADE_SUCCEEDED -> "tradeSucceeded";
            case TRADE_FAILED, ORDER_MANUAL_REVIEW -> "tradeFailed";
            case SCHEDULER_FATAL, PLAN_EXECUTION_SKIPPED, PLAN_EXECUTION_DELAYED -> "planAlerts";
            default -> "systemAlerts";
        };
    }
    static String urgency(NotificationEventType type) {
        return switch (type) {
            case TRADE_FAILED, SCHEDULER_FATAL, ORDER_MANUAL_REVIEW, MARKET_OUTAGE,
                    PLAN_EXECUTION_SKIPPED, PLAN_EXECUTION_DELAYED -> "time-sensitive";
            case SYSTEM_RECOVERED, SERVICE_STARTED -> "passive";
            default -> "active";
        };
    }
    static String channel(NotificationEventType type, boolean sound) {
        String channel = switch (category(type)) {
            case "tradeSucceeded", "tradeFailed" -> "bitpongo_trades";
            case "planAlerts" -> "bitpongo_alerts";
            default -> "bitpongo_system";
        };
        return channel + (sound && !urgency(type).equals("passive") ? "" : "_silent");
    }
}
