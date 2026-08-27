package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.exchange.*;
import com.multind.bitpongo.notification.NotificationEvent;
import com.multind.bitpongo.notification.NotificationEventType;
import com.multind.bitpongo.notification.NotificationPublisher;
import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.common.time.UtcDateTimes;
import com.multind.bitpongo.plan.PlanRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OrderReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);
    private static final List<String> RECOVERABLE = List.of(
            "READY", "PENDING_RECONCILIATION", "SUBMITTING", "RECONCILING");
    private static final Set<String> OPEN_STATUSES = Set.of("NEW", "PENDING_NEW", "PARTIALLY_FILLED");
    private static final Set<String> TERMINAL_STATUSES = Set.of("CANCELED", "EXPIRED", "REJECTED");
    private final OrderIntentRepository intents;
    private final PlanRepository plans;
    private final ExchangeRepository exchanges;
    private final ExchangeGatewayRegistry gateways;
    private final OrderPersistenceService persistence;
    private final Clock clock;
    private final Duration staleAge;
    private final int maxAttempts;
    private final NotificationPublisher notifications;

    @Autowired
    public OrderReconciliationService(
            OrderIntentRepository intents, PlanRepository plans,
            ExchangeRepository exchanges, ExchangeGatewayRegistry gateways,
            OrderPersistenceService persistence,
            NotificationPublisher notifications,
            @Value("${zhitoubao.orders.reconciliation-stale-age:PT30S}") Duration staleAge,
            @Value("${zhitoubao.orders.reconciliation-max-attempts:20}") int maxAttempts) {
        this(intents, plans, exchanges, gateways, persistence, notifications,
                Clock.systemUTC(), staleAge, maxAttempts);
    }

    OrderReconciliationService(
            OrderIntentRepository intents, PlanRepository plans,
            ExchangeRepository exchanges, ExchangeGatewayRegistry gateways,
            OrderPersistenceService persistence,
            NotificationPublisher notifications,
            Clock clock, Duration staleAge, int maxAttempts) {
        this.intents = intents; this.plans = plans; this.exchanges = exchanges;
        this.gateways = gateways; this.persistence = persistence;
        this.notifications = notifications;
        this.clock = clock; this.staleAge = staleAge; this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${zhitoubao.orders.reconciliation-delay:60s}")
    public void reconcilePending() {
        LocalDateTime now = UtcDateTimes.toDatabase(clock.instant())
                .truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime cutoff = now.minus(staleAge);
        intents.findByStatusInAndUpdatedAtBeforeOrderByCreatedAtAsc(RECOVERABLE, cutoff).forEach(intent -> {
            boolean neverSubmitted = "READY".equals(intent.getStatus());
            if (intents.acquireForReconciliation(intent.getId(), RECOVERABLE, cutoff, now) != 1) return;
            try {
                plans.findById(intent.getPlanId()).flatMap(plan ->
                        exchanges.findByIdAndUserId(plan.getExchangeId(), intent.getUserId()))
                        .ifPresentOrElse(exchange -> reconcile(intent, exchange, now, neverSubmitted),
                                () -> mark(intent, "MANUAL_REVIEW", now));
            } catch (RuntimeException exception) {
                String status = exception instanceof BusinessException business && business.getCode() == 401
                        ? "MANUAL_REVIEW" : nextRetryStatus(intent);
                mark(intent, status, now);
                log.error("订单对账失败，intentId={}", intent.getId(), exception);
            }
        });
    }

    private void reconcile(
            OrderIntentEntity intent, ExchangeEntity exchange,
            LocalDateTime leaseAcquiredAt, boolean neverSubmitted) {
        ExchangeGateway gateway = gateways.require(exchange.getExchange());
        ExchangeCredentials credentials = new ExchangeCredentials(
                exchange.getAccessKey(), exchange.getSecretKey(), exchange.getPassword());
        Optional<OrderResult> result = gateway.findOrder(credentials, intent.getSymbol(), intent.getClientOrderId());
        result.ifPresentOrElse(value -> applyResult(intent, value, leaseAcquiredAt), () -> {
            if (neverSubmitted) {
                mark(intent, "MANUAL_REVIEW", leaseAcquiredAt);
            } else {
                requeueOrEscalate(intent, leaseAcquiredAt);
            }
        });
    }

    private void applyResult(
            OrderIntentEntity intent, OrderResult result, LocalDateTime leaseAcquiredAt) {
        String status = result.status() == null ? "" : result.status().toUpperCase(Locale.ROOT);
        if ("FILLED".equals(status)) {
            if (result.quantity().signum() <= 0) {
                mark(intent, "MANUAL_REVIEW", leaseAcquiredAt);
            } else {
                persistence.confirmAfterReconciliation(intent, result, leaseAcquiredAt);
            }
            return;
        }
        if (OPEN_STATUSES.contains(status)) {
            requeueOrEscalate(intent, leaseAcquiredAt);
            return;
        }
        if (TERMINAL_STATUSES.contains(status)) {
            if (result.quantity().signum() > 0) {
                persistence.confirmAfterReconciliation(intent, result, leaseAcquiredAt);
            } else {
                mark(intent, status, leaseAcquiredAt);
            }
            return;
        }
        requeueOrEscalate(intent, leaseAcquiredAt);
    }

    private void requeueOrEscalate(OrderIntentEntity intent, LocalDateTime leaseAcquiredAt) {
        mark(intent, nextRetryStatus(intent), leaseAcquiredAt);
    }

    private boolean mark(
            OrderIntentEntity intent,
            String status,
            LocalDateTime leaseAcquiredAt) {
        boolean changed = persistence.markAfterReconciliation(intent, status, leaseAcquiredAt);
        if (changed && "MANUAL_REVIEW".equals(status)) {
            publishManualReview(intent);
        }
        return changed;
    }

    private void publishManualReview(OrderIntentEntity intent) {
        NotificationEvent event = new NotificationEvent(
                NotificationEventType.ORDER_MANUAL_REVIEW,
                intent.getUserId(),
                intent.getPlanId(),
                intent.getId(),
                clock.instant(),
                "order-manual-review:" + intent.getId(),
                Map.of("symbol", intent.getSymbol(), "status", "MANUAL_REVIEW"));
        try {
            notifications.publish(event);
        } catch (RuntimeException notificationFailure) {
            log.warn("人工对账通知发布失败 intentId={} errorType={}",
                    intent.getId(), notificationFailure.getClass().getSimpleName());
        }
    }

    private String nextRetryStatus(OrderIntentEntity intent) {
        int attempts = intent.getAttempts() == null ? 0 : intent.getAttempts();
        return attempts >= maxAttempts ? "MANUAL_REVIEW" : "PENDING_RECONCILIATION";
    }
}
