package com.multind.bitpongo.alerts;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.exchange.ExchangeCredentials;
import com.multind.bitpongo.exchange.ExchangeEntity;
import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.exchange.FuturesGateway;
import com.multind.bitpongo.exchange.FuturesGatewayRegistry;
import com.multind.bitpongo.exchange.ReduceOnlyOrderRejectedException;
import com.multind.bitpongo.notification.AlertNotificationBridge;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * market position, monitors profit against the stop-loss/target/drawdown/cycle rules, and closes it with a
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
                   p.exit_filled_qty,p.exit_filled_quote,p.exit_order_filled_qty,p.exit_order_filled_quote,
                   s.name strategy_name,s.enabled,s.direction,s.profit_target_percent,
                   s.profit_drawdown_percent,s.stop_loss_percent,s.profit_target_type,s.bureau_period,s.max_orders,s.max_positions,
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
        List<Runnable> steps = List.of(this::retryCloseRemainders, this::openPending, this::reconcileOpening,
                this::monitorOpen, this::reconcileClosing);
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
                + "('PENDING_ENTRY','OPENING','OPEN','CLOSING','CLOSE_PENDING','CLOSE_REVIEW')", strategyId);
        if (positions >= maxPositions) return;
        int orders = count("select count(*) from fng_position where strategy_id=? and status in "
                + "('PENDING_ENTRY','OPENING','CLOSING','CLOSE_PENDING','CLOSE_REVIEW')", strategyId);
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
                if (reason != null) close(row, context, row.quantity(), reason);
            } catch (RuntimeException exception) {
                log.warn("F&G 持仓监控失败 positionId={} errorType={}", row.id(), exception.getClass().getSimpleName());
            }
        }
    }

    private String exitReason(Row row, BigDecimal pnl, BigDecimal pnlPercent, BigDecimal peak) {
        if (hitsStopLoss(row.stopLoss(), pnlPercent)) return "STOP_LOSS";
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

    /** Stop loss is a percentage of margin; null or zero means none (strategies created before V40). */
    static boolean hitsStopLoss(BigDecimal stopLossPercent, BigDecimal pnlPercent) {
        return stopLossPercent != null && stopLossPercent.signum() > 0
                && pnlPercent.compareTo(stopLossPercent.negate()) <= 0;
    }

    private void close(Row row, ExchangeContext context, BigDecimal quantity, String reason) {
        String exitClientOrderId = "fngx" + UUID.randomUUID().toString().replace("-", "");
        int claimed = jdbc.update("""
                update fng_position set status='CLOSING',exit_client_order_id=?,close_reason=?,last_error=null,
                    exit_order_filled_qty=0,exit_order_filled_quote=0,updated_at=?
                where id=? and status=? and exit_filled_qty=? and exit_client_order_id <=> ?
                """, exitClientOrderId, reason, now(), row.id(), row.status(), row.exitFilledQty(), row.exitClientOrderId());
        if (claimed == 0) return;
        try {
            String side = "LONG".equals(row.side()) ? "SELL" : "BUY";
            context.gateway().marketOrder(context.credentials(), row.symbol(), side, quantity, exitClientOrderId, true);
        } catch (ReduceOnlyOrderRejectedException exception) {
            updateCloseStatus(row.id(), "CLOSING", exitClientOrderId, "CLOSE_REVIEW",
                    "CLOSE_REJECTED_" + exception.exchangeCode());
        } catch (RuntimeException exception) {
            // A failed request does not prove that Binance did not receive it.
            updateCloseStatus(row.id(), "CLOSING", exitClientOrderId, "CLOSING", "CLOSE_SUBMISSION_UNCONFIRMED");
        }
    }

    private void retryCloseRemainders() {
        for (Row row : rowsWhere("CLOSE_PENDING", "p.updated_at<=?", now().minusSeconds(2), 20)) {
            try {
                ExchangeContext context = context(row);
                BigDecimal remaining = row.quantity().subtract(row.exitFilledQty());
                // Re-query the prior terminal order with the exact credentials used for the retry.
                // A connection can be edited in place; its ID alone does not identify an exchange account.
                Optional<FuturesGateway.Order> prior = context.gateway().findOrder(
                        context.credentials(), row.symbol(), row.exitClientOrderId());
                if (prior.filter(order -> row.exitClientOrderId().equals(order.clientOrderId())
                        && canRetryRemainder(order.status()) && order.executedQty() != null
                        && order.executedQty().compareTo(row.exitOrderFilledQty()) == 0
                        && cumulativeQuote(order) != null
                        && cumulativeQuote(order).compareTo(row.exitOrderFilledQuote()) == 0).isEmpty()) {
                    updateCloseStatus(row.id(), "CLOSE_PENDING", row.exitClientOrderId(),
                            "CLOSE_REVIEW", "CLOSE_PRIOR_ORDER_UNVERIFIED");
                    continue;
                }
                FuturesGateway.Rules rules = context.gateway().rules(row.symbol());
                if (remaining.signum() <= 0 || rules.stepSize().signum() <= 0
                        || remaining.compareTo(rules.minQty()) < 0
                        || (rules.maxQty().signum() > 0 && remaining.compareTo(rules.maxQty()) > 0)
                        || remaining.remainder(rules.stepSize()).signum() != 0) {
                    updateCloseStatus(row.id(), "CLOSE_PENDING", row.exitClientOrderId(),
                            "CLOSE_REVIEW", "CLOSE_MARKET_QUANTITY_INVALID");
                    continue;
                }
                BigDecimal expected = "LONG".equals(row.side()) ? remaining : remaining.negate();
                // In one-way mode the exchange reports the account's net position, not this strategy's.
                if (hasOtherExposure(row) || !context.gateway().oneWayMode(context.credentials())
                        || context.gateway().position(context.credentials(), row.symbol())
                        .filter(position -> position.amount().compareTo(expected) == 0).isEmpty()) {
                    updateCloseStatus(row.id(), "CLOSE_PENDING", row.exitClientOrderId(),
                            "CLOSE_REVIEW", "CLOSE_POSITION_MISMATCH");
                    continue;
                }
                close(row, context, remaining, row.closeReason());
            } catch (RuntimeException exception) {
                updateCloseStatus(row.id(), "CLOSE_PENDING", row.exitClientOrderId(), "CLOSE_PENDING", "CLOSE_RECHECK_FAILED");
            }
        }
    }

    private boolean hasOtherExposure(Row row) {
        Integer others = jdbc.queryForObject("""
                select count(*) from fng_position
                where exchange_id=? and symbol=? and id<>? and status not in ('CLOSED','FAILED')
                """, Integer.class, row.exchangeId(), row.symbol(), row.id());
        return others != null && others > 0;
    }

    private void reconcileClosing() {
        LocalDateTime threshold = now().minusSeconds(2);
        for (Row row : rowsWhere("CLOSING", "p.updated_at<=?", threshold, 20)) {
            try {
                ExchangeContext context = context(row);
                Optional<FuturesGateway.Order> order = context.gateway().findOrder(
                        context.credentials(), row.symbol(), row.exitClientOrderId());
                if (order.isEmpty()) {
                    updateCloseStatus(row.id(), "CLOSING", row.exitClientOrderId(), "CLOSING", "CLOSE_ORDER_NOT_FOUND");
                    continue;
                }
                applyExit(row, order.get());
            } catch (RuntimeException exception) {
                updateCloseStatus(row.id(), "CLOSING", row.exitClientOrderId(), "CLOSING", "CLOSE_LOOKUP_FAILED");
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

    private void applyExit(Row row, FuturesGateway.Order order) {
        BigDecimal executed = order.executedQty();
        BigDecimal orderQuote = cumulativeQuote(order);
        if (!row.exitClientOrderId().equals(order.clientOrderId()) || executed == null
                || executed.compareTo(row.exitOrderFilledQty()) < 0 || orderQuote == null) {
            updateCloseStatus(row.id(), "CLOSING", row.exitClientOrderId(), "CLOSING", "CLOSE_FILL_UNVERIFIED");
            return;
        }
        BigDecimal qty = row.exitFilledQty().subtract(row.exitOrderFilledQty()).add(executed);
        BigDecimal quote = row.exitFilledQuote().subtract(row.exitOrderFilledQuote()).add(orderQuote);
        if (qty.compareTo(row.quantity()) > 0 || orderQuote.compareTo(row.exitOrderFilledQuote()) < 0) {
            updateCloseStatus(row.id(), "CLOSING", row.exitClientOrderId(), "CLOSING", "CLOSE_FILL_UNVERIFIED");
            return;
        }
        String status = "CLOSING";
        String error = null;
        BigDecimal exitPrice = null;
        LocalDateTime closedAt = null;
        if (closeOrderTerminal(order.status())) {
            if ("REJECTED".equalsIgnoreCase(order.status()) && executed.signum() > 0) {
                updateCloseStatus(row.id(), "CLOSING", row.exitClientOrderId(), "CLOSE_REVIEW", "CLOSE_FILL_UNVERIFIED");
                return;
            } else if (qty.signum() > 0 && qty.compareTo(row.quantity()) == 0) {
                status = "CLOSED";
                exitPrice = quote.divide(qty, 18, RoundingMode.HALF_UP);
                closedAt = now();
            } else if (executed.signum() > 0 && canRetryRemainder(order.status())) {
                status = "CLOSE_PENDING";
            } else {
                status = "CLOSE_REVIEW";
                error = executed.signum() == 0 ? "CLOSE_ORDER_NO_FILL" : "CLOSE_FILL_UNVERIFIED";
            }
        }
        int updated = jdbc.update("""
                update fng_position set status=?,exit_price=?,closed_at=?,last_error=?,updated_at=?,
                    exit_filled_qty=?,exit_filled_quote=?,exit_order_filled_qty=?,exit_order_filled_quote=?
                where id=? and status='CLOSING' and exit_client_order_id=?
                    and exit_order_filled_qty=? and exit_order_filled_quote=?
                """, status, exitPrice, closedAt, error, now(), qty, quote, executed, orderQuote,
                row.id(), row.exitClientOrderId(), row.exitOrderFilledQty(), row.exitOrderFilledQuote());
        if (updated > 0 && closedAt != null) notifyExit(row, exitPrice, row.closeReason());
    }

    private static BigDecimal cumulativeQuote(FuturesGateway.Order order) {
        if (order.executedQty() == null) return null;
        if (order.executedQty().signum() == 0) return BigDecimal.ZERO;
        BigDecimal quote = order.cumulativeQuote();
        if (quote == null && order.avgPrice() != null) quote = order.executedQty().multiply(order.avgPrice());
        return quote == null || quote.signum() <= 0 ? null : quote.setScale(30, RoundingMode.HALF_UP);
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
        return jdbc.query(BASE_SELECT + " where p.status=? and " + extraWhere + " order by p.updated_at,p.id limit ?",
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
                rs.getBigDecimal("stop_loss_percent"),
                rs.getString("profit_target_type"), rs.getInt("bureau_period"), rs.getInt("max_orders"),
                rs.getInt("max_positions"), rs.getString("exchange"), rs.getBigDecimal("exit_filled_qty"),
                rs.getBigDecimal("exit_filled_quote"), rs.getBigDecimal("exit_order_filled_qty"),
                rs.getBigDecimal("exit_order_filled_quote"));
    }

    private static boolean filled(FuturesGateway.Order order) {
        return order.executedQty().signum() > 0
                && ("FILLED".equalsIgnoreCase(order.status()) || "PARTIALLY_FILLED".equalsIgnoreCase(order.status()));
    }

    private static boolean terminal(String status) {
        return "REJECTED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status)
                || "CANCELED".equalsIgnoreCase(status);
    }

    private static boolean closeOrderTerminal(String status) {
        return terminal(status) || "FILLED".equalsIgnoreCase(status) || "EXPIRED_IN_MATCH".equalsIgnoreCase(status);
    }

    private static boolean canRetryRemainder(String status) {
        return "EXPIRED".equalsIgnoreCase(status) || "EXPIRED_IN_MATCH".equalsIgnoreCase(status)
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

    private void updateCloseStatus(long id, String expected, String orderId, String status, String error) {
        jdbc.update("""
                update fng_position set status=?,last_error=?,updated_at=?
                where id=? and status=? and exit_client_order_id <=> ?
                """, status, error, now(), id, expected, orderId);
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
            BigDecimal target, BigDecimal drawdown, BigDecimal stopLoss, String targetType, int cycleDays, int maxOrders,
            int maxPositions, String exchange, BigDecimal exitFilledQty, BigDecimal exitFilledQuote,
            BigDecimal exitOrderFilledQty, BigDecimal exitOrderFilledQuote) {}
}
