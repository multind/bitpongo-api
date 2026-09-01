package com.multind.bitpongo.plan;

import com.multind.bitpongo.auth.JwtTokenService;
import com.multind.bitpongo.auth.UserRepository;
import com.multind.bitpongo.exchange.ExchangeApplicationService;
import com.multind.bitpongo.strategy.StrategyApplicationService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class PlanControllerContractTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @MockitoBean PlanApplicationService plans;
    @MockitoBean ExchangeApplicationService exchangeApplicationService;
    @MockitoBean StrategyApplicationService strategyApplicationService;
    @MockitoBean UserRepository users;
    @MockitoBean com.multind.bitpongo.auth.AccountDeletionService accountDeletionService;

    @Test
    void listDetailAndStatusKeepPythonPathsAndNestedShape() throws Exception {
        var user = new com.multind.bitpongo.auth.UserEntity();
        user.setId(7L);
        user.setStatus("active");
        when(users.findById(7L)).thenReturn(java.util.Optional.of(user));
        OrderEntity order = new OrderEntity();
        order.setId(9L);
        order.setCreatedAt(LocalDateTime.parse("2026-08-09T08:15:30"));
        SnapshotEntity snapshot = new SnapshotEntity();
        snapshot.setId(10L);
        snapshot.setCreatedAt(LocalDateTime.parse("2026-08-09T09:16:31"));
        PlanDtos.PlanView view = new PlanDtos.PlanView(42L, new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("120"), Instant.parse("2026-08-10T08:00:00Z"),
                Instant.parse("2026-08-10T08:00:00Z"), "active", 7L, 1,
                Instant.parse("2026-08-09T08:00:00Z"),
                null, List.of(), List.of(order), List.of(snapshot));
        when(plans.active(7L)).thenReturn(List.of(view));
        when(plans.detail(7L, 42L, true)).thenReturn(view);

        mvc.perform(get("/api/plans/list/active").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].total_value").value(120))
                .andExpect(jsonPath("$.data[0].next_execution_at").value("2026-08-10T08:00:00Z"))
                .andExpect(jsonPath("$.data[0].next_time").value("2026-08-10T08:00:00Z"))
                .andExpect(jsonPath("$.data[0].created_at").value("2026-08-09T08:00:00Z"));
        mvc.perform(get("/api/plans/42").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.orders[0].created_at").value("2026-08-09T08:15:30Z"))
                .andExpect(jsonPath("$.data.snapshots[0].created_at").value("2026-08-09T09:16:31Z"));
        mvc.perform(get("/api/plans/42/stop").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(plans).updateStatus(7L, 42L, "stop");
    }

    @Test
    void tradeHistorySupportsNewestFirstPagination() throws Exception {
        var user = new com.multind.bitpongo.auth.UserEntity();
        user.setId(7L); user.setStatus("active");
        when(users.findById(7L)).thenReturn(java.util.Optional.of(user));
        OrderEntity order = new OrderEntity();
        order.setId(9L); order.setSymbol("BTC/USDT");
        order.setCreatedAt(LocalDateTime.parse("2026-08-09T08:15:30"));
        when(plans.orders(7L, 42L, 0, 20)).thenReturn(
                new PlanDtos.OrderPage(List.of(order), 0, 20, 1, false));

        mvc.perform(get("/api/plans/42/orders?page=0&size=20")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(9))
                .andExpect(jsonPath("$.data.items[0].created_at")
                        .value("2026-08-09T08:15:30Z"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.has_more").value(false));
        verify(plans).orders(7L, 42L, 0, 20);
    }

    @Test
    void detailCanSkipTheLegacyUnpagedOrderList() throws Exception {
        var user = new com.multind.bitpongo.auth.UserEntity();
        user.setId(7L); user.setStatus("active");
        when(users.findById(7L)).thenReturn(java.util.Optional.of(user));
        PlanDtos.PlanView view = new PlanDtos.PlanView(
                42L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, "active", 7L, 0, Instant.parse("2026-08-09T08:00:00Z"),
                null, List.of(), List.of(), List.of());
        when(plans.detail(7L, 42L, false)).thenReturn(view);

        mvc.perform(get("/api/plans/42?include_orders=false")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders").isEmpty());
        verify(plans).detail(7L, 42L, false);
    }

    private String bearer() { return "Bearer " + tokens.issue(7L); }
}
