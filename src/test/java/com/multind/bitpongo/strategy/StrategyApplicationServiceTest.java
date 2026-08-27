package com.multind.bitpongo.strategy;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.exchange.ExchangeEntity;
import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.exchange.ExchangeGatewayRegistry;
import com.multind.bitpongo.plan.PlanEntity;
import com.multind.bitpongo.plan.PlanRepository;
import com.multind.bitpongo.scheduler.PlanScheduleService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.multind.bitpongo.strategy.StrategyDtos.CoinRequest;
import static com.multind.bitpongo.strategy.StrategyDtos.StrategyCreateRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyApplicationServiceTest {
    @Test
    void convertsFiveFieldCronWithoutDroppingCalendarConstraints() {
        assertThat(StrategyApplicationService.normalizeCron("0 8 * * 1-5"))
                .isEqualTo("0 0 8 ? * 2-6");
        assertThat(StrategyApplicationService.normalizeCron("30 9 15 * *"))
                .isEqualTo("0 30 9 15 * ?");
        assertThat(StrategyApplicationService.normalizeCron("0 0 * * 0"))
                .isEqualTo("0 0 0 ? * 1");
        assertThat(StrategyApplicationService.normalizeCron("0 0 * * 7"))
                .isEqualTo("0 0 0 ? * 1");
    }

    @Test
    void rejectsFiveFieldCronThatConstrainsDayOfMonthAndWeek() {
        assertThatThrownBy(() -> StrategyApplicationService.normalizeCron("0 8 1 * 1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("日期");
    }
    private final StrategyRepository strategies = mock(StrategyRepository.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final CoinRepository coins = mock(CoinRepository.class);
    private final ExchangeRepository exchanges = mock(ExchangeRepository.class);
    private final ExchangeGatewayRegistry gateways = mock(ExchangeGatewayRegistry.class);
    private final PlanScheduleService schedules = mock(PlanScheduleService.class);
    private StrategyApplicationService service;

    @BeforeEach
    void setUp() {
        service = new StrategyApplicationService(strategies, plans, coins, exchanges, gateways, schedules,
                Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC));
        ExchangeEntity exchange = new ExchangeEntity();
        exchange.setId(3L);
        exchange.setUserId(7L);
        exchange.setExchange("binance");
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(exchange));
        when(strategies.save(any())).thenAnswer(invocation -> {
            StrategyEntity value = invocation.getArgument(0);
            value.setId(11L);
            return value;
        });
        when(plans.save(any())).thenAnswer(invocation -> {
            PlanEntity value = invocation.getArgument(0);
            value.setId(12L);
            return value;
        });
        when(coins.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsStrategyPlanAndCoinsForAuthenticatedUser() {
        var result = service.create(7L, request("0 8 * * *", new BigDecimal("60"), new BigDecimal("40")));

        assertThat(result.strategy().getUserId()).isEqualTo(7L);
        assertThat(result.strategy().getScheduleTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(result.plan().getStatus()).isEqualTo("active");
        assertThat(result.coins()).hasSize(2).allMatch(c -> c.getPlanId().equals(12L));
        verify(schedules).schedule(12L, "0 0 8 * * ?", ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void acceptsOnlyRegionBasedScheduleZones() {
        assertThat(StrategyApplicationService.scheduleZone(null))
                .isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(StrategyApplicationService.scheduleZone("America/New_York"))
                .isEqualTo(ZoneId.of("America/New_York"));

        assertThatThrownBy(() -> StrategyApplicationService.scheduleZone("CST"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("策略时区");
        assertThatThrownBy(() -> StrategyApplicationService.scheduleZone("+08:00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("地区名称");
        assertThatThrownBy(() -> StrategyApplicationService.scheduleZone("Not/AZone"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无效");
    }

    @Test
    void resolvesTheInitialExecutionInTheStrategyZoneAndStoresUtc() {
        StrategyCreateRequest request = new StrategyCreateRequest(
                "New York DCA", new BigDecimal("100"), 3L, "daily", "0 8 * * *",
                "America/New_York", "", List.of(
                new CoinRequest(new BigDecimal("100"), "btc", BigDecimal.ZERO,
                        BigDecimal.ZERO, false, "BTC", true)));

        var result = service.create(7L, request);

        assertThat(result.strategy().getScheduleTimezone()).isEqualTo("America/New_York");
        assertThat(result.plan().getNextTime()).isEqualTo(LocalDateTime.parse("2026-08-09T12:00:00"));
    }

    @Test
    void rejectsInvalidCronProportionsAndForeignExchange() {
        assertThatThrownBy(() -> service.create(7L, request("invalid", new BigDecimal("60"), new BigDecimal("40"))))
                .hasMessageContaining("Cron");
        assertThatThrownBy(() -> service.create(7L, request("0 8 * * *", new BigDecimal("70"), new BigDecimal("40"))))
                .hasMessageContaining("100");
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(7L, request("0 8 * * *", new BigDecimal("60"), new BigDecimal("40"))))
                .hasMessageContaining("交易所不存在");
    }

    @Test
    void rejectsMissingConditionWhenAverageDownIsEnabled() {
        StrategyCreateRequest request = new StrategyCreateRequest(
                "每日定投", new BigDecimal("100"), 3L, "daily", "0 8 * * *", null, "",
                List.of(new CoinRequest(new BigDecimal("100"), "btc", BigDecimal.ZERO,
                        BigDecimal.ZERO, true, "BTC", true)));

        assertThatThrownBy(() -> service.create(7L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("逢低买入条件");
    }

    private StrategyCreateRequest request(String cron, BigDecimal first, BigDecimal second) {
        return new StrategyCreateRequest("每日定投", new BigDecimal("100"), 3L, "daily", cron,
                null, "last_average", List.of(
                new CoinRequest(first, "btc", BigDecimal.ZERO, BigDecimal.ZERO, true, "BTC", true),
                new CoinRequest(second, "eth", BigDecimal.ZERO, BigDecimal.ZERO, false, "ETH", true)));
    }
}
