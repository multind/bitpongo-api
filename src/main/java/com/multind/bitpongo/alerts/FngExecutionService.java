package com.multind.bitpongo.alerts;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.exchange.ExchangeCredentials;
import com.multind.bitpongo.exchange.ExchangeEntity;
import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.exchange.FuturesGateway;
import com.multind.bitpongo.exchange.FuturesGatewayRegistry;
import com.multind.bitpongo.notification.AlertNotificationBridge;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Testnet-first USDT-M futures execution for F&amp;G strategies.
 *
 * <p>A signal is persisted as PENDING_ENTRY before any exchange call. The worker then opens a
 * market position, monitors profit against the target/drawdown/cycle rules, and closes it with a
 * reduce-only market order. Notifications are sent only after an entry or exit is filled.</p>
 */
@Service
@Profile("!test")
public class FngExecutionService {
    private static final Logger log = LoggerFactory.getLogger(FngExecutionService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String BASE_SELECT = """
            select p.id,p.strategy_id,p.user_id,p.exchange_id,p.symbol,p.side,p.status,
                   p.entry_client_order_id,p.exit_client_order_id,p.margin,p.quantity,p.entry_price,p.leverage,
                   p.peak_profit_percent,p.close_reason,p.opened_at,p.updated_at,
                   s.name strategy_name,s.enabled,s.direction,s.profit_target_percent,
                   s.profit_drawdown_percent,s.profit_target_type,s.bureau_period,s.max_orders,s.max_positions,
                   e.exchange
            from fng_position p
            join fng_strategy s on s.id=p.strategy_id
            join exchange e on e.id=p.exchange_id
            """;

    private final JdbcTemplate jdbc;
    private final ExchangeRepository exchanges;
    private final FuturesGatewayRegistry gateways;
    private final AlertNotificationBridge notifications;
    private final boolean enabled;

    public FngExecutionService(
            JdbcTemplate jdbc,
            ExchangeRepository exchanges,
            FuturesGatewayRegistry gateways,
            AlertNotificationBridge notifications,
            @Value("${zhitoubao.fng.execution-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.exchanges = exchanges;
        this.gateways = gateways;
        this.notifications = notifications;
        this.enabled = enabled;
    }

    public void process() {
        if (!enabled) return;
        List<Runnable> steps = List.of(this::openPending, this::reconcileOpening, this::monitorOpen, this::reconcileClosing);
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException exception) {
                log.warn("F&G 执行任务失败 step={} errorType={}", step.getClass().getSimpleName(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    /** Persists a band-entry signal; the worker submits it later so market sampling never waits on HTTP. */
    public void signal(long strategyId, long userId, long exchangeId, String symbol, String side,
            BigDecimal margin, int leverage, int maxOrders, int maxPositions, boolean strategyEnabled) {
        if (!enabled || !strategyEnabled || exchangeId <= 0 || margin == null || margin.signum() <= 0) return;
        int positions = count("select count(*) from fng_position where strategy_id=? and status in "
                + "('PENDING_ENTRY','OPENING','OPEN','CLOSING')", strategyId);
        if (positions >= maxPositions) return;
        int orders = count("select count(*) from fng_position where strategy_id=? and status in "
                + "('PENDING_ENTRY','OPENING','CLOSING')", strategyId);
        if (orders >= maxOrders) return;
        String clientOrderId = "fng-" + strategyId + "-" + System.currentTimeMillis();
        LocalDateTime now = now();
        jdbc.update("""
                insert into fng_position(strategy_id,user_id,exchange_id,symbol,side,status,entry_client_order_id,
                    margin,quantity,leverage,peak_profit_percent,opened_at,updated_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, strategyId, userId, exchangeId, symbol, side, "PENDING_ENTRY", clientOrderId,
                margin, BigDecimal.ZERO, leverage, BigDecimal.ZERO, now, now);
    }

    private void openPending() {
        for (Row row : rows("PENDING_ENTRY", 5)) {
            boolean submitted = false;
            if (!row.enabled()) {
                failed(row.id(), "STRATEGY_DISABLED");
                continue;
            }
            try {
                ExchangeContext context = context(row);
                FuturesGateway gateway = context.gateway();
                if (!gateway.oneWayMode(context.credentials())) {
                    throw new BusinessException(400, "请将合约账户切换为单向持仓模式");
                }
                FuturesGateway.Rules rules = gateway.rules(row.symbol());
                BigDecimal mark = gateway.markPrice(row.symbol());
                if (mark.signum() <= 0) throw new BusinessException(502, "合约标记价格无效");
                FuturesGateway.Balance balance = gateway.balance(context.credentials());
                if (balance.available().compareTo(row.margin()) < 0) {
                    throw new BusinessException(400, "合约可用余额不足");
                }
                BigDecimal quantity = quantity(row.margin(), row.leverage(), mark, rules);
                gateway.setLeverage(context.credentials(), row.symbol(), row.leverage());
                int claimed = jdbc.update("""
                        update fng_position set status='OPENING',quantity=?,last_error=null,updated_at=?
                        where id=? and status='PENDING_ENTRY'
                        """, quantity, now(), row.id());
                if (claimed == 0) continue;
                submitted = true;
                String side = "LONG".equals(row.side()) ? "BUY" : "SELL";
                FuturesGateway.Order placed = gateway.marketOrder(context.credentials(), row.symbol(), side,
                        quantity, row.entryClientOrderId(), false);
                applyEntry(row, gateway, context.credentials(), mark, placed);
            } catch (RuntimeException exception) {
                if (submitted) {
                    updateError(row.id(), sanitized(exception));
                } else {
                    failed(row.id(), sanitized(exception));
                }
            }
        }
    }

    private void reconcileOpening() {
        LocalDateTime threshold = now().minusSeconds(2);
        for (Row row : rowsWhere("OPENING", "p.updated_at<=?", threshold, 20)) {
            try {
                ExchangeContext context = context(row);
                Optional<FuturesGateway.Order> order = context.gateway().findOrder(
                        context.credentials(), row.symbol(), row.entryClientOrderId());
                if (order.isEmpty()) {
                    if (row.updatedAt().isBefore(Instant.now().minusSeconds(120))) {
                        failed(row.id(), "ORDER_NOT_FOUND");
                    }
                    continue;
                }
                applyEntry(row, context.gateway(), context.credentials(), row.entryPrice(), order.get());
                if (!filled(order.get()) && !terminal(order.get().status())
                        && row.updatedAt().isBefore(Instant.now().minusSeconds(120))) {
                    failed(row.id(), "ORDER_TIMEOUT");
                }
            } catch (RuntimeException exception) {
                log.warn("F&G 开仓核对失败 positionId={} errorType={}", row.id(), exception.getClass().getSimpleName());
            }
        }
    }

    private void monitorOpen() {
        for (Row row : rows("OPEN", 20)) {
            try {
                ExchangeContext context = context(row);
                BigDecimal mark = context.gateway().markPrice(row.symbol());
                if (mark.signum() <= 0 || row.entryPrice() == null || row.entryPrice().signum() <= 0
                        || row.quantity().signum() <= 0 || row.margin().signum() <= 0) continue;
                BigDecimal pnl = "LONG".equals(row.side())
                        ? mark.subtract(row.entryPrice()).multiply(row.quantity())
                        : row.entryPrice().subtract(mark).multiply(row.quantity());
                BigDecimal pnlPercent = pnl.divide(row.margin(), 8, RoundingMode.HALF_UP).multiply(HUNDRED);
                BigDecimal peak = row.peak().max(pnlPercent);
                jdbc.update("update fng_position set peak_profit_percent=?,updated_at=? where id=?",
                        peak, now(), row.id());
                String reason = exitReason(row, pnl, pnlPercent, peak);
                if (reason != null) close(row, context, mark, reason);
            } catch (RuntimeException exception) {
                log.warn("F&G 持仓监控失败 positionId={} errorType={}", row.id(), exception.getClass().getSimpleName());
            }
        }
    }

    private String exitReason(Row row, BigDecimal pnl, BigDecimal pnlPercent, BigDecimal peak) {
        if ("USDT".equalsIgnoreCase(row.targetType())) {
            if (pnl.compareTo(row.target()) >= 0) return "TARGET";
        } else if (pnlPercent.compareTo(row.target()) >= 0) {
            return "TARGET";
        }
        if (peak.signum() > 0) {
            BigDecimal floor = peak.multiply(BigDecimal.ONE.subtract(
                    row.drawdown().divide(HUNDRED, 8, RoundingMode.HALF_UP)));
            if (pnlPercent.compareTo(floor) <= 0) return "DRAWDOWN";
        }
        if (row.cycleDays() > 0 && !Instant.now().isBefore(row.openedAt().plus(Duration.ofDays(row.cycleDays())))) {
            return "CYCLE";
        }
        return null;
    }

    private void close(Row row, ExchangeContext context, BigDecimal mark, String reason) {
        String exitClientOrderId = "fngx-" + row.id() + "-" + System.currentTimeMillis();
        int claimed = jdbc.update("""
                update fng_position set status='CLOSING',exit_client_order_id=?,close_reason=?,last_error=null,updated_at=?
                where id=? and status='OPEN'
                """, exitClientOrderId, reason, now(), row.id());
        if (claimed == 0) return;
        boolean submitted = false;
        try {
            String side = "LONG".equals(row.side()) ? "SELL" : "BUY";
            submitted = true;
            FuturesGateway.Order placed = context.gateway().marketOrder(context.credentials(), row.symbol(), side,
                    row.quantity(), exitClientOrderId, true);
            FuturesGateway.Order latest = context.gateway().findOrder(context.credentials(), row.symbol(), exitClientOrderId)
                    .orElse(placed);
            applyExit(row, latest, mark, reason);
        } catch (RuntimeException exception) {
            if (submitted) {
                updateError(row.id(), sanitized(exception));
            } else {
                reopen(row.id(), sanitized(exception));
            }
        }
    }

    private void reconcileClosing() {
        LocalDateTime threshold = now().minusSeconds(2);
        for (Row row : rowsWhere("CLOSING", "p.updated_at<=?", threshold, 20)) {
            try {
                ExchangeContext context = context(row);
                Optional<FuturesGateway.Order> order = context.gateway().findOrder(
                        context.credentials(), row.symbol(), row.exitClientOrderId());
                if (order.isEmpty()) {
                    if (row.updatedAt().isBefore(Instant.now().minusSeconds(120))) reopen(row.id(), "CLOSE_ORDER_NOT_FOUND");
                    continue;
                }
                FuturesGateway.Order latest = order.get();
                if (filled(latest)) {
                    closed(row, latest.avgPrice().signum() > 0 ? latest.avgPrice() : row.entryPrice(),
                            row.closeReason());
                } else if (terminal(latest.status())) {
                    reopen(row.id(), latest.status());
                } else if (row.updatedAt().isBefore(Instant.now().minusSeconds(120))) {
                    reopen(row.id(), "CLOSE_ORDER_TIMEOUT");
                }
            } catch (RuntimeException exception) {
                log.warn("F&G 平仓核对失败 positionId={} errorType={}", row.id(), exception.getClass().getSimpleName());
            }
        }
    }

    private void applyEntry(Row row, FuturesGateway gateway, ExchangeCredentials credentials, BigDecimal mark,
            FuturesGateway.Order order) {
        FuturesGateway.Order latest = gateway.findOrder(credentials, row.symbol(), row.entryClientOrderId()).orElse(order);
        if (filled(latest)) {
            BigDecimal quantity = latest.executedQty().signum() > 0 ? latest.executedQty() : row.quantity();
            BigDecimal price = latest.avgPrice().signum() > 0 ? latest.avgPrice() : (mark == null ? row.entryPrice() : mark);
            jdbc.update("""
                    update fng_position set status='OPEN',quantity=?,entry_price=?,opened_at=?,updated_at=?
                    where id=? and status in ('OPENING','PENDING_ENTRY')
                    """, quantity, price, now(), now(), row.id());
            notifyEntry(row, price, quantity);
        } else if (terminal(latest.status())) {
            failed(row.id(), latest.status());
        }
    }

    private void applyExit(Row row, FuturesGateway.Order order, BigDecimal mark, String reason) {
        if (filled(order)) {
            closed(row, order.avgPrice().signum() > 0 ? order.avgPrice() : mark, reason);
        } else if (terminal(order.status())) {
            reopen(row.id(), order.status());
        }
    }

    private void closed(Row row, BigDecimal price, String reason) {
        int updated = jdbc.update("""
                update fng_position set status='CLOSED',exit_price=?,closed_at=?,exit_client_order_id=null,last_error=null,updated_at=?
                where id=? and status='CLOSING'
                """, price, now(), now(), row.id());
        if (updated > 0) notifyExit(row, price, reason);
    }

    private void notifyEntry(Row row, BigDecimal price, BigDecimal quantity) {
        notify(row, "fng-entry:" + row.id(), "F&G 开仓成交",
                row.symbol() + " " + row.side() + " opened qty " + plain(quantity) + " @ " + plain(price)
                        + " (" + row.leverage() + "x)");
    }

    private void notifyExit(Row row, BigDecimal price, String reason) {
        notify(row, "fng-exit:" + row.id(), "F&G 平仓成交",
                row.symbol() + " " + row.side() + " closed @ " + plain(price) + " reason " + reason);
    }

    private void notify(Row row, String eventKey, String title, String body) {
        Integer existing = jdbc.queryForObject("select count(*) from alert_event where event_key=?", Integer.class, eventKey);
        if (existing != null && existing > 0) return;
        Instant at = Instant.now();
        jdbc.update("insert into alert_event(user_id,event_type,event_key,title,body,occurred_at) values(?,?,?,?,?,?)",
                row.userId(), "STRATEGY_SIGNAL", eventKey, title, body, PriceAlertService.utc(at));
        notifications.strategy(row.userId(), eventKey, body, at);
    }

    private ExchangeContext context(Row row) {
        ExchangeEntity exchange = exchanges.findByIdAndUserId(row.exchangeId(), row.userId())
                .orElseThrow(() -> new BusinessException(404, "交易所连接不存在"));
        FuturesGateway gateway = gateways.require(exchange.getExchange());
        ExchangeCredentials credentials = new ExchangeCredentials(
                exchange.getAccessKey(), exchange.getSecretKey(), exchange.getPassword());
        return new ExchangeContext(gateway, credentials);
    }

    private BigDecimal quantity(BigDecimal margin, int leverage, BigDecimal mark, FuturesGateway.Rules rules) {
        if (rules.stepSize().signum() <= 0) throw new BusinessException(502, "合约数量精度无效");
        BigDecimal notional = margin.multiply(BigDecimal.valueOf(leverage));
        BigDecimal raw = notional.divide(mark, 16, RoundingMode.DOWN);
        BigDecimal quantity = raw.divide(rules.stepSize(), 0, RoundingMode.DOWN)
                .multiply(rules.stepSize()).stripTrailingZeros();
        if (quantity.signum() <= 0 || quantity.compareTo(rules.minQty()) < 0
                || (rules.maxQty().signum() > 0 && quantity.compareTo(rules.maxQty()) > 0)) {
            throw new BusinessException(400, "合约下单数量不符合交易所规则");
        }
        if (rules.minNotional().signum() > 0 && notional.compareTo(rules.minNotional()) < 0) {
            throw new BusinessException(400, "合约名义价值低于最小下单金额");
        }
        return quantity;
    }

    private List<Row> rows(String status, int limit) {
        return jdbc.query(BASE_SELECT + " where p.status=? order by p.id limit ?", FngExecutionService::row, status, limit);
    }

    private List<Row> rowsWhere(String status, String extraWhere, Object argument, int limit) {
        return jdbc.query(BASE_SELECT + " where p.status=? and " + extraWhere + " order by p.id limit ?",
                FngExecutionService::row, status, argument, limit);
    }

    private static Row row(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new Row(
                rs.getLong("id"), rs.getLong("strategy_id"), rs.getLong("user_id"), rs.getLong("exchange_id"),
                rs.getString("symbol"), rs.getString("side"), rs.getString("status"),
                rs.getString("entry_client_order_id"), rs.getString("exit_client_order_id"),
                rs.getBigDecimal("margin"), rs.getBigDecimal("quantity"), rs.getBigDecimal("entry_price"),
                rs.getInt("leverage"), rs.getBigDecimal("peak_profit_percent"), rs.getString("close_reason"),
                PriceAlertService.instant(rs.getObject("opened_at")), PriceAlertService.instant(rs.getObject("updated_at")),
                rs.getString("strategy_name"), rs.getBoolean("enabled"), rs.getString("direction"),
                rs.getBigDecimal("profit_target_percent"), rs.getBigDecimal("profit_drawdown_percent"),
                rs.getString("profit_target_type"), rs.getInt("bureau_period"), rs.getInt("max_orders"),
                rs.getInt("max_positions"), rs.getString("exchange"));
    }

    private static boolean filled(FuturesGateway.Order order) {
        return order.executedQty().signum() > 0
                && ("FILLED".equalsIgnoreCase(order.status()) || "PARTIALLY_FILLED".equalsIgnoreCase(order.status()));
    }

    private static boolean terminal(String status) {
        return "REJECTED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status)
                || "CANCELED".equalsIgnoreCase(status);
    }

    private int count(String sql, long strategyId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, strategyId);
        return value == null ? 0 : value;
    }

    private void failed(long id, String error) {
        jdbc.update("update fng_position set status='FAILED',last_error=?,updated_at=? where id=?",
                truncate(error), now(), id);
    }

    private void reopen(long id, String error) {
        jdbc.update("""
                update fng_position set status='OPEN',exit_price=null,exit_client_order_id=null,close_reason=null,last_error=?,updated_at=?
                where id=? and status='CLOSING'
                """, truncate(error), now(), id);
    }

    private void updateError(long id, String error) {
        jdbc.update("update fng_position set last_error=?,updated_at=? where id=?", truncate(error), now(), id);
    }

    private static String sanitized(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    private record ExchangeContext(FuturesGateway gateway, ExchangeCredentials credentials) {}

    private record Row(long id, long strategyId, long userId, long exchangeId, String symbol, String side,
            String status, String entryClientOrderId, String exitClientOrderId, BigDecimal margin,
            BigDecimal quantity, BigDecimal entryPrice, int leverage, BigDecimal peak, String closeReason,
            Instant openedAt, Instant updatedAt, String strategyName, boolean enabled, String direction,
            BigDecimal target, BigDecimal drawdown, String targetType, int cycleDays, int maxOrders,
            int maxPositions, String exchange) {}
}