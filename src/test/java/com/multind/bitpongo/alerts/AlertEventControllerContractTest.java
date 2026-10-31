package com.multind.bitpongo.alerts;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.GlobalExceptionHandler;
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
class AlertEventControllerContractTest {
    @Autowired JsonMapper json;
    AlertEventService service;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(AlertEventService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AlertEventController(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == AuthenticatedUser.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r, WebDataBinderFactory f) {
                        return new AuthenticatedUser(7, "test@example.com", "Test");
                    }
                }).build();
    }

    @Test
    void exposesConfirmationStateOnInboxEvents() throws Exception {
        Instant now = Instant.parse("2026-10-25T00:00:00Z");
        when(service.list(7, false, 0, 20)).thenReturn(new AlertDtos.Page<>(
                List.of(new AlertDtos.Event(1, null, "STRATEGY_SIGNAL", "Signal", "Body", "BTCUSDT", null, now, null, null, true)),
                1, 0, 20, false));

        mvc.perform(get("/api/alerts/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].confirmationRequired").value(true))
                .andExpect(jsonPath("$.data.items[0].confirmedAt").doesNotExist());
    }

    @Test
    void confirmsAnAlertForTheCurrentUser() throws Exception {
        mvc.perform(put("/api/alerts/events/9/confirm"))
                .andExpect(status().isOk());

        verify(service).confirm(7, 9);
    }
}
