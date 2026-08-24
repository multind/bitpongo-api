package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.exchange.*;
import com.multind.bitpongo.market.PriceCache;
import com.multind.bitpongo.notification.NotificationEvent;
import com.multind.bitpongo.notification.NotificationEventType;
import com.multind.bitpongo.notification.NotificationPublisher;
import com.multind.bitpongo.plan.*;
import com.multind.bitpongo.strategy.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    private final CollectingNotificationPublisher notifications =
            new CollectingNotificationPublisher();
    private ScheduledPurchaseService service;

    @BeforeEach
    void setUp() {
        PlanEntity plan = new PlanEntity(); plan.setId(42L); plan.setUserId(7L); plan.setStatus("active");
        plan.setStrategyId(11L); plan.setExchangeId(3L);
        StrategyEntity strategy = new StrategyEntity(); strategy.setId(11L); strategy.setUserId(7L);
        strategy.setInstalment(100); strategy.setCondition("last_average");
        CoinEntity coin = coin(5L, "BTC", "100");
        ExchangeEntity exchange = new ExchangeEntity(); exchange.setId(3L); exchange.setUserId(7L);
        exchange.setExchange("binance"); exchange.setAccessKey("access"); exchange.setSecretKey("secret");
        when(plans.findById(42L)).thenReturn(Optional.of(plan));
        when(strategies.findById(11L)).thenReturn(Optional.of(strategy));
        when(coins.findByPlanIdAndUserId(42L, 7L)).thenReturn(List.of(coin));
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(exchange));
        when(gateways.require("binance")).thenReturn(gateway);
        when(gateway.getMarketRules(anyString())).thenReturn(new MarketRules(
                new BigDecimal("10"), new BigDecimal("0.0001"),
                new BigDecimal("0.0001"), new BigDecimal("100")));
        prices.put("binance", "BTC/USDT", new BigDecimal("62000"), fire);
        Map<String, OrderIntentEntity> claimed = new HashMap<>();
        when(intents.findByClientOrderId(any())).thenAnswer(
                invocation -> Optional.ofNullable(claimed.get(invocation.getArgument(0))));
        when(intents.saveAndFlush(any())).thenAnswer(invocation -> {
            OrderIntentEntity intent = invocation.getArgument(0);
            claimed.put(intent.getClientOrderId(), intent);
            return intent;
        });
        when(gateway.marketBuy(any(), anyString(), any(), any())).thenAnswer(invocation ->
                new OrderResult(invocation.getArgument(1), "99", invocation.getArgument(3), "FILLED",
                        invocation.getArgument(2), new BigDecimal("12.4"), new BigDecimal("62000"),
                        Map.of()));
        service = new ScheduledPurchaseService(
                plans, strategies, coins, orders,
                exchanges, intents, gateways, new OrderSizingService(), prices,
                new OrderIdFactory(), persistence, notifications,
                Clock.fixed(fire, ZoneOffset.UTC));
    }

    @Test
    void sameScheduledFireOnlySubmitsOnce() {
        service.execute(42L, fire);
        service.execute(42L, fire);
        verify(gateway, times(1)).marketBuy(any(), eq("BTCUSDT"), any(), any());
        verify(persistence).confirm(any(OrderIntentEntity.class), any(OrderResult.class));
        verify(persistence, times(2)).beginTrigger(42L, fire);
    }

    @Test
    void ambiguousSubmissionIsQueuedForReconciliationWithoutRetryOrFailureNotification() {
        when(gateway.marketBuy(any(), eq("BTCUSDT"), any(), any()))
                .thenThrow(new AmbiguousOrderException("timeout", new java.net.SocketTimeoutException()));

        service.execute(42L, fire);

        verify(gateway, times(1)).marketBuy(any(), eq("BTCUSDT"), any(), any());
        verify(persistence).mark(any(OrderIntentEntity.class), eq("PENDING_RECONCILIATION"));
        verify(persistence, never()).confirm(any(), any());
        assertThat(notifications.events()).noneMatch(
                event -> event.type() == NotificationEventType.TRADE_FAILED);
    }

    @Test
    void openSubmissionIsQueuedInsteadOfRecordedAsHolding() {
        when(gateway.marketBuy(any(), eq("BTCUSDT"), any(), any())).thenAnswer(invocation ->
                new OrderResult("BTCUSDT", "99", invocation.getArgument(3), "NEW",
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of()));

        service.execute(42L, fire);

        verify(persistence).mark(any(OrderIntentEntity.class), eq("PENDING_RECONCILIATION"));
        verify(persistence, never()).confirm(any(), any());
    }

    @Test
    void freshWebsocketPriceDoesNotCallRestTicker() {
        service.execute(42L, fire);

        verify(gateway, never()).latestPrice(anyString());
        verify(gateway).marketBuy(any(), eq("BTCUSDT"), any(), any());
    }

    @Test
    void staleWebsocketPriceFallsBackToRestTickerAndRefreshesCache() {
        prices.put("binance", "BTC/USDT", new BigDecimal("61000"),
                fire.minus(Duration.ofMinutes(2)));
        when(gateway.latestPrice("BTCUSDT")).thenReturn(new BigDecimal("62000"));

        service.execute(42L, fire);

        verify(gateway).latestPrice("BTCUSDT");
        verify(gateway).marketBuy(any(), eq("BTCUSDT"), any(), any());
        assertThat(prices.getFresh("binance", "BTC/USDT", fire))
                .contains(new BigDecimal("62000"));
    }

    @Test
    void restTickerFailureDoesNotCreateOrSubmitOrder() {
        prices.put("binance", "BTC/USDT", new BigDecimal("61000"),
                fire.minus(Duration.ofMinutes(2)));
        when(gateway.latestPrice("BTCUSDT"))
                .thenThrow(new RuntimeException("ticker unavailable"));

        service.execute(42L, fire);

        verify(intents, never()).saveAndFlush(any());
        verify(gateway, never()).marketBuy(any(), anyString(), any(), any());
    }

    @Test
    void aggregatesFilledCoinsIntoOneSuccessNotificationInStableSymbolOrder() {
        CoinEntity eth = coin(6L, "ETH", "50");
        CoinEntity btc = coin(5L, "BTC", "50");
        when(coins.findByPlanIdAndUserId(42L, 7L)).thenReturn(List.of(eth, btc));
        prices.put("binance", "ETH/USDT", new BigDecimal("2500"), fire);

        service.execute(42L, fire);

        assertThat(notifications.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(NotificationEventType.TRADE_SUCCEEDED);
            assertThat(event.userId()).isEqualTo(7L);
            assertThat(event.planId()).isEqualTo(42L);
            assertThat(event.intentId()).isNull();
            assertThat(event.occurredAt()).isEqualTo(fire);
            assertThat(event.dedupeKey()).isEqualTo("trade-success:42:" + fire);
            assertThat(event.attributes()).containsEntry("status", "FILLED");
            assertThat(event.attributes().get("symbols"))
                    .isEqualTo(List.of("BTCUSDT", "ETHUSDT"));
            assertThat(event.attributes().keySet())
                    .containsExactlyInAnyOrder("symbols", "status");
        });
        verify(gateway, times(2)).marketBuy(any(), anyString(), any(), any());
    }

    @Test
    void reportsDefiniteFailureWithoutChangingFailedIntentStatus() {
        when(gateway.marketBuy(any(), eq("BTCUSDT"), any(), any()))
                .thenThrow(new RuntimeException("rejected"));

        service.execute(42L, fire);

        verify(persistence).mark(any(OrderIntentEntity.class), eq("FAILED"));
        assertThat(notifications.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(NotificationEventType.TRADE_FAILED);
            assertThat(event.dedupeKey()).isEqualTo("trade-failed:42:" + fire);
            assertThat(event.attributes()).containsEntry("status", "FAILED");
            assertThat(event.attributes().get("symbols")).isEqualTo(List.of("BTCUSDT"));
        });
    }

    @Test
    void reportsUnavailablePriceAsOneSkippedEventWithoutSubmittingOrder() {
        prices.put("binance", "BTC/USDT", new BigDecimal("61000"),
                fire.minus(Duration.ofMinutes(2)));
        when(gateway.latestPrice("BTCUSDT"))
                .thenThrow(new RuntimeException("price unavailable"));

        service.execute(42L, fire);

        verify(gateway, never()).marketBuy(any(), anyString(), any(), any());
        assertThat(notifications.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(NotificationEventType.PLAN_EXECUTION_SKIPPED);
            assertThat(event.userId()).isEqualTo(7L);
            assertThat(event.planId()).isEqualTo(42L);
            assertThat(event.occurredAt()).isEqualTo(fire);
            assertThat(event.dedupeKey()).isEqualTo("plan-skipped:42:" + fire);
            assertThat(event.attributes()).containsEntry("status", "PRICE_UNAVAILABLE");
            assertThat(event.attributes().get("symbols")).isEqualTo(List.of("BTCUSDT"));
        });
    }

    @Test
    void publishesAtMostOneEventForEachNonEmptyOutcomeCategory() {
        CoinEntity btc = coin(5L, "BTC", "34");
        CoinEntity eth = coin(6L, "ETH", "33");
        CoinEntity sol = coin(7L, "SOL", "33");
        when(coins.findByPlanIdAndUserId(42L, 7L)).thenReturn(List.of(sol, eth, btc));
        prices.put("binance", "ETH/USDT", new BigDecimal("2500"), fire);
        when(gateway.latestPrice("SOLUSDT"))
                .thenThrow(new RuntimeException("price unavailable"));
        when(gateway.marketBuy(any(), eq("ETHUSDT"), any(), any()))
                .thenThrow(new RuntimeException("rejected"));

        service.execute(42L, fire);

        assertThat(notifications.events()).extracting(NotificationEvent::type)
                .containsExactly(
                        NotificationEventType.TRADE_SUCCEEDED,
                        NotificationEventType.TRADE_FAILED,
                        NotificationEventType.PLAN_EXECUTION_SKIPPED);
        assertThat(notifications.events()).allSatisfy(event ->
                assertThat((List<?>) event.attributes().get("symbols")).hasSize(1));
    }

    @Test
    void oneNotificationFailureDoesNotBlockLaterOutcomeCategories() {
        CoinEntity btc = coin(5L, "BTC", "34");
        CoinEntity eth = coin(6L, "ETH", "33");
        CoinEntity sol = coin(7L, "SOL", "33");
        when(coins.findByPlanIdAndUserId(42L, 7L)).thenReturn(List.of(sol, eth, btc));
        prices.put("binance", "ETH/USDT", new BigDecimal("2500"), fire);
        when(gateway.latestPrice("SOLUSDT"))
                .thenThrow(new RuntimeException("price unavailable"));
        when(gateway.marketBuy(any(), eq("ETHUSDT"), any(), any()))
                .thenThrow(new RuntimeException("rejected"));
        notifications.failNextPublishing();

        assertDoesNotThrow(() -> service.execute(42L, fire));

        assertThat(notifications.attemptedTypes()).containsExactly(
                NotificationEventType.TRADE_SUCCEEDED,
                NotificationEventType.TRADE_FAILED,
                NotificationEventType.PLAN_EXECUTION_SKIPPED);
        assertThat(notifications.events()).extracting(NotificationEvent::type)
                .containsExactly(
                        NotificationEventType.TRADE_FAILED,
                        NotificationEventType.PLAN_EXECUTION_SKIPPED);
        verify(persistence).confirm(any(OrderIntentEntity.class), any(OrderResult.class));
        verify(persistence).mark(any(OrderIntentEntity.class), eq("FAILED"));
    }
    @Test
    void publishesNothingWhenNoOutcomeCategoryHasEntries() {
        when(coins.findByPlanIdAndUserId(42L, 7L)).thenReturn(List.of());

        service.execute(42L, fire);

        assertThat(notifications.events()).isEmpty();
    }

    @Test
    void notificationFailureDoesNotChangeConfirmedTradeOutcome() {
        notifications.failPublishing();

        assertDoesNotThrow(() -> service.execute(42L, fire));

        verify(persistence).confirm(any(OrderIntentEntity.class), any(OrderResult.class));
        verify(persistence, never()).mark(any(OrderIntentEntity.class), eq("FAILED"));
    }

    private static CoinEntity coin(long id, String symbol, String proportion) {
        CoinEntity coin = new CoinEntity();
        coin.setId(id); coin.setPlanId(42L); coin.setUserId(7L);
        coin.setSymbol(symbol); coin.setProportion(proportion); coin.setAverageDown(false);
        coin.setAverage(BigDecimal.ZERO); coin.setTotalAmount(BigDecimal.ZERO);
        return coin;
    }

    private static final class CollectingNotificationPublisher implements NotificationPublisher {
        private final List<NotificationEvent> events = new ArrayList<>();
        private final List<NotificationEventType> attemptedTypes = new ArrayList<>();
        private boolean failPublishing;
        private int failuresRemaining;

        @Override
        public void publish(NotificationEvent event) {
            attemptedTypes.add(event.type());
            if (failPublishing) {
                throw new RuntimeException("notification unavailable");
            }
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new RuntimeException("notification unavailable");
            }
            events.add(event);
        }

        List<NotificationEvent> events() {
            return List.copyOf(events);
        }

        List<NotificationEventType> attemptedTypes() {
            return List.copyOf(attemptedTypes);
        }

        void failNextPublishing() {
            failuresRemaining = 1;
        }

        void failPublishing() {
            failPublishing = true;
        }
    }
}
