package com.multind.bitpongo.alerts;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = JacksonAutoConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FngStrategyControllerContractTest {
    @Autowired JsonMapper json;
    FngStrategyService service;
    FngPositionService positions;
    MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(FngStrategyService.class);
        positions = mock(FngPositionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FngStrategyController(service, positions))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == AuthenticatedUser.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r, WebDataBinderFactory f) {
                        return new AuthenticatedUser(7, "test@example.com", "Test");
                    }
                }).build();
    }

    @Test void createsAndListsStrategiesWithCamelCaseFields() throws Exception {
        var body = """
                {"name":"F&G alert","exchange":"binance","exchangeId":1,"symbol":"BTCUSDT","upperLimitValue":75,
                 "upperLimitInterval":5,"lowerLimitValue":25,"lowerLimitInterval":5,"basicAmount":"100",
                 "increaseRatio":"10","leverage":1,"bureauPeriod":7,"direction":"LONG",
                 "profitTargetPercent":"10","profitDrawdownPercent":"5","profitTargetType":"PERCENT",
                 "maxOrders":10,"maxPositions":5}
                """;
        when(service.create(eq(7L), any())).thenReturn(11L);
        mvc.perform(post("/api/fng-strategies").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(11));
        verify(service).create(eq(7L), any(FngStrategyDtos.Create.class));

        when(service.list(7)).thenReturn(List.of(new FngStrategyDtos.View(
                11, "F&G alert", "binance", 1L, "BTCUSDT", 75, 5, 25, 5, "100", "10", 1, 7,
                "LONG", "10", "5", "PERCENT", 10, 5, true, Instant.parse("2026-10-25T00:00:00Z"))));
        mvc.perform(get("/api/fng-strategies")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].upperLimitValue").value(75))
                .andExpect(jsonPath("$.data[0].basicAmount").value("100"))
                .andExpect(jsonPath("$.data[0].enabled").value(true));
    }

    @Test void togglesAndDeletesStrategies() throws Exception {
        mvc.perform(put("/api/fng-strategies/11/enabled").contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        verify(service).enabled(7, 11, false);
        mvc.perform(delete("/api/fng-strategies/11")).andExpect(status().isOk());
        verify(service).delete(7, 11);
    }

    @Test void exposesDetailAndPerformanceEndpoints() throws Exception {
        when(service.detail(7, 11)).thenReturn(null);
        mvc.perform(get("/api/fng-strategies/11")).andExpect(status().isOk());
        when(positions.performance(7, 11)).thenReturn(new FngStrategyDtos.Performance(
                "100", "12.5", "66.67", 4, 1, 3,
                List.of(new FngStrategyDtos.Point(Instant.parse("2026-10-25T00:00:00Z"), "12.5"))));
        mvc.perform(get("/api/fng-strategies/11/performance")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.realizedPnl").value("12.5"))
                .andExpect(jsonPath("$.data.curve[0].pnl").value("12.5"));
    }

    @Test void rejectsUnknownEnabledFields() throws Exception {
        mvc.perform(put("/api/fng-strategies/11/enabled").contentType("application/json")
                        .content("{\"enabled\":true,\"token\":\"secret\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}