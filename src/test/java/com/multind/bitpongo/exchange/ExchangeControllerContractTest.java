package com.multind.bitpongo.exchange;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class ExchangeControllerContractTest {
    @Autowired private MockMvc mvc;
    @Autowired private com.multind.bitpongo.auth.JwtTokenService tokens;

    @MockitoBean private ExchangeRepository exchanges;
    @MockitoBean private ExchangeGatewayRegistry gateways;
    @MockitoBean private com.multind.bitpongo.auth.UserRepository users;
    @MockitoBean private com.multind.bitpongo.auth.AccountDeletionService accountDeletionService;
    @MockitoBean private com.multind.bitpongo.plan.PlanApplicationService planApplicationService;
    @MockitoBean private com.multind.bitpongo.strategy.StrategyApplicationService strategyApplicationService;

    private final ExchangeGateway gateway = org.mockito.Mockito.mock(ExchangeGateway.class);
    private ExchangeEntity exchange;

    @BeforeEach
    void setUp() {
        var user = new com.multind.bitpongo.auth.UserEntity();
        user.setId(7L);
        user.setStatus("active");
        when(users.findById(7L)).thenReturn(Optional.of(user));

        exchange = new ExchangeEntity();
        exchange.setId(3L);
        exchange.setUserId(7L);
        exchange.setName("主账号");
        exchange.setExchange("binance");
        exchange.setAccessKey("abcdefghij");
        exchange.setSecretKey("abcdefghijklmnop");
        exchange.setStatus("active");
        exchange.setCreatedAt(LocalDateTime.of(2025, 1, 2, 3, 4));
        when(exchanges.findByUserId(7L)).thenReturn(List.of(exchange));
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(exchange));
        when(exchanges.save(any(ExchangeEntity.class))).thenAnswer(invocation -> {
            ExchangeEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(8L);
            return saved;
        });
        when(gateways.require("binance")).thenReturn(gateway);
    }

    @Test
    void listAndDetailOnlyReturnCurrentUsersMaskedCredentials() throws Exception {
        mvc.perform(get("/api/exchanges/list").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].access_key").value("abc*******"))
                .andExpect(jsonPath("$.data[0].secret_key").doesNotExist());

        mvc.perform(get("/api/exchanges/3").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_key").value("abcd********ghij"))
                .andExpect(jsonPath("$.data.secret_key").value("abcd********mnop"));
    }

    @Test
    void createForcesAuthenticatedUserAndMaskedUpdateKeepsStoredKeys() throws Exception {
        mvc.perform(post("/api/exchanges/create")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新账号\",\"exchange\":\"binance\","
                                + "\"access_key\":\"new-access\",\"secret_key\":\"new-secret\","
                                + "\"user_id\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(8));

        mvc.perform(put("/api/exchanges/3")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名\",\"access_key\":\"abcd********ghij\","
                                + "\"secret_key\":\"abcd********mnop\"}"))
                .andExpect(status().isOk());

        verify(exchanges, times(2)).save(org.mockito.ArgumentMatchers.argThat(saved -> saved.getUserId() == 7L));
        org.assertj.core.api.Assertions.assertThat(exchange.getAccessKey()).isEqualTo("abcdefghij");
        org.assertj.core.api.Assertions.assertThat(exchange.getSecretKey()).isEqualTo("abcdefghijklmnop");
    }

    @Test
    void checkAndMinimumAmountUseOwnedExchange() throws Exception {
        when(gateway.verifyCredentials(any()))
                .thenReturn(new AccountBalance("USDT", new BigDecimal("12.3"), BigDecimal.ZERO));
        when(gateway.getMarketRules("BTCUSDT"))
                .thenReturn(new MarketRules(new BigDecimal("10"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN));
        when(gateway.getMarketRules("ETHUSDT"))
                .thenReturn(new MarketRules(new BigDecimal("5"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN));

        mvc.perform(post("/api/exchanges/check")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("API密钥验证成功"))
                .andExpect(jsonPath("$.data.free").value(12.3));

        mvc.perform(post("/api/exchanges/minimumAmount")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange_id\":3,\"coins\":[\"BTC\",\"ETH\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(15));
    }

    @Test
    void ownershipViolationReturns404AndDoesNotDelete() throws Exception {
        when(exchanges.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

        mvc.perform(delete("/api/exchanges/99").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("交易所不存在"));
        verify(exchanges, never()).delete(any());
    }

    private String bearer() { return "Bearer " + tokens.issue(7L); }
}
