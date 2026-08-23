package com.multind.bitpongo.notification;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!test")
@Service
public class NotificationOutboxLeaseService {

    static final int BATCH_SIZE = 50;
    static final int LEASE_SECONDS = 30;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public NotificationOutboxLeaseService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    NotificationOutboxLeaseService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Long> leaseDue() {
        return leaseAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Long> leaseDue(LocalDateTime now) {
        return leaseAt(Objects.requireNonNull(now, "now"));
    }

    private List<Long> leaseAt(LocalDateTime now) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM notification_outbox
                WHERE ((status = 'PENDING' AND next_attempt_at <= ?)
                    OR (status = 'SENDING' AND lease_until < ?))
                ORDER BY priority, created_at
                LIMIT 50 FOR UPDATE SKIP LOCKED
                """, Long.class, now, now);
        if (ids.isEmpty()) {
            return List.of();
        }

        List<Object> parameters = new ArrayList<>(ids.size() + 2);
        parameters.add(now.plusSeconds(LEASE_SECONDS));
        parameters.add(now);
        parameters.addAll(ids);
        jdbc.update("""
                UPDATE notification_outbox
                   SET status = 'SENDING', lease_until = ?, updated_at = ?
                 WHERE id IN (%s)
                """.formatted(placeholders(ids.size())), parameters.toArray());
        return List.copyOf(ids);
    }

    private static String placeholders(int size) {
        return String.join(", ", java.util.Collections.nCopies(size, "?"));
    }
}
