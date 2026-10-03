package com.multind.bitpongo.alerts;

import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service @Profile("!test")
public class AlertEventService {
    private final JdbcTemplate jdbc;
    public AlertEventService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public AlertDtos.Page<AlertDtos.Event> list(long userId, boolean unreadOnly, int page, int size) {
        return page(userId, unreadOnly, page, size, false);
    }
    public AlertDtos.Page<AlertDtos.Event> logs(long userId, int page, int size) {
        return page(userId, false, page, size, true);
    }
    private AlertDtos.Page<AlertDtos.Event> page(long userId, boolean unreadOnly, int page, int size, boolean logs) {
        PriceAlertService.bounds(page, size);
        String filter = "user_id=? and " + (logs ? "log_deleted" : "inbox_deleted") + "=false" + (unreadOnly ? " and read_at is null" : "");
        Long total = jdbc.queryForObject("select count(*) from alert_event where " + filter, Long.class, userId);
        var items = jdbc.query("select * from alert_event where " + filter + " order by id desc limit ? offset ?", (rs, i) -> {
            long rule = rs.getLong("rule_id");
            Long ruleId = rs.wasNull() ? null : rule;
            return new AlertDtos.Event(rs.getLong("id"), ruleId, rs.getString("event_type"), rs.getString("title"), rs.getString("body"),
                    rs.getString("symbol"), rs.getBigDecimal("price"), PriceAlertService.instant(rs.getObject("occurred_at")), PriceAlertService.instant(rs.getObject("read_at")));
        }, userId, size, (long) page * size);
        return new AlertDtos.Page<>(items, total, page, size, ((long) page + 1) * size < total);
    }
    public long unread(long userId) { return jdbc.queryForObject("select count(*) from alert_event where user_id=? and inbox_deleted=false and read_at is null", Long.class, userId); }
    public void read(long userId, long id) { jdbc.update("update alert_event set read_at=coalesce(read_at,?) where user_id=? and id=? and inbox_deleted=false", PriceAlertService.utc(Instant.now()), userId, id); }
    public void readAll(long userId) { jdbc.update("update alert_event set read_at=? where user_id=? and inbox_deleted=false and read_at is null", PriceAlertService.utc(Instant.now()), userId); }
    public void delete(long userId, long id) { jdbc.update("update alert_event set inbox_deleted=true where user_id=? and id=?", userId, id); }
    public void clear(long userId) { jdbc.update("update alert_event set inbox_deleted=true where user_id=?", userId); }
    public void deleteLog(long userId, long id) { jdbc.update("update alert_event set log_deleted=true where user_id=? and id=?", userId, id); }
    public void clearLogs(long userId) { jdbc.update("update alert_event set log_deleted=true where user_id=?", userId); }
}
