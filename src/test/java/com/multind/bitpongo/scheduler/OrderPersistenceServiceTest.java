package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.exchange.OrderResult;
import com.multind.bitpongo.plan.OrderEntity;
import com.multind.bitpongo.plan.OrderRepository;
import com.multind.bitpongo.plan.PlanEntity;
import com.multind.bitpongo.plan.PlanRepository;
import com.multind.bitpongo.strategy.CoinEntity;
import com.multind.bitpongo.strategy.CoinRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderPersistenceServiceTest {
    @Test
    void staleLeaseOwnerCannotOverwriteNewerReconciliation() {
        OrderIntentRepository intents = mock(OrderIntentRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        OrderIntentEntity current = new OrderIntentEntity(); current.setId(1L);
        current.setStatus("RECONCILING"); current.setUpdatedAt(LocalDateTime.parse("2026-08-09T00:01:30"));
        when(intents.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
        OrderPersistenceService service = new OrderPersistenceService(
                intents, orders, mock(CoinRepository.class),
                mock(PlanRepository.class), mock(JdbcTemplate.class),
                Clock.systemUTC());
        OrderIntentEntity stale = new OrderIntentEntity(); stale.setId(1L);
        OrderResult result = new OrderResult("BTCUSDT", "99", "client-1", "FILLED",
                new BigDecimal("0.1"), new BigDecimal("10"), new BigDecimal("100"),
                Map.of());

        boolean completed = service.confirmAfterReconciliation(
                stale, result, LocalDateTime.parse("2026-08-09T00:01:00"));

        assertThat(completed).isFalse();
        verifyNoInteractions(orders);
    }

    @Test
    void auditAndResolvedExecutionTimesStayUtc() {
        PlanRepository plans = mock(PlanRepository.class);
        OrderIntentRepository intents = mock(OrderIntentRepository.class);
        PlanEntity plan = new PlanEntity();
        plan.setId(42L);
        when(plans.findById(42L)).thenReturn(Optional.of(plan));
        OrderPersistenceService service = new OrderPersistenceService(
                intents, mock(OrderRepository.class),
                mock(CoinRepository.class), plans, mock(JdbcTemplate.class),
                Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
        OrderIntentEntity intent = new OrderIntentEntity();
        intent.setAttempts(0);

        service.mark(intent, "SUBMITTING");
        service.updateNextFireTime(42L, Instant.parse("2026-08-09T00:00:00Z"));

        assertThat(intent.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-08-09T00:00:00"));
        assertThat(plan.getNextTime()).isEqualTo(LocalDateTime.parse("2026-08-09T00:00:00"));
    }

    @Test
    void accountsEachCommissionAssetWithoutMixingCurrencies() {
        OrderIntentRepository intents = mock(OrderIntentRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        CoinRepository coins = mock(CoinRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        OrderIntentEntity intent = new OrderIntentEntity();
        intent.setPlanId(42L); intent.setUserId(7L); intent.setCoinId(5L); intent.setClientOrderId("client-1");
        CoinEntity coin = new CoinEntity();
        coin.setId(5L); coin.setTotalAmount(BigDecimal.ZERO); coin.setAverage(BigDecimal.ZERO);
        PlanEntity plan = new PlanEntity();
        plan.setId(42L); plan.setTotalFunds(BigDecimal.ZERO);
        when(orders.findByClientOrderId("client-1")).thenReturn(Optional.empty());
        when(coins.findByIdAndUserId(5L, 7L)).thenReturn(Optional.of(coin));
        when(plans.findById(42L)).thenReturn(Optional.of(plan));
        OrderPersistenceService service = new OrderPersistenceService(
                intents, orders, coins, plans,
                mock(JdbcTemplate.class), Clock.systemUTC());
        OrderResult result = new OrderResult("BTCUSDT", "99", "client-1", "FILLED",
                new BigDecimal("0.1"), new BigDecimal("10"), new BigDecimal("100"),
                Map.of("BTC", new BigDecimal("0.001"), "USDT", new BigDecimal("0.5")));

        service.confirm(intent, result);

        assertThat(coin.getTotalAmount()).isEqualByComparingTo("0.099");
        assertThat(coin.getAverage()).isEqualByComparingTo(
                new BigDecimal("10.5").divide(new BigDecimal("0.099"), 18, java.math.RoundingMode.HALF_UP));
        assertThat(plan.getTotalFunds()).isEqualByComparingTo("10.5");
        ArgumentCaptor<OrderEntity> saved = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orders).save(saved.capture());
        assertThat(saved.getValue().getFee()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
