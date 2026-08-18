package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.exchange.*;
import com.multind.bitpongo.plan.PlanEntity;
import com.multind.bitpongo.plan.PlanRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderReconciliationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T00:01:00Z");

    @Test
    void ambiguousOrderIsFoundAndPersistedWithoutSecondMarketBuy() {
        OrderIntentRepository intents = mock(OrderIntentRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        ExchangeRepository exchanges = mock(ExchangeRepository.class);
        ExchangeGatewayRegistry gateways = mock(ExchangeGatewayRegistry.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        OrderPersistenceService persistence = mock(OrderPersistenceService.class);
        OrderIntentEntity intent = new OrderIntentEntity(); intent.setId(1L); intent.setPlanId(42L);
        intent.setUserId(7L); intent.setSymbol("BTCUSDT"); intent.setClientOrderId("client-1");
        intent.setStatus("PENDING_RECONCILIATION");
        intent.setAttempts(1); intent.setUpdatedAt(LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        PlanEntity plan = new PlanEntity(); plan.setId(42L); plan.setExchangeId(3L);
        ExchangeEntity exchange = exchange();
        exchange.setAccessKey("access"); exchange.setSecretKey("secret");
        OrderResult result = new OrderResult("BTCUSDT", "99", "client-1", "FILLED",
                new BigDecimal("0.1"), new BigDecimal("10"), new BigDecimal("100"),
                Map.of());
        when(intents.findByStatusInAndUpdatedAtBeforeOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(intent));
        when(intents.acquireForReconciliation(eq(1L), any(), any(), any())).thenReturn(1);
        when(plans.findById(42L)).thenReturn(Optional.of(plan));
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(exchange));
        when(gateways.require("binance")).thenReturn(gateway);
        when(gateway.findOrder(any(), eq("BTCUSDT"), eq("client-1"))).thenReturn(Optional.of(result));

        service(intents, plans, exchanges, gateways, persistence).reconcilePending();

        verify(persistence).confirmAfterReconciliation(intent, result,
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(gateway, never()).marketBuy(any(), any(), any(), any());
    }

    @Test
    void staleSubmittingIntentIsRecoveredAfterCrash() {
        OrderIntentRepository intents = mock(OrderIntentRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        ExchangeRepository exchanges = mock(ExchangeRepository.class);
        ExchangeGatewayRegistry gateways = mock(ExchangeGatewayRegistry.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        OrderPersistenceService persistence = mock(OrderPersistenceService.class);
        OrderIntentEntity intent = intent("SUBMITTING", 1);
        PlanEntity plan = new PlanEntity(); plan.setExchangeId(3L);
        ExchangeEntity exchange = exchange();
        when(intents.findByStatusInAndUpdatedAtBeforeOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(intent));
        when(intents.acquireForReconciliation(eq(1L), any(), any(), any())).thenReturn(1);
        when(plans.findById(42L)).thenReturn(Optional.of(plan));
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(exchange));
        when(gateways.require("binance")).thenReturn(gateway);
        when(gateway.findOrder(any(), any(), any())).thenReturn(Optional.empty());

        service(intents, plans, exchanges, gateways, persistence).reconcilePending();

        verify(persistence).markAfterReconciliation(intent, "PENDING_RECONCILIATION",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void staleReadyIntentIsEscalatedInsteadOfBeingSilentlyOrphaned() {
        OrderIntentRepository intents = mock(OrderIntentRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        ExchangeRepository exchanges = mock(ExchangeRepository.class);
        ExchangeGatewayRegistry gateways = mock(ExchangeGatewayRegistry.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        OrderPersistenceService persistence = mock(OrderPersistenceService.class);
        OrderIntentEntity intent = intent("READY", 0);
        PlanEntity plan = new PlanEntity(); plan.setExchangeId(3L);
        when(intents.findByStatusInAndUpdatedAtBeforeOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(intent));
        when(intents.acquireForReconciliation(eq(1L), any(), any(), any())).thenReturn(1);
        when(plans.findById(42L)).thenReturn(Optional.of(plan));
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(exchange()));
        when(gateways.require("binance")).thenReturn(gateway);
        when(gateway.findOrder(any(), any(), any())).thenReturn(Optional.empty());

        service(intents, plans, exchanges, gateways, persistence).reconcilePending();

        verify(persistence).markAfterReconciliation(intent, "MANUAL_REVIEW",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void openOrderIsNotPersistedAsACompletedPurchase() {
        OrderIntentRepository intents = mock(OrderIntentRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        ExchangeRepository exchanges = mock(ExchangeRepository.class);
        ExchangeGatewayRegistry gateways = mock(ExchangeGatewayRegistry.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        OrderPersistenceService persistence = mock(OrderPersistenceService.class);
        OrderIntentEntity intent = intent("PENDING_RECONCILIATION", 1);
        PlanEntity plan = new PlanEntity(); plan.setExchangeId(3L);
        ExchangeEntity exchange = exchange();
        when(intents.findByStatusInAndUpdatedAtBeforeOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(intent));
        when(intents.acquireForReconciliation(eq(1L), any(), any(), any())).thenReturn(1);
        when(plans.findById(42L)).thenReturn(Optional.of(plan));
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(exchange));
        when(gateways.require("binance")).thenReturn(gateway);
        OrderResult open = new OrderResult("BTCUSDT", "99", "client-1", "NEW",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of());
        when(gateway.findOrder(any(), any(), any())).thenReturn(Optional.of(open));

        service(intents, plans, exchanges, gateways, persistence).reconcilePending();

        verify(persistence).markAfterReconciliation(intent, "PENDING_RECONCILIATION",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(persistence, never()).confirmAfterReconciliation(any(), any(), any());
    }

    @Test
    void persistentLookupFailureEscalatesAtMaximumAttempts() {
        OrderIntentRepository intents = mock(OrderIntentRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        ExchangeRepository exchanges = mock(ExchangeRepository.class);
        ExchangeGatewayRegistry gateways = mock(ExchangeGatewayRegistry.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        OrderPersistenceService persistence = mock(OrderPersistenceService.class);
        OrderIntentEntity intent = intent("PENDING_RECONCILIATION", 20);
        PlanEntity plan = new PlanEntity(); plan.setExchangeId(3L);
        when(intents.findByStatusInAndUpdatedAtBeforeOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(intent));
        when(intents.acquireForReconciliation(eq(1L), any(), any(), any())).thenReturn(1);
        when(plans.findById(42L)).thenReturn(Optional.of(plan));
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(exchange()));
        when(gateways.require("binance")).thenReturn(gateway);
        when(gateway.findOrder(any(), any(), any())).thenThrow(new RetryableExchangeException(
                "still unavailable", new RuntimeException()));

        service(intents, plans, exchanges, gateways, persistence).reconcilePending();

        verify(persistence).markAfterReconciliation(intent, "MANUAL_REVIEW",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private static OrderIntentEntity intent(String status, int attempts) {
        OrderIntentEntity intent = new OrderIntentEntity(); intent.setId(1L); intent.setPlanId(42L);
        intent.setUserId(7L); intent.setSymbol("BTCUSDT"); intent.setClientOrderId("client-1");
        intent.setStatus(status); intent.setAttempts(attempts);
        intent.setUpdatedAt(LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        return intent;
    }

    private static ExchangeEntity exchange() {
        ExchangeEntity exchange = new ExchangeEntity(); exchange.setExchange("binance");
        exchange.setAccessKey("access"); exchange.setSecretKey("secret");
        return exchange;
    }

    private static OrderReconciliationService service(
            OrderIntentRepository intents, PlanRepository plans, ExchangeRepository exchanges,
            ExchangeGatewayRegistry gateways, OrderPersistenceService persistence) {
        return new OrderReconciliationService(intents, plans, exchanges, gateways, persistence,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), 20);
    }
}
