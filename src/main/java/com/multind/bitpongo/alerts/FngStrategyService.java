package com.multind.bitpongo.alerts;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.market.FearGreedService;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class FngStrategyService {
    private static final Set<String> DIRECTIONS = Set.of("LONG", "SHORT");
    private static final Set<String> TARGET_TYPES = Set.of("PERCENT", "USDT");
    // A cycle starts when a position is opened and ends at the take-profit exit.
    // bureauPeriod is the maximum number of days allowed for that cycle.
    private static final int CYCLE_MIN_DAYS = 1;
    private static final int CYCLE_MAX_DAYS = 25;

    private final JdbcTemplate jdbc;
    private final FngExecutionService execution;

    public FngStrategyService(JdbcTemplate jdbc, FngExecutionService execution) {
        this.jdbc = jdbc;
        this.execution = execution;
    }

    @Transactional
    public long create(long userId, FngStrategyDtos.Create request) {
        activeUser(userId);
        Validated value = validate(userId, request);
        var key = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into fng_strategy(user_id,name,exchange,exchange_id,symbol,upper_limit_value,upper_limit_interval,
                        lower_limit_value,lower_limit_interval,basic_amount,increase_ratio,leverage,bureau_period,
                        direction,profit_target_percent,profit_drawdown_percent,profit_target_type,
                        max_orders,max_positions,enabled,valid_from,created_at,updated_at)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,?,?,?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] values = { userId, value.name(), value.exchange(), value.exchangeId(), value.symbol(), value.upperValue(),
                    value.upperInterval(), value.lowerValue(), value.lowerInterval(), value.basicAmount(),
                    value.increaseRatio(), value.leverage(), value.bureauPeriod(), value.direction(),
                    value.profitTargetPercent(), value.profitDrawdownPercent(),
                    value.profitTargetType(), value.maxOrders(), value.maxPositions(),
                    PriceAlertService.utc(now), PriceAlertService.utc(now), PriceAlertService.utc(now) };
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    @Transactional(readOnly = true)
    public FngStrategyDtos.View detail(long userId, long id) {
        activeUser(userId);
        return jdbc.query("select * from fng_strategy where user_id=? and id=?", FngStrategyService::view, userId, id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(404, "F&G 策略不存在"));
    }

    @Transactional(readOnly = true)
    public List<FngStrategyDtos.View> list(long userId) {
        activeUser(userId);
        return jdbc.query("select * from fng_strategy where user_id=? order by id desc", FngStrategyService::view, userId);
    }
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record StrategyPage(List<FngStrategyDtos.View> items, int page, int size, long total, long activeTotal, boolean hasMore) {}
    @Transactional(readOnly = true)
    public StrategyPage page(long userId, int page, int size) {
        activeUser(userId);
        if(page<0 || size<1 || size>50) throw new com.multind.bitpongo.common.api.BusinessException(400,"分页参数无效");
        long total=jdbc.queryForObject("select count(*) from fng_strategy where user_id=?",Long.class,userId);
        long active=jdbc.queryForObject("select count(*) from fng_strategy where user_id=? and enabled=true",Long.class,userId);
        var items=jdbc.query("select * from fng_strategy where user_id=? order by id desc limit ? offset ?",FngStrategyService::view,userId,size,(long)page*size);
        return new StrategyPage(items,page,size,total,active,((long)page+1)*size<total);
    }

    @Transactional
    public void update(long userId, long id, FngStrategyDtos.Create request) {
        activeUser(userId);
        owned(userId, id);
        Validated value = validate(userId, request);
        Instant now = Instant.now();
        int updated = jdbc.update("""
                update fng_strategy set name=?,exchange=?,exchange_id=?,symbol=?,upper_limit_value=?,upper_limit_interval=?,
                    lower_limit_value=?,lower_limit_interval=?,basic_amount=?,increase_ratio=?,leverage=?,bureau_period=?,
                    direction=?,profit_target_percent=?,profit_drawdown_percent=?,profit_target_type=?,
                    max_orders=?,max_positions=?,valid_from=?,last_observed_at=null,last_zone=null,updated_at=?
                where user_id=? and id=?
                """, value.name(), value.exchange(), value.exchangeId(), value.symbol(), value.upperValue(), value.upperInterval(),
                value.lowerValue(), value.lowerInterval(), value.basicAmount(), value.increaseRatio(), value.leverage(),
                value.bureauPeriod(), value.direction(), value.profitTargetPercent(),
                value.profitDrawdownPercent(), value.profitTargetType(), value.maxOrders(), value.maxPositions(),
                PriceAlertService.utc(now), PriceAlertService.utc(now), userId, id);
        if (updated == 0) throw new BusinessException(404, "F&G 策略不存在");
    }

    @Transactional
    public void enabled(long userId, long id, boolean enabled) {
        activeUser(userId);
        Instant now = Instant.now();
        int updated = jdbc.update("""
                update fng_strategy set enabled=?,valid_from=?,last_observed_at=null,last_zone=null,updated_at=?
                where user_id=? and id=?
                """, enabled, PriceAlertService.utc(now), PriceAlertService.utc(now), userId, id);
        if (updated == 0) throw new BusinessException(404, "F&G 策略不存在");
    }

    @Transactional
    public void delete(long userId, long id) {
        activeUser(userId);
        if (jdbc.update("delete from fng_strategy where user_id=? and id=?", userId, id) == 0) {
            throw new BusinessException(404, "F&G 策略不存在");
        }
    }

    public List<Active> activeStrategies() {
        return jdbc.query("""
                select s.id,s.user_id from fng_strategy s join user u on u.id=s.user_id
                where s.enabled=true and u.status='active'
                """, (rs, index) -> new Active(rs.getLong("id"), rs.getLong("user_id")));
    }

    @Transactional
    public void sample(long id, long userId, FearGreedService.Point point) {
        if (point == null || point.observedAt() == null || point.value() < 0 || point.value() > 100
                || point.observedAt().isBefore(Instant.now().minusSeconds(172800))
                || point.observedAt().isAfter(Instant.now().plusSeconds(300))) return;
        if (jdbc.queryForList("select id from user where id=? and status='active' for update", userId).isEmpty()) return;
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "select * from fng_strategy where id=? and user_id=? and enabled=true for update", id, userId);
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        Instant validFrom = PriceAlertService.instant(row.get("valid_from"));
        Instant last = PriceAlertService.instant(row.get("last_observed_at"));
        if (!point.observedAt().isAfter(validFrom) || (last != null && !point.observedAt().isAfter(last))) return;
        int upper = ((Number) row.get("upper_limit_value")).intValue();
        int lower = ((Number) row.get("lower_limit_value")).intValue();
        int upperInterval = ((Number) row.get("upper_limit_interval")).intValue();
        int lowerInterval = ((Number) row.get("lower_limit_interval")).intValue();
        var amount = FngAmountCalculator.plan(
                amount(row.get("basic_amount")),
                amount(row.get("increase_ratio")),
                lower, lowerInterval, upper, upperInterval, point.value());
        FngZone zone = amount.zone();
        String previousZone = (String) row.get("last_zone");
        jdbc.update("update fng_strategy set last_observed_at=?,last_zone=?,updated_at=? where id=?",
                PriceAlertService.utc(point.observedAt()), zone.name(), PriceAlertService.utc(Instant.now()), id);
        if (zone == FngZone.INSIDE || zone == FngZone.OUTSIDE || zone.name().equals(previousZone)) return;
        String direction = (String) row.get("direction");
        if ("LONG".equals(direction) != (zone == FngZone.LOWER)) return;
        Object exchangeId = row.get("exchange_id");
        if (!(exchangeId instanceof Number number) || number.longValue() <= 0) return;
        execution.signal(id, userId, number.longValue(), (String) row.get("symbol"), direction,
                amount.suggestedAmount(), ((Number) row.get("leverage")).intValue(),
                ((Number) row.get("max_orders")).intValue(), ((Number) row.get("max_positions")).intValue(), true);
    }

    public record Active(long id, long userId) {}

    private void activeUser(long userId) {
        if (jdbc.queryForList("select id from user where id=? and status='active'", String.class, userId).isEmpty()) {
            throw new BusinessException(401, "无法验证凭据");
        }
    }

    private void owned(long userId, long id) {
        if (jdbc.queryForList("select id from fng_strategy where user_id=? and id=?", Integer.class, userId, id).isEmpty()) {
            throw new BusinessException(404, "F&G 策略不存在");
        }
    }

    private static FngStrategyDtos.View view(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new FngStrategyDtos.View(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("exchange"),
                rs.getObject("exchange_id") == null ? null : rs.getLong("exchange_id"),
                rs.getString("symbol"),
                rs.getInt("upper_limit_value"),
                rs.getInt("upper_limit_interval"),
                rs.getInt("lower_limit_value"),
                rs.getInt("lower_limit_interval"),
                decimal(rs.getBigDecimal("basic_amount")),
                decimal(rs.getBigDecimal("increase_ratio")),
                rs.getInt("leverage"),
                rs.getInt("bureau_period"),
                rs.getString("direction"),
                decimal(rs.getBigDecimal("profit_target_percent")),
                decimal(rs.getBigDecimal("profit_drawdown_percent")),
                rs.getString("profit_target_type"),
                rs.getInt("max_orders"),
                rs.getInt("max_positions"),
                rs.getBoolean("enabled"),
                PriceAlertService.instant(rs.getObject("created_at")));
    }

    private Validated validate(long userId, FngStrategyDtos.Create request) {
        if (request == null) throw invalid("策略参数无效");
        String name = required(request.name(), 100, "策略名称");
        long exchangeId = requireExchangeId(userId, request.exchangeId());
        String exchange = exchange(userId, exchangeId);
        String symbol = required(request.symbol(), 32, "交易对").toUpperCase(Locale.ROOT).replace("/", "");
        if (!symbol.matches("[A-Z0-9]{1,20}USDT")) throw invalid("交易对无效");
        int upperValue = range(request.upperLimitValue(), 1, 100, "上限值");
        int lowerValue = range(request.lowerLimitValue(), 0, 99, "下限值");
        if (lowerValue >= upperValue) throw invalid("下限必须小于上限");
        int upperInterval = range(request.upperLimitInterval(), 1, 25, "上限区间");
        int lowerInterval = range(request.lowerLimitInterval(), 1, 25, "下限区间");
        BigDecimal basicAmount = decimal(request.basicAmount(), "基础下单金额", false);
        BigDecimal increaseRatio = decimal(request.increaseRatio(), "加仓比例", true);
        if (increaseRatio.signum() < 0) throw invalid("加仓比例无效");
        int leverage = range(request.leverage(), 1, 125, "杠杆倍数");
        // One cycle = entry -> take-profit exit; this is the maximum cycle length in days.
        int bureauPeriod = range(request.bureauPeriod(), CYCLE_MIN_DAYS, CYCLE_MAX_DAYS, "交易周期");
        String direction = choice(request.direction(), DIRECTIONS, "策略方向");
        // Take profit when unrealized profit reaches this percentage.
        BigDecimal profitTarget = decimal(request.profitTargetPercent(), "止盈比例", false);
        if (profitTarget.compareTo(BigDecimal.valueOf(100)) > 0) throw invalid("止盈比例不能超过100");
        // Trailing stop relative to peak profit: peak 20% with 5% drawdown exits at 19%.
        BigDecimal profitDrawdown = decimal(request.profitDrawdownPercent(), "回撤比例", false);
        if (profitDrawdown.compareTo(BigDecimal.valueOf(100)) > 0) throw invalid("回撤比例不能超过100");
        String targetType = choice(request.profitTargetType(), TARGET_TYPES, "止盈类型");
        // Execution limits stored for the future trading integration; F&G alerts do not place orders.
        int maxOrders = range(request.maxOrders(), 1, 10000, "最大订单数");
        int maxPositions = range(request.maxPositions(), 1, 10000, "最大持仓数");
        return new Validated(name, exchange, exchangeId, symbol, upperValue, upperInterval, lowerValue, lowerInterval,
                basicAmount, increaseRatio, leverage, bureauPeriod, direction,
                profitTarget, profitDrawdown, targetType, maxOrders, maxPositions);
    }

    private record Validated(String name, String exchange, long exchangeId, String symbol, int upperValue, int upperInterval,
            int lowerValue, int lowerInterval, BigDecimal basicAmount, BigDecimal increaseRatio, int leverage,
            int bureauPeriod, String direction, BigDecimal profitTargetPercent,
            BigDecimal profitDrawdownPercent, String profitTargetType, int maxOrders, int maxPositions) {}

    private long requireExchangeId(long userId, Long exchangeId) {
        if (exchangeId == null) throw invalid("请选择合约交易所");
        if (jdbc.queryForList("select id from exchange where id=? and user_id=?", Integer.class, exchangeId, userId).isEmpty()) {
            throw invalid("交易所不存在");
        }
        return exchangeId;
    }

    private String exchange(long userId, long exchangeId) {
        var rows = jdbc.queryForList("select exchange from exchange where id=? and user_id=?", String.class, exchangeId, userId);
        String code = rows.isEmpty() ? "" : String.valueOf(rows.getFirst());
        if (!"binance".equalsIgnoreCase(code)) throw invalid("目前仅支持 Binance 合约");
        return "binance";
    }

    private static String required(String value, int max, String field) {
        if (value == null || value.isBlank()) throw invalid(field + "不能为空");
        String trimmed = value.trim();
        if (trimmed.length() > max) throw invalid(field + "过长");
        return trimmed;
    }

    private static int range(Integer value, int min, int max, String field) {
        if (value == null || value < min || value > max) throw invalid(field + "必须在" + min + "-" + max + "之间");
        return value;
    }

    private static BigDecimal decimal(BigDecimal value, String field, boolean zeroAllowed) {
        if (value == null || (!zeroAllowed && value.signum() <= 0) || value.scale() > 18 || value.precision() - value.scale() > 20) {
            throw invalid(field + "无效");
        }
        return value;
    }

    private static String choice(String value, Set<String> allowed, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid(field + "无效");
        return normalized;
    }

    private static BigDecimal amount(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private static String decimal(BigDecimal value) {
        if (value == null) return null;
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message);
    }
}
