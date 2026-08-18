package com.multind.bitpongo.plan;

import com.multind.bitpongo.auth.JwtTokenService;
import com.multind.bitpongo.auth.UserRepository;
import com.multind.bitpongo.exchange.ExchangeApplicationService;
import com.multind.bitpongo.strategy.StrategyApplicationService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
class PlanControllerContractTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @MockitoBean PlanApplicationService plans;
    @MockitoBean ExchangeApplicationService exchangeApplicationService;
    @MockitoBean StrategyApplicationService strategyApplicationService;
    @MockitoBean UserRepository users;
    @MockitoBean com.multind.bitpongo.auth.DeletedExternalIdentityRepository deletedExternalIdentities;
    @MockitoBean com.multind.bitpongo.auth.AccountDeletionService accountDeletionService;

    @Test
    void listDetailAndStatusKeepPythonPathsAndNestedShape() throws Exception {
        var user = new com.multind.bitpongo.auth.UserEntity();
        user.setId(7L);
        user.setStatus("active");
        when(users.findById(7L)).thenReturn(java.util.Optional.of(user));
        PlanDtos.PlanView view = new PlanDtos.PlanView(42L, new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("120"), LocalDateTime.parse("2026-08-10T08:00:00"),
                "active", 7L, 1, LocalDateTime.parse("2026-08-09T08:00:00"),
                null, List.of(), List.of(), List.of());
        when(plans.active(7L)).thenReturn(List.of(view));
        when(plans.detail(7L, 42L)).thenReturn(view);

        mvc.perform(get("/api/plans/list/active").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].total_value").value(120));
        mvc.perform(get("/api/plans/42").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.coins.length()").value(0));
        mvc.perform(get("/api/plans/42/stop").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(plans).updateStatus(7L, 42L, "stop");
    }

    private String bearer() { return "Bearer " + tokens.issue(7L); }
}
