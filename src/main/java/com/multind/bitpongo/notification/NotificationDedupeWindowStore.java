package com.multind.bitpongo.notification;

import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Profile("!test")
@Component
final class NotificationDedupeWindowStore {
    private final JdbcTemplate jdbc;

    NotificationDedupeWindowStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    boolean tryAcquire(String recipientScope, LocalDateTime now, LocalDateTime expiresAt) {
        int renewed = jdbc.update("""
                update notification_dedupe_window
                   set expires_at = ?, updated_at = ?
                 where scope_key = ? and expires_at <= ?
                """, expiresAt, now, recipientScope, now);
        if (renewed == 1) {
            return true;
        }
        try {
            return jdbc.update("""
                    insert into notification_dedupe_window (
                        scope_key, expires_at, updated_at
                    ) values (?, ?, ?)
                    """, recipientScope, expiresAt, now) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
