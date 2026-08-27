package com.multind.bitpongo.strategy;

import com.multind.bitpongo.auth.JwtTokenService;
import com.multind.bitpongo.auth.UserRepository;
import com.multind.bitpongo.exchange.ExchangeRepository;
import com.multind.bitpongo.plan.PlanRepository;
import com.multind.bitpongo.scheduler.PlanScheduleService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@MockitoBean(types = {
        com.multind.bitpongo.plan.AssetSnapshotService.class,
        com.multind.bitpongo.scheduler.OrderPersistenceService.class,
        com.multind.bitpongo.scheduler.OrderReconciliationService.class,
        com.multind.bitpongo.scheduler.ScheduledPurchaseService.class
})
@org.springframework.context.annotation.Import(com.multind.bitpongo.TestNotificationPublisherConfiguration.class)
class StrategyControllerContractTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @MockitoBean StrategyRepository strategies;
    @MockitoBean PlanRepository plans;
    @MockitoBean CoinRepository coins;
    @MockitoBean ExchangeRepository exchanges;
    @MockitoBean PlanScheduleService schedules;
    @MockitoBean UserRepository users;
    @MockitoBean com.multind.bitpongo.auth.AccountDeletionService accountDeletionService;
    @MockitoBean com.multind.bitpongo.plan.PlanApplicationService planApplicationService;

    @Test
    void createReturnsStrategyPlanAndCoinsAndListIsUserScoped() throws Exception {
        var user = new com.multind.bitpongo.auth.UserEntity();
        user.setId(7L);
        user.setStatus("active");
        when(users.findById(7L)).thenReturn(java.util.Optional.of(user));
        var exchange = new com.multind.bitpongo.exchange.ExchangeEntity();
        exchange.setId(3L);
        exchange.setExchange("binance");
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(java.util.Optional.of(exchange));
        when(strategies.save(any())).thenAnswer(invocation -> {
            StrategyEntity entity = invocation.getArgument(0); entity.setId(11L); return entity;
        });
        when(plans.save(any())).thenAnswer(invocation -> {
            com.multind.bitpongo.plan.PlanEntity entity = invocation.getArgument(0); entity.setId(12L); return entity;
        });
        when(coins.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/api/strategies/create").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"每日定投","instalment":100,"exchange_id":3,"frequency":"daily",
                     "cron":"0 8 * * *","condition":"last_average","user_id":999,
                     "schedule_timezone":"America/New_York",
                     "coins":[{"proportion":100,"icon":"btc","min":0,"max":0,
                     "average_down":true,"symbol":"BTC","checked":true}]}
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategy.user_id").value(7))
                .andExpect(jsonPath("$.data.strategy.schedule_timezone").value("America/New_York"))
                .andExpect(jsonPath("$.data.plan.id").value(12))
                .andExpect(jsonPath("$.data.coins[0].plan_id").value(12));

        var own = new StrategyEntity(); own.setId(1L); own.setUserId(7L); own.setName("自己的");
        own.setCreatedAt(LocalDateTime.now());
        when(strategies.findByUserId(7L)).thenReturn(List.of(own));
        mvc.perform(get("/api/strategies/list/active").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("自己的"));
    }

    @Test
    void rejectsInvalidScheduleZones() throws Exception {
        var user = new com.multind.bitpongo.auth.UserEntity();
        user.setId(7L);
        user.setStatus("active");
        when(users.findById(7L)).thenReturn(java.util.Optional.of(user));
        var exchange = new com.multind.bitpongo.exchange.ExchangeEntity();
        exchange.setId(3L);
        exchange.setExchange("binance");
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(java.util.Optional.of(exchange));

        for (String timezone : List.of("CST", "+08:00", "Not/AZone")) {
            mvc.perform(post("/api/strategies/create").header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {"name":"每日定投","instalment":100,"exchange_id":3,"frequency":"daily",
                                 "cron":"0 8 * * *","condition":"last_average","schedule_timezone":"%s",
                                 "coins":[{"proportion":100,"icon":"btc","min":0,"max":0,
                                 "average_down":true,"symbol":"BTC","checked":true}]}
                                """.formatted(timezone)))
                    .andExpect(status().isBadRequest());
        }
    }

    private String bearer() { return "Bearer " + tokens.issue(7L); }
}
