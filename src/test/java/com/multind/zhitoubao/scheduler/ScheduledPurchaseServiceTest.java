package com.multind.zhitoubao.scheduler;

import com.multind.zhitoubao.exchange.*;
import com.multind.zhitoubao.market.PriceCache;
import com.multind.zhitoubao.plan.*;
import com.multind.zhitoubao.strategy.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScheduledPurchaseServiceTest {
    private final PlanRepository plans = mock(PlanRepository.class);
    private final StrategyRepository strategies = mock(StrategyRepository.class);
    private final CoinRepository coins = mock(CoinRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final ExchangeRepository exchanges = mock(ExchangeRepository.class);
    private final OrderIntentRepository intents = mock(OrderIntentRepository.class);
    private final ExchangeGatewayRegistry gateways = mock(ExchangeGatewayRegistry.class);
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final OrderPersistenceService persistence = mock(OrderPersistenceService.class);
    private final Instant fire = Instant.parse("2026-08-09T00:00:00Z");
    private final PriceCache prices = new PriceCache(Duration.ofMinutes(1));
    private ScheduledPurchaseService service;

    @BeforeEach
    void setUp() {
        PlanEntity plan = new PlanEntity(); plan.setId(42L); plan.setUserId(7L); plan.setStatus("active");
        plan.setStrategyId(11L); plan.setExchangeId(3L);
        StrategyEntity strategy = new StrategyEntity(); strategy.setId(11L); strategy.setUserId(7L);
        strategy.setInstalment(100); strategy.setCondition("last_average");
        CoinEntity coin = new CoinEntity(); coin.setId(5L); coin.setPlanId(42L); coin.setUserId(7L);
        coin.setSymbol("BTC"); coin.setProportion("100"); coin.setAverageDown(false);
        coin.setAverage(BigDecimal.ZERO); coin.setTotalAmount(BigDecimal.ZERO);
        ExchangeEntity exchange = new ExchangeEntity(); exchange.setId(3L); exchange.setUserId(7L);
        exchange.setExchange("binance"); exchange.setAccessKey("access"); exchange.setSecretKey("secret");
        when(plans.findById(42L)).thenReturn(Optional.of(plan));
        when(strategies.findById(11L)).thenReturn(Optional.of(strategy));
        when(coins.findByPlanIdAndUserId(42L, 7L)).thenReturn(List.of(coin));
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(exchange));
        when(gateways.require("binance")).thenReturn(gateway);
        when(gateway.getMarketRules("BTCUSDT")).thenReturn(new MarketRules(
                new BigDecimal("10"), new BigDecimal("0.0001"),
                new BigDecimal("0.0001"), new BigDecimal("100")));
        prices.put("binance", "BTC/USDT", new BigDecimal("62000"), fire);
        AtomicReference<OrderIntentEntity> claimed = new AtomicReference<>();
        when(intents.findByClientOrderId(any())).thenAnswer(inv -> Optional.ofNullable(claimed.get()));
        when(intents.saveAndFlush(any())).thenAnswer(inv -> { claimed.set(inv.getArgument(0)); return claimed.get(); });
        when(gateway.marketBuy(any(), eq("BTCUSDT"), any(), any())).thenAnswer(inv ->
                new OrderResult("BTCUSDT", "99", inv.getArgument(3), "FILLED",
                        inv.getArgument(2), new BigDecimal("12.4"), new BigDecimal("62000")));
        service = new ScheduledPurchaseService(plans, strategies, coins, orders, exchanges, intents,
                gateways, new OrderSizingService(), prices, new OrderIdFactory(), persistence,
                Clock.fixed(fire, ZoneOffset.UTC));
    }

    @Test
    void sameScheduledFireOnlySubmitsOnce() {
        service.execute(42L, fire);
        service.execute(42L, fire);
        verify(gateway, times(1)).marketBuy(any(), eq("BTCUSDT"), any(), any());
        verify(persistence).confirm(any(OrderIntentEntity.class), any(OrderResult.class));
        verify(persistence).finishTrigger(42L, fire);
    }

    @Test
    void ambiguousSubmissionIsQueuedForReconciliationWithoutRetry() {
        when(gateway.marketBuy(any(), eq("BTCUSDT"), any(), any()))
                .thenThrow(new AmbiguousOrderException("timeout", new java.net.SocketTimeoutException()));

        service.execute(42L, fire);

        verify(gateway, times(1)).marketBuy(any(), eq("BTCUSDT"), any(), any());
        verify(persistence).mark(any(OrderIntentEntity.class), eq("PENDING_RECONCILIATION"));
        verify(persistence, never()).confirm(any(), any());
    }
}
