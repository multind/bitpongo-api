package com.multind.zhitoubao.scheduler;

import com.multind.zhitoubao.exchange.*;
import com.multind.zhitoubao.plan.PlanEntity;
import com.multind.zhitoubao.plan.PlanRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderReconciliationServiceTest {
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
        PlanEntity plan = new PlanEntity(); plan.setId(42L); plan.setExchangeId(3L);
        ExchangeEntity exchange = new ExchangeEntity(); exchange.setExchange("binance");
        exchange.setAccessKey("access"); exchange.setSecretKey("secret");
        OrderResult result = new OrderResult("BTCUSDT", "99", "client-1", "FILLED",
                new BigDecimal("0.1"), new BigDecimal("10"), new BigDecimal("100"));
        when(intents.findByStatusOrderByCreatedAtAsc("PENDING_RECONCILIATION")).thenReturn(List.of(intent));
        when(plans.findById(42L)).thenReturn(Optional.of(plan));
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(exchange));
        when(gateways.require("binance")).thenReturn(gateway);
        when(gateway.findOrder(any(), eq("BTCUSDT"), eq("client-1"))).thenReturn(Optional.of(result));

        new OrderReconciliationService(intents, plans, exchanges, gateways, persistence).reconcilePending();

        verify(persistence).confirm(intent, result);
        verify(gateway, never()).marketBuy(any(), any(), any(), any());
    }
}
