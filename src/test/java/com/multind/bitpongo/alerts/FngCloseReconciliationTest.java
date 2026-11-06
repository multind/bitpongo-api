package com.multind.bitpongo.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.multind.bitpongo.exchange.ExchangeEntity;
import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.exchange.AmbiguousOrderException;
import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.exchange.FuturesGateway;
import com.multind.bitpongo.exchange.FuturesGatewayRegistry;
import com.multind.bitpongo.exchange.ReduceOnlyOrderRejectedException;
import com.multind.bitpongo.notification.AlertNotificationBridge;
import java.math.BigDecimal;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class FngCloseReconciliationTest {
    @Container static final MySQLContainer mysql = new MySQLContainer(
            System.getProperty("test.mysql.image", "mysql:9.7.0"));
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private FuturesGateway gateway;
    private AlertNotificationBridge notifications;
    private FngExecutionService execution;
    private long strategyId;

    @BeforeAll
    static void migrate() {
        var datasource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(datasource).load().migrate();
        jdbc = new JdbcTemplate(datasource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        jdbc.update("insert into user (id,name,email,password) values (901,'Audit','audit@example.test','test')");
        jdbc.update("insert into exchange (id,name,exchange,user_id) values (901,'Fake','binance',901)");
    }

    @BeforeEach
    void seed() {
        jdbc.update("delete from fng_strategy where user_id=901");
        jdbc.update("delete from alert_event where user_id=901");
        gateway = mock(FuturesGateway.class);
        notifications = mock(AlertNotificationBridge.class);
        var repository = mock(ExchangeRepository.class);
        var exchange = new ExchangeEntity();
        exchange.setExchange("binance");
        exchange.setAccessKey("fake-key");
        exchange.setSecretKey("fake-secret");
        when(repository.findByIdAndUserId(901L, 901L)).thenReturn(Optional.of(exchange));
        execution = new FngExecutionService(jdbc, repository, new FuturesGatewayRegistry(gateway), notifications, true);
        strategyId = new FngStrategyService(jdbc, execution).create(901, new FngStrategyDtos.Create(
                "Audit", null, 901L, "BTCUSDT", 75, 5, 25, 5, d("100"), d("10"), 2, 7,
                "LONG", d("10"), d("5"), d("20"), "PERCENT", 2, 25));
        jdbc.update("""
                insert into fng_position(strategy_id,user_id,exchange_id,symbol,side,status,entry_client_order_id,
                  exit_client_order_id,margin,quantity,entry_price,leverage,opened_at,updated_at,close_reason)
                values(?,901,901,'BTCUSDT','LONG','CLOSING','entry','exit',100,1,100,2,
                  utc_timestamp()-interval 1 hour,utc_timestamp()-interval 5 minute,'STOP_LOSS')
                """, strategyId);
        when(gateway.oneWayMode(any())).thenReturn(true);
    }

    @Test
    void repeatedPartialFillDoesNotCloseOrCountTwice() {
        report("exit", "PARTIALLY_FILLED", "0.4", "100");
        tick();
        tick();
        assertThat(field("status")).isEqualTo("CLOSING");
        assertThat(number("exit_filled_qty")).isEqualByComparingTo("0.4");
        assertThat(field("exit_client_order_id")).isEqualTo("exit");
        verifyNoInteractions(notifications);
        verify(gateway, never()).marketOrder(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void terminalPartialFillRetriesOnlyRemainingQuantityAndWeightsFillPrices() {
        report("exit", "EXPIRED_IN_MATCH", "0.4", "100");
        tick();
        assertThat(field("status")).isEqualTo("CLOSE_PENDING");
        residual("0.6", "0.1");
        tick();
        String nextId = field("exit_client_order_id");
        assertThat(nextId).isNotBlank().isNotEqualTo("exit");
        verify(gateway).marketOrder(any(), eq("BTCUSDT"), eq("SELL"),
                argThat(q -> q.compareTo(d("0.6")) == 0), eq(nextId), eq(true));
        when(gateway.findOrder(any(), eq("BTCUSDT"), eq(nextId))).thenReturn(Optional.of(
                new FuturesGateway.Order(nextId, 2, "FILLED", d("0.6"), d("109.99"), d("66"))));
        tick();
        tick();
        assertThat(field("status")).isEqualTo("CLOSED");
        assertThat(number("exit_price")).isEqualByComparingTo("106");
        verify(notifications).strategy(eq(901L), anyString(), anyString(), any());
    }

    @Test
    void residualBelowMinimumRemainsVisibleWithoutAnotherOrder() {
        report("exit", "EXPIRED", "0.95", "100");
        tick();
        residual("0.05", "0.1");
        tick();
        tick();
        assertThat(field("status")).isEqualTo("CLOSE_REVIEW");
        assertThat(field("last_error")).isEqualTo("CLOSE_MARKET_QUANTITY_INVALID");
        assertThat(number("exit_filled_qty")).isEqualByComparingTo("0.95");
        var positions = new FngPositionService(jdbc);
        assertThat(positions.positions(901, strategyId, 0, 20).items().getFirst().remainingQuantity())
                .isEqualTo("0.05");
        verify(gateway, never()).marketOrder(any(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(notifications);
    }

    @Test
    void missingOrderKeepsOriginalIdEvenAfterTimeout() {
        when(gateway.findOrder(any(), eq("BTCUSDT"), eq("exit"))).thenReturn(Optional.empty());
        tick();
        assertThat(field("status")).isEqualTo("CLOSING");
        assertThat(field("exit_client_order_id")).isEqualTo("exit");
        report("exit", "FILLED", "1", "102");
        tick();
        assertThat(field("status")).isEqualTo("CLOSED");
    }

    @Test
    void staleReportDoesNotReleaseTheOrderOrOverwriteItsFill() {
        report("exit", "PARTIALLY_FILLED", "0.4", "100");
        tick();
        report("exit", "EXPIRED", "0.2", "100");
        tick();
        assertThat(field("status")).isEqualTo("CLOSING");
        assertThat(number("exit_filled_qty")).isEqualByComparingTo("0.4");
        report("exit", "FILLED", "1", "103");
        tick();
        assertThat(field("status")).isEqualTo("CLOSED");
        assertThat(number("exit_price")).isEqualByComparingTo("103");
    }

    @Test
    void netPositionMismatchDoesNotSendAnOrder() {
        report("exit", "CANCELED", "0.4", "100");
        tick();
        residual("0.7", "0.1");
        tick();
        assertThat(field("status")).isEqualTo("CLOSE_REVIEW");
        assertThat(field("last_error")).isEqualTo("CLOSE_POSITION_MISMATCH");
        verify(gateway, never()).marketOrder(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void definitiveResidualRejectionDoesNotResubmit() {
        report("exit", "EXPIRED", "0.4", "100");
        tick();
        residual("0.6", "0.1");
        when(gateway.marketOrder(any(), any(), any(), any(), any(), eq(true)))
                .thenThrow(new ReduceOnlyOrderRejectedException(-4023));
        tick();
        tick();
        assertThat(field("status")).isEqualTo("CLOSE_REVIEW");
        assertThat(field("last_error")).isEqualTo("CLOSE_REJECTED_-4023");
        verify(gateway, times(1)).marketOrder(any(), any(), any(), any(), any(), eq(true));
    }

    @Test
    void previousOrderMustStillBeVisibleOnTheRetryAccount() {
        report("exit", "EXPIRED", "0.4", "100");
        tick();
        residual("0.6", "0.1");
        when(gateway.findOrder(any(), eq("BTCUSDT"), eq("exit"))).thenReturn(Optional.empty());
        tick();
        assertThat(field("status")).isEqualTo("CLOSE_REVIEW");
        assertThat(field("last_error")).isEqualTo("CLOSE_PRIOR_ORDER_UNVERIFIED");
        verify(gateway, never()).marketOrder(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void contradictoryFilledReportNeverCreatesAnotherOrder() {
        report("exit", "FILLED", "0.4", "100");
        tick();
        residual("0.6", "0.1");
        tick();
        assertThat(field("status")).isEqualTo("CLOSE_REVIEW");
        verify(gateway, never()).marketOrder(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void ambiguousSubmissionIsReconciledUsingTheSameOrderId() {
        report("exit", "EXPIRED", "0.4", "100");
        tick();
        residual("0.6", "0.1");
        when(gateway.marketOrder(any(), any(), any(), any(), any(), eq(true)))
                .thenThrow(new AmbiguousOrderException("timeout", new java.net.SocketTimeoutException()));
        tick();
        String submittedId = field("exit_client_order_id");
        assertThat(field("status")).isEqualTo("CLOSING");
        tick();
        assertThat(field("exit_client_order_id")).isEqualTo(submittedId);
        report(submittedId, "FILLED", "0.6", "110");
        tick();
        assertThat(field("status")).isEqualTo("CLOSED");
        verify(gateway, times(1)).marketOrder(any(), any(), any(), any(), any(), eq(true));
    }

    private void tick() {
        jdbc.update("update fng_position set updated_at=utc_timestamp()-interval 5 minute where strategy_id=?", strategyId);
        execution.process();
    }

    @Test
    void openingSurvivesAnOldNewReportAndUsesLaterActualFill() {
        jdbc.update("update fng_position set status='OPENING', entry_price=null where strategy_id=?", strategyId);
        when(gateway.findOrder(any(), eq("BTCUSDT"), eq("entry")))
                .thenReturn(Optional.of(new FuturesGateway.Order("entry", 1, "NEW", d("0"), d("0"))))
                .thenReturn(Optional.of(new FuturesGateway.Order("entry", 1, "FILLED", d("1"), d("103"))));
        tick();
        assertThat(field("status")).isEqualTo("OPENING");
        tick();
        assertThat(field("status")).isEqualTo("OPEN");
        assertThat(number("entry_price")).isEqualByComparingTo("103");
        verify(notifications, times(1)).strategy(eq(901L), anyString(), anyString(), any());
    }

    @Test
    void expiredEntryWithRealPartialFillIsAnOpenPosition() {
        jdbc.update("update fng_position set status='OPENING', entry_price=null where strategy_id=?", strategyId);
        report("entry", "EXPIRED", "0.4", "101");
        tick();
        assertThat(field("status")).isEqualTo("OPEN");
        assertThat(number("quantity")).isEqualByComparingTo("0.4");
    }

    @Test
    void missingEntryOrderIsNotDiscardedOrSubmittedAgain() {
        jdbc.update("update fng_position set status='OPENING', entry_price=null where strategy_id=?", strategyId);
        tick();
        tick();
        assertThat(field("status")).isEqualTo("OPENING");
        verify(gateway, never()).marketOrder(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void oldestTwentyBrokenLookupsCannotStarveTheNextPosition() {
        jdbc.update("update fng_position set status='OPEN', symbol='ETHUSDT' where strategy_id=?", strategyId);
        for (int i = 1; i <= 20; i++) {
            jdbc.update("""
                    insert into fng_position(strategy_id,user_id,exchange_id,symbol,side,status,
                      entry_client_order_id,margin,quantity,entry_price,leverage,opened_at,updated_at)
                    values(?,901,901,'BTCUSDT','LONG','OPEN',?,100,1,100,2,
                      utc_timestamp()-interval 1 hour,utc_timestamp()-interval 1 day)
                    """, strategyId, "broken-" + i);
        }
        // Place the healthy row last in both primary-key and last-check order.
        jdbc.update("update fng_position set id=id+1000 where strategy_id=? and symbol='ETHUSDT'", strategyId);
        when(gateway.markPrice("BTCUSDT")).thenThrow(new IllegalStateException("unavailable"));
        when(gateway.markPrice("ETHUSDT")).thenReturn(d("75"));
        execution.process();
        execution.process();
        assertThat(jdbc.queryForObject("select status from fng_position where entry_client_order_id='entry'", String.class))
                .isEqualTo("CLOSING");
    }

    @Test
    void deletionRejectsUnresolvedPositionAndKeepsItsRecord() {
        var service = new FngStrategyService(jdbc, execution);
        assertThatThrownBy(() -> delete(service)).isInstanceOf(BusinessException.class);
        assertThat(field("status")).isEqualTo("CLOSING");
    }

    @Test
    void deletionArchivesClosedStrategyAndPreservesHistory() {
        jdbc.update("update fng_position set status='CLOSED',closed_at=utc_timestamp(),exit_price=102 where strategy_id=?",
                strategyId);
        var service = new FngStrategyService(jdbc, execution);
        delete(service);
        assertThat(service.list(901)).isEmpty();
        assertThat(service.page(901, 0, 20).total()).isZero();
        assertThat(service.archive(901, 0, 20).items()).extracting(FngStrategyDtos.View::id).containsExactly(strategyId);
        assertThat(service.detail(901, strategyId).enabled()).isFalse();
        assertThat(new FngPositionService(jdbc).positions(901, strategyId, 0, 20).total()).isEqualTo(1);
        assertThatThrownBy(() -> service.enabled(901, strategyId, true)).isInstanceOf(BusinessException.class);
    }

    @Test
    void deletionBlocksLegacyFailedRowsWithUnverifiedExposure() {
        jdbc.update("update fng_position set status='FAILED',last_error='ORDER_TIMEOUT' where strategy_id=?", strategyId);
        var service = new FngStrategyService(jdbc, execution);
        assertThatThrownBy(() -> delete(service)).isInstanceOf(BusinessException.class);
    }

    @Test
    void terminalZeroFillAllowsDeletionWithoutCreatingHistory() {
        jdbc.update("update fng_position set status='OPENING', entry_price=null where strategy_id=?", strategyId);
        report("entry", "CANCELED", "0", "0");
        tick();
        assertThat(field("status")).isEqualTo("FAILED");
        assertThat(number("quantity")).isEqualByComparingTo("0");
        var service = new FngStrategyService(jdbc, execution);
        delete(service);
        assertThat(service.archive(901, 0, 20).total()).isZero();
        assertThatThrownBy(() -> service.detail(901, strategyId)).isInstanceOf(BusinessException.class);
    }

    @Test
    void aLivePartialEntryKeepsItsOriginalOrderUntilFinalQuantityIsKnown() {
        jdbc.update("update fng_position set status='OPENING', entry_price=null where strategy_id=?", strategyId);
        report("entry", "PARTIALLY_FILLED", "0.4", "101");
        tick();
        assertThat(field("status")).isEqualTo("OPENING");
        verifyNoInteractions(notifications);
        report("entry", "FILLED", "1", "104");
        tick();
        assertThat(field("status")).isEqualTo("OPEN");
        assertThat(number("quantity")).isEqualByComparingTo("1");
    }

    @Test
    void terminalZeroCannotOverridePreviouslyObservedPartialEntry() {
        jdbc.update("update fng_position set status='OPENING', entry_price=null where strategy_id=?", strategyId);
        report("entry", "PARTIALLY_FILLED", "0.4", "101");
        tick();
        report("entry", "CANCELED", "0", "0");
        tick();
        assertThat(field("status")).isEqualTo("OPENING");
        assertThatThrownBy(() -> delete(new FngStrategyService(jdbc, execution))).isInstanceOf(BusinessException.class);
    }

    @Test
    void positiveQuoteWithZeroExecutedQuantityIsNotDefinitive() {
        jdbc.update("update fng_position set status='OPENING', entry_price=null where strategy_id=?", strategyId);
        when(gateway.findOrder(any(), eq("BTCUSDT"), eq("entry"))).thenReturn(Optional.of(
                new FuturesGateway.Order("entry", 1, "EXPIRED", BigDecimal.ZERO, BigDecimal.ZERO, d("40.4"))));
        tick();
        assertThat(field("status")).isEqualTo("OPENING");
    }

    private void delete(FngStrategyService service) {
        transactions.executeWithoutResult(status -> service.delete(901, strategyId));
    }

    private void report(String id, String status, String qty, String price) {
        when(gateway.findOrder(any(), eq("BTCUSDT"), eq(id)))
                .thenReturn(Optional.of(new FuturesGateway.Order(id, 1, status, d(qty), d(price))));
    }

    private void residual(String amount, String minimum) {
        when(gateway.position(any(), eq("BTCUSDT"))).thenReturn(Optional.of(new FuturesGateway.Position(
                "BTCUSDT", d(amount), d("100"), d("100"), BigDecimal.ZERO)));
        when(gateway.rules("BTCUSDT")).thenReturn(new FuturesGateway.Rules(
                d("1000"), d(minimum), d(minimum), d("1000"), 2, 3));
    }

    private String field(String name) {
        return jdbc.queryForObject("select " + name + " from fng_position where strategy_id=?", String.class, strategyId);
    }

    private BigDecimal number(String name) {
        return jdbc.queryForObject("select " + name + " from fng_position where strategy_id=?", BigDecimal.class, strategyId);
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }
}
