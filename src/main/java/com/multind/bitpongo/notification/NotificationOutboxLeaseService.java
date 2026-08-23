package com.multind.bitpongo.notification;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
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
    public List<Lease> leaseDue() {
        return leaseAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Lease> leaseDue(LocalDateTime now) {
        return leaseAt(Objects.requireNonNull(now, "now"));
    }

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRES_NEW)
    public boolean renew(Lease lease, LocalDateTime now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(now, "now");
        return jdbc.update("""
                update notification_outbox
                   set lease_until = ?, updated_at = ?
                 where id = ? and lease_token = ? and status = 'SENDING'
                   and lease_until >= ?
                """, now.plusSeconds(LEASE_SECONDS), now,
                lease.id(), lease.token(), now) == 1;
    }

    private List<Lease> leaseAt(LocalDateTime now) {
        List<Long> ids = jdbc.queryForList("""
                select id from notification_outbox
                where ((status = 'PENDING' and next_attempt_at <= ?)
                    or (status = 'SENDING' and lease_until < ?))
                order by priority, created_at, id
                limit ? for update skip locked
                """, Long.class, now, now, BATCH_SIZE);
        if (ids.isEmpty()) {
            return List.of();
        }

        String token = UUID.randomUUID().toString();
        List<Object> parameters = new ArrayList<>(ids.size() + 3);
        parameters.add(now.plusSeconds(LEASE_SECONDS));
        parameters.add(token);
        parameters.add(now);
        parameters.addAll(ids);
        jdbc.update("""
                update notification_outbox
                   set status = 'SENDING', lease_until = ?, lease_token = ?, updated_at = ?
                 where id in (%s)
                """.formatted(placeholders(ids.size())), parameters.toArray());
        return ids.stream().map(id -> new Lease(id, token)).toList();
    }

    private static String placeholders(int size) {
        return String.join(", ", java.util.Collections.nCopies(size, "?"));
    }

    public record Lease(long id, String token) {

        public Lease {
            if (id <= 0) {
                throw new IllegalArgumentException("lease id must be positive");
            }
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("lease token must not be blank");
            }
        }
    }
}
