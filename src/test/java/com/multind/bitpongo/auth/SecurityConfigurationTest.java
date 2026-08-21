package com.multind.bitpongo.auth;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=security-test-secret",
        "zhitoubao.jwt.access-token-expire-minutes=5",
        "zhitoubao.cors.allowed-origins=*"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(SecurityConfigurationTest.TestEndpoints.class)
@MockitoBean(types = {
        com.multind.bitpongo.plan.AssetSnapshotService.class,
        com.multind.bitpongo.scheduler.OrderPersistenceService.class,
        com.multind.bitpongo.scheduler.OrderReconciliationService.class,
        com.multind.bitpongo.scheduler.ScheduledPurchaseService.class
})
class SecurityConfigurationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService tokens;
    @MockitoBean private UserApplicationService userApplicationService;
    @MockitoBean private AccountDeletionService accountDeletionService;
    @MockitoBean private UserRepository users;
    @MockitoBean private DeletedExternalIdentityRepository deletedExternalIdentities;
    @MockitoBean private com.multind.bitpongo.exchange.ExchangeApplicationService exchangeApplicationService;
    @MockitoBean private com.multind.bitpongo.plan.PlanApplicationService planApplicationService;
    @MockitoBean private com.multind.bitpongo.strategy.StrategyApplicationService strategyApplicationService;

    @Test
    void compatibilityPublicPathsRemainAnonymous() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(post("/api/users/login")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/users/v1/login")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/users/register")).andExpect(status().isBadRequest());
    }

    @Test
    void protectedPathRejectsMissingToken() throws Exception {
        mvc.perform(get("/api/plans/list/active"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validBearerTokenPopulatesUserContext() throws Exception {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setStatus("active");
        when(users.findById(42L)).thenReturn(Optional.of(user));

        mvc.perform(get("/test/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42));
    }

    @Test
    void corsPreflightSupportsFrontendCalls() throws Exception {
        mvc.perform(options("/api/plans/list/active")
                        .header(HttpHeaders.ORIGIN, "https://front.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://front.example"));
    }

    @RestController
    public static class TestEndpoints {
        @GetMapping("/test/me")
        Map<String, Long> me(@AuthenticationPrincipal AuthenticatedUser user) {
            return Map.of("id", user.id());
        }
    }
}
