package com.multind.bitpongo.notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable wave cursor; the dispatch callback must only write transactional outbox rows. */
@Service
@Profile("!test")
class NotificationPerceptionQueue {
    private final JdbcTemplate jdbc;

    NotificationPerceptionQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Wave(long eventId, long userId, String eventKey, String eventType, String title, String body,
                String symbol, int stage, List<String> channels) {}
    private record Inbox(long userId, String eventKey, String eventType, String title, String body,
                         String symbol, boolean closed) {}

    @Transactional
    public void schedule(long owner, long eventId, List<List<String>> tiers, boolean confirmation, LocalDateTime now) {
        if (tiers.isEmpty()) return;
        var owned = jdbc.queryForList("SELECT id FROM alert_event WHERE id=? AND user_id=? FOR UPDATE", Long.class, eventId, owner);
        if (owned.isEmpty() || !jdbc.queryForList(
                "SELECT stage FROM notification_perception_wave WHERE event_id=? AND stage=0 FOR UPDATE", Integer.class, eventId).isEmpty()) {
            return;
        }
        if (!confirmation && tiers.size() != 1) {
            throw new IllegalArgumentException("Immediate delivery must use a single wave");
        }
        for (int stage = 0; stage < tiers.size(); stage++) {
            jdbc.update("""
                    INSERT INTO notification_perception_wave(event_id,stage,channel_codes,state,due_at)
                    VALUES (?,?,?,?,?)
                    """, eventId, stage, String.join(",", tiers.get(stage)),
                    stage == 0 ? "READY" : "WAITING", stage == 0 ? now : null);
        }
    }

    @Transactional
    public boolean advance(LocalDateTime now, Consumer<Wave> dispatch) {
        var candidates = jdbc.queryForList("""
                SELECT event_id FROM notification_perception_wave
                WHERE state='READY' AND due_at<=? ORDER BY due_at,event_id LIMIT 16
                """, Long.class, now);
        for (long eventId : candidates) {
            // Same lock as confirmation/delete. A concurrent confirmation either wins before this wave,
            // or happens after this wave's durable dispatch; it still cancels later waves.
            var inbox = jdbc.query("""
                    SELECT user_id,event_key,event_type,title,body,symbol,confirmed_at,inbox_deleted
                    FROM alert_event WHERE id=? FOR UPDATE SKIP LOCKED
                    """, (rs, i) -> new Inbox(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getObject(7) != null || rs.getBoolean(8)), eventId);
            if (inbox.isEmpty()) continue;
            Inbox event = inbox.getFirst();
            var waves = jdbc.query("""
                    SELECT stage,channel_codes FROM notification_perception_wave
                    WHERE event_id=? AND state='READY' AND due_at<=? FOR UPDATE
                    """, (rs, i) -> new Wave(eventId, event.userId(), event.eventKey(), event.eventType(),
                    event.title(), event.body(), event.symbol(), rs.getInt(1), List.of(rs.getString(2).split(","))), eventId, now);
            if (waves.isEmpty()) continue;
            Wave wave = waves.getFirst();
            boolean active = !jdbc.queryForList("SELECT id FROM user WHERE id=? AND status='active'", Long.class, event.userId()).isEmpty();
            boolean confirmationOn = Boolean.TRUE.equals(jdbc.queryForList(
                    "SELECT confirmation_enabled FROM user_notification_channel WHERE user_id=?", Boolean.class, event.userId())
                    .stream().findFirst().orElse(false));
            if (!active || event.closed() || (wave.stage() > 0 && !confirmationOn)) {
                jdbc.update("""
                        UPDATE notification_perception_wave SET state='CANCELLED',due_at=NULL
                        WHERE event_id=? AND state IN ('READY','WAITING')
                        """, eventId);
                return true;
            }
            dispatch.accept(wave);
            jdbc.update("UPDATE notification_perception_wave SET state='DISPATCHED',due_at=NULL WHERE event_id=? AND stage=?",
                    eventId, wave.stage());
            jdbc.update("""
                    UPDATE notification_perception_wave SET state='READY',due_at=?
                    WHERE event_id=? AND stage=? AND state='WAITING'
                    """, now.plusMinutes(5), eventId, wave.stage() + 1);
            return true;
        }
        return false;
    }
}
