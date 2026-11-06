package com.multind.bitpongo.preset;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.market.PriceCache;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class PresetService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Set<String> SORTS = Set.of("comprehensive", "return", "followers");
    private static final Set<String> OWNERS = Set.of("all", "official", "user");
    private static final Set<String> EXCHANGES = Set.of("all", "binance", "okx");
    private static final Set<String> RUNTIMES = Set.of("all", "1-50", "51-100", "100+");
    private static final Set<String> TYPES = Set.of("all", "dca", "fng");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PriceCache prices;

    public PresetService(JdbcTemplate jdbc, ObjectProvider<Clock> clock, PriceCache prices) {
        this.jdbc = jdbc;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.prices = prices;
    }

    @Transactional(readOnly = true)
    public PresetDtos.Page list(
            long userId,
            String query,
            String sort,
            String owner,
            String exchange,
            String runtime,
            String type,
            int page,
            int size) {
        activeUser(userId);
        String search = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (search.length() > 100) throw invalid("搜索内容过长");
        String effectiveSort = valueOr(sort, "comprehensive", SORTS);
        String effectiveOwner = valueOr(owner, "all", OWNERS);
        String effectiveExchange = valueOr(exchange, "all", EXCHANGES);
        String effectiveRuntime = valueOr(runtime, "all", RUNTIMES);
        String effectiveType = valueOr(type, "all", TYPES);
        if (page < 0 || page > 100000 || size < 1 || size > 50) throw invalid("分页参数无效");

        List<PresetDtos.Preset> items = all(userId).stream()
                .filter(item -> matchesOwner(item, effectiveOwner))
                .filter(item -> matchesType(item, effectiveType))
                .filter(item -> matchesExchange(item, effectiveExchange))
                .filter(item -> matchesRuntime(item, effectiveRuntime))
                .filter(item -> matchesQuery(item, search))
                .sorted(comparator(effectiveSort))
                .toList();
        long total = items.size();
        int from = Math.min(page * size, items.size());
        int to = Math.min(from + size, items.size());
        return new PresetDtos.Page(items.subList(from, to), page, size, total, to < total);
    }

    @Transactional(readOnly = true)
    public PresetDtos.Preset detail(long userId, String key) {
        activeUser(userId);
        return all(userId).stream()
                .filter(item -> item.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "策略不存在"));
    }

    @Transactional
    public PresetDtos.Favorite favorite(long userId, String key) {
        activeUser(userId);
        requirePreset(key);
        int deleted = jdbc.update("delete from preset_favorite where user_id=? and preset_key=?", userId, key);
        if (deleted == 0) {
            jdbc.update("insert into preset_favorite(user_id,preset_key,created_at) values(?,?,?)",
                    userId, key, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        }
        long followers = jdbc.queryForObject(
                "select count(*) from preset_favorite where preset_key=?", Long.class, key);
        return new PresetDtos.Favorite(deleted == 0, followers);
    }

    private List<PresetDtos.Preset> all(long userId) {
        Set<String> favorites = new HashSet<>(jdbc.queryForList(
                "select preset_key from preset_favorite where user_id=?", String.class, userId));
        Map<String, Long> followers = new HashMap<>();
        jdbc.query("select preset_key,count(*) total from preset_favorite group by preset_key", rs -> {
            followers.put(rs.getString("preset_key"), rs.getLong("total"));
        });
        Map<String, Mark> marks = marks();
        List<PresetDtos.Preset> items = new ArrayList<>();
        addDca(favorites, followers, marks, items);
        addFng(favorites, followers, marks, items);
        return items;
    }

    private void addDca(
            Set<String> favorites,
            Map<String, Long> followers,
            Map<String, Mark> marks,
            List<PresetDtos.Preset> items) {
        jdbc.query("""
                select p.id as plan_id,p.total_funds,p.created_at,
                       s.name as strategy_name,s.instalment,s.frequency,s.cron,s.condition,s.schedule_timezone,
                       e.exchange,u.name as user_name,u.email as user_email,ua.image as author_avatar
                from plan p
                join strategy s on s.id=p.strategy_id
                join exchange e on e.id=p.exchange_id
                join user u on u.id=p.user_id
                left join user_avatar ua on ua.user_id=u.id
                left join strategy_visibility v on v.strategy_type='DCA' and v.strategy_id=p.id
                left join strategy_mark m on m.strategy_type='DCA' and m.strategy_id=p.id
                where p.status='active' and u.status='active'
                  and coalesce(v.is_public,m.official,false)=true
                order by p.id desc
                """, rs -> {
            long planId = rs.getLong("plan_id");
            String key = "dca:" + planId;
            String strategyName = rs.getString("strategy_name");
            String exchange = rs.getString("exchange");
            List<Map<String, Object>> coins = coins(planId);
            List<String> symbols = coins.stream().map(coin -> String.valueOf(coin.get("symbol"))).toList();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", strategyName);
            params.put("instalment", rs.getObject("instalment"));
            params.put("frequency", rs.getString("frequency"));
            params.put("cron", rs.getString("cron"));
            params.put("condition", rs.getString("condition"));
            params.put("scheduleTimezone", rs.getString("schedule_timezone"));
            params.put("coins", coins);
            Mark mark = marks.getOrDefault("DCA:" + planId, Mark.NONE);
            var positions = jdbc.query("select symbol,total_amount from coin where plan_id=?", (row, index) -> new DcaPerformance.Position(row.getString("symbol"), row.getBigDecimal("total_amount")), planId);
            var result = DcaPerformance.calculate(prices, exchange, positions, rs.getBigDecimal("total_funds"), clock.instant());
            items.add(new PresetDtos.Preset(
                    key, "DCA",
                    strategyName == null || strategyName.isBlank() ? "DCA Strategy" : strategyName,
                    author(rs.getString("user_name"), rs.getString("user_email")),
                    avatar(rs.getBytes("author_avatar")),
                    mark.official(), mark.recommended(),
                    followers.getOrDefault(key, 0L), favorites.contains(key),
                    result == null ? null : decimal(result.revenue()), result == null ? null : decimal(result.ratio()),
                    exchange, symbols, runtimeDays(rs.getObject("created_at")), sparkline(planId), params));
        });
    }

    private void addFng(
            Set<String> favorites,
            Map<String, Long> followers,
            Map<String, Mark> marks,
            List<PresetDtos.Preset> items) {
        Map<Long, FngPerformance> performance = fngPerformance();
        jdbc.query("""
                select f.id,f.name,f.symbol,f.exchange,f.upper_limit_value,f.upper_limit_interval,
                       f.lower_limit_value,f.lower_limit_interval,f.basic_amount,f.increase_ratio,
                       f.leverage,f.bureau_period,f.direction,f.profit_target_percent,
                       f.profit_drawdown_percent,f.stop_loss_percent,f.profit_target_type,f.max_orders,f.max_positions,
                       f.created_at,u.name as user_name,u.email as user_email,ua.image as author_avatar
                from fng_strategy f
                join user u on u.id=f.user_id
                left join user_avatar ua on ua.user_id=u.id
                left join strategy_visibility v on v.strategy_type='FNG' and v.strategy_id=f.id
                left join strategy_mark m on m.strategy_type='FNG' and m.strategy_id=f.id
                where f.enabled=true and f.archived_at is null and u.status='active'
                  and coalesce(v.is_public,m.official,false)=true
                order by f.id desc
                """, rs -> {
            long strategyId = rs.getLong("id");
            String key = "fng:" + strategyId;
            String symbol = rs.getString("symbol");
            String base = symbol != null && symbol.endsWith("USDT")
                    ? symbol.substring(0, symbol.length() - 4)
                    : symbol;
            Mark mark = marks.getOrDefault("FNG:" + strategyId, Mark.NONE);
            FngPerformance summary = performance.getOrDefault(strategyId, FngPerformance.NONE);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", rs.getString("name"));
            params.put("symbol", symbol);
            params.put("upperLimitValue", rs.getInt("upper_limit_value"));
            params.put("upperLimitInterval", rs.getInt("upper_limit_interval"));
            params.put("lowerLimitValue", rs.getInt("lower_limit_value"));
            params.put("lowerLimitInterval", rs.getInt("lower_limit_interval"));
            params.put("basicAmount", decimal(rs.getObject("basic_amount")));
            params.put("increaseRatio", decimal(rs.getObject("increase_ratio")));
            params.put("leverage", rs.getInt("leverage"));
            params.put("bureauPeriod", rs.getInt("bureau_period"));
            params.put("direction", rs.getString("direction"));
            params.put("profitTargetPercent", decimal(rs.getObject("profit_target_percent")));
            params.put("profitDrawdownPercent", decimal(rs.getObject("profit_drawdown_percent")));
            params.put("stopLossPercent", rs.getObject("stop_loss_percent") == null
                    ? null : decimal(rs.getObject("stop_loss_percent")));
            params.put("profitTargetType", rs.getString("profit_target_type"));
            params.put("maxOrders", rs.getInt("max_orders"));
            params.put("maxPositions", rs.getInt("max_positions"));
            items.add(new PresetDtos.Preset(
                    key, "FNG", rs.getString("name"),
                    author(rs.getString("user_name"), rs.getString("user_email")),
                    avatar(rs.getBytes("author_avatar")),
                    mark.official(), mark.recommended(),
                    followers.getOrDefault(key, 0L), favorites.contains(key),
                    decimal(summary.pnl()), decimal(summary.ratio()),
                    rs.getString("exchange"), symbol == null ? List.of() : List.of(base),
                    runtimeDays(rs.getObject("created_at")), fngSparkline(strategyId), params));
        });
    }

    @Transactional(readOnly = true)
    public PresetDtos.Visibility visibility(long userId, String type, long strategyId) {
        String normalized = visibilityType(type);
        requireOwner(userId, normalized, strategyId);
        Boolean visible = jdbc.queryForObject("""
                select coalesce(
                    (select is_public from strategy_visibility where strategy_type=? and strategy_id=?),
                    (select official from strategy_mark where strategy_type=? and strategy_id=?),
                    false)
                """, Boolean.class, normalized, strategyId, normalized, strategyId);
        return new PresetDtos.Visibility(Boolean.TRUE.equals(visible));
    }

    @Transactional
    public PresetDtos.Visibility updateVisibility(long userId, String type, long strategyId, boolean visible) {
        String normalized = visibilityType(type);
        requireOwner(userId, normalized, strategyId);
        if ("FNG".equals(normalized) && jdbc.queryForList("""
                select id from fng_strategy where id=? and user_id=? and archived_at is null for update
                """, Long.class, strategyId, userId).isEmpty()) {
            throw new BusinessException(409, "已归档的策略不能修改展示设置");
        }
        jdbc.update("""
                insert into strategy_visibility(strategy_type,strategy_id,user_id,is_public,updated_at)
                values(?,?,?,?,?)
                on duplicate key update is_public=?,updated_at=?
                """, normalized, strategyId, userId, visible, Timestamp.from(clock.instant()),
                visible, Timestamp.from(clock.instant()));
        return new PresetDtos.Visibility(visible);
    }

    private static String visibilityType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!"DCA".equals(normalized) && !"FNG".equals(normalized)) {
            throw new BusinessException(400, "策略类型无效");
        }
        return normalized;
    }

    private void requireOwner(long userId, String type, long strategyId) {
        String sql = "DCA".equals(type)
                ? "select count(*) from plan where id=? and user_id=?"
                : "select count(*) from fng_strategy where id=? and user_id=?";
        Integer owned = jdbc.queryForObject(sql, Integer.class, strategyId, userId);
        if (owned == null || owned == 0) throw new BusinessException(404, "策略不存在");
    }

    private Map<String, Mark> marks() {
        Map<String, Mark> marks = new HashMap<>();
        jdbc.query("select strategy_type,strategy_id,official,recommended from strategy_mark", rs -> {
            marks.put(
                    rs.getString("strategy_type") + ":" + rs.getLong("strategy_id"),
                    new Mark(rs.getBoolean("official"), rs.getBoolean("recommended")));
        });
        return marks;
    }

    private Map<Long, FngPerformance> fngPerformance() {
        Map<Long, FngPerformance> performance = new HashMap<>();
        jdbc.query("""
                select strategy_id,sum(margin) margin,
                       sum(case when side='LONG' then (exit_price-entry_price)*quantity
                                else (entry_price-exit_price)*quantity end) pnl
                from fng_position
                where status='CLOSED' and exit_price is not null
                group by strategy_id
                """, rs -> {
            BigDecimal pnl = rs.getBigDecimal("pnl");
            performance.put(rs.getLong("strategy_id"),
                    new FngPerformance(pnl, ratio(pnl, rs.getBigDecimal("margin"))));
        });
        return performance;
    }

    private List<Map<String, Object>> coins(long planId) {
        return jdbc.query("""
                select symbol,proportion,`min`,`max`,average_down from coin where plan_id=? order by id
                """, (rs, index) -> {
            Map<String, Object> coin = new LinkedHashMap<>();
            coin.put("symbol", rs.getString("symbol"));
            coin.put("proportion", rs.getString("proportion"));
            coin.put("min", decimal(rs.getObject("min")));
            coin.put("max", decimal(rs.getObject("max")));
            coin.put("averageDown", rs.getBoolean("average_down"));
            return coin;
        }, planId);
    }

    private List<String> sparkline(long planId) {
        List<String> values = new ArrayList<>(jdbc.queryForList("""
                select value from snapshot where plan_id=? and type='return' order by created_at desc limit 12
                """, String.class, planId));
        java.util.Collections.reverse(values);
        return values;
    }

    private List<String> fngSparkline(long strategyId) {
        List<BigDecimal> pnls = jdbc.queryForList("""
                select case when side='LONG' then (exit_price-entry_price)*quantity
                            else (entry_price-exit_price)*quantity end pnl
                from fng_position
                where strategy_id=? and status='CLOSED' and exit_price is not null
                order by closed_at,id
                """, BigDecimal.class, strategyId);
        BigDecimal sum = BigDecimal.ZERO;
        List<String> values = new ArrayList<>();
        for (BigDecimal pnl : pnls) {
            if (pnl == null) continue;
            sum = sum.add(pnl);
            values.add(decimal(sum));
        }
        int from = Math.max(0, values.size() - 12);
        return values.subList(from, values.size());
    }

    private void requirePreset(String key) {
        if (key == null) throw new BusinessException(404, "策略不存在");
        int separator = key.indexOf(':');
        if (separator <= 0 || separator == key.length() - 1) throw new BusinessException(404, "策略不存在");
        String type = key.substring(0, separator);
        long id;
        try {
            id = Long.parseLong(key.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw new BusinessException(404, "策略不存在");
        }
        if ("fng".equals(type)) {
            Integer count = jdbc.queryForObject("""
                    select count(*) from fng_strategy f join user u on u.id=f.user_id
                    left join strategy_visibility v on v.strategy_type='FNG' and v.strategy_id=f.id
                    left join strategy_mark m on m.strategy_type='FNG' and m.strategy_id=f.id
                    where f.id=? and f.enabled=true and f.archived_at is null and u.status='active'
                      and coalesce(v.is_public,m.official,false)=true
                    """, Integer.class, id);
            if (count == null || count == 0) throw new BusinessException(404, "策略不存在");
            return;
        }
        if (!"dca".equals(type)) throw new BusinessException(404, "策略不存在");
        Integer count = jdbc.queryForObject("""
                select count(*) from plan p join user u on u.id=p.user_id
                left join strategy_visibility v on v.strategy_type='DCA' and v.strategy_id=p.id
                left join strategy_mark m on m.strategy_type='DCA' and m.strategy_id=p.id
                where p.id=? and p.status='active' and u.status='active'
                  and coalesce(v.is_public,m.official,false)=true
                """, Integer.class, id);
        if (count == null || count == 0) throw new BusinessException(404, "策略不存在");
    }

    private void activeUser(long userId) {
        List<String> rows = jdbc.queryForList("select id from user where id=? and status='active'", String.class, userId);
        if (rows.isEmpty()) throw new BusinessException(401, "无法验证凭据");
    }

    private static boolean matchesOwner(PresetDtos.Preset item, String owner) {
        return "all".equals(owner) || ("official".equals(owner) == item.official());
    }

    private static boolean matchesType(PresetDtos.Preset item, String type) {
        return "all".equals(type) || type.equalsIgnoreCase(item.type());
    }

    private static boolean matchesExchange(PresetDtos.Preset item, String exchange) {
        return "all".equals(exchange) || exchange.equalsIgnoreCase(item.exchange());
    }

    private static boolean matchesRuntime(PresetDtos.Preset item, String runtime) {
        if ("all".equals(runtime)) return true;
        Long days = item.runtimeDays();
        if (days == null) return false;
        return switch (runtime) {
            case "1-50" -> days >= 1 && days <= 50;
            case "51-100" -> days >= 51 && days <= 100;
            case "100+" -> days > 100;
            default -> false;
        };
    }

    private static boolean matchesQuery(PresetDtos.Preset item, String query) {
        if (query.isEmpty()) return true;
        if (item.name().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (item.author().toLowerCase(Locale.ROOT).contains(query)) return true;
        return item.symbols().stream().anyMatch(symbol -> symbol.toLowerCase(Locale.ROOT).contains(query));
    }

    private static Comparator<PresetDtos.Preset> comparator(String sort) {
        Comparator<PresetDtos.Preset> byFollowers = Comparator.comparingLong(PresetDtos.Preset::followers).reversed();
        Comparator<PresetDtos.Preset> byReturn = Comparator.comparing(
                item -> item.totalRatio() == null ? null : new BigDecimal(item.totalRatio()), Comparator.nullsLast(Comparator.reverseOrder()));
        Comparator<PresetDtos.Preset> byName = Comparator.comparing(PresetDtos.Preset::name);
        if ("followers".equals(sort)) return byFollowers.thenComparing(byReturn).thenComparing(byName);
        if ("return".equals(sort)) return byReturn.thenComparing(byFollowers).thenComparing(byName);
        Comparator<PresetDtos.Preset> byOfficial = Comparator.comparing(PresetDtos.Preset::official).reversed();
        Comparator<PresetDtos.Preset> byRecommended = Comparator.comparing(PresetDtos.Preset::recommended).reversed();
        return byOfficial.thenComparing(byRecommended).thenComparing(byFollowers)
                .thenComparing(byReturn).thenComparing(byName);
    }

    private static String valueOr(String value, String fallback, Set<String> allowed) {
        String normalized = value == null ? fallback : value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid("筛选条件无效");
        return normalized;
    }

    private static String avatar(byte[] image) {
        return image == null ? null : "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(image);
    }

    private static String author(String name, String email) {
        if (name != null && !name.isBlank()) return name.trim();
        if (email == null || email.isBlank()) return "Bitpongo user";
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        String domain = at > 0 ? email.substring(at) : "";
        return local.length() <= 2 ? local.substring(0, 1) + "***" + domain : local.substring(0, 2) + "***" + domain;
    }

    private static BigDecimal ratio(BigDecimal pnl, BigDecimal margin) {
        if (pnl == null || margin == null || margin.signum() == 0) return null;
        return pnl.multiply(HUNDRED).divide(margin, 2, RoundingMode.HALF_UP);
    }

    private static String decimal(Object value) {
        if (value == null) return null;
        BigDecimal number = value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
        return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
    }

    private Long runtimeDays(Object value) {
        Instant createdAt = instant(value);
        if (createdAt == null) return null;
        return Math.max(0, ChronoUnit.DAYS.between(createdAt, clock.instant()));
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        if (value instanceof Instant instant) return instant;
        return null;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message);
    }

    private record Mark(boolean official, boolean recommended) {
        private static final Mark NONE = new Mark(false, false);
    }

    private record FngPerformance(BigDecimal pnl, BigDecimal ratio) {
        private static final FngPerformance NONE = new FngPerformance(null, null);
    }
}
