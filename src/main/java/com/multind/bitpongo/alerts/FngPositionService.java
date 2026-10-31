package com.multind.bitpongo.alerts;

import com.multind.bitpongo.common.api.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only views over positions created by the F&amp;G futures execution engine. */
@Service
@Profile("!test")
public class FngPositionService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String POSITION_SELECT = """
            select id,symbol,side,status,quantity,margin,entry_price,exit_price,leverage,
                   close_reason,opened_at,closed_at,updated_at,last_error
            from fng_position where strategy_id=? and user_id=?
            """;

    private final JdbcTemplate jdbc;

    public FngPositionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public FngStrategyDtos.PositionPage positions(long userId, long strategyId, int page, int size) {
        owned(userId, strategyId);
        bounds(page, size);
        long total = count("select count(*) from fng_position where strategy_id=? and user_id=?", userId, strategyId);
        List<FngStrategyDtos.Position> items = jdbc.query(
                POSITION_SELECT + " order by id desc limit ? offset ?",
                (rs, index) -> position(rs), strategyId, userId, size, (long) page * size);
        return new FngStrategyDtos.PositionPage(items, page, size, total, ((long) page + 1) * size < total);
    }

    @Transactional(readOnly = true)
    public FngStrategyDtos.ExecutionPage executions(long userId, long strategyId, int page, int size) {
        owned(userId, strategyId);
        bounds(page, size);
        List<PositionRow> positions = jdbc.query(POSITION_SELECT + " order by id desc limit 1000",
                (rs, index) -> positionRow(rs), strategyId, userId);
        List<FngStrategyDtos.Execution> events = new ArrayList<>();
        for (PositionRow row : positions) {
            events.add(new FngStrategyDtos.Execution(row.id(), row.id(), "ENTRY", row.status(), row.symbol(),
                    row.side(), plain(row.entryPrice()), plain(row.quantity()), null,
                    row.openedAt()));
            if (row.closedAt() != null) {
                events.add(new FngStrategyDtos.Execution(row.id(), row.id(), "EXIT", "CLOSED", row.symbol(),
                        row.side(), plain(row.exitPrice()), plain(row.quantity()), row.closeReason(),
                        row.closedAt()));
            }
            if ("FAILED".equalsIgnoreCase(row.status()) && row.lastError() != null) {
                events.add(new FngStrategyDtos.Execution(row.id(), row.id(), "FAILED", "FAILED", row.symbol(),
                        row.side(), null, null, row.lastError(), row.updatedAt()));
            }
        }
        events.sort(Comparator.comparing(FngStrategyDtos.Execution::occurredAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        long total = events.size();
        int from = Math.min(page * size, events.size());
        int to = Math.min(from + size, events.size());
        return new FngStrategyDtos.ExecutionPage(events.subList(from, to), page, size, total, to < total);
    }

    @Transactional(readOnly = true)
    public FngStrategyDtos.Performance performance(long userId, long strategyId) {
        owned(userId, strategyId);
        List<PositionRow> positions = jdbc.query(POSITION_SELECT + " order by id desc limit 1000",
                (rs, index) -> positionRow(rs), strategyId, userId);
        BigDecimal totalMargin = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        int closed = 0;
        int wins = 0;
        int open = 0;
        List<FngStrategyDtos.Point> curve = new ArrayList<>();
        List<PositionRow> closedRows = positions.stream()
                .filter(row -> row.closedAt() != null && row.exitPrice() != null)
                .sorted(Comparator.comparing(PositionRow::closedAt))
                .toList();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (PositionRow row : closedRows) {
            BigDecimal pnl = pnl(row);
            realized = realized.add(pnl);
            cumulative = cumulative.add(pnl);
            closed++;
            if (pnl.signum() > 0) wins++;
            curve.add(new FngStrategyDtos.Point(row.closedAt(), plain(cumulative)));
        }
        for (PositionRow row : positions) {
            totalMargin = totalMargin.add(row.margin());
            if (isOpen(row.status())) open++;
        }
        String winRate = closed == 0 ? "0" : BigDecimal.valueOf(wins)
                .multiply(HUNDRED).divide(BigDecimal.valueOf(closed), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        return new FngStrategyDtos.Performance(plain(totalMargin), plain(realized), winRate,
                positions.size(), open, closed, curve);
    }

    private FngStrategyDtos.Position position(java.sql.ResultSet rs) throws java.sql.SQLException {
        PositionRow row = positionRow(rs);
        BigDecimal pnl = row.closedAt() == null || row.exitPrice() == null ? null : pnl(row);
        String pnlPercent = pnl == null || row.margin().signum() == 0 ? null
                : plain(pnl.multiply(HUNDRED).divide(row.margin(), 8, RoundingMode.HALF_UP));
        return new FngStrategyDtos.Position(row.id(), row.symbol(), row.side(), row.status(),
                plain(row.quantity()), plain(row.margin()), plain(row.entryPrice()), plain(row.exitPrice()),
                plain(pnl), pnlPercent, row.leverage(), row.closeReason(),
                row.openedAt(), row.closedAt());
    }

    private static PositionRow positionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PositionRow(rs.getLong("id"), rs.getString("symbol"), rs.getString("side"),
                rs.getString("status"), rs.getBigDecimal("quantity"), rs.getBigDecimal("margin"),
                rs.getBigDecimal("entry_price"), rs.getBigDecimal("exit_price"), rs.getInt("leverage"),
                rs.getString("close_reason"), PriceAlertService.instant(rs.getObject("opened_at")),
                PriceAlertService.instant(rs.getObject("closed_at")),
                PriceAlertService.instant(rs.getObject("updated_at")), rs.getString("last_error"));
    }

    private static BigDecimal pnl(PositionRow row) {
        if (row.entryPrice() == null || row.exitPrice() == null) return BigDecimal.ZERO;
        BigDecimal diff = "LONG".equalsIgnoreCase(row.side())
                ? row.exitPrice().subtract(row.entryPrice())
                : row.entryPrice().subtract(row.exitPrice());
        return diff.multiply(row.quantity());
    }

    private static boolean isOpen(String status) {
        return "PENDING_ENTRY".equals(status) || "OPENING".equals(status)
                || "OPEN".equals(status) || "CLOSING".equals(status);
    }

    private void owned(long userId, long strategyId) {
        if (jdbc.queryForList("select id from fng_strategy where user_id=? and id=?", Integer.class, userId, strategyId)
                .isEmpty()) throw new BusinessException(404, "F&G 策略不存在");
    }

    private long count(String sql, long userId, long strategyId) {
        Long value = jdbc.queryForObject(sql, Long.class, strategyId, userId);
        return value == null ? 0 : value;
    }

    private static void bounds(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 50) throw new BusinessException(400, "分页参数无效");
    }

    private static String plain(BigDecimal value) {
        if (value == null) return null;
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private record PositionRow(long id, String symbol, String side, String status, BigDecimal quantity,
            BigDecimal margin, BigDecimal entryPrice, BigDecimal exitPrice, int leverage, String closeReason,
            Instant openedAt, Instant closedAt, Instant updatedAt, String lastError) {}
}
