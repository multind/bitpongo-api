package com.multind.zhitoubao.plan;

import com.multind.zhitoubao.exchange.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AssetSnapshotServiceTest {
    @Test
    void capturesEachActivePlansOwnUsdtBalanceAndContinuesAfterFailure() {
        PlanRepository plans = mock(PlanRepository.class);
        SnapshotRepository snapshots = mock(SnapshotRepository.class);
        ExchangeRepository exchanges = mock(ExchangeRepository.class);
        ExchangeGatewayRegistry gateways = mock(ExchangeGatewayRegistry.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        PlanEntity first = plan(1L, 7L, 3L); PlanEntity second = plan(2L, 8L, 4L);
        ExchangeEntity firstExchange = exchange(3L, 7L, "a1", "s1");
        ExchangeEntity secondExchange = exchange(4L, 8L, "a2", "s2");
        when(plans.findByStatus("active")).thenReturn(List.of(first, second));
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(firstExchange));
        when(exchanges.findByIdAndUserId(4L, 8L)).thenReturn(Optional.of(secondExchange));
        when(gateways.require("binance")).thenReturn(gateway);
        when(gateway.verifyCredentials(any())).thenReturn(
                new AccountBalance("USDT", new BigDecimal("12.30"), BigDecimal.ZERO),
                new AccountBalance("USDT", new BigDecimal("5"), BigDecimal.ZERO));

        new AssetSnapshotService(plans, snapshots, exchanges, gateways, null,
                Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC)).captureAll();

        verify(snapshots).save(argThat(value -> value.getPlanId() == 1L && value.getUserId() == 7L
                && value.getType().equals("asset") && value.getValue().equals("12.30")));
        verify(snapshots).save(argThat(value -> value.getPlanId() == 2L && value.getUserId() == 8L
                && value.getValue().equals("5")));
    }

    private PlanEntity plan(long id, long user, long exchange) {
        PlanEntity plan = new PlanEntity(); plan.setId(id); plan.setUserId(user); plan.setExchangeId(exchange); return plan;
    }
    private ExchangeEntity exchange(long id, long user, String access, String secret) {
        ExchangeEntity value = new ExchangeEntity(); value.setId(id); value.setUserId(user);
        value.setExchange("binance"); value.setAccessKey(access); value.setSecretKey(secret); return value;
    }
}
