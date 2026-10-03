package com.multind.bitpongo.alerts;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.market.MarketDataService;
import com.multind.bitpongo.market.MarketDtos;
import com.multind.bitpongo.notification.AlertNotificationBridge;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("!test")
public class PriceAlertService {
    private static final Set<String> CONDITIONS = Set.of("ABOVE", "BELOW", "CROSS", "CROSS_UP", "CROSS_DOWN", "CHANNEL_OUTSIDE");
    private static final Set<String> FREQUENCIES = Set.of("ONCE", "EVERY_TIME", "PER_MINUTE", "EACH_CANDLE", "CANDLE_CLOSE");
    private static final Set<String> INTERVALS = Set.of("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w");
    private final JdbcTemplate jdbc;
    private final MarketDataService market;
    private final AlertNotificationBridge notifications;
    public PriceAlertService(JdbcTemplate jdbc, MarketDataService market, AlertNotificationBridge notifications) {
        this.jdbc = jdbc; this.market = market; this.notifications = notifications;
    }

    @Transactional public long create(long userId, AlertDtos.Create request) {
        validate(request);
        String symbol = request.symbol().trim().toUpperCase(Locale.ROOT).replace("/", "");
        if (market.symbols(symbol).stream().noneMatch(s -> s.symbol().equals(symbol))) throw invalid("交易对不可用");
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into price_alert(user_id,name,symbol,condition_type,target,lower_price,upper_price,frequency,candle_interval,expires_at,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?)
                """, java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] values = { userId, request.name().trim(), symbol, request.condition(), request.target(), request.lower(),
                    request.upper(), request.frequency(), request.interval(), utc(request.expiresAt()), utc(Instant.now()) };
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public AlertDtos.Page<AlertDtos.Rule> list(long userId, String query, String status, int page, int size) {
        bounds(page, size);
        if (query == null || query.length() > 100 || status == null || !Set.of("all", "active", "inactive").contains(status)) throw invalid("筛选条件无效");
        String filter = "user_id=? and name like ?" + ("active".equals(status) ? " and enabled=true" : "inactive".equals(status) ? " and enabled=false" : "");
        Long total = jdbc.queryForObject("select count(*) from price_alert where " + filter, Long.class, userId, "%" + query + "%");
        var items = jdbc.query("select * from price_alert where " + filter + " order by id desc limit ? offset ?", PriceAlertService::rule,
                userId, "%" + query + "%", size, (long) page * size);
        return new AlertDtos.Page<>(items, total, page, size, ((long) page + 1) * size < total);
    }
    public AlertDtos.Rule detail(long userId, long id) {
        return jdbc.query("select * from price_alert where user_id=? and id=?", PriceAlertService::rule, userId, id).stream()
                .findFirst().orElseThrow(() -> new BusinessException(404, "提醒不存在"));
    }
    public void enabled(long userId, long id, boolean enabled) {
        var rule = detail(userId, id);
        if (enabled && rule.expiresAt() != null && !rule.expiresAt().isAfter(Instant.now())) throw invalid("提醒已过期");
        if (jdbc.update("update price_alert set enabled=?,last_price=null,last_sample_at=null,last_bucket=null where user_id=? and id=?", enabled, userId, id) == 0)
            throw new BusinessException(404, "提醒不存在");
    }
    public void delete(long userId, long id) {
        if (jdbc.update("delete from price_alert where user_id=? and id=?", userId, id) == 0) throw new BusinessException(404, "提醒不存在");
    }
    @Transactional public long copy(long userId, long id) {
        var r = detail(userId, id);
        return create(userId, new AlertDtos.Create(r.name(), r.symbol(), r.condition(), r.target(), r.lower(), r.upper(), r.frequency(), r.interval(), null));
    }
    @Transactional public void bulk(long userId, AlertDtos.Bulk body) {
        if (body == null || body.ids() == null || body.ids().isEmpty() || body.ids().size() > 100 || body.action() == null || !Set.of("enable", "disable", "delete").contains(body.action())) throw invalid("批量操作无效");
        for (Long id : body.ids().stream().distinct().toList()) {
            if (id == null || id < 1) throw invalid("提醒编号无效");
            if (body.action().equals("delete")) delete(userId, id); else enabled(userId, id, body.action().equals("enable"));
        }
    }

    public List<Map<String, Object>> activeRules() {
        return jdbc.queryForList("select p.id,p.user_id,p.symbol,p.frequency,p.candle_interval from price_alert p join user u on u.id=p.user_id where p.enabled=true and u.status='active' and (p.expires_at is null or p.expires_at>?) order by p.id", utc(Instant.now()));
    }

    @Transactional public void sample(long userId, long id, BigDecimal price, Instant sampleAt, long candleBucket) {
        var user = jdbc.queryForList("select id from user where id=? and status='active' for update", userId);
        if (user.isEmpty()) return;
        var rows = jdbc.queryForList("select * from price_alert where user_id=? and id=? and enabled=true for update", userId, id);
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        Instant expiry = instant(row.get("expires_at"));
        Instant lastSample = instant(row.get("last_sample_at"));
        Instant created = instant(row.get("created_at"));
        if (sampleAt == null || (created != null && sampleAt.isBefore(created)) || price == null || price.signum() <= 0
                || (expiry != null && (!sampleAt.isBefore(expiry) || !Instant.now().isBefore(expiry)))
                || (lastSample != null && !sampleAt.isAfter(lastSample))) return;
        boolean match = PriceAlertEvaluator.matches((String) row.get("condition_type"), (BigDecimal) row.get("last_price"), price,
                (BigDecimal) row.get("target"), (BigDecimal) row.get("lower_price"), (BigDecimal) row.get("upper_price"));
        String frequency = (String) row.get("frequency");
        long bucket = frequency.equals("PER_MINUTE") ? sampleAt.getEpochSecond() / 60 : candleBucket;
        Number lastBucket = (Number) row.get("last_bucket");
        if (!frequency.equals("ONCE") && !frequency.equals("EVERY_TIME") && lastBucket != null && lastBucket.longValue() == bucket) match = false;
        jdbc.update("update price_alert set last_price=?,last_sample_at=? where id=?", price, utc(sampleAt), id);
        if (!match) return;
        long count = ((Number) row.get("triggered_count")).longValue() + 1;
        String name = (String) row.get("name");
        String symbol = (String) row.get("symbol");
        String body = name + " · " + symbol + " · " + row.get("condition_type") + " · " + price.toPlainString();
        String eventKey = "price-alert:" + id + ":" + count;
        jdbc.update("insert into alert_event(user_id,rule_id,event_type,event_key,title,body,symbol,price,occurred_at) values(?,?,?,?,?,?,?,?,?)",
                userId, id, "PRICE_ALERT_TRIGGERED", eventKey, name, body, symbol, price, utc(sampleAt));
        notifications.priceAlert(userId, eventKey, symbol, body, sampleAt);
        jdbc.update("update price_alert set triggered_count=?,last_triggered_at=?,last_bucket=?,enabled=? where id=?",
                count, utc(sampleAt), bucket, !frequency.equals("ONCE"), id);
    }

    static void validate(AlertDtos.Create r) {
        if (r == null || r.name() == null || r.name().isBlank() || r.name().length() > 100 || r.symbol() == null
                || !r.symbol().trim().toUpperCase(Locale.ROOT).replace("/", "").matches("[A-Z0-9]{1,20}USDT")
                || !CONDITIONS.contains(r.condition() == null ? "" : r.condition()) || !FREQUENCIES.contains(r.frequency() == null ? "" : r.frequency())
                || !INTERVALS.contains(r.interval() == null ? "" : r.interval()) || (r.expiresAt() != null && !r.expiresAt().isAfter(Instant.now()))) throw invalid("提醒参数无效");
        if (r.condition().equals("CHANNEL_OUTSIDE")) {
            if (!price(r.lower()) || !price(r.upper()) || r.lower().compareTo(r.upper()) >= 0) throw invalid("价格区间无效");
        } else if (!price(r.target())) throw invalid("目标价格必须大于零");
    }
    private static boolean price(BigDecimal n) { return n != null && n.signum() > 0 && n.scale() <= 18 && n.precision() - n.scale() <= 20; }
    static void bounds(int page, int size) { if (page < 0 || page > 100000 || size < 1 || size > 100) throw invalid("分页参数无效"); }
    static BusinessException invalid(String message) { return new BusinessException(400, message); }
    static LocalDateTime utc(Instant instant) { return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dt) return dt.toInstant(ZoneOffset.UTC);
        return ((java.sql.Timestamp) value).toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
    private static Instant time(ResultSet rs, String field) throws SQLException { return instant(rs.getObject(field)); }
    private static AlertDtos.Rule rule(ResultSet rs, int index) throws SQLException {
        return new AlertDtos.Rule(rs.getLong("id"), rs.getString("name"), rs.getString("symbol"), rs.getString("condition_type"),
                rs.getBigDecimal("target"), rs.getBigDecimal("lower_price"), rs.getBigDecimal("upper_price"), rs.getString("frequency"),
                rs.getString("candle_interval"), time(rs,"expires_at"), rs.getBoolean("enabled"), rs.getLong("triggered_count"),
                time(rs,"last_triggered_at"), time(rs,"created_at"));
    }
}
