package com.multind.bitpongo.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class WebhookReminderOutbox {
    private final JdbcTemplate jdbc;
    private final WebhookReminderService settings;

    WebhookReminderOutbox(JdbcTemplate jdbc, WebhookReminderService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    void enqueue(NotificationEvent event) {
        switch (event.type()) {
            case STRATEGY_SIGNAL -> insert(event, "Strategy signal");
            case TRADE_SUCCEEDED -> insert(event, "Trade succeeded");
            case TRADE_FAILED -> insert(event, "Trade failed");
            default -> { }
        }
    }

    void enqueuePrice(NotificationEvent event) {
        if (event.type() == NotificationEventType.PRICE_ALERT_TRIGGERED) insert(event, "Price alert triggered");
    }

    private void insert(NotificationEvent event, String title) {
        if (!settings.available() || event.userId() == null || event.dedupeKey() == null) return;
        jdbc.update("""
                INSERT INTO outbound_webhook_delivery (destination_id,event_key,message,next_attempt_at,updated_at)
                SELECT d.id,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
                FROM outbound_webhook_destination d JOIN user u ON u.id=d.user_id
                WHERE d.user_id=? AND d.enabled=true AND u.status='active'
                ORDER BY d.id
                ON DUPLICATE KEY UPDATE outbound_webhook_delivery.id=outbound_webhook_delivery.id
                """, hash(event.type().name() + ":" + event.dedupeKey()), message(event, title), event.userId());
    }

    private static String message(NotificationEvent event, String title) {
        // Never forward raw errors, custom user text, account details or arbitrary attributes.
        var symbols = new LinkedHashSet<String>();
        addSymbol(symbols, event.attributes().get("symbol"));
        if (event.attributes().get("symbols") instanceof List<?> list) {
            list.stream().limit(8).forEach(value -> addSymbol(symbols, value));
        }
        return "Bitpongo · " + title
                + (symbols.isEmpty() ? "" : "\n" + String.join(", ", symbols))
                + (event.planId() == null ? "" : "\nPlan #" + event.planId())
                + (event.occurredAt() == null ? "" : "\n" + event.occurredAt());
    }

    private static void addSymbol(LinkedHashSet<String> symbols, Object value) {
        if (value instanceof String symbol && symbol.matches("[A-Z0-9]{2,20}(/[A-Z0-9]{2,12})?")) symbols.add(symbol);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
