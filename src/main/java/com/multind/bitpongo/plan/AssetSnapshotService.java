package com.multind.bitpongo.plan;

import com.multind.bitpongo.exchange.*;
import com.multind.bitpongo.common.time.UtcDateTimes;
import com.multind.bitpongo.notification.NotificationDedupeWindow;
import com.multind.bitpongo.notification.NotificationEvent;
import com.multind.bitpongo.notification.NotificationEventType;
import com.multind.bitpongo.notification.NotificationMessageRenderer;
import com.multind.bitpongo.notification.NotificationPublisher;
import com.multind.bitpongo.scheduler.AssetSnapshotUseCase;
import com.multind.bitpongo.strategy.CoinEntity;
import com.multind.bitpongo.strategy.CoinRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AssetSnapshotService implements AssetSnapshotUseCase {
    private static final Logger log = LoggerFactory.getLogger(AssetSnapshotService.class);
    private final PlanRepository plans;
    private final SnapshotRepository snapshots;
    private final ExchangeRepository exchanges;
    private final ExchangeGatewayRegistry gateways;
    private final CoinRepository coins;
    private final PortfolioCalculator calculator;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final NotificationPublisher notifications;

    @Autowired
    public AssetSnapshotService(
            PlanRepository plans, SnapshotRepository snapshots,
            ExchangeRepository exchanges, ExchangeGatewayRegistry gateways,
            CoinRepository coins, PortfolioCalculator calculator,
            ObjectProvider<PlatformTransactionManager> transactionManager,
            NotificationPublisher notifications) {
        this(plans, snapshots, exchanges, gateways, coins, calculator,
                transactionManager.getIfAvailable(), notifications, Clock.systemUTC());
    }

    AssetSnapshotService(
            PlanRepository plans, SnapshotRepository snapshots,
            ExchangeRepository exchanges, ExchangeGatewayRegistry gateways,
            CoinRepository coins, PortfolioCalculator calculator,
            PlatformTransactionManager transactionManager,
            NotificationPublisher notifications, Clock clock) {
        this.plans = plans; this.snapshots = snapshots; this.exchanges = exchanges;
        this.gateways = gateways; this.coins = coins; this.calculator = calculator; this.clock = clock;
        this.notifications = notifications;
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (this.transactions != null) this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void captureAll() {
        plans.findByStatus("active").forEach(this::captureSafely);
    }

    @Override
    public void capture(long planId) {
        plans.findById(planId)
                .filter(plan -> "active".equals(plan.getStatus()))
                .ifPresent(this::captureSafely);
    }

    private void captureSafely(PlanEntity plan) {
        try {
            ExchangeEntity exchange = exchanges.findByIdAndUserId(plan.getExchangeId(), plan.getUserId())
                    .orElseThrow(() -> new IllegalStateException("交易所不存在"));
            ExchangeGateway gateway = gateways.require(exchange.getExchange());
            var positions = coins.findByPlanIdAndUserId(plan.getId(), plan.getUserId()).stream()
                    .map(coin -> new PortfolioCalculator.Position(
                            coin.getTotalAmount(), gateway.latestPrice(marketSymbol(coin))))
                    .toList();
            BigDecimal returnValue = calculator.revenue(
                    calculator.value(positions), plan.getTotalFunds());
            SnapshotEntity snapshot = new SnapshotEntity();
            snapshot.setValue(returnValue.toPlainString());
            snapshot.setType("return");
            snapshot.setPlanId(plan.getId());
            snapshot.setUserId(plan.getUserId());
            snapshot.setCreatedAt(UtcDateTimes.toDatabase(clock.instant()));
            if (transactions == null) snapshots.save(snapshot);
            else transactions.executeWithoutResult(status -> snapshots.save(snapshot));
        } catch (RuntimeException exception) {
            log.error("资产快照失败，planId={}", plan.getId(), exception);
            publishFailure(plan, exception);
        }
    }

    private static String marketSymbol(CoinEntity coin) {
        String symbol = coin.getSymbol() == null ? "" : coin.getSymbol().toUpperCase();
        return symbol.endsWith("USDT") ? symbol.replace("/", "") : symbol + "USDT";
    }

    private void publishFailure(PlanEntity plan, RuntimeException failure) {
        Instant occurredAt = clock.instant();
        NotificationEvent event = new NotificationEvent(
                NotificationEventType.ASSET_SNAPSHOT_FAILED,
                plan.getUserId(),
                plan.getId(),
                null,
                occurredAt,
                "asset-snapshot-failed:" + plan.getId(),
                Map.of(
                        "status", "ASSET_SNAPSHOT_FAILED",
                        "errorSummary", NotificationMessageRenderer.sanitizeError(
                                failure.getMessage() == null
                                        ? failure.getClass().getSimpleName()
                                        : failure.getMessage())),
                null,
                new NotificationDedupeWindow(
                        "asset-snapshot-failed:" + plan.getId(), Duration.ofMinutes(30)));
        try {
            notifications.publish(event);
        } catch (RuntimeException notificationFailure) {
            log.warn("资产快照失败通知发布失败 planId={} errorType={}",
                    plan.getId(), notificationFailure.getClass().getSimpleName());
        }
    }
}
