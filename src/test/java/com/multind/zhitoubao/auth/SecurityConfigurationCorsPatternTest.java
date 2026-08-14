package com.multind.zhitoubao.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=security-test-secret",
        "zhitoubao.jwt.access-token-expire-minutes=5",
        "zhitoubao.cors.allowed-origins=http://localhost:5173,http://localhost:*,http://127.0.0.1:*"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SecurityConfigurationCorsPatternTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private UserApplicationService userApplicationService;
    @MockitoBean private com.multind.zhitoubao.exchange.ExchangeApplicationService exchangeApplicationService;
    @MockitoBean private com.multind.zhitoubao.plan.PlanApplicationService planApplicationService;
    @MockitoBean private com.multind.zhitoubao.strategy.StrategyApplicationService strategyApplicationService;

    @Test
    void preflightAllowsExactAndWildcardLoopbackOrigins() throws Exception {
        mvc.perform(options("/api/users/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));

        mvc.perform(options("/api/users/login")
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:54321")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:54321"));
    }

    @Test
    void preflightRejectsUnknownOrigin() throws Exception {
        mvc.perform(options("/api/users/login")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isForbidden());
    }
}
