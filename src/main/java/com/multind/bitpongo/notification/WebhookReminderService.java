package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WebhookReminderService {
    private static final String TEST_MESSAGE = "Bitpongo: Webhook test notification";
    private static final String DESTINATION = """
            SELECT id, provider, name, enabled, url_ciphertext, secret_ciphertext
            FROM outbound_webhook_destination WHERE user_id=? AND id=?
            """;
    private static final RowMapper<Destination> DESTINATION_ROW = (rs, row) -> new Destination(
            WebhookReminderProvider.valueOf(rs.getString("provider")), rs.getString("name"),
            rs.getBoolean("enabled"), rs.getString("url_ciphertext"), rs.getString("secret_ciphertext"));

    record Draft(String provider, String name, String url, String signingSecret, Boolean enabled) {
        @Override public String toString() { return "WebhookReminderDraft[redacted]"; }
    }
    record Item(long id, WebhookReminderProvider provider, String name, boolean enabled, String maskedUrl) {}
    record Settings(boolean available, List<Item> items) {}
    private record Destination(WebhookReminderProvider provider, String name, boolean enabled,
                               String urlCiphertext, String secretCiphertext) {}

    private final JdbcTemplate jdbc;
    private final WebhookReminderClient client;
    private final BarkCredentialCipher cipher;

    WebhookReminderService(JdbcTemplate jdbc, WebhookReminderClient client,
                           @Value("${zhitoubao.notifications.outbound-webhook.enabled:false}") boolean enabled,
                           @Value("${zhitoubao.notifications.outbound-webhook.encryption-key:}") String key) {
        this.jdbc = jdbc;
        this.client = client;
        try {
            this.cipher = enabled ? new BarkCredentialCipher(key) : null;
        } catch (IllegalStateException invalid) {
            throw new IllegalStateException("OUTBOUND_WEBHOOK_ENCRYPTION_KEY must be 32-byte Base64");
        }
    }

    boolean available() { return cipher != null; }

    Settings list(long userId) {
        return new Settings(available(), jdbc.query("""
                SELECT id, provider, name, enabled FROM outbound_webhook_destination WHERE user_id=? ORDER BY id
                """, (rs, row) -> {
            var provider = WebhookReminderProvider.valueOf(rs.getString("provider"));
            return new Item(rs.getLong("id"), provider, rs.getString("name"), rs.getBoolean("enabled"), provider.maskedUrl());
        }, userId));
    }

    long create(long userId, Draft draft) {
        requireAvailable();
        var provider = provider(draft.provider());
        String name = name(draft.name());
        String address = encryptUrl(provider, draft.url());
        String secret = encryptSecret(provider, draft.signingSecret());
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO outbound_webhook_destination (user_id, provider, name, url_ciphertext, secret_ciphertext)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, provider.name());
            statement.setString(3, name);
            statement.setString(4, address);
            statement.setString(5, secret);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    @Transactional
    public void update(long userId, long id, Draft draft) {
        requireAvailable();
        Destination old = find(userId, id, true);
        if (draft.provider() != null && provider(draft.provider()) != old.provider()) {
            throw new BusinessException(400, "Create a new destination to change the webhook provider");
        }
        jdbc.update("""
                UPDATE outbound_webhook_destination SET name=?, enabled=?, url_ciphertext=?, secret_ciphertext=?
                WHERE user_id=? AND id=?
                """, draft.name() == null ? old.name() : name(draft.name()),
                draft.enabled() == null ? old.enabled() : draft.enabled(),
                draft.url() == null ? old.urlCiphertext() : encryptUrl(old.provider(), draft.url()),
                draft.signingSecret() == null ? old.secretCiphertext() : encryptSecret(old.provider(), draft.signingSecret()),
                userId, id);
    }

    void delete(long userId, long id) {
        if (jdbc.update("DELETE FROM outbound_webhook_destination WHERE user_id=? AND id=?", userId, id) != 1) {
            throw notFound();
        }
    }

    void test(long userId, long id) {
        requireAvailable();
        Destination destination = find(userId, id, false);
        // Autocommit: a failed remote send must not roll back the consumed rate-limit slot.
        jdbc.update("""
                INSERT IGNORE INTO outbound_webhook_test_limit (user_id, next_allowed_at)
                VALUES (?, '1970-01-01 00:00:00')
                """, userId);
        if (jdbc.update("""
                UPDATE outbound_webhook_test_limit SET next_allowed_at=UTC_TIMESTAMP(6) + INTERVAL 10 SECOND
                WHERE user_id=? AND next_allowed_at<=UTC_TIMESTAMP(6)
                """, userId) != 1) {
            throw new BusinessException(429, "Wait before sending another webhook test");
        }
        if (send(destination, TEST_MESSAGE) != WebhookReminderClient.Result.SENT) {
            throw new BusinessException(502, "Webhook test notification could not be delivered");
        }
    }

    private WebhookReminderClient.Result send(Destination destination, String message) {
        final String url;
        final String secret;
        try {
            url = cipher.decrypt(destination.urlCiphertext());
            secret = destination.secretCiphertext() == null ? null : cipher.decrypt(destination.secretCiphertext());
        } catch (BusinessException invalid) {
            return WebhookReminderClient.Result.REJECTED;
        }
        return client.send(destination.provider(), url, secret, message);
    }

    private Destination find(long userId, long id, boolean lock) {
        return jdbc.query(lock ? DESTINATION + " FOR UPDATE" : DESTINATION, DESTINATION_ROW, userId, id)
                .stream().findFirst().orElseThrow(WebhookReminderService::notFound);
    }

    private void requireAvailable() {
        if (!available()) throw new BusinessException(503, "Outbound webhook reminders are unavailable");
    }

    private String encryptUrl(WebhookReminderProvider provider, String raw) {
        try {
            return cipher.encrypt(provider.validate(raw == null ? null : raw.strip()).toString());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(400, "Invalid webhook URL for the selected provider");
        }
    }

    private String encryptSecret(WebhookReminderProvider provider, String raw) {
        if (raw == null || raw.isBlank()) return null;
        if ((provider != WebhookReminderProvider.DINGTALK && provider != WebhookReminderProvider.FEISHU)
                || raw.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new BusinessException(400, "Invalid webhook signing secret for the selected provider");
        }
        return cipher.encrypt(raw.strip());
    }

    private static WebhookReminderProvider provider(String value) {
        if (value != null) {
            try {
                return WebhookReminderProvider.valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Report only a generic validation error.
            }
        }
        throw new BusinessException(400, "Select a supported webhook provider");
    }

    private static String name(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 80) {
            throw new BusinessException(400, "Webhook name must contain 1–80 characters");
        }
        return value.strip();
    }

    private static BusinessException notFound() { return new BusinessException(404, "Webhook destination not found"); }
}
